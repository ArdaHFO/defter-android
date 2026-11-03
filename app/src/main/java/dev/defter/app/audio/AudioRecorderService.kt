package dev.defter.app.audio

import dev.defter.app.localization.tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.defter.app.MainActivity
import dev.defter.app.R
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.AudioRecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/** Captures on a dedicated Looper. Only complete files are published to the library. */
class AudioRecorderService : Service() {
    companion object {
        const val ACTION_START = "dev.defter.app.audio.START"
        const val ACTION_PAUSE = "dev.defter.app.audio.PAUSE"
        const val ACTION_RESUME = "dev.defter.app.audio.RESUME"
        const val ACTION_STOP = "dev.defter.app.audio.STOP"
        const val EXTRA_RECORDING_ID = "recordingId"
        private const val CHANNEL_ID = "defter_audio_recording"
        private const val NOTIFICATION_ID = 7301
        private const val MIN_FREE_BYTES = 32L * 1024 * 1024
        private const val PART_SIZE_BYTES = 4_800_000L // About ten minutes of 64 kbit/s mono AAC.
    }

    inner class LocalBinder : Binder() {
        val state get() = AudioRecorderSession.state
    }

    private data class Command(val action: String, val id: String? = null,
        val token: Long? = null, val error: String? = null)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private lateinit var thread: HandlerThread
    private lateinit var worker: CoroutineScope
    private lateinit var workerHandler: Handler
    private lateinit var repository: DocumentRepository
    private var recorder: MediaRecorder? = null
    private var pendingFile: File? = null
    private var nextFile: File? = null
    private val completedFiles = ArrayDeque<File>()
    private var recordingId: String? = null
    private var completedMs = 0L
    private var captureGeneration = 0L
    @Volatile private var destroyed = false
    @Volatile private var foregroundStarted = false
    @Volatile private var latestStartId = 0
    @Volatile private var startupFailureId: String? = null
    private var lastDiskCheck = 0L
    private val clock = RecordingClock()

