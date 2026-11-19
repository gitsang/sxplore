package com.sxplore.app.proxy

import android.net.Uri
import android.webkit.MimeTypeMap
import com.sxplore.app.vfs.Vfs
import com.sxplore.app.vfs.VfsRandomAccess
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Local streaming proxy: binds only to 127.0.0.1 on a random port.
 *
 * Player HTTP Range requests -> VFS positional reads (SMB READ with offset /
 * local RandomAccessFile). Data is read and sent on the fly, never written to
 * disk. The built-in ExoPlayer and external players (MX/VLC) share one URL.
 *
 * URL shape: http://127.0.0.1:{port}/s/{random-token}/{file-name}
 * Tokens are unguessable and reachable only from this device, so other
 * devices on the LAN cannot read the streams.
 */
class StreamProxy : NanoHTTPD("127.0.0.1", 0) {

    private data class Ref(val vfs: Vfs, val path: String, val size: Long, val mime: String)

    private val refs = ConcurrentHashMap<String, Ref>()
    private val order = ConcurrentLinkedQueue<String>()

    fun startProxy() {
        start(SOCKET_READ_TIMEOUT, false)
    }

    fun register(vfs: Vfs, path: String, size: Long, fileName: String): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        refs[token] = Ref(vfs, path, size, mimeOf(fileName))
        order.add(token)
        while (order.size > MAX_REFS) {
            order.poll()?.let { refs.remove(it) }
        }
        return "http://127.0.0.1:$listeningPort/s/$token/${Uri.encode(fileName)}"
    }

    fun unregister(url: String) {
        val token = url.substringAfter("/s/", "").substringBefore('/')
        if (token.isNotEmpty()) {
            refs.remove(token)
            order.remove(token)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.split("/")
        if (parts.size < 4 || parts[1] != "s") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
        val ref = refs[parts[2]]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "expired")

        val total = ref.size

        if (session.method == Method.HEAD) {
            return newFixedLengthResponse(Response.Status.OK, ref.mime, null, total).apply {
                addHeader("Accept-Ranges", "bytes")
            }
        }
        if (session.method != Method.GET) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "method not allowed")
        }

        val rangeHeader = session.headers["range"]
        var start = 0L
        var end = total - 1
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val s = spec.substring(0, dash).trim()
                val e = spec.substring(dash + 1).trim()
                when {
                    s.isEmpty() && e.isNotEmpty() -> {
                        val suffix = e.toLongOrNull() ?: 0L
                        start = (total - suffix).coerceAtLeast(0L)
                    }
                    s.isNotEmpty() -> {
                        start = s.toLongOrNull() ?: 0L
                        if (e.isNotEmpty()) end = minOf(e.toLongOrNull() ?: end, total - 1)
                    }
                }
            }
        }

        if (total == 0L) {
            return newFixedLengthResponse(Response.Status.OK, ref.mime, null, 0).apply {
                addHeader("Accept-Ranges", "bytes")
            }
        }
        if (start >= total || start > end) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "range").apply {
                addHeader("Content-Range", "bytes */$total")
            }
        }

        val len = end - start + 1
        val ra = try {
            ref.vfs.openRandomAccess(ref.path)
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "open failed: ${e.message}")
        }

        val partial = rangeHeader != null
        val status = if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        return newFixedLengthResponse(status, ref.mime, RaInputStream(ra, start, len), len).apply {
            addHeader("Accept-Ranges", "bytes")
            if (partial) addHeader("Content-Range", "bytes $start-$end/$total")
        }
    }

    /** Wraps a VfsRandomAccess as an InputStream limited to [start, start+len). */
    private class RaInputStream(
        private val ra: VfsRandomAccess,
        start: Long,
        private var left: Long,
    ) : InputStream() {
        private var pos = start
        private val chunk = ByteArray(64 * 1024)
        private val one = ByteArray(1)

        override fun read(): Int {
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val want = minOf(len.toLong(), left, chunk.size.toLong()).toInt()
            val buf = if (want == chunk.size) chunk else ByteArray(want)
            val n = ra.readAt(pos, buf)
            if (n <= 0) return -1
            System.arraycopy(buf, 0, b, off, n)
            pos += n
            left -= n
            return n
        }

        override fun close() {
            runCatching { ra.close() }
        }
    }

    companion object {
        private const val MAX_REFS = 32

        private val VIDEO_MIME = mapOf(
            "mkv" to "video/x-matroska",
            "mp4" to "video/mp4",
            "m4v" to "video/mp4",
            "mov" to "video/quicktime",
            "avi" to "video/x-msvideo",
            "wmv" to "video/x-ms-wmv",
            "flv" to "video/x-flv",
            "webm" to "video/webm",
            "ts" to "video/mp2t",
            "m2ts" to "video/mp2t",
            "mpg" to "video/mpeg",
            "mpeg" to "video/mpeg",
            "3gp" to "video/3gpp",
            "rmvb" to "video/vnd.rn-realvideo",
            "mp3" to "audio/mpeg",
            "flac" to "audio/flac",
            "wav" to "audio/x-wav",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "opus" to "audio/opus",
            "ape" to "audio/x-ape",
            "wma" to "audio/x-ms-wma",
        )

        fun mimeOf(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            VIDEO_MIME[ext]?.let { return it }
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
            return "application/octet-stream"
        }

        fun isMedia(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_MIME) return true
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: return false
            return mime.startsWith("video/") || mime.startsWith("audio/")
        }
    }
}
