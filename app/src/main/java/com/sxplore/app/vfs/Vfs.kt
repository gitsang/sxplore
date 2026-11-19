package com.sxplore.app.vfs

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * 统一文件条目。path 为 VFS 内绝对路径，始终使用 '/' 分隔，以 '/' 开头。
 * 例如 "/Movies/demo.mkv"。
 */
data class VfsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * 随机读句柄，供流式代理按偏移读取（HTTP Range → readAt）。
 * 每个 HTTP 请求独占一个句柄，避免并发游标冲突。
 */
interface VfsRandomAccess : Closeable {
    val length: Long

    /** 从 offset 读取最多 buf.size 字节，返回实际读取数；EOF 返回 -1。 */
    fun readAt(offset: Long, buf: ByteArray): Int
}

/**
 * 虚拟文件系统抽象：本地存储 / SMB 统一接口。
 * 后续扩展 WebDAV / FTP / SFTP 只需新增实现。
 * 所有方法均为阻塞 IO，调用方负责切到 Dispatchers.IO。
 */
interface Vfs : Closeable {
    /** 稳定标识，如 "local" 或 "smb://host/share"。 */
    val id: String
    val displayName: String

    fun list(path: String): List<VfsEntry>
    fun stat(path: String): VfsEntry
    fun openRandomAccess(path: String): VfsRandomAccess
    fun openInput(path: String): InputStream
    fun openOutput(path: String): OutputStream
    fun mkdir(path: String)
    fun delete(path: String)
    fun rename(path: String, newName: String)
}

object VfsPaths {
    fun parent(path: String): String {
        val p = path.trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "/" else p.substring(0, idx)
    }

    fun join(dir: String, name: String): String =
        dir.trimEnd('/') + "/" + name

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')
}
