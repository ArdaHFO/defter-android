package dev.defter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.audio.*
import dev.defter.app.data.DocumentRepository
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Activity, repository, ViewModels and Compose controls with synthetic local WAV fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1280dp-h900dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AudioWorkspaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun notes() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
    private fun audio() = ViewModelProvider(compose.activity)[AudioViewModel::class.java]
    private fun repository() = ReflectionHelpers.getField<DocumentRepository>(audio(), "repository")

    @After fun resetCaptureState() {
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        AudioRecorderSession.serviceOwner = null
    }

    @Test fun independentArchiveOpensFromFilesAndSearchesTurkishTranscript() {
        fresh()
        seed("Biyoloji dersi", "Hücre zarında seçici geçirgenlik vardır.")
        val target = seed("Matematik dersi", "İntegral alanı hesaplar.")
        openArchive()
        compose.onNodeWithText("Dosyalar · bağımsız ses arşivi").assertIsDisplayed()
        compose.onNodeWithText("Biyoloji dersi").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Ses veya transkript ara")).performTextInput("İNTEGRAL")
        compose.mainClock.advanceTimeBy(400)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        // Async library refreshes may restart the debounce after the first clock advance.
        // PAUSED loopers need to advance that newly scheduled timer while IO finishes.
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            !audio().searching && target.id in audio().matches
        }
        compose.runOnIdle { assertEquals(setOf(target.id), audio().matches); assertNull(audio().error) }
        compose.onNodeWithText("Matematik dersi").assertIsDisplayed()
        compose.onNodeWithText("Biyoloji dersi").assertDoesNotExist()
        compose.onNodeWithText("Matematik dersi").performClick()
        awaitIdle()
        compose.onNodeWithText("İntegral alanı hesaplar.").performScrollTo().assertIsDisplayed()
        capture("audio-tablet-transcript.png")
    }

    @Test fun folderScopeAndNotebookEntryShowOnlyTheirOwnRecordings() {
        fresh()
        compose.runOnIdle { notes().createFolder("Ders sesleri") }
        awaitIdle()
        val folderId = notes().library.folders.single().id
        compose.runOnIdle { notes().createNotebook("Fizik defteri", PaperTemplate.GRID, 0xFF7865CA.toInt()) }
        awaitIdle()
        val bookId = notes().notebookId!!
        seed("Kök ses", "Genel kayıt.")
        seed("Klasör sesi", "Klasörde kayıt.", folderId = folderId)
        seed("Fizik anlatımı", "Kuvvet ve hareket.", notebookId = bookId)
        compose.runOnIdle { notes().closeNotebook() }
        awaitIdle()
        compose.runOnIdle { notes().navigateFolder(folderId) }
        openArchive()
        compose.onNodeWithText("Klasör sesi").assertIsDisplayed()
        compose.onNodeWithText("Kök ses").assertDoesNotExist()
        compose.onNodeWithText("Fizik anlatımı").assertDoesNotExist()
        compose.onNodeWithText("Tüm sesler").performClick()
        compose.onNodeWithText("Kök ses").assertIsDisplayed()
        compose.onNodeWithText("Fizik anlatımı").assertIsDisplayed()
        compose.onNodeWithContentDescription("Seslerden geri dön").performClick()
        compose.runOnIdle { notes().openNotebook(bookId) }
        awaitIdle()
        compose.onNodeWithContentDescription("Defter sesleri").performScrollTo().performClick()
        awaitIdle()
        compose.onNodeWithText("Fizik anlatımı").assertIsDisplayed()
        compose.onNodeWithText("Klasör sesi").assertDoesNotExist()
        compose.onNodeWithText("Kök ses").assertDoesNotExist()
    }

    @Test fun editDraftSurvivesActivityRecreationAndTxtSrtExportUseEditedText() {
        fresh()
        val recording = seed("İntegral dersi", "Tanıma hatası olan cümle.")
        openArchive()
        compose.onNodeWithText(recording.title).performClick()
        awaitIdle()
        compose.onNodeWithContentDescription("Transkript bölümünü düzenle").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Konuşma metni")).performTextReplacement("İntegral eğrinin altındaki alanı verir.")
        compose.activityRule.scenario.recreate()
        awaitIdle()
        compose.onNodeWithText("Transkripti düzenle").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Konuşma metni")).assertTextContains("İntegral eğrinin altındaki alanı verir.")
        compose.onNodeWithText("Kaydet").performClick()
        awaitIdle()
        compose.runOnIdle {
            assertEquals("İntegral eğrinin altındaki alanı verir.", audio().selected!!.transcript.single().text)
            assertTrue(audio().selected!!.transcript.single().edited)
        }
        for (format in listOf("txt", "srt")) {
            compose.onNodeWithText(format.uppercase()).performScrollTo().performClick()
            // The actual UI prepared the document/format. Supply a local SAF result to the actual resolver path.
            val output = File(compose.activity.cacheDir, "audio-export-${UUID.randomUUID()}.$format")
            compose.runOnIdle { audio().exportTranscript(Uri.fromFile(output)) }
            awaitIdle()
            val text = output.readText()
            assertTrue(text.contains("İntegral eğrinin altındaki alanı verir."))
            if (format == "srt") assertTrue(text.contains("00:00:00,000 --> 00:00:03,000"))
            else assertTrue(text.startsWith("İntegral dersi"))
            compose.onNodeWithText("Transkript kaydedildi.").assertIsDisplayed()
            compose.onNodeWithText("Tamam").performClick()
            output.delete()
        }
    }

    @Test fun attachDetachAndTrashRestorePreserveAudioAndTranscript() {
        fresh()
        compose.runOnIdle { notes().createNotebook("Kimya", PaperTemplate.LINED, 0xFF7865CA.toInt()) }
        awaitIdle()
        val bookId = notes().notebookId!!
        compose.runOnIdle { notes().closeNotebook() }
        awaitIdle()
        val recording = seed("Kimya anlatımı", "Atomlar bağ oluşturur.")
        openArchive()
        compose.onNodeWithText(recording.title).performClick()
        awaitIdle()
        compose.onNodeWithText("Defter bağlantısı").performClick()
        compose.onNodeWithText("Kimya").performClick()
        awaitIdle()
        assertEquals(bookId, audio().selected!!.notebookId)
        compose.onNodeWithText("Defter bağlantısı").performClick()
        compose.onNodeWithText("Bağımsız ses olarak tut").performClick()
        awaitIdle()
        assertNull(audio().selected!!.notebookId)
        compose.onNodeWithText("Sil").performClick()
        compose.onNodeWithText("Ses çöp kutusuna taşınsın mı?").assertIsDisplayed()
        compose.onAllNodesWithText("Sil").onLast().performClick()
        awaitIdle()
        compose.onNodeWithText("Silinenler").performClick()
        compose.onNodeWithContentDescription("Ses seçenekleri ${recording.title}").performClick()
        compose.onNodeWithText("Sesi geri getir").performClick()
        awaitIdle()
        compose.onNodeWithText("Kayıtlar").performClick()
        compose.onNodeWithText(recording.title).assertIsDisplayed()
        val restored = runBlocking { repository().loadAudioRecording(recording.id) }
        assertNull(restored.deletedAt)
        assertEquals(recording.parts, restored.parts)
        assertEquals(recording.transcript, restored.transcript)
        assertTrue(repository().audioPartFile(restored.parts.single()).isFile)
    }

    @Test fun ongoingCaptureStripDoesNotBlockStylusWritingOrUndo() {
        fresh()
        compose.runOnIdle { notes().createNotebook("Ders notu", PaperTemplate.DOTS, 0xFF7865CA.toInt()) }
        awaitIdle()
        val recording = seed("Canlı ders", "", notebookId = notes().notebookId)
        compose.runOnIdle {
            AudioRecorderSession.mutableState.value = AudioRecorderState(recording.id, AudioRecorderPhase.RECORDING, 24_000)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Ses kaydını duraklat").assertIsDisplayed()
        compose.runOnIdle {
            val canvas = findCanvas(compose.activity.window.decorView) ?: error("No editable notebook canvas")
            val x = canvas.width * .45f
            val y = canvas.height * .4f
            stylus(canvas, MotionEvent.ACTION_DOWN, x, y, 100)
            stylus(canvas, MotionEvent.ACTION_MOVE, x + 60, y + 30, 120)
            stylus(canvas, MotionEvent.ACTION_UP, x + 120, y + 60, 140)
        }
        awaitIdle()
        assertTrue(notes().page!!.elements.any { it is StrokeElement })
        assertEquals(AudioRecorderPhase.RECORDING, audio().recorder.state.value.phase)
        compose.onNodeWithText("Geri al").performClick()
        awaitIdle()
        assertTrue(notes().page!!.elements.isEmpty())
    }

    @Test fun bookmarkStillOpensItsActualPageAfterAudioIsLinkedToAnotherNotebook() {
        fresh()
        compose.runOnIdle { notes().createNotebook("Kaynak defter", PaperTemplate.LINED, 0xFF7865CA.toInt()) }
        awaitIdle()
        val sourceBook = notes().notebookId!!
        val sourcePage = notes().page!!.id
        compose.runOnIdle { notes().addText("İşaretlenen özgün sayfa") }
        awaitIdle()
        compose.runOnIdle { notes().createNotebook("İkinci defter", PaperTemplate.GRID, 0xFF397AC6.toInt()) }
        awaitIdle()
        val otherBook = notes().notebookId!!
        val recording = seed("Sayfaya bağlı ders", "Kaynak sayfayı inceleyelim.", notebookId = sourceBook)
        runBlocking { repository().addAudioBookmark(recording.id, 1_000, "Önemli açıklama", sourcePage) }
        compose.runOnIdle { notes().openNotebook(sourceBook) }
        awaitIdle()
        compose.onNodeWithContentDescription("Defter sesleri").performScrollTo().performClick()
        awaitIdle()
        compose.onNodeWithText(recording.title).performClick()
        awaitIdle()
        compose.onNodeWithText("Defter bağlantısı").performClick()
        compose.onNodeWithText("İkinci defter").performClick()
        awaitIdle()
        assertEquals(otherBook, audio().selected!!.notebookId)
        assertEquals(sourcePage, audio().selected!!.bookmarks.single().pageId)
        compose.onNodeWithText("Sayfaya git").performScrollTo().performClick()
        awaitIdle()
        compose.runOnIdle {
            assertFalse(audio().visible)
            assertEquals(sourceBook, notes().notebookId)
            assertEquals(sourcePage, notes().page!!.id)
            assertEquals("İşaretlenen özgün sayfa", (notes().page!!.elements.single() as TextElement).text)
        }
    }

    @Test @Config(qualifiers = "w393dp-h851dp-port-mdpi")
    fun phoneArchiveAndTranscriptControlsRemainReachable() {
        fresh()
        val recording = seed("Biyoloji · hücre ve enerji", "Hücre, canlıların temel yapı birimidir. Enerji dönüşümü mitokondride gerçekleşir.")
        openArchive()
        compose.onNodeWithText("Kayıt başlat").assertIsDisplayed()
        compose.onNodeWithText("Ses içe aktar").performScrollTo().assertIsDisplayed()
        capture("audio-phone-library.png")
        compose.onNodeWithText(recording.title).performClick()
        awaitIdle()
        compose.onNodeWithContentDescription("Transkript bölümünü düzenle").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SRT").performScrollTo().assertIsDisplayed()
        capture("audio-phone-transcript.png")
    }

    private fun fresh() {
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        awaitIdle()
        compose.runOnIdle { audio().close(); audio().clearSelection(); resetSyntheticLibrary(compose.activity) }
        awaitIdle()
        compose.runOnIdle { audio().open() }
        awaitIdle()
        compose.runOnIdle { audio().close() }
    }

    private fun seed(title: String, text: String, folderId: String? = null, notebookId: String? = null): AudioRecording = runBlocking {
        val repository = repository()
        var recording = repository.createAudioRecording(title, folderId, notebookId)
        val source = File(compose.activity.cacheDir, "${UUID.randomUUID()}.wav")
        val size = 16_000 * 2 * 6
        source.writeBytes(ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
        }.array())
        try { recording = repository.commitAudioPart(recording.id, source, 6_000, "audio/wav") }
        finally { source.delete() }
        recording = repository.setAudioState(recording.id, AudioRecordingState.READY)
        if (text.isNotBlank()) recording = repository.updateTranscript(recording.id,
            listOf(TranscriptSegment(startMs = 0, endMs = 3_000, text = text, language = "tr")), recording.revision)
        recording
    }

    private fun openArchive() {
        compose.onNodeWithContentDescription("Ses arşivini aç").performClick()
        awaitIdle()
    }

    private fun awaitIdle() {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !notes().loading && !notes().busy && notes().pendingSaves == 0 && !audio().busy
        }
        compose.runOnIdle { assertNull(notes().error); assertNull(audio().error) }
    }

    private fun findCanvas(view: View): NotebookCanvas? {
        if (view is NotebookCanvas && !view.isReadOnly) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findCanvas(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun stylus(view: NotebookCanvas, action: Int, x: Float, y: Float, time: Long) {
        val pointer = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .8f; size = .1f }
        val event = MotionEvent.obtain(100, time, action, 1, arrayOf(pointer), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { val decor = compose.activity.window.decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) } }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 20) for (x in 0 until bitmap.width step 20) colors += bitmap.getPixel(x, y)
        assertTrue("Audio screen must contain rendered content", colors.size > 12)
        val output = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
        File(output, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
