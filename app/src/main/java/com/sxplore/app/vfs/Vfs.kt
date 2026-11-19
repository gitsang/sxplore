package com.sxplore.app.vfs

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Unified file entry. path is absolute within the VFS, always '/'-separated,
 * starting with '/'. Example: "/Movies/demo.mkv".
 */
data class VfsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * Random-access handle for positional reads by the streaming proxy (HTTP Range -> readAt).
 * Each HTTP request owns its handle, avoiding concurrent cursor conflicts.
 */
interface VfsRandomAccess : Closeable {
    val length: Long

    /** Reads up to buf.size bytes at offset; returns the count read, or -1 at EOF. */
    fun readAt(offset: Long, buf: ByteArray): Int
}

/**
 * Virtual file system abstraction: one interface for local storage / SMB.
 * Adding WebDAV / FTP / SFTP later only requires a new implementation.
 * All methods are blocking IO; callers must switch to Dispatchers.IO.
 */
interface Vfs : Closeable {
    /** Stable identifier, e.g. "local" or "smb://host/share". */
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
