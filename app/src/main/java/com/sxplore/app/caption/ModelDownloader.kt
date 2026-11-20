package com.sxplore.app.caption

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Downloads ggml Whisper models with progress, cancellation and deletion.
 * Default source is hf-mirror.com (reachable from China); configurable.
 */
class ModelDownloader(context: Context) {

    enum class Status { IDLE, DOWNLOADING, READY, ERROR }

    data class State(val status: Status, val progress: Int = 0, val message: String = "")

    data class ModelInfo(val name: String, val label: String, val sizeMb: Int)

    val models = listOf(
        ModelInfo("tiny", "Tiny", 75),
        ModelInfo("base", "Base", 142),
        ModelInfo("small", "Small", 466),
    )

    val states = mutableStateMapOf<String, State>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val dir = File(context.filesDir, "whisper")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun modelFile(name: String): File = File(dir, "ggml-$name.bin")

    fun isReady(name: String): Boolean {
        val f = modelFile(name)
        return f.exists() && f.length() > 1_000_000
    }

    fun stateOf(name: String): State =
        states[name] ?: if (isReady(name)) State(Status.READY, 100) else State(Status.IDLE)

    fun download(name: String, sourceBase: String) {
        if (stateOf(name).status == Status.DOWNLOADING) return
        states[name] = State(Status.DOWNLOADING, 0)
        jobs[name] = scope.launch {
            var part: File? = null
            try {
                dir.mkdirs()
                val base = sourceBase.trim().trimEnd('/')
                val url = "$base/ggerganov/whisper.cpp/resolve/main/ggml-$name.bin"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty response")
                    val total = body.contentLength()
                    part = File(dir, "ggml-$name.bin.part")
                    part!!.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
                        var done = 0L
                        body.byteStream().use { input ->
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    states[name] = State(Status.DOWNLOADING, (done * 100 / total).toInt())
                                }
                            }
                        }
                    }
                }
                val target = modelFile(name)
                target.delete()
                if (!part!!.renameTo(target)) throw IOException("Rename failed")
                states[name] = State(Status.READY, 100)
            } catch (e: CancellationException) {
                part?.delete()
                states[name] = State(Status.IDLE)
            } catch (e: Exception) {
                part?.delete()
                states[name] = State(Status.ERROR, 0, e.message ?: "Download failed")
            }
        }
    }

    fun cancel(name: String) {
        jobs[name]?.cancel()
    }

    fun delete(name: String) {
        modelFile(name).delete()
        states[name] = State(Status.IDLE)
    }
}
