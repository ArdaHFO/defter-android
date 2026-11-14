package dev.defter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.audio.AudioViewModel
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.localization.FreshInstallApplication
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Native UI geometry and view continuity checks; captures are synthetic fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FreshInstallApplication::class, sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EditorChromeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun notes() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
    private fun await() {
        compose.waitUntil(25_000) { shadowOf(Looper.getMainLooper()).idle(); !notes().loading && !notes().busy && notes().pendingSaves == 0 }
        compose.runOnIdle { assertNull(notes().error) }
    }
    private fun open() {
        await(); compose.runOnIdle { resetSyntheticLibrary(compose.activity) }; await()
        compose.runOnIdle { notes().createNotebook("Calculus", PaperTemplate.DOTS, 0xff7162c9.toInt()) }; await()
        compose.runOnIdle { notes().changePage(notes().page!!.copy(elements = listOf(
            TextElement(text = "Derivatives & change", x = 88f, y = 90f, size = 44f),
            TextElement(text = "STUDY NOTES  /  CHAPTER 04", x = 90f, y = 157f, size = 19f, color = 0xff68758d.toInt()),
            TextElement(text = "The derivative describes the rate of change.", x = 90f, y = 235f, size = 26f),
            StrokeElement(color = 0xff397ac6.toInt(), width = 7f, points = (0..60).map { InkPoint(130f + it * 10, 640f - it * it * .075f) }),
            ShapeElement(shape = ShapeKind.LINE, startX = 100f, startY = 660f, endX = 900f, endY = 660f, width = 3f),
            TextElement(text = "Practice  /  compare the slopes at two points.", x = 90f, y = 750f, size = 25f)
        ))) }; await()
    }
    private fun canvas(v: View = compose.activity.window.decorView): NotebookCanvas? {
        if (v is NotebookCanvas && !v.isReadOnly) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) canvas(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        try {
            val directory = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Test fun toolbarAndCanvasStayMountedAcrossPaletteToolSideButtonAndSaveChanges() {
        open()
        val view = compose.runOnIdle { requireNotNull(canvas()) }
        val toolbar = compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("editor-document-header").fetchSemanticsNode().boundsInRoot
        assertEquals(64f, toolbar.height, 0f)
        assertTrue("Paper starts after two compact rows", compose.runOnIdle { view.top } <= 140)
        compose.onNodeWithContentDescription("Color and width").performClick()
        compose.onNodeWithContentDescription("Blue color").performClick()
        compose.onNodeWithText("Thickness setting").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithContentDescription("Stroke eraser").performClick()
        compose.runOnIdle { view.onTemporaryEraserChanged?.invoke(true) }
        compose.onNodeWithText("Side button · Eraser").assertIsDisplayed()
        compose.runOnIdle { view.onTemporaryEraserChanged?.invoke(false); notes().addText("Saved locally") }; await()
        compose.runOnIdle { assertSame("UI state changes cannot recreate the canvas", view, canvas()) }
        assertEquals(toolbar, compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot)
        assertEquals(header, compose.onNodeWithTag("editor-document-header").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Saved on your device").assertIsDisplayed()
        capture("fixed-toolbar-editor-en-tablet.png")
    }

    @Test fun pageManagerUsesASidePanelAndKeepsTheSameNativeCanvas() {
        open()
        val view = compose.runOnIdle { requireNotNull(canvas()) }
        compose.onNodeWithContentDescription("Show pages").performClick()
        compose.onNodeWithTag("pages-side-panel").assertIsDisplayed()
        compose.onNodeWithTag("editor-toolbar").assertIsDisplayed()
        compose.onNodeWithText("Add page").performClick(); await()
        compose.runOnIdle { assertEquals(2, notes().notebook!!.pageIds.size); assertSame(view, canvas()) }
        capture("fixed-toolbar-pages-en-tablet.png")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithTag("pages-side-panel").assertDoesNotExist()
        compose.runOnIdle { assertSame(view, canvas()) }
        compose.onNodeWithContentDescription("Show pages").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("pages-side-panel").assertDoesNotExist()
        compose.runOnIdle { assertNotNull(notes().notebookId); assertSame(view, canvas()) }
    }

    @Test fun notebookAudioOpensBesideTheEditorAndReturnsWithoutRecreatingIt() {
        open()
        val view = compose.runOnIdle { requireNotNull(canvas()) }
        compose.onNodeWithContentDescription("Insert and tools").performClick()
        compose.onNodeWithText("Notebook audio").performClick(); await()
        compose.onNodeWithTag("audio-side-panel").assertIsDisplayed()
        compose.onNodeWithTag("editor-toolbar").assertIsDisplayed()
        compose.runOnIdle { assertSame(view, canvas()) }
        capture("fixed-toolbar-audio-en-tablet.png")
        compose.onNodeWithContentDescription("Back from audio").performClick()
        compose.runOnIdle { assertFalse(ViewModelProvider(compose.activity)[AudioViewModel::class.java].visible); assertSame(view, canvas()) }
    }

    @Test
    @Config(application = FreshInstallApplication::class, qualifiers = "w320dp-h720dp-port-mdpi")
    fun smallPhonePinsHistoryAndFingerModeAndKeepsAllDrawingToolsReachable() {
        open()
        for (label in listOf("Undo", "Redo", "Color and width", "Insert and tools", "Pan with your finger and draw with your pen. Switch to draw mode.")) {
            val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Touch target: $label", bounds.width >= 48f && bounds.height >= 48f)
            assertTrue("Pinned control: $label", bounds.left >= 0f && bounds.right <= 320f)
        }
        compose.onNodeWithContentDescription("Pan").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(DrawingTool.HAND, notes().config.tool) }
        compose.onNodeWithContentDescription("Color and width").performClick()
        compose.onNodeWithContentDescription("Blue color").assertIsDisplayed().performClick()
        compose.onNodeWithText("Thickness setting").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithContentDescription("Show pages").performClick()
        compose.onNodeWithTag("pages-side-panel").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        capture("fixed-toolbar-editor-en-phone.png")
    }

    @Test
    @Config(application = FreshInstallApplication::class, qualifiers = "w320dp-h720dp-port-mdpi")
    fun activeNotebookTabAndItsCloseButtonStayVisibleAfterOpeningAndSwitchingTabs() {
        open()
        val first = notes().notebookId!!
        for (title in listOf("Physics lecture", "Linear algebra", "A very long notebook title")) {
            compose.runOnIdle { notes().createNotebook(title, PaperTemplate.LINED, 0xff7162c9.toInt()) }; await()
            compose.onNodeWithContentDescription("Tab $title").assertIsDisplayed().assertIsSelected()
            val close = compose.onNodeWithContentDescription("Close $title tab").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("The active tab's close target must fit the header", close.left >= 0f && close.right <= 320f && close.width >= 48f)
        }
        compose.runOnIdle { notes().openNotebook(first) }; await()
        compose.onNodeWithContentDescription("Tab Calculus").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("Close Calculus tab").assertIsDisplayed()
    }
}
