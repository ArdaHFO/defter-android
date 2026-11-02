package dev.defter.app.audio

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.AudioRecording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class AudioPlaybackState(
    val recordingId: String? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null
)

/** Activity/ViewModel-owned player. All public controls are called on the main thread. */
class AudioPlaybackController(context: Context) {
    private val app = context.applicationContext
    private val repository = DocumentRepository(app)
    private val mutableState = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = mutableState.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private var hasAudioFocus = false
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause()
        }, handler).build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private var receiverRegistered = false
    private var files: List<File> = emptyList()
    private var timeline = AudioTimeline(emptyList())
    private var player: MediaPlayer? = null
    private var partIndex = -1
    private var prepared = false
    private var wantsPlayback = false
    private var generation = 0L
    private var requestGeneration = 0L
    private val ticker = object : Runnable {
        override fun run() {
            val current = player
            if (prepared && current != null && state.value.isPlaying) {
                runCatching { current.currentPosition.toLong() }.getOrNull()?.let {
                    mutableState.value = state.value.copy(positionMs = timeline.globalPosition(partIndex, it))
                }
                handler.postDelayed(this, 200)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    suspend fun load(recordingId: String) {
        requireMainThread()
        val request = ++requestGeneration
        try {
            val recording = repository.loadAudioRecording(recordingId)
            val partFiles = recording.parts.map(repository::audioPartFile)
            if (request == requestGeneration) load(recording, partFiles)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (request == requestGeneration) fail("Ses kaydı açılamadı: ${error.message.orEmpty()}")
        }
    }

    fun load(recording: AudioRecording, files: List<File>) {
        requireMainThread()
        ++requestGeneration
        releasePlayer()
        wantsPlayback = false
        abandonAudioFocus()
        try {
            require(files.size == recording.parts.size) { "Ses parçaları eksik." }
            timeline = AudioTimeline(recording.parts.map { it.durationMs })
            this.files = files.toList()
            mutableState.value = AudioPlaybackState(recordingId = recording.id,
                durationMs = timeline.durationMs, speed = state.value.speed)
            if (files.isEmpty()) fail("Bu kayıtta henüz oynatılabilir ses yok.")
        } catch (error: Exception) {
            this.files = emptyList()
            timeline = AudioTimeline(emptyList())
            mutableState.value = AudioPlaybackState(recordingId = recording.id, error = error.message)
        }
    }

    fun play() {
        requireMainThread()
        if (files.isEmpty()) return
        wantsPlayback = true
        val state = state.value
        if (state.positionMs >= timeline.durationMs) seekTo(0)
        else if (prepared && player != null) startPrepared()
        else if (!state.isLoading) loadPart(timeline.locate(state.positionMs)!!)
    }

    fun pause() {
        requireMainThread()
        wantsPlayback = false
        if (prepared) runCatching {
            player?.let { current ->
                mutableState.value = state.value.copy(positionMs = timeline.globalPosition(partIndex, current.currentPosition.toLong()))
                if (current.isPlaying) current.pause()
            }
        }
        abandonAudioFocus()
        handler.removeCallbacks(ticker)
        mutableState.value = state.value.copy(isPlaying = false)
    }

    fun togglePlayback() { if (wantsPlayback) pause() else play() }

    fun seekTo(positionMs: Long) {
        requireMainThread()
        val target = timeline.locate(positionMs) ?: return
        val position = positionMs.coerceIn(0, timeline.durationMs)
        mutableState.value = state.value.copy(positionMs = position, error = null)
        if (target.partIndex != partIndex || !prepared) loadPart(target)
        else try {
            player?.seekTo(target.partPositionMs, MediaPlayer.SEEK_CLOSEST)
        } catch (error: Exception) { fail("Ses konumu değiştirilemedi.") }
    }

    fun setSpeed(speed: Float) {
        requireMainThread()
        try {
            val value = validatedPlaybackSpeed(speed)
            if (prepared && wantsPlayback) {
                player?.playbackParams = PlaybackParams().setSpeed(value).setPitch(1f)
            }
            mutableState.value = state.value.copy(speed = value, error = null)
        } catch (error: Exception) {
            mutableState.value = state.value.copy(error = "Bu oynatma hızı uygulanamadı.")
        }
    }

    fun release() {
        requireMainThread()
        ++requestGeneration
        wantsPlayback = false
        abandonAudioFocus()
        releasePlayer()
        if (receiverRegistered) { app.unregisterReceiver(noisyReceiver); receiverRegistered = false }
        files = emptyList()
        timeline = AudioTimeline(emptyList())
        mutableState.value = AudioPlaybackState()
    }

    private fun loadPart(target: AudioTimeline.Position) {
        releasePlayer()
        val token = generation
        partIndex = target.partIndex
        mutableState.value = state.value.copy(isLoading = true, isPlaying = false, error = null)
        try {
            val current = MediaPlayer()
            player = current
            current.setAudioAttributes(audioAttributes)
            current.setOnPreparedListener {
                if (token != generation || player !== current) return@setOnPreparedListener
                prepared = true
                mutableState.value = state.value.copy(isLoading = false)
                try {
                    if (target.partPositionMs > 0) current.seekTo(target.partPositionMs, MediaPlayer.SEEK_CLOSEST)
                    else if (wantsPlayback) startPrepared()
                } catch (error: Exception) { fail("Ses parçası oynatılamadı.") }
            }
            current.setOnSeekCompleteListener {
                if (token == generation && player === current && wantsPlayback) startPrepared()
            }
            current.setOnCompletionListener {
                if (token != generation || player !== current) return@setOnCompletionListener
                if (partIndex < files.lastIndex && wantsPlayback) {
                    val next = partIndex + 1
                    mutableState.value = state.value.copy(positionMs = timeline.globalPosition(next, 0))
                    loadPart(AudioTimeline.Position(next, 0))
                } else {
                    wantsPlayback = false
                    abandonAudioFocus()
                    handler.removeCallbacks(ticker)
                    mutableState.value = state.value.copy(isPlaying = false, positionMs = timeline.durationMs)
                }
            }
            current.setOnErrorListener { _, _, _ ->
                if (token == generation && player === current) fail("Ses dosyası okunamadı veya oynatma kesildi.")
                true
            }
            require(files[target.partIndex].isFile) { "Ses dosyası bulunamadı." }
            current.setDataSource(files[target.partIndex].absolutePath)
            current.prepareAsync()
        } catch (error: Exception) { fail("Ses dosyası açılamadı: ${error.message.orEmpty()}") }
    }

    private fun startPrepared() {
        if (!prepared || !wantsPlayback) return
        try {
            if (!hasAudioFocus) {
                hasAudioFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                check(hasAudioFocus) { "Ses çıkışı başka bir uygulama tarafından kullanılıyor." }
            }
            player?.playbackParams = PlaybackParams().setSpeed(state.value.speed).setPitch(1f)
            player?.start()
            mutableState.value = state.value.copy(isPlaying = true, error = null)
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } catch (error: Exception) { fail("Ses oynatma başlatılamadı.") }
    }

    private fun releasePlayer() {
        ++generation
        handler.removeCallbacks(ticker)
        prepared = false
        partIndex = -1
        val previous = player
        player = null
        runCatching { previous?.setOnPreparedListener(null) }
        runCatching { previous?.setOnCompletionListener(null) }
        runCatching { previous?.setOnSeekCompleteListener(null) }
        runCatching { previous?.setOnErrorListener(null) }
        runCatching { previous?.release() }
    }

    private fun fail(message: String) {
        wantsPlayback = false
        abandonAudioFocus()
        releasePlayer()
        mutableState.value = state.value.copy(isLoading = false, isPlaying = false, error = message)
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        hasAudioFocus = false
    }

    private fun requireMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "Ses oynatıcı kontrolleri ana iş parçacığında çağrılmalı."
    }
}