    override fun onCreate() {
        super.onCreate()
        repository = DocumentRepository(applicationContext)
        thread = HandlerThread("DefterAudioRecorder").also { it.start() }
        workerHandler = Handler(thread.looper)
        worker = CoroutineScope(SupervisorJob() + workerHandler.asCoroutineDispatcher("DefterAudioRecorder"))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("Ses kaydı"), NotificationManager.IMPORTANCE_LOW)
                .also { it.description = tr("Devam eden ders kaydı ve kayıt kontrolleri") })
        worker.launch {
            for (command in commands) {
                if (destroyed && command.action != "destroy") continue
                if (command.token != null && command.token != captureGeneration) continue
                try {
                    when (command.action) {
                        ACTION_START -> startRecording(command.id)
                        ACTION_PAUSE -> pauseRecording()
                        ACTION_RESUME -> resumeRecording()
                        ACTION_STOP -> finishRecording()
                        "prepareNext" -> prepareNextPart()
                        "rotated" -> completeRollover()
                        "tick" -> tick()
                        "interruption" -> pauseRecording(command.error ?: tr("Mikrofon kaydı kesildi."))
                        "destroy" -> { preserveOnDestroy(); break }
                    }
                } catch (error: Exception) {
                    handleFailure(error)
                }
            }
            worker.cancel()
            thread.quitSafely()
        }
        worker.launch {
            while (isActive) { delay(1_000); commands.trySend(Command("tick")) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        latestStartId = startId
        if (action !in setOf(ACTION_START, ACTION_PAUSE, ACTION_RESUME, ACTION_STOP)) return START_NOT_STICKY
        if (action == ACTION_START && !foregroundStarted) {
            AudioRecorderSession.serviceOwner = this
            try {
                check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    tr("Mikrofon izni verilmedi.")
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(AudioRecorderPhase.STARTING, 0),
                    if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                foregroundStarted = true
            } catch (error: Exception) {
                startupFailureId = intent.getStringExtra(EXTRA_RECORDING_ID)
                AudioRecorderSession.mutableState.value = AudioRecorderState(intent.getStringExtra(EXTRA_RECORDING_ID),
                    AudioRecorderPhase.ERROR, error = tr("Mikrofon kaydı başlatılamadı. İzni kontrol ederek uygulamadan tekrar deneyin."))
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        commands.trySend(Command(action, intent.getStringExtra(EXTRA_RECORDING_ID)))
        return START_NOT_STICKY // A background restart must never secretly reopen the microphone.
    }

    private suspend fun startRecording(id: String?) = AudioCaptureFiles.withRecoveryLock {
        if (recordingId != null) return@withRecoveryLock
        require(!id.isNullOrBlank()) { tr("Ses kaydı bulunamadı.") }
        recordingId = id
        val recording = repository.loadAudioRecording(id)
        require(recording.deletedAt == null) { tr("Bu ses kaydı silinmiş.") }
        completedMs = recording.durationMs
        clock.settle(completedMs)
        publish(AudioRecorderPhase.STARTING)
        repository.setAudioState(id, AudioRecordingState.RECORDING)
        startPart()
    }

    @Suppress("DEPRECATION")
    private fun startPart() {
        check(!destroyed) { tr("Ses servisi kapatıldı.") }
        check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            tr("Mikrofon izni kaldırıldı.")
        }
        val id = checkNotNull(recordingId)
        val directory = AudioCaptureFiles.directory(applicationContext, id)
        check(directory.usableSpace >= MIN_FREE_BYTES) { tr("Ses kaydı için yeterli boş alan yok.") }
        val file = File(directory, "${UUID.randomUUID()}.m4a")
        pendingFile = file
        val current = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(applicationContext) else MediaRecorder()
        recorder = current
        val token = ++captureGeneration
        current.setOnErrorListener { _, _, _ ->
            commands.trySend(Command("interruption", token = token, error = tr("Mikrofon veya kayıt cihazı kesintiye uğradı.")))
        }
        current.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING ->
                    commands.trySend(Command("prepareNext", token = token))
                MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED ->
                    commands.trySend(Command("rotated", token = token))
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ->
                    commands.trySend(Command("interruption", token = token,
                        error = tr("Sonraki ses parçası açılamadı. Kayıt güvenli biçimde duraklatıldı.")))
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            current.registerAudioRecordingCallback(Executor { workerHandler.post(it) },
                object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (configs.any { it.isClientSilenced }) commands.trySend(Command("interruption", token = token,
                            error = tr("Mikrofon başka bir işlem veya gizlilik ayarı nedeniyle susturuldu. Kayıt duraklatıldı.")))
                    }
                })
        }
        current.setAudioSource(MediaRecorder.AudioSource.MIC)
        current.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        current.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        current.setAudioChannels(1)
        current.setAudioSamplingRate(44_100)
        current.setAudioEncodingBitRate(64_000)
        current.setMaxFileSize(PART_SIZE_BYTES)
        current.setOutputFile(file.absolutePath)
        current.prepare()
        current.start()
        clock.start(completedMs, SystemClock.elapsedRealtime())
        publish(AudioRecorderPhase.RECORDING)
    }

    private suspend fun finalizePart() {
        val current = recorder
        recorder = null
        ++captureGeneration // Ignore late recorder callbacks from a released part.
        if (current != null) {
            runCatching { current.setOnErrorListener(null) }
            runCatching { current.setOnInfoListener(null) }
            runCatching { current.stop() }
            runCatching { current.reset() }
            runCatching { current.release() }
        }
        pendingFile?.let { if (it !in completedFiles) completedFiles.addLast(it) }
        pendingFile = null
        // A native rollover can race its callback with stop. Probe the reserved successor too.
        nextFile?.let { file ->
            if (file.length() > 0L && file !in completedFiles) completedFiles.addLast(file)
            else if (file.length() == 0L) file.delete() // Unused, app-created reservation, no captured audio.
        }
        nextFile = null
        commitCompletedParts()
        clock.settle(completedMs)
    }

    private suspend fun commitCompletedParts() {
        var invalidPart = false
        while (completedFiles.isNotEmpty()) {
            val file = completedFiles.first()
            val duration = AudioCaptureFiles.durationMs(file)
            if (duration == null) {
                // Preserve unreadable containers, but do not block all future recording on an unrepairable file.
                completedFiles.removeFirst()
                invalidPart = true
                continue
            }
            val saved = repository.commitAudioPart(checkNotNull(recordingId), file, duration)
            completedMs = saved.durationMs
            file.delete() // Metadata is durable. A leftover is safe because commit uses sourceKey.
            completedFiles.removeFirst()
        }
        if (invalidPart) throw IllegalStateException(tr("Son ses parçası tamamlanamadı. Kurtarma için cihazda korundu."))
    }

    private suspend fun pauseRecording(message: String? = null) {
        if (recordingId == null || AudioRecorderSession.state.value.phase !in
            setOf(AudioRecorderPhase.RECORDING, AudioRecorderPhase.STARTING)) return
        publish(AudioRecorderPhase.FINALIZING, message)
        finalizePart()
        repository.setAudioState(checkNotNull(recordingId), AudioRecordingState.PAUSED)
        clock.settle(completedMs)
        publish(AudioRecorderPhase.PAUSED, message ?: AudioRecorderSession.state.value.error)
    }

    private suspend fun resumeRecording() {
        if (recordingId == null || AudioRecorderSession.state.value.phase != AudioRecorderPhase.PAUSED) return
        publish(AudioRecorderPhase.STARTING)
        repository.setAudioState(checkNotNull(recordingId), AudioRecordingState.RECORDING)
        startPart()
    }

    private fun prepareNextPart() {
        if (AudioRecorderSession.state.value.phase != AudioRecorderPhase.RECORDING) return
        if (nextFile != null) return
        val directory = AudioCaptureFiles.directory(applicationContext, checkNotNull(recordingId))
        check(directory.usableSpace >= MIN_FREE_BYTES) { tr("Sonraki ses parçası için boş alan yetersiz.") }
        val file = File(directory, "${UUID.randomUUID()}.m4a")
        nextFile = file
        checkNotNull(recorder).setNextOutputFile(file)
    }

    private suspend fun completeRollover() {
        if (AudioRecorderSession.state.value.phase != AudioRecorderPhase.RECORDING) return
        val successor = checkNotNull(nextFile) { tr("Sonraki ses parçası takip edilemedi.") }
        pendingFile?.let { completedFiles.addLast(it) }
        pendingFile = successor
        nextFile = null
        // Native capture is already writing the successor. Committing the closed predecessor does not stop it.
        commitCompletedParts()
        publish(AudioRecorderPhase.RECORDING)
    }

    private suspend fun finishRecording() {
        val id = recordingId ?: run { stopSelf(); return }
        val endingStartId = latestStartId
        publish(AudioRecorderPhase.FINALIZING)
        finalizePart()
        repository.setAudioState(id, AudioRecordingState.READY)
        clock.settle(completedMs)
        recordingId = null
        if (foregroundStarted) { stopForeground(STOP_FOREGROUND_REMOVE); foregroundStarted = false }
        // Make the service ready for another start before exposing an idle UI state.
        publish(AudioRecorderPhase.IDLE, visibleRecordingId = id)
        stopSelfResult(endingStartId)
    }

    private suspend fun tick() {
        if (AudioRecorderSession.state.value.phase != AudioRecorderPhase.RECORDING) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiskCheck >= 5_000) {
            lastDiskCheck = now
            if ((pendingFile?.parentFile?.usableSpace ?: Long.MAX_VALUE) < MIN_FREE_BYTES) {
                pauseRecording(tr("Depolama alanı azaldı. Tamamlanan ses korundu ve kayıt duraklatıldı."))
                return
            }
        }
        publish(AudioRecorderPhase.RECORDING)
    }

    private suspend fun handleFailure(error: Exception) {
        val endingStartId = latestStartId
        val message = error.message ?: tr("Ses kaydı işlemi tamamlanamadı.")
        // Stop capture before reporting a storage failure; retain the pending valid file for retry.
        if (recorder != null) runCatching { finalizePart() }
        val hasPendingCommit = completedFiles.any { it.exists() } || pendingFile?.exists() == true
        if (hasPendingCommit) {
            publish(AudioRecorderPhase.FINALIZING, tr("Ses cihazda korundu, kaydetme tamamlanamadı. Tekrar durdurarak deneyin. %1\$s", message))
        } else {
            recordingId?.let { runCatching { repository.setAudioState(it, AudioRecordingState.INTERRUPTED) } }
            clock.settle(completedMs)
            val failedId = recordingId
            recordingId = null
            if (foregroundStarted) { stopForeground(STOP_FOREGROUND_REMOVE); foregroundStarted = false }
            publish(AudioRecorderPhase.ERROR, message, visibleRecordingId = failedId)
            stopSelfResult(endingStartId)
        }
    }

    private fun publish(phase: AudioRecorderPhase, error: String? = null, visibleRecordingId: String? = recordingId) {
        if (AudioRecorderSession.serviceOwner !== this) return
        val visible = AudioRecorderSession.state.value
        if (visible.phase == AudioRecorderPhase.STARTING && visible.recordingId != visibleRecordingId &&
            visible.recordingId != null) return
        val elapsed = clock.elapsed(SystemClock.elapsedRealtime())
        AudioRecorderSession.mutableState.value = AudioRecorderState(visibleRecordingId, phase, elapsed, error)
        if (foregroundStarted && !destroyed) runCatching { getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(phase, elapsed)) }
    }

    private fun notification(phase: AudioRecorderPhase, elapsed: Long): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(action: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, AudioRecorderService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val minutes = elapsed / 60_000
        val seconds = elapsed / 1_000 % 60
        val label = when (phase) {
            AudioRecorderPhase.PAUSED -> tr("Kayıt duraklatıldı")
            AudioRecorderPhase.FINALIZING -> tr("Ses kaydediliyor")
            AudioRecorderPhase.STARTING -> tr("Mikrofon hazırlanıyor")
            else -> tr("Ses kaydı sürüyor")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_audio_recording)
            .setContentTitle(tr("Defter · %1\$s", label)).setContentText("$minutes:${seconds.toString().padStart(2, '0')}")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                // Resume is deliberately an in-app visible action because microphone access is while-in-use.
                if (phase == AudioRecorderPhase.RECORDING) addAction(0, tr("Duraklat"), action(ACTION_PAUSE, 1))
                addAction(0, tr("Durdur ve kaydet"), action(ACTION_STOP, 2))
            }.build()
    }

    override fun onDestroy() {
        destroyed = true
        if (::worker.isInitialized) commands.trySend(Command("destroy"))
        super.onDestroy()
    }

    private suspend fun preserveOnDestroy() {
        val id = recordingId
        if (id != null) {
            runCatching { finalizePart() }
            runCatching { repository.setAudioState(id, AudioRecordingState.INTERRUPTED) }
            clock.settle(completedMs)
            publish(AudioRecorderPhase.ERROR, tr("Ses kaydı kesildi. Tamamlanan parçalar cihazda korundu."))
        }
        startupFailureId?.let { failedId ->
            // Serialize against a newer Start/recovery. Never overwrite a replacement service's active session.
            AudioCaptureFiles.withRecoveryLock {
                val active = AudioRecorderSession.state.value
                val replacementOwnsId = AudioRecorderSession.serviceOwner !== this &&
                    active.isBusy && active.recordingId == failedId
                if (!replacementOwnsId) runCatching { repository.setAudioState(failedId, AudioRecordingState.INTERRUPTED) }
            }
            startupFailureId = null
        }
        if (AudioRecorderSession.serviceOwner === this) AudioRecorderSession.serviceOwner = null
        commands.close()
    }
}
