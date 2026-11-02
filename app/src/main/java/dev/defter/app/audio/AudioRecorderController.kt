package dev.defter.app.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioRecorderPhase { IDLE, STARTING, RECORDING, PAUSED, FINALIZING, ERROR }

data class AudioRecorderState(
    val recordingId: String? = null,
    val phase: AudioRecorderPhase = AudioRecorderPhase.IDLE,
    val elapsedMs: Long = 0,
    val error: String? = null
) {
    val isRecording: Boolean get() = phase == AudioRecorderPhase.RECORDING
    val isBusy: Boolean get() = phase in setOf(AudioRecorderPhase.STARTING,
        AudioRecorderPhase.RECORDING, AudioRecorderPhase.PAUSED, AudioRecorderPhase.FINALIZING)
}

/** Process-local observable state. The service, not an Activity, owns the recorder. */
internal object AudioRecorderSession {
    val mutableState = MutableStateFlow(AudioRecorderState())
    val state = mutableState.asStateFlow()
    @Volatile var serviceOwner: Any? = null
}

class AudioRecorderController(context: Context) {
    private val app = context.applicationContext
    val state: StateFlow<AudioRecorderState> = AudioRecorderSession.state

    /** Call only from a visible user action after RECORD_AUDIO has been granted. */
    fun start(recordingId: String): Boolean {
        if (state.value.isBusy) return false
        AudioRecorderSession.mutableState.value = AudioRecorderState(recordingId, AudioRecorderPhase.STARTING)
        return send(AudioRecorderService.ACTION_START, recordingId, foreground = true)
    }

    fun pause(): Boolean = if (state.value.isRecording) send(AudioRecorderService.ACTION_PAUSE) else false
    fun resume(): Boolean = if (state.value.phase == AudioRecorderPhase.PAUSED)
        send(AudioRecorderService.ACTION_RESUME) else false
    fun stop(): Boolean = if (state.value.isBusy) send(AudioRecorderService.ACTION_STOP) else false
    fun retryFinalization(): Boolean = if (state.value.phase == AudioRecorderPhase.FINALIZING)
        send(AudioRecorderService.ACTION_STOP) else false
    fun clearError() {
        AudioRecorderSession.mutableState.value = state.value.copy(error = null)
    }

    suspend fun recoverInterrupted(): AudioRecoveryResult = AudioCaptureFiles.recover(app)

    // Closing a screen must not stop or unbind an active lecture recording.
    fun release() = Unit

    private fun send(action: String, id: String? = state.value.recordingId, foreground: Boolean = false): Boolean {
        return try {
            val intent = Intent(app, AudioRecorderService::class.java).setAction(action)
                .putExtra(AudioRecorderService.EXTRA_RECORDING_ID, id)
            if (foreground) ContextCompat.startForegroundService(app, intent) else app.startService(intent)
            true
        } catch (error: Exception) {
            val previous = state.value
            AudioRecorderSession.mutableState.value = previous.copy(
                phase = if (foreground) AudioRecorderPhase.ERROR else previous.phase,
                error = "Ses kaydı başlatılamadı. Mikrofon iznini ve uygulamanın açık olduğunu kontrol edin."
            )
            false
        }
    }
}
