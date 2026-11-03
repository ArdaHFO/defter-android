package dev.defter.app.audio

import android.app.Application
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.AudioRecordingState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE)
class AudioCaptureRecoveryTest {
    private lateinit var app: Application
    private lateinit var repository: DocumentRepository

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        repository = DocumentRepository(app)
        val root = File(app.filesDir, "defter")
        check(root.canonicalFile == File(app.filesDir, "defter").canonicalFile)
        check(!root.exists() || root.deleteRecursively())
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        AudioRecorderSession.serviceOwner = null
    }

    @After fun cleanup() {
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        AudioRecorderSession.serviceOwner = null
    }

    @Test fun unreadableInterruptedPartIsRetainedAndNeverPublishedAsRecordedAudio() = runBlocking {
        val recording = repository.createAudioRecording("Kesilen ders")
        val pending = File(AudioCaptureFiles.directory(app, recording.id), "${UUID.randomUUID()}.m4a")
        pending.writeBytes(byteArrayOf())
        val result = AudioRecorderController(app).recoverInterrupted()
        assertEquals(1, result.unreadableFiles)
        assertEquals(1, result.interruptedRecordings)
        assertTrue(pending.exists())
        val recovered = repository.loadAudioRecording(recording.id)
        assertEquals(AudioRecordingState.INTERRUPTED, recovered.state)
        assertTrue(recovered.parts.isEmpty())
    }

    @Test fun recoveryNeverTouchesAnActiveServicePendingFile() = runBlocking {
        val recording = repository.createAudioRecording("Devam eden ders")
        val pending = File(AudioCaptureFiles.directory(app, recording.id), "${UUID.randomUUID()}.m4a")
        pending.writeBytes(byteArrayOf())
        AudioRecorderSession.mutableState.value = AudioRecorderState(recording.id, AudioRecorderPhase.RECORDING)
        val result = AudioCaptureFiles.recover(app)
        assertEquals(AudioRecoveryResult(0, 0, 0), result)
        assertTrue(pending.exists())
        assertEquals(AudioRecordingState.RECORDING, repository.loadAudioRecording(recording.id).state)
    }

    @Test fun orphanMetadataWithoutPendingFilesIsReconciledButStartingReservationIsNot() = runBlocking {
        val paused = repository.createAudioRecording("Duraklatılmış ders")
        repository.setAudioState(paused.id, AudioRecordingState.PAUSED)
        val starting = repository.createAudioRecording("Yeni ders")
        AudioRecorderSession.mutableState.value = AudioRecorderState(starting.id, AudioRecorderPhase.STARTING)
        val result = AudioCaptureFiles.recover(app)
        assertEquals(1, result.interruptedRecordings)
        assertEquals(AudioRecordingState.INTERRUPTED, repository.loadAudioRecording(paused.id).state)
        assertEquals(AudioRecordingState.RECORDING, repository.loadAudioRecording(starting.id).state)
    }

    @Test fun pendingCapturePathsRejectTraversalBeforeCreatingFiles() {
        listOf("../outside", "a/b", "a\\b", "", "..").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { AudioCaptureFiles.directory(app, id) }
        }
    }

    @Test fun controllerCannotReplaceAnUncommittedRecordingOrResumeWhileSaving() {
        AudioRecorderSession.mutableState.value = AudioRecorderState("existing", AudioRecorderPhase.FINALIZING,
            error = "Depolama hatası")
        val controller = AudioRecorderController(app)
        assertFalse(controller.start("new"))
        assertFalse(controller.resume())
        assertEquals("existing", controller.state.value.recordingId)
    }
}
