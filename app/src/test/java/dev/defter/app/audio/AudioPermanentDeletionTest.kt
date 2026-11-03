package dev.defter.app.audio

import android.app.Application
import androidx.lifecycle.ViewModelStore
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.AudioRecordingState
import dev.defter.app.localization.TurkishTestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = TurkishTestApplication::class)
class AudioPermanentDeletionTest {
    private val scheduler = TestCoroutineScheduler()
    private val store = ViewModelStore()
    private lateinit var repository: DocumentRepository
    private lateinit var model: AudioViewModel

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        AudioRuntime.transcription.value = TranscriptionWorkState()
        val app: Application = RuntimeEnvironment.getApplication()
        repository = DocumentRepository(app)
        model = AudioViewModel(app)
        store.put("audio-purge", model)
        awaitIdle()
    }

    @After fun cleanup() {
        store.clear()
        scheduler.runCurrent()
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        AudioRuntime.transcription.value = TranscriptionWorkState()
        Dispatchers.resetMain()
    }

    private fun trashed(title: String): String = runBlocking {
        val recording = repository.createAudioRecording(title)
        repository.setAudioState(recording.id, AudioRecordingState.READY)
        repository.setAudioDeleted(recording.id, true)
        recording.id
    }

    @Test fun selectedTrashedRecordingIsClearedAfterSuccessfulBatchDeletion() {
        val first = trashed("First trashed recording")
        val second = trashed("Second trashed recording")
        val untouched = trashed("Keep this recording")
        model.select(first); awaitIdle()
        assertEquals(first, model.selected?.id)
        model.permanentlyDelete(setOf(first, second)); awaitIdle()
        assertNull(model.error)
        assertNull(model.selected)
        val ids = runBlocking { repository.loadLibrary() }.audioItems.map { it.id }
        assertFalse(first in ids); assertFalse(second in ids); assertTrue(untouched in ids)
    }

    @Test fun activeCaptureBlocksTheEntirePurgeBatch() {
        val first = trashed("Capture protected")
        val second = trashed("Other batch member")
        AudioRecorderSession.mutableState.value = AudioRecorderState(first, AudioRecorderPhase.PAUSED)
        model.permanentlyDelete(setOf(first, second)); awaitIdle()
        assertNotNull(model.error)
        val ids = runBlocking { repository.loadLibrary() }.audioItems.map { it.id }
        assertTrue(first in ids); assertTrue(second in ids)
    }

    @Test fun activeTranscriptionBlocksTheEntirePurgeBatch() {
        val first = trashed("Transcription protected")
        val second = trashed("Other batch member")
        AudioRuntime.transcription.value = TranscriptionWorkState(first, running = true)
        model.permanentlyDelete(setOf(first, second)); awaitIdle()
        assertNotNull(model.error)
        val ids = runBlocking { repository.loadLibrary() }.audioItems.map { it.id }
        assertTrue(first in ids); assertTrue(second in ids)
    }

    private fun awaitIdle() {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (!model.busy) return
            Thread.sleep(5)
        }
        fail("Audio operation did not finish: ${model.error}")
    }
}
