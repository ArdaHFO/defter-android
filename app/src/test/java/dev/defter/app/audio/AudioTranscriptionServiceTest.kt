package dev.defter.app.audio

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowService
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/** Actual service lifecycle. Hold model verification before any decoder or native inference runs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE, shadows = [AudioTranscriptionServiceTest.FailableForegroundServiceShadow::class,
    AudioTranscriptionServiceTest.ThermalExecutorShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioTranscriptionServiceTest {
    @Implements(Service::class)
    class FailableForegroundServiceShadow : ShadowService() {
        companion object { var failNextStart = false }
        @Implementation protected override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
            if (failNextStart) { failNextStart = false; throw SecurityException("Synthetic foreground denial") }
            super.startForeground(id, notification, foregroundServiceType)
        }
    }

    /** Robolectric 4.14.1 shadows only the one-argument registration API. Preserve its listener tracking. */
    @Implements(PowerManager::class)
    class ThermalExecutorShadow : ShadowPowerManager() {
        companion object { var failRegistration = false }
        @Implementation protected fun addThermalStatusListener(executor: Executor, listener: PowerManager.OnThermalStatusChangedListener) {
            if (failRegistration) throw RuntimeException("Synthetic thermal registration failure")
            super.addThermalStatusListener(listener)
        }
    }

    private lateinit var app: Application
    private lateinit var lifecycle: ServiceController<AudioTranscriptionService>
    private lateinit var service: AudioTranscriptionService
    private lateinit var modelMutex: Mutex
    private var previousManager: WhisperModelManager? = null
    private val observerScope = CoroutineScope(Dispatchers.Unconfined)

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        previousManager = ReflectionHelpers.getField(AudioRuntime, "manager")
        val model = WhisperModelManager(File(app.cacheDir, "service-model-${UUID.randomUUID()}"))
        modelMutex = ReflectionHelpers.getField(model, "mutex")
        runBlocking { modelMutex.lock() }
        ReflectionHelpers.setField(AudioRuntime, "manager", model)
        AudioRuntime.transcription.value = TranscriptionWorkState()
        FailableForegroundServiceShadow.failNextStart = false
        ThermalExecutorShadow.failRegistration = false
        lifecycle = Robolectric.buildService(AudioTranscriptionService::class.java)
        lifecycle.create()
        service = lifecycle.get()
    }

    @After fun cleanup() {
        if (::lifecycle.isInitialized) lifecycle.destroy()
        if (::modelMutex.isInitialized && modelMutex.isLocked) modelMutex.unlock()
        shadowOf(Looper.getMainLooper()).idle()
        observerScope.cancel()
        AudioRuntime.transcription.value = TranscriptionWorkState()
        ReflectionHelpers.setField(AudioRuntime, "manager", previousManager)
        FailableForegroundServiceShadow.failNextStart = false
        ThermalExecutorShadow.failRegistration = false
    }

    @Test fun pauseCommandWithNewerStartIdStopsOriginalForegroundJobAndReleasesResources() {
        val token = start(startId = 40)
        assertTrue(AudioRuntime.transcription.value.running)
        assertTrue(shadowOf(service).isLastForegroundNotificationAttached)
        service.onStartCommand(Intent(app, AudioTranscriptionService::class.java).setAction(AudioTranscriptionService.PAUSE), 0, 41)
        awaitStopped(token)
        assertEquals(41, shadowOf(service).stopSelfResultId)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertNull(ReflectionHelpers.getField<Any?>(service, "wakeLock"))
    }

    @Test fun foregroundFailureCleansUpBeforePublishingAndAllowsAnotherStart() {
        FailableForegroundServiceShadow.failNextStart = true
        var cleanupObservedAtFailure = false
        observerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            AudioRuntime.transcription.filter { !it.running && it.error != null }.first()
            cleanupObservedAtFailure = shadowOf(service).isForegroundStopped && shadowOf(service).stopSelfResultId == 51
        }
        val first = start(startId = 51)
        assertFalse(AudioRuntime.transcription.value.running)
        assertEquals(first, AudioRuntime.transcription.value.token)
        assertTrue(cleanupObservedAtFailure)
        val replacement = start(startId = 52)
        assertNotEquals(first, replacement)
        assertTrue(AudioRuntime.transcription.value.running)
        assertTrue(shadowOf(service).isLastForegroundNotificationAttached)
        service.onStartCommand(Intent(app, AudioTranscriptionService::class.java).setAction(AudioTranscriptionService.PAUSE), 0, 53)
        awaitStopped(replacement)
        assertEquals(53, shadowOf(service).stopSelfResultId)
        assertNull(AudioRuntime.transcription.value.error)
    }

    @Test fun thermalListenerRegistrationFailureStillAllowsCancelableWork() {
        lifecycle.destroy()
        ThermalExecutorShadow.failRegistration = true
        lifecycle = Robolectric.buildService(AudioTranscriptionService::class.java)
        lifecycle.create() // A real listener-registration exception must not crash service creation.
        service = lifecycle.get()
        val token = start(startId = 61)
        assertTrue(AudioRuntime.transcription.value.running)
        service.onStartCommand(Intent(app, AudioTranscriptionService::class.java).setAction(AudioTranscriptionService.PAUSE), 0, 62)
        awaitStopped(token)
        assertEquals(62, shadowOf(service).stopSelfResultId)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertNull(ReflectionHelpers.getField<Any?>(service, "wakeLock"))
    }

    private fun start(startId: Int): Long {
        val token = AudioRuntime.sequence.incrementAndGet()
        val id = UUID.randomUUID().toString()
        AudioRuntime.transcription.value = TranscriptionWorkState(id, running = true, token = token)
        service.onStartCommand(Intent(app, AudioTranscriptionService::class.java)
            .setAction(AudioTranscriptionService.START).putExtra("id", id).putExtra("token", token).putExtra("language", "tr"), 0, startId)
        return token
    }

    private fun awaitStopped(token: Long) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (AudioRuntime.transcription.value.running && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(token, AudioRuntime.transcription.value.token)
        assertFalse("Cancelled transcription must publish its stopped state", AudioRuntime.transcription.value.running)
    }
}
