package dev.defter.app.data

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.defter.app.model.AudioRecordingState
import dev.defter.app.model.newId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Platform-contract regression. Real WAV decoding is separately checked on Android. */
@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE, shadows = [AudioImportTest.TrackExtractor::class])
class AudioImportTest {
    private lateinit var repository: DocumentRepository
    @Before fun setup() {
        repository = DocumentRepository(RuntimeEnvironment.getApplication())
        repository.root.deleteRecursively()
        TrackExtractor.formats = listOf(MediaFormat.createAudioFormat("audio/raw", 16_000, 1).apply {
            setLong(MediaFormat.KEY_DURATION, 1_000_000)
        })
    }

    @Test fun wavImportWorksWithoutOptionalRetrieverHasAudioOrMimeMetadata() = runBlocking {
        // Robolectric's retriever has no registered metadata. The audio/raw track is enough
        // to establish audio presence; RIFF/WAVE identifies its container for durable storage.
        val file = wave()
        val recording = repository.importAudio(Uri.fromFile(file), "WAV ders")
        assertEquals(AudioRecordingState.READY, recording.state)
        assertEquals(1000L, recording.durationMs)
        assertEquals("audio/wav", recording.parts.single().mimeType)
        assertArrayEquals(file.readBytes(), repository.audioPartFile(recording.parts.single()).readBytes())
    }

    @Test fun videoOnlyTrackIsRejectedWithoutPublishingAudio() = runBlocking {
        TrackExtractor.formats = listOf(MediaFormat.createVideoFormat("video/avc", 10, 10).apply {
            setLong(MediaFormat.KEY_DURATION, 1_000_000)
        })
        rejected { repository.importAudio(Uri.fromFile(wave()), "Ses olmayan") }
        assertTrue(repository.loadLibrary().audioItems.isEmpty())
    }

    @Test fun oversizedDurationAndUnknownContainerAreRejectedBeforePublication() = runBlocking {
        TrackExtractor.formats = listOf(MediaFormat.createAudioFormat("audio/raw", 16_000, 1).apply {
            setLong(MediaFormat.KEY_DURATION, Long.MAX_VALUE)
        })
        rejected { repository.importAudio(Uri.fromFile(wave()), "Çok uzun") }
        TrackExtractor.formats = listOf(MediaFormat.createAudioFormat("audio/raw", 16_000, 1).apply {
            setLong(MediaFormat.KEY_DURATION, 1_000_000)
        })
        val invalid = File(RuntimeEnvironment.getApplication().cacheDir, "${newId()}.bin").apply { writeText("unsupported audio container") }
        rejected { repository.importAudio(Uri.fromFile(invalid), "Bilinmeyen biçim") }
        assertTrue(repository.loadLibrary().audioItems.isEmpty())
    }

    private fun wave(): File {
        val samples = ByteArray(32_000)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size)
        }.array()
        return File(RuntimeEnvironment.getApplication().cacheDir, "${newId()}.wav").apply { outputStream().use { it.write(header); it.write(samples) } }
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Invalid audio import accepted") } catch (_: IOException) { }
    }

    @Implements(MediaExtractor::class)
    class TrackExtractor {
        @Implementation fun __constructor__() = Unit
        @Implementation fun setDataSource(path: String) { require(File(path).isFile) }
        @Implementation fun getTrackCount(): Int = formats.size
        @Implementation fun getTrackFormat(index: Int): MediaFormat = formats[index]
        @Implementation fun release() = Unit
        companion object { var formats: List<MediaFormat> = emptyList() }
    }
}
