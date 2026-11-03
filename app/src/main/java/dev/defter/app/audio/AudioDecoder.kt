package dev.defter.app.audio

import dev.defter.app.localization.tr

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

data class DecodedAudioChunk(val startMs: Long, val endMs: Long, val samples: FloatArray)

/** Uses device codecs; emits at most 30 seconds of 16 kHz mono PCM at once. */
class AudioDecoder {
    suspend fun durationMs(file: File): Long = withContext(Dispatchers.IO) {
        val extractor = open(file)
        try { audioTrack(extractor).second.durationMs() } finally { extractor.release() }
    }

    suspend fun decode(
        file: File,
        startMs: Long = 0,
        onChunk: suspend (DecodedAudioChunk) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        require(startMs >= 0) { tr("Başlangıç zamanı geçersiz.") }
        val extractor = open(file)
        var codec: MediaCodec? = null
        var started = false
        try {
            val (track, inputFormat) = audioTrack(extractor)
            val duration = inputFormat.durationMs()
            if (startMs >= duration) return@withContext
            extractor.selectTrack(track)
            if (startMs > 0) extractor.seekTo(startMs * 1_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw IOException(tr("Ses biçimi eksik."))
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            started = true
            var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            validatePcmFormat(rate, channels, encoding)
            var resampler: PcmResampler? = null
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            val chunk = FloatArray(CHUNK_SAMPLES)
            var filled = 0
            var emittedSamples = 0L
            var originUs: Long? = null
            var lastProgress = SystemClock.elapsedRealtime()

            suspend fun append(samples: FloatArray) {
                var offset = 0
                while (offset < samples.size) {
                    currentCoroutineContext().ensureActive()
                    val count = min(chunk.size - filled, samples.size - offset)
                    samples.copyInto(chunk, filled, offset, offset + count)
                    filled += count
                    offset += count
                    if (filled == chunk.size) {
                        val origin = checkNotNull(originUs)
                        val begin = (origin + emittedSamples * 1_000_000 / SAMPLE_RATE) / 1_000
                        emittedSamples += filled
                        val end = (origin + emittedSamples * 1_000_000 / SAMPLE_RATE) / 1_000
                        onChunk(DecodedAudioChunk(begin, end, chunk.copyOf()))
                        filled = 0
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
            }

            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: throw IOException(tr("Ses giriş tamponu alınamadı."))
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        val newRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val newEncoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else AudioFormat.ENCODING_PCM_16BIT
                        validatePcmFormat(newRate, newChannels, newEncoding)
                        if (resampler != null && (newRate != rate || newChannels != channels)) {
                            throw IOException(tr("Kayıt içinde değişen ses örnekleme biçimi desteklenmiyor."))
                        }
                        rate = newRate
                        channels = newChannels
                        encoding = newEncoding
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (SystemClock.elapsedRealtime() - lastProgress > 30_000) throw IOException(tr("Ses çözümleyici yanıt vermiyor."))
                    }
                    else -> if (index >= 0) {
                        val pcm: FloatArray
                        val presentationUs = info.presentationTimeUs
                        try {
                            pcm = if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = decoder.getOutputBuffer(index) ?: throw IOException(tr("Ses çıkış tamponu alınamadı."))
                                if (info.size > MAX_CODEC_BUFFER_BYTES || info.offset < 0 || info.offset.toLong() + info.size > buffer.capacity()) {
                                    throw IOException(tr("Geçersiz veya aşırı büyük ses tamponu."))
                                }
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                decodePcm(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, encoding)
                            } else FloatArray(0)
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
                        if (pcm.isNotEmpty()) {
                            // A seek can return a preceding AAC/MP3 frame. Drop its pre-roll using timestamps.
                            val skip = (((startMs * 1_000 - presentationUs).coerceAtLeast(0) * rate + 999_999) / 1_000_000)
                                .coerceAtMost(pcm.size.toLong()).toInt()
                            if (skip < pcm.size) {
                                if (originUs == null) originUs = (presentationUs + skip.toLong() * 1_000_000 / rate).coerceAtLeast(startMs * 1_000)
                                val converter = resampler ?: PcmResampler(rate).also { resampler = it }
                                append(converter.process(if (skip == 0) pcm else pcm.copyOfRange(skip, pcm.size)))
                            }
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
            }
            val converter = resampler ?: throw IOException(tr("Kayıtta çözümlenebilir ses bulunamadı."))
            append(converter.finish())
            if (filled > 0) {
                val origin = checkNotNull(originUs)
                val begin = (origin + emittedSamples * 1_000_000 / SAMPLE_RATE) / 1_000
                val end = ((origin + (emittedSamples + filled) * 1_000_000 / SAMPLE_RATE) / 1_000).coerceAtLeast(begin + 1)
                onChunk(DecodedAudioChunk(begin, end, chunk.copyOf(filled)))
            }
        } finally {
            if (started) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun open(file: File): MediaExtractor {
        require(file.isFile && file.length() > 0) { tr("Ses dosyası bulunamadı veya boş.") }
        val extractor = MediaExtractor()
        try { extractor.setDataSource(file.absolutePath) } catch (error: Exception) { extractor.release(); throw error }
        return extractor
    }

    private fun audioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat> {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return index to format
        }
        throw IOException(tr("Dosyada desteklenen bir ses izi bulunamadı."))
    }

    private fun MediaFormat.durationMs(): Long {
        if (!containsKey(MediaFormat.KEY_DURATION)) throw IOException(tr("Kayıt süresi okunamadı."))
        val durationUs = getLong(MediaFormat.KEY_DURATION)
        if (durationUs <= 0 || durationUs > 24L * 60 * 60 * 1_000_000) throw IOException(tr("Kayıt süresi geçersiz veya 24 saatten uzun."))
        return (durationUs + 999) / 1_000
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 30 * SAMPLE_RATE
        private const val MAX_CODEC_BUFFER_BYTES = 1_048_576

        internal fun validatePcmFormat(rate: Int, channels: Int, encoding: Int) {
            if (rate !in 8_000..192_000 || channels !in 1..8 || encoding !in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) {
                throw IOException(tr("Ses örnekleme biçimi desteklenmiyor."))
            }
        }

        internal fun decodePcm(buffer: ByteBuffer, channels: Int, encoding: Int): FloatArray {
            require(channels in 1..8)
            val bytes = when (encoding) { AudioFormat.ENCODING_PCM_16BIT -> 2; AudioFormat.ENCODING_PCM_FLOAT -> 4; else -> throw IOException(tr("PCM biçimi desteklenmiyor.")) }
            if (buffer.remaining() % (channels * bytes) != 0) throw IOException(tr("Kesilmiş PCM ses çerçevesi."))
            return FloatArray(buffer.remaining() / (channels * bytes)) {
                var value = 0f
                repeat(channels) {
                    val sample = if (bytes == 2) buffer.short / 32768f else buffer.float
                    value += if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
                }
                value / channels
            }
        }
    }
}

/** Bounded 32-tap windowed-sinc resampling. State is continuous across codec buffers. */
internal class PcmResampler(private val sourceRate: Int, private val targetRate: Int = 16_000) {
    init { require(sourceRate in 8_000..192_000 && targetRate == 16_000) }
    private val ring = FloatArray(64)
    private var count = 0L
    private var produced = 0L
    private var first = 0f
    private var last = 0f
    private var finished = false
    private val kernels = Array(1024) { phase ->
        val fraction = phase / 1024.0
        val cutoff = min(1.0, targetRate.toDouble() / sourceRate) * 0.95
        val values = DoubleArray(32) { tap ->
            val x = tap - 15 - fraction
            val sinc = if (kotlin.math.abs(x) < 1e-9) cutoff else sin(PI * cutoff * x) / (PI * x)
            sinc * (0.5 + 0.5 * cos(PI * x / 16.0))
        }
        val sum = values.sum()
        FloatArray(32) { (values[it] / sum).toFloat() }
    }

    fun process(input: FloatArray): FloatArray {
        check(!finished)
        if (sourceRate == targetRate) { count += input.size; produced += input.size; return input }
        val result = FloatArray((input.size.toLong() * targetRate / sourceRate + 64).toInt())
        var size = 0
        for (value in input) {
            if (count == 0L) first = value
            ring[(count % ring.size).toInt()] = value
            last = value
            count++
            while (floor(position()).toLong() + 16 < count) result[size++] = next()
        }
        return result.copyOf(size)
    }

    fun finish(): FloatArray {
        check(!finished)
        finished = true
        if (sourceRate == targetRate || count == 0L) return FloatArray(0)
        val result = FloatArray(64)
        var size = 0
        while (produced * sourceRate < count * targetRate) result[size++] = next()
        return result.copyOf(size)
    }

    private fun position() = produced.toDouble() * sourceRate / targetRate
    private fun next(): Float {
        val position = position()
        val center = floor(position).toLong()
        val kernel = kernels[((position - center) * 1024).toInt().coerceIn(0, 1023)]
        var value = 0f
        for (tap in kernel.indices) {
            val index = center + tap - 15
            val sample = when { index < 0 -> first; index >= count -> last; else -> ring[(index % ring.size).toInt()] }
            value += sample * kernel[tap]
        }
        produced++
        return value.coerceIn(-1f, 1f)
    }
}
