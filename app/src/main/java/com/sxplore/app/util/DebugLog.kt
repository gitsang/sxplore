package com.sxplore.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unified diagnostic logger. Always logs to logcat; when debug mode is on,
 * also appends to a rotating private file that the user can export from
 * Settings (no adb needed).
 */
object DebugLog {

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    @Volatile
    var enabled: Boolean = false

    private lateinit var dir: File
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context, enabled: Boolean) {
        dir = File(context.filesDir, "logs")
        dir.mkdirs()
        this.enabled = enabled
        if (enabled) {
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?"
            d("App", "=== debug session start | version $version | ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT}) ===")
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        write("D", tag, msg, null)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        write("W", tag, msg, null)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        write("E", tag, msg, tr)
    }

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        if (!enabled || !::dir.isInitialized) return
        synchronized(lock) {
            try {
                val f = File(dir, "sxplore.log")
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    val old = File(dir, "sxplore.log.1")
                    old.delete()
                    f.renameTo(old)
                }
                val sb = StringBuilder()
                    .append(fmt.format(Date())).append(' ')
                    .append(level).append('/').append(tag).append(": ")
                    .append(msg).append('\n')
                if (tr != null) sb.append(Log.getStackTraceString(tr)).append('\n')
                FileOutputStream(f, true).use { it.write(sb.toString().toByteArray()) }
            } catch (_: Exception) {
                // never let logging break the app
            }
        }
    }

    fun logFile(): File = File(dir, "sxplore.log")

    fun totalSize(): Long = synchronized(lock) {
        listOf(File(dir, "sxplore.log"), File(dir, "sxplore.log.1"))
            .filter { it.exists() }.sumOf { it.length() }
    }

    fun clear() {
        synchronized(lock) {
            File(dir, "sxplore.log").delete()
            File(dir, "sxplore.log.1").delete()
        }
    }

    /** Packs current + rotated logs into a zip for sharing. Returns false if empty. */
    fun exportZip(target: File): Boolean {
        synchronized(lock) {
            val files = listOf(File(dir, "sxplore.log"), File(dir, "sxplore.log.1"))
                .filter { it.exists() && it.length() > 0 }
            if (files.isEmpty()) return false
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                files.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return true
        }
    }
}
