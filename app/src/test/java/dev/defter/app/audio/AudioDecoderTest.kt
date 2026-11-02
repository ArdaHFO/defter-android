package dev.defter.app.audio

import android.media.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioDecoderTest {
    @Test fun stereoPcm16DownmixPreservesAmplitudeAndSignedExtremes() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(32767).putShort(-32768).putShort(16384).putShort(16384)
        bytes.flip()
        val pcm = AudioDecoder.decodePcm(bytes, 2, AudioFormat.ENCODING_PCM_16BIT)
        assertEquals(2, pcm.size)
        assertEquals(-1f / 65536, pcm[0], 0.00001f)
        assertEquals(0.5f, pcm[1], 0.00001f)
    }

    @Test fun floatPcmRejectsNonFiniteSamplesAndClampsOutOfRangeInput() {
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).putFloat(2f).putFloat(-2f)
        bytes.flip()
        assertArrayEquals(floatArrayOf(0f, 1f, -1f), AudioDecoder.decodePcm(bytes, 1, AudioFormat.ENCODING_PCM_FLOAT), 0f)
    }

    @Test fun truncatedPcmFrameIsRejectedRatherThanSilentlyDropped() {
        try { AudioDecoder.decodePcm(ByteBuffer.allocate(3), 1, AudioFormat.ENCODING_PCM_16BIT); fail() }
        catch (_: IOException) { }
    }

    @Test fun resamplingIsIndependentOfDecoderBufferBoundariesAndKeepsExactDuration() {
        val source = FloatArray(44_100) { sin(2 * PI * 1000 * it / 44_100).toFloat() * 0.5f }
        val whole = PcmResampler(44_100)
        val expected = whole.process(source) + whole.finish()
        val split = PcmResampler(44_100)
        val actual = buildList<Float> {
            var offset = 0
            while (offset < source.size) {
                val next = minOf(source.size, offset + 137)
                addAll(split.process(source.copyOfRange(offset, next)).toList())
                offset = next
            }
            addAll(split.finish().toList())
        }.toFloatArray()
        assertEquals(16_000, actual.size)
        assertArrayEquals(expected, actual, 0f)
    }

    @Test fun downsamplingSuppressesFrequenciesAboveTargetNyquist() {
        fun convert(frequency: Int): FloatArray {
            val converter = PcmResampler(48_000)
            val source = FloatArray(48_000) { sin(2 * PI * frequency * it / 48_000).toFloat() * 0.8f }
            return converter.process(source) + converter.finish()
        }
        fun rms(samples: FloatArray) = sqrt(samples.drop(100).dropLast(100).sumOf { it.toDouble() * it } / (samples.size - 200))
        assertTrue(rms(convert(1000)) > 0.5)
        assertTrue("High frequencies must not alias strongly into speech", rms(convert(12_000)) < 0.04)
    }

    @Test fun tinyAndUpsampledInputsHaveBoundedFlushAndNoLostSamples() {
        for (length in listOf(1, 15, 16, 17, 300)) {
            val converter = PcmResampler(8_000)
            val output = converter.process(FloatArray(length) { 0.25f }) + converter.finish()
            assertEquals(length * 2, output.size)
            output.forEach { assertEquals(0.25f, it, 0.00001f) }
        }
    }

    @Test fun nativeRatePassesThroughWithoutUnnecessaryFiltering() {
        val converter = PcmResampler(16_000)
        val input = floatArrayOf(-1f, 0f, 0.5f, 1f)
        assertArrayEquals(input, converter.process(input), 0f)
        assertEquals(0, converter.finish().size)
    }
}
