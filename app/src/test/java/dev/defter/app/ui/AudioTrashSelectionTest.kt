package dev.defter.app.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.audio.AudioRecorderSession
import dev.defter.app.audio.AudioRecorderState
import dev.defter.app.audio.AudioViewModel
import dev.defter.app.data.DocumentRepository
import dev.defter.app.localization.TurkishTestApplication
import dev.defter.app.model.AudioRecordingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1280dp-h900dp-land-mdpi", application = TurkishTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AudioTrashSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun audio() = ViewModelProvider(compose.activity)[AudioViewModel::class.java]
    private fun notes() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]

    @Test fun trashSelectionRequiresConfirmationAndKeepsUnselectedRecordings() {
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        awaitIdle()
        compose.runOnIdle { audio().close(); audio().clearSelection(); resetSyntheticLibrary(compose.activity) }
        awaitIdle()
        val repository = ReflectionHelpers.getField<DocumentRepository>(audio(), "repository")
        val ids = runBlocking {
            listOf("First", "Second", "Keep").map { title ->
                val recording = repository.createAudioRecording(title)
                repository.setAudioState(recording.id, AudioRecordingState.READY)
                repository.setAudioDeleted(recording.id, true)
                recording.id
            }
        }
        compose.runOnIdle { audio().open() }
        awaitIdle()
        compose.onNodeWithText("Silinenler").performClick()
        compose.onNodeWithText("Seçilenleri sil").assertIsNotEnabled()
        compose.onNodeWithText("Tümünü seç").performClick()
        compose.onNodeWithText("3 seçildi").assertIsDisplayed()
        compose.onNodeWithText("Seçimi temizle").performClick()
        compose.onNodeWithText("0 seçildi").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ses kaydını seç: First").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ses kaydını seç: Second").performScrollTo().performClick()
        compose.onNodeWithText("Seçilenleri sil").performScrollTo().performClick()
        compose.onNodeWithText("Kalıcı olarak silinsin mi?").assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").performClick()
        assertTrue(runBlocking { repository.loadLibrary() }.audioItems.map { it.id }.containsAll(ids))
        compose.onNodeWithText("Seçilenleri sil").performClick()
        compose.onNodeWithText("Kalıcı sil").performClick()
        awaitIdle()
        compose.onNodeWithText("Tamam").performClick()
        val remaining = runBlocking { repository.loadLibrary() }.audioItems.map { it.id }
        assertFalse(ids[0] in remaining); assertFalse(ids[1] in remaining); assertTrue(ids[2] in remaining)
        compose.onNodeWithText("0 seçildi").assertIsDisplayed()
        compose.onNodeWithText("Keep").assertIsDisplayed()
    }

    private fun awaitIdle() {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !notes().loading && !notes().busy && notes().pendingSaves == 0 && !audio().busy
        }
        compose.runOnIdle { assertNull(notes().error); assertNull(audio().error) }
    }
}
