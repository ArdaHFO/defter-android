package dev.defter.app.audio

import dev.defter.app.localization.tr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WhisperSegment(val startMs: Long, val endMs: Long, val text: String)
data class WhisperChunk(val startMs: Long, val endMs: Long, val segments: List<WhisperSegment>)

interface LocalAudioTranscriber {
    /** Times are relative to this input file. Await each chunk's persistence before proceeding. */
    suspend fun transcribe(
        audioFile: File,
        startMs: Long = 0,
        language: String = "tr",
        onChunk: suspend (WhisperChunk) -> Unit,
    )
}

class WhisperTranscriber(
    private val modelManager: WhisperModelManager,
    private val decoder: AudioDecoder = AudioDecoder(),
) : LocalAudioTranscriber {
    override suspend fun transcribe(
        audioFile: File,
        startMs: Long,
        language: String,
        onChunk: suspend (WhisperChunk) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        require(language in setOf("tr", "en", "auto")) { tr("Desteklenmeyen yazıya dökme dili.") }
        require(startMs >= 0)
        val model = modelManager.verifiedModelFile() ?: throw IOException(tr("Önce Whisper dil modelini indirip doğrula."))
        // Validate media before allocating a model context.
        if (startMs >= decoder.durationMs(audioFile)) return@withContext
        currentCoroutineContext().ensureActive()
        WhisperNativeSession.open(model).use { session ->
            currentCoroutineContext().ensureActive()
            decoder.decode(audioFile, startMs) { chunk ->
                currentCoroutineContext().ensureActive()
                // Digital silence cannot contain speech. Do not ask a generative decoder to invent text for it.
                val raw = if (chunk.samples.all { it == 0f }) "[]".toByteArray() else session.transcribe(chunk.samples, language)
                val segments = parseSegments(raw, chunk.startMs, chunk.endMs)
                currentCoroutineContext().ensureActive()
                // An empty transcript still advances the durable checkpoint through silence.
                onChunk(WhisperChunk(chunk.startMs, chunk.endMs, segments))
            }
        }
    }

    internal companion object {
        fun parseSegments(bytes: ByteArray, startMs: Long, endMs: Long): List<WhisperSegment> {
            require(startMs >= 0 && endMs >= startMs)
            val array = JSONArray(bytes.toString(Charsets.UTF_8))
            require(array.length() <= 10_000) { tr("Aşırı büyük yazıya dökme sonucu.") }
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val rawBegin = item.getLong("startMs")
                    val rawEnd = item.getLong("endMs")
                    // Whisper pads short inputs internally. Ignore segments entirely in that padding.
                    if (rawBegin >= endMs - startMs || rawEnd <= 0 || rawEnd <= rawBegin) continue
                    val begin = rawBegin.coerceIn(0, endMs - startMs) + startMs
                    val end = rawEnd.coerceIn(0, endMs - startMs) + startMs
                    val text = item.getString("text").trim()
                    if (text.isNotEmpty() && end > begin) add(WhisperSegment(begin, end, text))
                }
            }
        }
    }
}

internal object WhisperNative {
    init { System.loadLibrary("defter_whisper") }
    external fun version(): String
    external fun create(modelPath: String, threads: Int): Long
    external fun resetCancellation(handle: Long)
    external fun transcribe(handle: Long, samples: FloatArray, language: String): ByteArray
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}

/** The native registry retains in-flight contexts until their worker exits after cancellation. */
internal class WhisperNativeSession private constructor(private val handle: Long) : Closeable {
    suspend fun transcribe(samples: FloatArray, language: String): ByteArray {
        require(samples.isNotEmpty() && samples.size <= AudioDecoder.CHUNK_SAMPLES)
        currentCoroutineContext().ensureActive()
        WhisperNative.resetCancellation(handle)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { WhisperNative.cancel(handle) }
            worker.execute {
                if (continuation.isActive) {
                    try {
                        val result = WhisperNative.transcribe(handle, samples, language)
                        continuation.resume(result)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        }
    }
    override fun close() { WhisperNative.cancel(handle); WhisperNative.close(handle) }

    companion object {
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Defter-Whisper").apply { isDaemon = true } }
        fun open(model: File): WhisperNativeSession {
            require(model.isFile && model.length() > 0) { tr("Whisper modeli bulunamadı.") }
            val handle = WhisperNative.create(model.absolutePath, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            if (handle == 0L) throw IOException(tr("Whisper modeli açılamadı."))
            return WhisperNativeSession(handle)
        }
    }
}
