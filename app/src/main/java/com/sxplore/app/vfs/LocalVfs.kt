package com.sxplore.app.vfs

import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Local storage VFS rooted at the external storage root (requires "All files access").
 */
class LocalVfs : Vfs {

    val root: File = Environment.getExternalStorageDirectory()

    override val id: String = "local"
    override val displayName: String = "Local storage"

    fun realFile(path: String): File =
        if (path == "/" || path.isEmpty()) root else File(root, path.trimStart('/'))

    private fun toEntry(f: File): VfsEntry {
        val rel = "/" + f.relativeTo(root).path.replace(File.separatorChar, '/')
        return VfsEntry(
            name = f.name,
            path = if (rel == "/.") "/" else rel,
            isDir = f.isDirectory,
            size = if (f.isDirectory) 0L else f.length(),
            lastModified = f.lastModified(),
        )
    }

    override fun list(path: String): List<VfsEntry> {
        val dir = realFile(path)
        val files = dir.listFiles() ?: throw IllegalStateException("Cannot read directory: $path")
        return files.map { toEntry(it) }
    }

    override fun stat(path: String): VfsEntry {
        val f = realFile(path)
        if (!f.exists()) throw IllegalStateException("File not found: $path")
        return toEntry(f)
    }

    override fun openRandomAccess(path: String): VfsRandomAccess {
        val raf = RandomAccessFile(realFile(path), "r")
        return object : VfsRandomAccess {
            override val length: Long = raf.length()
            override fun readAt(offset: Long, buf: ByteArray): Int {
                if (offset >= length) return -1
                synchronized(raf) {
                    raf.seek(offset)
                    val n = raf.read(buf)
                    return if (n <= 0) -1 else n
                }
            }
            override fun close() = raf.close()
        }
    }

    override fun openInput(path: String): InputStream = FileInputStream(realFile(path))

    override fun openOutput(path: String): OutputStream {
        val f = realFile(path)
        f.parentFile?.mkdirs()
        return FileOutputStream(f)
    }

    override fun mkdir(path: String) {
        val f = realFile(path)
        if (!f.exists() && !f.mkdirs()) throw IllegalStateException("Failed to create directory: $path")
    }

    override fun delete(path: String) {
        val f = realFile(path)
        if (!f.deleteRecursively()) throw IllegalStateException("Delete failed: $path")
    }

    override fun rename(path: String, newName: String) {
        val f = realFile(path)
        val target = File(f.parentFile, newName)
        if (target.exists()) throw IllegalStateException("Target already exists: $newName")
        if (!f.renameTo(target)) throw IllegalStateException("Rename failed: $path")
    }

    override fun close() {}
}
