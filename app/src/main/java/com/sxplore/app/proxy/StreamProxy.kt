package com.sxplore.app.proxy

import android.net.Uri
import android.webkit.MimeTypeMap
import com.sxplore.app.vfs.Vfs
import com.sxplore.app.vfs.VfsRandomAccess
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

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
        val chunk = minOf(ref.vfs.preferredReadSize, 1024 * 1024)
        return newFixedLengthResponse(status, ref.mime, PrefetchInputStream(ra, start, len, chunk), len).apply {
            addHeader("Accept-Ranges", "bytes")
            if (partial) addHeader("Content-Range", "bytes $start-$end/$total")
        }
    }

    /**
     * Prefetching InputStream over a VfsRandomAccess, limited to [start, start+total).
     *
     * A background worker reads ahead in ~1MB chunks into a bounded queue, so
     * SMB round-trip latency and Wi-Fi jitter are hidden from the player: the
     * HTTP side consumes buffered chunks while the next ones are already in
     * flight. Without prefetch, every small read waits a full RTT and playback
     * constantly re-buffers.
     */
    private class PrefetchInputStream(
        private val ra: VfsRandomAccess,
        start: Long,
        total: Long,
        chunkSize: Int,
    ) : InputStream() {

        private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_DEPTH)
        @Volatile private var failure: IOException? = null
        @Volatile private var closed = false
        @Volatile private var done = false

        private var current: ByteArray? = null
        private var currentPos = 0
        private var remaining = total

        private val worker = Thread({
            try {
                var pos = start
                var left = total
                while (!closed && left > 0) {
                    val want = minOf(chunkSize.toLong(), left).toInt()
                    val buf = ByteArray(want)
                    var filled = 0
                    while (filled < want && !closed) {
                        val n = ra.readAt(pos + filled, buf, filled, want - filled)
                        if (n <= 0) break
                        filled += n
                    }
                    if (filled == 0) break
                    queue.put(if (filled == want) buf else buf.copyOf(filled))
                    pos += filled
                    left -= filled
                    if (filled < want) break // EOF
                }
            } catch (_: InterruptedException) {
                // closed while blocked on the queue
            } catch (e: Exception) {
                failure = IOException("prefetch failed", e)
            } finally {
                done = true
            }
        }, "sxplore-prefetch").apply {
            isDaemon = true
            start()
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("stream closed")
            if (remaining <= 0) return -1
            while (current == null || currentPos >= current!!.size) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (done) {
                        failure?.let { throw it }
                        return -1
                    }
                    if (closed) throw IOException("stream closed")
                    continue
                }
                current = chunk
                currentPos = 0
            }
            val c = current!!
            val n = minOf(len, c.size - currentPos, remaining.toInt())
            System.arraycopy(c, currentPos, b, off, n)
            currentPos += n
            remaining -= n
            return n
        }

        override fun close() {
            if (closed) return
            closed = true
            worker.interrupt()
            queue.clear()
            runCatching { ra.close() }
        }

        companion object {
            private const val QUEUE_DEPTH = 4
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
