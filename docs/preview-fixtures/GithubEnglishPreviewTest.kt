package dev.defter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterApplication
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.audio.*
import dev.defter.app.data.DocumentRepository
import dev.defter.app.localization.AppLanguage
import dev.defter.app.localization.tr
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

/** English publication previews of the real 0.8 UI with explicitly synthetic content.
 * No microphone, model download, speech recognition, emulator or physical device is used. */
@RunWith(RobolectricTestRunner::class)
@Config(application = DefterApplication::class, sdk = [33], qualifiers = "en-rUS-w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GithubEnglishPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun notes() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
    private fun audio() = ViewModelProvider(compose.activity)[AudioViewModel::class.java]
    private fun repository() = ReflectionHelpers.getField<DocumentRepository>(audio(), "repository")

    @After fun resetRecorder() {
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        AudioRecorderSession.serviceOwner = null
    }

    @Test fun englishTabletLibrary() {
        fresh()
        for (name in listOf("Courses", "Personal")) {
            compose.runOnIdle { notes().createFolder(name) }; awaitIdle()
        }
        for ((title, color) in listOf("Calculus" to 0xff6261d8.toInt(), "Physics" to 0xff397d79.toInt(),
            "Linear algebra" to 0xffb27855.toInt(), "Ideas" to 0xff5d7da6.toInt())) {
            compose.runOnIdle { notes().createNotebook(title, PaperTemplate.DOTS, color) }; awaitIdle()
            compose.runOnIdle { notes().closeNotebook() }; awaitIdle()
        }
        compose.onNodeWithText("Your library").assertIsDisplayed()
        compose.runOnIdle { assertEquals(4, notes().library.notebooks.size); assertEquals(2, notes().library.folders.size) }
        capture("library-tablet.png")
    }

    @Test fun englishSplitEditorWithFingerPan() {
        fresh()
        createBook("Course notes")
        val referenceId = notes().notebookId!!
        fillLesson("Functions and change", "REFERENCE NOTES", false)
        compose.runOnIdle { notes().addPage(PaperTemplate.GRID) }; awaitIdle()
        fillLesson("Derivative rules", "CALCULUS / WORKED EXAMPLE", false)
        createBook("Study notebook")
        fillLesson("My solution", "CALCULUS / PRACTICE", true)
        compose.runOnIdle { notes().config = DrawingConfig(touchDrawing = false); notes().openReference(referenceId) }
        awaitIdle()
        compose.onNodeWithText("Reference · 2/2").assertIsDisplayed()
        compose.onNodeWithContentDescription(tr("Parmakla taşı, kalemle yaz. Çizim moduna geç.")).assertIsDisplayed()
        compose.onNodeWithContentDescription("Undo").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Pen").assertIsDisplayed()
        capture("editor-split.png")
    }

    @Test
    @Config(application = DefterApplication::class, qualifiers = "en-rUS-w393dp-h851dp-port-mdpi")
    fun englishPhoneAudioLibrary() {
        fresh()
        seed("Biology: cells and energy", "Cells are the basic units of life. Mitochondria help release energy.")
        seed("Calculus: rates of change", "The derivative describes the rate at which a function changes.")
        compose.onNodeWithContentDescription("Open audio archive").performClick(); awaitIdle()
        compose.onNodeWithText("Start recording").assertIsDisplayed()
        compose.onNodeWithText("Import audio").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Biology: cells and energy").assertIsDisplayed()
        capture("audio-phone-library.png")
    }

    @Test
    @Config(application = DefterApplication::class, qualifiers = "en-rUS-w1280dp-h900dp-land-mdpi")
    fun englishTabletTranscript() {
        fresh()
        val recording = seed("Calculus: rates of change", "A derivative measures the instantaneous rate of change of a function.")
        runBlocking {
            val current = repository().loadAudioRecording(recording.id)
            repository().updateTranscript(recording.id, listOf(
                TranscriptSegment(startMs = 0, endMs = 12_000, text = "A derivative measures the instantaneous rate of change of a function.", language = "en"),
                TranscriptSegment(startMs = 12_000, endMs = 28_000, text = "On a graph, the derivative is the slope of the tangent line at a chosen point.", language = "en"),
                TranscriptSegment(startMs = 28_000, endMs = 44_000, text = "Compare two points on the curve. Where is the function changing more quickly?", language = "en")
            ), current.revision)
        }
        compose.onNodeWithContentDescription("Open audio archive").performClick(); awaitIdle()
        compose.onNodeWithText(recording.title).performClick(); awaitIdle()
        compose.onNodeWithText("English").performClick().assertIsSelected()
        compose.onNodeWithText("A derivative measures the instantaneous rate of change of a function.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Edit transcript section").onFirst().assertIsDisplayed()
        capture("audio-tablet-transcript.png")
    }

    private fun fresh() {
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        awaitIdle()
        compose.runOnIdle {
            assertEquals("en", AppLanguage.selectedTag)
            assertEquals("en", AppLanguage.tag(compose.activity))
            audio().close(); audio().clearSelection(); resetSyntheticLibrary(compose.activity)
        }
        awaitIdle()
        compose.runOnIdle { audio().open() }; awaitIdle()
        compose.runOnIdle { audio().close() }
    }

    private fun createBook(title: String) {
        compose.runOnIdle { notes().createNotebook(title, PaperTemplate.DOTS, 0xFF6254D9.toInt()) }; awaitIdle()
    }

    private fun fillLesson(title: String, subtitle: String, practice: Boolean) {
        compose.runOnIdle {
            notes().changePage(notes().page!!.copy(elements = listOf(
                TextElement(text = title, x = 90f, y = 90f, size = 46f),
                TextElement(text = subtitle, x = 92f, y = 160f, size = 22f, color = 0xFF657087.toInt()),
                StrokeElement(points = listOf(InkPoint(90f, 270f), InkPoint(1020f, 270f)), kind = InkKind.HIGHLIGHTER, width = 44f, color = 0xFFF4CD4C.toInt()),
                TextElement(text = if (practice) "Slope of the tangent = derivative" else "Study the graph to understand change.", x = 92f, y = 235f, size = 32f),
                ShapeElement(shape = ShapeKind.ARROW, startX = 160f, startY = 680f, endX = 1060f, endY = 680f, color = 0xFF657087.toInt()),
                ShapeElement(shape = ShapeKind.ARROW, startX = 160f, startY = 680f, endX = 160f, endY = 360f, color = 0xFF657087.toInt()),
                StrokeElement(points = (0..25).map { val t = it / 25f; InkPoint(200f + t * 760f, 650f - t * t * 250f) }, width = 7f, color = 0xFF6254D9.toInt()),
                ShapeElement(shape = ShapeKind.LINE, startX = 450f, startY = 640f, endX = 950f, endY = 400f, color = 0xffcd7759.toInt(), width = 3.5f),
                TextElement(text = "f(x)", x = 970f, y = 370f, size = 27f, color = 0xFF6254D9.toInt()),
                TextElement(text = "TRY IT YOURSELF", x = 90f, y = 850f, size = 24f, color = 0xFF6254D9.toInt()),
                TextElement(text = "1. Compare the slope at two points.\n2. Explain your answer in your own words.", x = 90f, y = 910f, size = 28f)
            )))
        }
        awaitIdle()
    }

    private fun seed(title: String, text: String): AudioRecording = runBlocking {
        val repository = repository()
        var recording = repository.createAudioRecording(title)
        val source = File(compose.activity.cacheDir, "${newId()}.wav")
        val size = 16_000 * 2 * 60
        source.writeBytes(ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
        }.array())
        try { recording = repository.commitAudioPart(recording.id, source, 60_000, "audio/wav") }
        finally { source.delete() }
        recording = repository.setAudioState(recording.id, AudioRecordingState.READY)
        repository.updateTranscript(recording.id,
            listOf(TranscriptSegment(startMs = 0, endMs = 12_000, text = text, language = "en")), recording.revision)
    }

    private fun awaitIdle() {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !notes().loading && !notes().busy && notes().pendingSaves == 0 && !notes().pageWindowLoading && !audio().busy
        }
        compose.runOnIdle { assertNull(notes().error); assertNull(audio().error); assertFalse(notes().unsaved) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        try {
            val colors = mutableSetOf<Int>()
            for (y in 0 until bitmap.height step 20) for (x in 0 until bitmap.width step 20) colors += bitmap.getPixel(x, y)
            assertTrue("Preview must contain real rendered app content", colors.size > 12)
            val output = File(requireNotNull(System.getenv("DEFTER_SCREENSHOT_DIR"))).apply { mkdirs() }
            File(output, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
