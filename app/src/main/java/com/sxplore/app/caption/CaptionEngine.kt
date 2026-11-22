package com.sxplore.app.caption

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.sxplore.app.data.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** One subtitle cue in absolute media time. */
data class CaptionCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String?,
)

data class CaptionConfig(
    val whisperBase: String,
    val whisperKey: String,
    val whisperModel: String,
    val whisperLanguage: String,
    val translate: Boolean,
    val targetLang: String,
    val transBase: String,
    val transKey: String,
    val transModel: String,
    val chunkSeconds: Int = 8,
    /** Non-null = use on-device whisper.cpp with this model file. */
    val localModelPath: String? = null,
) {
    companion object {
        /** Accepts bases with or without the /v1 suffix. */
        fun normalizeBase(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.isNotEmpty() && !u.endsWith("/v1")) u += "/v1"
            return u
        }

        /** null when the selected engine is not configured. */
        fun from(s: SettingsStore, localModelPath: String?): CaptionConfig? {
            val provider = s.asrProvider
            val local = provider == "local"
            if (local && localModelPath == null) return null
            val base = if (local) "" else normalizeBase(s.effectiveBase("asr", provider))
            val key = if (local) "" else s.providerKey("asr", provider).trim()
            if (!local && (base.isEmpty() || key.isEmpty())) return null
            val tProvider = s.transProvider
            val transBase = normalizeBase(s.effectiveBase("trans", tProvider))
            val transKey = s.providerKey("trans", tProvider).trim()
            return CaptionConfig(
                whisperBase = base,
                whisperKey = key,
                whisperModel = s.effectiveModel("asr", provider).ifBlank { "whisper-1" },
                whisperLanguage = s.whisperLanguage.trim(),
                translate = s.translationEnabled && transBase.isNotEmpty() && transKey.isNotEmpty(),
                targetLang = s.translationTargetLang.ifBlank { "Chinese" },
                transBase = transBase,
                transKey = transKey,
                transModel = s.effectiveModel("trans", tProvider).ifBlank { "gpt-4o-mini" },
                localModelPath = if (local) localModelPath else null,
            )
        }
    }
}

/**
 * Live AI caption engine.
 *
 * Opens a SECOND stream of the playing media (proxy URL or file), demuxes and
 * decodes the audio track with MediaExtractor/MediaCodec, resamples to 16kHz
 * mono PCM, and sends ~8s WAV chunks to a Whisper-compatible
 * /audio/transcriptions endpoint. Segment timestamps are mapped to absolute
 * media time; an optional chat-completions call translates each chunk
 * line-by-line for bilingual display.
 *
 * The decode loop runs AHEAD of playback (up to 60s) so captions are ready
 * before the audio is heard, instead of lagging behind real-time.
 */
