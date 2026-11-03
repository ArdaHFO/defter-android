package dev.defter.app.audio

import android.app.Application
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Looper
import dev.defter.app.model.AudioPart
import dev.defter.app.model.AudioRecording
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.util.UUID

/** Real controller and Android callbacks. Codec decoding is the test framework boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE, shadows = [AudioPlaybackControllerTest.PlaybackParamsShadow::class])
class AudioPlaybackControllerTest {
    @Implements(MediaPlayer::class)
    class PlaybackParamsShadow : ShadowMediaPlayer() {
        @Implementation protected fun setPlaybackParams(params: PlaybackParams) = Unit
    }

    private lateinit var app: Application
    private lateinit var controller: AudioPlaybackController
    private val players = mutableListOf<ShadowMediaPlayer>()
    private val files = mutableListOf<File>()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        ShadowMediaPlayer.setCreateListener { _, shadow -> players += shadow }
        controller = AudioPlaybackController(app)
    }

    @After fun cleanup() {
        controller.release()
        ShadowMediaPlayer.setCreateListener(null)
        files.forEach { it.delete() }
    }

    private fun recording(durations: List<Int>, prepareDelay: Int = 0): Pair<AudioRecording, List<File>> {
        var offset = 0L
        val media = durations.map { duration ->
            val file = File(app.cacheDir, "${UUID.randomUUID()}.m4a").also { it.writeBytes(byteArrayOf(1)) }
            files += file
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), ShadowMediaPlayer.MediaInfo(duration, prepareDelay))
            file
        }
        val parts = durations.map { duration ->
            AudioPart(assetId = UUID.randomUUID().toString(), durationMs = duration.toLong(), offsetMs = offset,
                bytes = 1).also { offset += duration }
        }
        return AudioRecording(title = "Ders", parts = parts) to media
    }

    @Test fun pausedGlobalSeekPreparesCorrectPartWithoutStartingAudio() {
        val (recording, media) = recording(listOf(1_000, 2_000))
        controller.load(recording, media)
        controller.seekTo(1_250)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.state.value.isPlaying)
        assertEquals(1_250L, controller.state.value.positionMs)
        assertEquals(250, players.last().currentPositionRaw)
        assertEquals(ShadowMediaPlayer.State.PREPARED, players.last().state)
    }

    @Test fun switchingRecordingsReleasesPreparingPlayerAndIgnoresItsLateCallback() {
        val (first, firstFiles) = recording(listOf(1_000), prepareDelay = -1)
        controller.load(first, firstFiles)
        controller.play()
        val old = players.single()
        val (second, secondFiles) = recording(listOf(2_000))
        controller.load(second, secondFiles)
        assertEquals(ShadowMediaPlayer.State.END, old.state)
        old.invokePreparedListener()
        assertEquals(second.id, controller.state.value.recordingId)
        assertFalse(controller.state.value.isPlaying)
        assertEquals(2_000L, controller.state.value.durationMs)
    }

    @Test fun completionAdvancesPlaylistAndStopsAtGlobalEnd() {
        val (recording, media) = recording(listOf(1_000, 2_000))
        controller.load(recording, media)
        controller.play()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.state.value.isPlaying)
        players.first().invokeCompletionListener()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, players.size)
        assertTrue(controller.state.value.isPlaying)
        assertEquals(1_000L, controller.state.value.positionMs)
        players.last().invokeCompletionListener()
        assertFalse(controller.state.value.isPlaying)
        assertEquals(3_000L, controller.state.value.positionMs)
    }
}
