package dev.defter.app.audio

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Handler
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.AudioRecordingState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.UUID
import java.io.File

/** Real service lifecycle and repository, with denied permission so no physical microphone is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AudioRecorderServiceTest {
    @After fun cleanup() {
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        AudioRecorderSession.serviceOwner = null
    }

    @Test fun permissionFailureImmediatelyPersistsInterruptedWithoutOpeningMicrophone() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val repository = DocumentRepository(app)
        val recording = repository.createAudioRecording("İzin testi ${UUID.randomUUID()}")
        AudioRecorderSession.mutableState.value = AudioRecorderState(recording.id, AudioRecorderPhase.STARTING)
        val lifecycle = Robolectric.buildService(AudioRecorderService::class.java).create()
        val service = lifecycle.get()
        val handler = ReflectionHelpers.getField<Handler>(service, "workerHandler")
        service.onStartCommand(Intent(app, AudioRecorderService::class.java)
            .setAction(AudioRecorderService.ACTION_START)
            .putExtra(AudioRecorderService.EXTRA_RECORDING_ID, recording.id), 0, 1)
        assertEquals(AudioRecorderPhase.ERROR, AudioRecorderSession.state.value.phase)
        assertNotNull(AudioRecorderSession.state.value.error)
        assertNull(ReflectionHelpers.getField<Any?>(service, "recorder"))
        lifecycle.destroy()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (repository.loadAudioRecording(recording.id).state != AudioRecordingState.INTERRUPTED && System.nanoTime() < deadline) {
            drainWorker(handler)
            Thread.sleep(10)
        }
        assertEquals(AudioRecordingState.INTERRUPTED, repository.loadAudioRecording(recording.id).state)
        assertTrue(repository.loadAudioRecording(recording.id).parts.isEmpty())
    }

    @Test fun truncatedSuccessorAtStopIsPreservedAndDoesNotReportSuccessfulRecording() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val repository = DocumentRepository(app)
        val recording = repository.createAudioRecording("Yarım kalan parça ${UUID.randomUUID()}")
        val source = File(AudioCaptureFiles.directory(app, recording.id), "${UUID.randomUUID()}.m4a")
        source.writeBytes(byteArrayOf(0, 0, 0, 12, 102, 116, 121, 112))
        val lifecycle = Robolectric.buildService(AudioRecorderService::class.java).create()
        val service = lifecycle.get()
        val handler = ReflectionHelpers.getField<Handler>(service, "workerHandler")
        ReflectionHelpers.setField(service, "recordingId", recording.id)
        ReflectionHelpers.setField(service, "nextFile", source)
        AudioRecorderSession.serviceOwner = service
        AudioRecorderSession.mutableState.value = AudioRecorderState(recording.id, AudioRecorderPhase.PAUSED)
        service.onStartCommand(Intent(app, AudioRecorderService::class.java).setAction(AudioRecorderService.ACTION_STOP), 0, 1)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (AudioRecorderSession.state.value.phase != AudioRecorderPhase.ERROR && System.nanoTime() < deadline) {
            drainWorker(handler)
            Thread.sleep(10)
        }
        assertEquals(AudioRecorderPhase.ERROR, AudioRecorderSession.state.value.phase)
        assertEquals(AudioRecordingState.INTERRUPTED, repository.loadAudioRecording(recording.id).state)
        assertTrue(source.isFile)
        assertTrue(repository.loadAudioRecording(recording.id).parts.isEmpty())
        lifecycle.destroy()
        drainWorker(handler)
    }

    private fun drainWorker(handler: Handler) {
        try { shadowOf(handler.looper).idle() }
        catch (shutdown: IllegalStateException) {
            // Successful teardown intentionally quits this dedicated Looper before our next pump.
            // Persisted metadata and terminal state are still asserted by each test.
            if (shutdown.message != "Looper is quitting") throw shutdown
        }
    }
}