class CaptionEngine(
    private val url: String,
    private val startMs: Long,
    private val config: CaptionConfig,
    private val playbackPositionMs: () -> Long,
    private val onCues: (List<CaptionCue>) -> Unit,
    private val onStatus: (String) -> Unit,
) {

    private val cues = CopyOnWriteArrayList<CaptionCue>()
    @Volatile private var cancelled = false
    private var thread: Thread? = null
    private var whisperCtx: Long = 0L
    @Volatile private var slowHintShown = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun start() {
        thread = Thread({ run() }, "sxplore-captions").apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() {
        cancelled = true
        thread?.interrupt()
    }

    private fun run() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            var openTries = 0
            while (true) {
                try {
                    extractor.setDataSource(url)
                    break
                } catch (e: Exception) {
                    openTries++
                    if (cancelled || openTries >= 3) throw e
                    com.sxplore.app.util.DebugLog.w("Captions", "extractor open failed (attempt $openTries): ${e.message}")
                    Thread.sleep(600)
                }
            }
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) {
                onStatus("AI subtitles: no audio track")
                return
            }
            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            com.sxplore.app.util.DebugLog.d(
                "Captions",
                "audio track: $mime rate=${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)} ch=${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} startMs=$startMs local=${config.localModelPath != null}",
            )
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            ) {
                onStatus("AI subtitles: unsupported float PCM audio")
                return
            }
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // On-device engine: load the ggml model once per run.
            if (config.localModelPath != null) {
                if (!WhisperLocal.available) {
                    onStatus("AI subtitles: local engine unavailable (${WhisperLocal.loadError ?: "unknown error"})")
                    return
                }
                onStatus("Loading local Whisper model…")
                whisperCtx = WhisperLocal.init(config.localModelPath)
                if (whisperCtx == 0L) {
                    onStatus("AI subtitles: failed to load model file")
                    return
                }
                onStatus("")
            }

            val info = MediaCodec.BufferInfo()
            val chunkTargetBytes = config.chunkSeconds * 16000 * 2
            var pcm = ByteArrayOutputStream()
            var chunkStartMs = startMs
            var gotFirstPts = false
            var eosInput = false
            var aheadLogged = false
            var firstPcmLogged = false

            while (!cancelled) {
                // Pace: stay at most ~60s ahead of playback to bound API cost
                // and bandwidth on slow links. (16kHz mono 16-bit = 32 bytes/ms)
                val aheadMs = chunkStartMs + pcm.size() / 32 - playbackPositionMs()
                if (aheadMs > 60_000) {
                    if (!aheadLogged) {
                        com.sxplore.app.util.DebugLog.d("Captions", "running ${aheadMs}ms ahead of playback, waiting")
                        aheadLogged = true
                    }
                    Thread.sleep(500)
                    continue
                }

                if (!eosInput) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosInput = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0 && info.presentationTimeUs >= startMs * 1000) {
                        if (!gotFirstPts) {
                            chunkStartMs = info.presentationTimeUs / 1000
                            gotFirstPts = true
                        }
                        if (!firstPcmLogged) {
                            com.sxplore.app.util.DebugLog.d("Captions", "first PCM pts=${info.presentationTimeUs / 1000}ms size=${info.size}")
                            firstPcmLogged = true
                        }
                        val out = codec.getOutputBuffer(outIdx)!!
                        val bytes = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(bytes, 0, info.size)
                        pcm.write(resampleTo16kMono(bytes, sampleRate, channels))
                        if (pcm.size() >= chunkTargetBytes) {
                            val data = pcm.toByteArray()
                            flushChunk(data, chunkStartMs)
                            chunkStartMs += data.size / 32
                            pcm = ByteArrayOutputStream()
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        val data = pcm.toByteArray()
                        if (data.size >= 16000) flushChunk(data, chunkStartMs) // >= 0.5s tail
                        break
                    }
                }
            }
        } catch (e: InterruptedException) {
            // cancelled
        } catch (e: Exception) {
            if (!cancelled) {
                com.sxplore.app.util.DebugLog.e("Captions", "engine failed", e)
                onStatus("AI subtitles: ${e.message ?: e.javaClass.simpleName}")
            }
        } finally {
            if (whisperCtx != 0L) {
                runCatching { WhisperLocal.free(whisperCtx) }
                whisperCtx = 0L
            }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    /** Recognize one chunk (local whisper.cpp or Whisper API), optionally translate, publish cues. */
    private fun flushChunk(pcm: ByteArray, chunkStartMs: Long) {
        com.sxplore.app.util.DebugLog.d("Captions", "chunk flush: ${pcm.size}B @${chunkStartMs}ms")
        data class Line(val text: String, val startSec: Double, val endSec: Double)
        val lines = ArrayList<Line>()

        if (whisperCtx != 0L) {
            // ---- On-device whisper.cpp ----
            val floats = FloatArray(pcm.size / 2)
            val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            for (i in floats.indices) floats[i] = bb.getShort(i * 2) / 32768f
            val t0 = System.currentTimeMillis()
            val result = WhisperLocal.transcribe(
                whisperCtx, floats, config.whisperLanguage, WhisperLocal.defaultThreads(),
            )
            if (result.startsWith("ERR:")) {
                com.sxplore.app.util.DebugLog.e("Captions", "whisper_full failed: $result")
                return
            }
            result.lines().forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split('\t')
                if (parts.size >= 3) {
                    val ts0 = parts[0].toLongOrNull() ?: return@forEach
                    val ts1 = parts[1].toLongOrNull() ?: return@forEach
                    val text = parts.subList(2, parts.size).joinToString(" ").trim()
                    if (text.isNotEmpty()) lines.add(Line(text, ts0 / 1000.0, ts1 / 1000.0))
                }
            }
            com.sxplore.app.util.DebugLog.d(
                "Captions",
                "whisper: ${lines.size} lines in ${System.currentTimeMillis() - t0}ms (raw ${result.length} chars)",
            )
            val inferMs = System.currentTimeMillis() - t0
            if (!slowHintShown && inferMs > pcm.size / 32) {
                slowHintShown = true
                onStatus("Local model is slower than real-time (${inferMs / 1000}s per ${pcm.size / 32000}s chunk) — Tiny model recommended")
            }
        } else {
            // ---- Whisper API ----
            val wav = wavBytes(pcm)
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "chunk.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", config.whisperModel)
                .addFormDataPart("response_format", "verbose_json")
            if (config.whisperLanguage.isNotBlank()) {
                builder.addFormDataPart("language", config.whisperLanguage)
            }
            val req = Request.Builder()
                .url("${config.whisperBase}/audio/transcriptions")
                .header("Authorization", "Bearer ${config.whisperKey}")
                .post(builder.build())
                .build()
            val json = client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw IOException("Whisper HTTP ${resp.code}: ${body.take(200)}")
                }
                JSONObject(body)
            }
            val segments = json.optJSONArray("segments")
            if (segments != null && segments.length() > 0) {
                for (i in 0 until segments.length()) {
                    val s = segments.getJSONObject(i)
                    val t = s.optString("text").trim()
                    if (t.isNotEmpty()) lines.add(Line(t, s.optDouble("start", 0.0), s.optDouble("end", 0.0)))
                }
            } else {
                val t = json.optString("text").trim()
                if (t.isNotEmpty()) lines.add(Line(t, 0.0, pcm.size / 32000.0))
            }
        }
        if (lines.isEmpty()) return

        val translations = if (config.translate) translateLines(lines.map { it.text }) else null
        val newCues = lines.mapIndexed { i, l ->
            CaptionCue(
                startMs = chunkStartMs + (l.startSec * 1000).toLong(),
                endMs = chunkStartMs + (l.endSec * 1000).toLong(),
                text = l.text,
                translation = translations?.getOrNull(i),
            )
        }
        cues.addAll(newCues)
        com.sxplore.app.util.DebugLog.d(
            "Captions",
            "cues +${newCues.size}: ${newCues.first().startMs}..${newCues.last().endMs}ms | \"${newCues.first().text.take(40)}\"",
        )
        onCues(cues.toList())
    }

    /** One chat call per chunk; preserves line count/order for timing mapping. */
    private fun translateLines(lines: List<String>): List<String>? {
        return try {
            val payload = JSONObject().apply {
                put("model", config.transModel)
                put("temperature", 0)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "You are a subtitle translator. Translate each input line into ${config.targetLang}. " +
                                "Keep the exact number of lines and their order. " +
                                "Output only the translated lines, no numbering, no explanations."
                        )
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", lines.joinToString("\n"))
                    })
                })
            }
            val req = Request.Builder()
                .url("${config.transBase}/chat/completions")
                .header("Authorization", "Bearer ${config.transKey}")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val content = client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw IOException("Translate HTTP ${resp.code}: ${body.take(200)}")
                JSONObject(body).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
            }
            val out = content.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (out.size == lines.size) out else null
        } catch (e: Exception) {
            if (!cancelled) {
                com.sxplore.app.util.DebugLog.w("Captions", "translation failed: ${e.message}")
                onStatus("Translation: ${e.message ?: e.javaClass.simpleName}")
            }
            null
        }
    }

    /** Average channels + linear resample to 16kHz mono 16-bit little-endian. */
    private fun resampleTo16kMono(data: ByteArray, srcRate: Int, channels: Int): ByteArray {
        val frames = data.size / (2 * channels)
        if (frames == 0) return ByteArray(0)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += bb.getShort((i * channels + c) * 2)
            mono[i] = (sum / channels).toShort()
        }
        val out = if (srcRate == 16000) {
            mono
        } else {
            val outFrames = (frames.toLong() * 16000 / srcRate).toInt().coerceAtLeast(1)
            ShortArray(outFrames) { i ->
                val pos = i.toDouble() * srcRate / 16000
                val i0 = pos.toInt().coerceAtMost(frames - 1)
                val i1 = (i0 + 1).coerceAtMost(frames - 1)
                val frac = pos - i0
                (mono[i0] * (1 - frac) + mono[i1] * frac).toInt().toShort()
            }
        }
        val bytes = ByteArray(out.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(out)
        return bytes
    }

    private fun wavBytes(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = 16000 * 2
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)              // PCM
        header.putShort(1)              // mono
        header.putInt(16000)
        header.putInt(byteRate)
        header.putShort(2)              // block align
        header.putShort(16)             // bits per sample
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
