package com.sxplore.app.caption

/**
 * On-device Whisper via whisper.cpp (JNI, arm64-v8a).
 * Models are ggml .bin files downloaded by ModelDownloader.
 */
object WhisperLocal {

    var loadError: String? = null
        private set

    val available: Boolean by lazy {
        try {
            System.loadLibrary("whisper_jni")
            true
        } catch (e: Throwable) {
            loadError = e.message ?: e.javaClass.simpleName
            com.sxplore.app.util.DebugLog.e(
                "Whisper",
                "loadLibrary failed | ABIs=${android.os.Build.SUPPORTED_ABIS.joinToString()} | $loadError",
                e,
            )
            false
        }
    }

    /** Loads a model; returns a context handle (0 on failure). Blocking, heavy. */
    external fun init(modelPath: String): Long

    /**
     * Recognizes 16kHz mono float PCM.
     * Returns segments as lines "startMs\tendMs\ttext" (relative to the chunk),
     * or an empty string on failure. Blocking.
     */
    external fun transcribe(ctx: Long, pcm: FloatArray, language: String, threads: Int): String

    external fun free(ctx: Long)

    fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
}
