package dev.defter.app.audio

import dev.defter.app.localization.tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.defter.app.MainActivity
import dev.defter.app.data.DocumentRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class TranscriptionWorkState(val recordingId: String? = null, val running: Boolean = false,
    val completedMs: Long = 0, val totalMs: Long = 0, val error: String? = null, val token: Long = 0)

object AudioRuntime {
    @Volatile private var manager: WhisperModelManager? = null
    fun models(context: Context): WhisperModelManager = manager ?: synchronized(this) {
        manager ?: WhisperModelManager(context.applicationContext).also { manager = it }
    }
    internal val transcription = MutableStateFlow(TranscriptionWorkState())
    internal val sequence = AtomicLong()
}

class AudioTranscriptionController(context: Context) {
    private val app = context.applicationContext
    val state: StateFlow<TranscriptionWorkState> = AudioRuntime.transcription.asStateFlow()
    fun start(id: String, language: String, restart: Boolean = false): Boolean {
        if (state.value.running) return false
        val token = AudioRuntime.sequence.incrementAndGet()
        AudioRuntime.transcription.value = TranscriptionWorkState(id, running = true, token = token)
        return try {
            ContextCompat.startForegroundService(app, Intent(app, AudioTranscriptionService::class.java)
                .setAction(AudioTranscriptionService.START).putExtra("id", id).putExtra("language", language).putExtra("restart", restart).putExtra("token", token))
            true
        } catch (failure: Exception) {
            if (state.value.token == token) AudioRuntime.transcription.value = TranscriptionWorkState(id, error = failure.message ?: tr("Metne çevirme başlatılamadı."), token = token)
            false
        }
    }
    fun pause() {
        if (state.value.running) runCatching { app.startService(Intent(app, AudioTranscriptionService::class.java).setAction(AudioTranscriptionService.PAUSE)) }
    }
}

/** User-initiated local file processing, with resumable checkpoints and a stop notification. */
class AudioTranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var latestStartId = 0
    private var ownedToken = 0L
    private var lastNotifiedPercent = -1
    private var pausedReason: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Ses metne çevirme"), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                    pausedReason = tr("Cihaz ısındığı için metne çevirme duraklatıldı. Soğuduktan sonra devam edebilirsin.")
                    job?.cancel()
                }
            }
            // Some platform services reject listener registration. Keep the service usable and
            // poll at chunk boundaries instead of crashing before startForeground can run.
            if (runCatching { getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, listener) }.isSuccess)
                thermalListener = listener
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == PAUSE) { job?.cancel(); if (job == null) stopSelf(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        val id = intent?.getStringExtra("id") ?: run { stopSelf(); return START_NOT_STICKY }
        ownedToken = intent.getLongExtra("token", 0)
        val token = ownedToken
        pausedReason = null
        lastNotifiedPercent = -1
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(tr("Hazırlanıyor…")), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification(tr("Hazırlanıyor…")))
        } catch (failure: Exception) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(latestStartId)
            if (AudioRuntime.transcription.value.token == token) AudioRuntime.transcription.value = TranscriptionWorkState(id, error = tr("Arka plan işlemi başlatılamadı. %1\$s", failure.message.orEmpty()), token = token)
            return START_NOT_STICKY
        }
        job = scope.launch {
            var error: String? = null
            try {
                val power = getSystemService(PowerManager::class.java)
                if (isDeviceHot())
                    throw IllegalStateException(tr("Cihazın sıcaklığı yüksek. Soğuduktan sonra metne çevirmeyi başlat."))
                // Bound the lock by the foreground job's Android 15 time budget.
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Defter:Transcription").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
                val manager = AudioRuntime.models(applicationContext)
                check(manager.refresh()) { tr("Önce konuşma modelini indir.") }
                val runner = TranscriptionRunner(DocumentRepository(applicationContext), WhisperTranscriber(manager))
                runner.run(id, intent.getStringExtra("language") ?: "tr", intent.getBooleanExtra("restart", false)) { done, total ->
                    if (done < total && isDeviceHot()) {
                        pausedReason = tr("Cihaz ısındığı için metne çevirme duraklatıldı. Soğuduktan sonra devam edebilirsin.")
                        throw CancellationException(pausedReason)
                    }
                    if (AudioRuntime.transcription.value.token == token) AudioRuntime.transcription.value = TranscriptionWorkState(id, true, done, total, token = token)
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    if (percent != lastNotifiedPercent) {
                        lastNotifiedPercent = percent
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("%$percent · ${TranscriptTools.clock(done)} / ${TranscriptTools.clock(total)}"))
                    }
                }
            } catch (_: CancellationException) {
                // The runner has persisted the last completed checkpoint.
                error = pausedReason
            } catch (failure: Exception) { error = failure.message?.take(350) ?: tr("Ses metne çevrilemedi.") }
            finally {
                releaseWakeLock()
                if (AudioRuntime.transcription.value.token == token) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // A PAUSE notification is another start command. Stop using its latest ID,
                    // otherwise Android retains a finished service after cancellation.
                    stopSelfResult(latestStartId)
                    AudioRuntime.transcription.value = AudioRuntime.transcription.value.copy(recordingId = id, running = false, error = error)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(progress: String): Notification {
        val open = PendingIntent.getActivity(this, 80, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 81, Intent(this, AudioTranscriptionService::class.java).setAction(PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(tr("Ses metne çevriliyor")).setContentText(progress).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).addAction(Notification.Action.Builder(null, tr("Duraklat"), pause).build()).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        pausedReason = tr("Android arka plan işlem süresi dolduğu için çeviri duraklatıldı. Tamamlanan bölümler korundu.")
        job?.cancel(); releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let { listener -> runCatching { getSystemService(PowerManager::class.java).removeThermalStatusListener(listener) } }
        if (AudioRuntime.transcription.value.token == ownedToken) AudioRuntime.transcription.value = AudioRuntime.transcription.value.copy(running = false)
        super.onDestroy()
    }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    private fun isDeviceHot(): Boolean = Build.VERSION.SDK_INT >= 29 &&
        runCatching { getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE }.getOrDefault(false)

    companion object {
        const val START = "dev.defter.app.audio.TRANSCRIBE"
        const val PAUSE = "dev.defter.app.audio.PAUSE_TRANSCRIPTION"
        private const val CHANNEL = "defter-transcription"
        private const val NOTIFICATION = 7202
    }
}
