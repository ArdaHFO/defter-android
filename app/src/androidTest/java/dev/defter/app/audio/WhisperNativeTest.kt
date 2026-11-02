package dev.defter.app.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class WhisperNativeTest {
    @Test fun packagedNativeEngineReportsPinnedVersionAndRejectsMissingModel() {
        assertEquals("1.9.5", WhisperNative.version())
        val missing = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "missing-whisper-model.bin")
        assertFalse(missing.exists())
        try { WhisperNative.create(missing.absolutePath, 1); fail("Missing model accepted") } catch (_: IOException) { }
        WhisperNative.cancel(-1)
        WhisperNative.close(-1)
    }

    @Test fun codecDecodesBoundedChunksAndResumesAtFileTimestamp() = runBlocking {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("decoder-fixture-", ".wav", directory)
        try {
            writeWave(file, 31)
            val decoder = AudioDecoder()
            assertEquals(31_000, decoder.durationMs(file))
            val chunks = mutableListOf<DecodedAudioChunk>()
            decoder.decode(file) { chunks += it }
            assertEquals(2, chunks.size)
            assertEquals(480_000, chunks[0].samples.size)
            assertEquals(16_000, chunks[1].samples.size)
            assertEquals(30_000, chunks[1].startMs)
            val resumed = mutableListOf<DecodedAudioChunk>()
            decoder.decode(file, 30_000) { resumed += it }
            assertEquals(1, resumed.size)
            assertEquals(30_000, resumed.single().startMs)
            assertEquals(31_000, resumed.single().endMs)
            assertArrayEquals(chunks[1].samples, resumed.single().samples, 0f)
        } finally { file.delete() }
    }

    @Test fun invalidAudioContainerFailsWithoutProducingTranscriptData() = runBlocking {
        val file = File.createTempFile("invalid-audio-", ".m4a", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeText("This is not an audio container")
            var chunks = 0
            try {
                AudioDecoder().decode(file) { chunks++ }
                fail("Invalid container accepted")
            } catch (_: IOException) { }
            assertEquals(0, chunks)
        } finally { file.delete() }
    }

    @Test fun interruptedChunkConsumerReleasesCodecAndCanResume() = runBlocking {
        val file = File.createTempFile("cancel-audio-", ".wav", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            writeWave(file, 31)
            val decoder = AudioDecoder()
            var chunks = 0
            try {
                decoder.decode(file) { chunks++; throw CancellationException("Stop after persisted first chunk") }
                fail("Cancellation swallowed")
            } catch (_: CancellationException) { }
            assertEquals(1, chunks)
            decoder.decode(file, 30_000) { assertEquals(30_000L, it.startMs); chunks++ }
            assertEquals(2, chunks)
        } finally { file.delete() }
    }

    private fun writeWave(file: File, seconds: Int) {
        val size = seconds * 16_000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(size)
        FileOutputStream(file).use { output ->
            output.write(header.array())
            repeat(seconds) { output.write(ByteArray(32_000)) }
        }
    }
}
