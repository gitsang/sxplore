package com.sxplore.app.vfs

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.sxplore.app.util.DebugLog
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.hierynomus.smbj.share.File as SmbFile

/**
 * SMB VFS (smbj, SMB2/3).
 * - Auto-reconnect: rebuild the session and retry once after a failure
 * - Positional random-access reads for the streaming proxy, zero disk caching
 */
class SmbVfs(val info: SmbConnectionInfo) : Vfs {

    override val id: String = "smb://${info.displayHost}/${info.share}"
    override val displayName: String = info.name

    private val clientConfig: SmbConfig = SmbConfig.builder()
        // Fail fast on stalled reads (server-side AV scan / dedup recall / disk
        // spin-up can hang a single READ); the proxy layer retries transparently.
        .withTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(60, TimeUnit.SECONDS)
        // Use the server-negotiated max read/write/transact sizes (multi-MB),
        // like desktop SMB clients. The 64KB default turns streaming into
        // hundreds of tiny round-trips and cripples throughput on Wi-Fi.
        .withNegotiatedBufferSize()
        .build()

    @Volatile
    private var negotiatedMaxRead: Int = 1024 * 1024

    override val preferredReadSize: Int
        get() = negotiatedMaxRead.coerceIn(64 * 1024, 8 * 1024 * 1024)

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        val s = share
        if (s != null && s.isConnected) return s
        closeQuietly()
        val c = SMBClient(clientConfig)
        val conn = c.connect(info.host, info.port)
        val auth = if (info.username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(info.username, info.password.toCharArray(), info.domain)
        }
        val sess = conn.authenticate(auth)
        val ds = sess.connectShare(info.share) as DiskShare
        client = c
        connection = conn
        session = sess
        share = ds
        negotiatedMaxRead = conn.negotiatedProtocol.maxReadSize
        com.sxplore.app.util.DebugLog.d(
            "SxploreSmb",
            "connected ${info.displayHost}/${info.share} dialect=${conn.negotiatedProtocol.dialect} maxRead=$negotiatedMaxRead",
        )
        return ds
    }

    /**
     * Retry once on failure. Only tear down and reconnect when the session is
     * actually dead — reconnecting on operation-level errors would kill every
     * other in-flight stream sharing this session (playback + captions).
     */
    private fun <T> withRetry(block: (DiskShare) -> T): T {
        return try {
            block(share())
        } catch (e: Exception) {
            val s = share
            if (s != null && s.isConnected) {
                DebugLog.w("SxploreSmb", "op failed on healthy session, retrying: ${e.message}")
                block(s)
            } else {
                DebugLog.w("SxploreSmb", "session lost, reconnecting: ${e.message}")
                synchronized(this) { closeQuietly() }
                block(share())
            }
        }
    }

    private fun toSmb(path: String): String =
        path.trimStart('/').replace('/', '\\')

    private fun fromSmb(parentPath: String, name: String): String =
        VfsPaths.join(parentPath, name)

    override fun list(path: String): List<VfsEntry> = withRetry { s ->
        s.list(toSmb(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                VfsEntry(
                    name = info.fileName,
                    path = fromSmb(path, info.fileName),
                    isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                    size = info.endOfFile,
                    lastModified = info.changeTime.toEpochMillis(),
                )
            }
    }

    override fun stat(path: String): VfsEntry = withRetry { s ->
        if (path == "/" || path.isEmpty()) {
            return@withRetry VfsEntry("/", "/", true, 0, 0)
        }
        val all = s.getFileInformation(toSmb(path), FileAllInformation::class.java)
        VfsEntry(
            name = VfsPaths.name(path),
            path = path,
            isDir = all.standardInformation.isDirectory,
            size = all.standardInformation.endOfFile,
            lastModified = all.basicInformation.changeTime.toEpochMillis(),
        )
    }

    private fun openRead(path: String): SmbFile = withRetry { s ->
        s.openFile(
            toSmb(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    override fun openRandomAccess(path: String): VfsRandomAccess {
        val f = openRead(path)
        val len = f.fileInformation.standardInformation.endOfFile
        return object : VfsRandomAccess {
            override val length: Long = len
            override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
                if (offset >= length) return -1
                val n = f.read(buf, offset, off, len).toInt()
                return if (n <= 0) -1 else n
            }
            override fun close() {
                runCatching { f.close() }
            }
        }
    }

    override fun openInput(path: String): InputStream {
        val f = openRead(path)
        val closed = AtomicBoolean(false)
        return object : FilterInputStream(f.inputStream) {
            override fun close() {
                runCatching { super.close() }
                if (closed.compareAndSet(false, true)) runCatching { f.close() }
            }
        }
    }

    override fun openOutput(path: String): OutputStream = withRetry { s ->
        val f = s.openFile(
            toSmb(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null,
        )
        val closed = AtomicBoolean(false)
        object : FilterOutputStream(f.outputStream) {
            override fun close() {
                runCatching { super.close() }
                if (closed.compareAndSet(false, true)) runCatching { f.close() }
            }
        }
    }

    override fun mkdir(path: String) {
        withRetry { s -> s.mkdir(toSmb(path)) }
    }

    override fun delete(path: String) {
        withRetry { s ->
            val st = stat(path)
            if (st.isDir) s.rmdir(toSmb(path), true) else s.rm(toSmb(path))
        }
    }

    override fun rename(path: String, newName: String) {
        val target = VfsPaths.join(VfsPaths.parent(path), newName)
        withRetry { s ->
            val st = stat(path)
            val entry = if (st.isDir) {
                s.openDirectory(
                    toSmb(path),
                    EnumSet.of(AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
            } else {
                s.openFile(
                    toSmb(path),
                    EnumSet.of(AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
            }
            entry.use { it.rename(toSmb(target)) }
        }
    }

    @Synchronized
    private fun closeQuietly() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        connection = null
        client = null
    }

    override fun close() = closeQuietly()
}
