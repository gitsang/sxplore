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
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.hierynomus.smbj.share.File as SmbFile

/**
 * SMB VFS（smbj，SMB2/3）。
 * - 断线自动重连（操作失败后重建会话并重试一次）
 * - 随机读句柄按偏移读取，供流式代理使用，全程不落盘
 */
class SmbVfs(val info: SmbConnectionInfo) : Vfs {

    override val id: String = "smb://${info.displayHost}/${info.share}"
    override val displayName: String = info.name

    private val clientConfig: SmbConfig = SmbConfig.builder()
        .withTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(60, TimeUnit.SECONDS)
        .build()

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
        return ds
    }

    /** 连接失效时重建并重试一次。 */
    private fun <T> withRetry(block: (DiskShare) -> T): T {
        return try {
            block(share())
        } catch (e: Exception) {
            synchronized(this) { closeQuietly() }
            block(share())
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
            override fun readAt(offset: Long, buf: ByteArray): Int {
                if (offset >= len) return -1
                val n = f.read(buf, offset).toInt()
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
