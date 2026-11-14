package dev.defter.app

import android.graphics.*
import android.os.SystemClock
import android.view.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real stylus MotionEvents and native bitmap samples in the optimized installed variant. */
@RunWith(AndroidJUnit4::class)
class EraserContinuityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun toolbarEraserPreservesOtherInkAndCanvasDuringContactAndSave() = exercise(false)
    @Test fun stylusSideButtonPreservesOtherInkAndRestoresThePen() = exercise(true)

    private fun exercise(sideButton: Boolean) {
        lateinit var model: DefterViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[DefterViewModel::class.java] }; await(model)
        val oldBook = model.notebookId; val oldPage = model.page?.id; val oldConfig = model.config
        try {
            compose.runOnIdle { model.createNotebook("Eraser continuity verification", PaperTemplate.BLANK, Color.BLUE) }; await(model)
            compose.runOnIdle {
                val strokes = listOf(Color.RED to 100f, Color.BLUE to 300f, Color.GREEN to 500f).map { (color, y) ->
                    StrokeElement(color = color, width = 12f, points = List(6_000) { InkPoint(50f + it % 500, y) })
                }
                model.changePage(model.page!!.copy(width = 1200f, height = 600f, backgroundColor = Color.WHITE, elements = strokes))
                model.config = model.config.copy(tool = if (sideButton) DrawingTool.PEN else DrawingTool.ERASER, eraserMode = EraserMode.STROKE, touchDrawing = false)
            }; await(model)
            val view = compose.runOnIdle { requireNotNull(find(compose.activity.window.decorView)) }
            val toolbar = compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot
            fun frame(): Bitmap = compose.runOnIdle {
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            frame().recycle(); waitRender(view)
            fun sample(bitmap: Bitmap, y: Float): Int = compose.runOnIdle {
                val bounds = requireNotNull(view.pageBoundsInView(model.page!!.id))
                bitmap.getPixel((bounds.left + bounds.width() / 3f).toInt(), (bounds.top + bounds.height() * y / 600f).toInt())
            }
            frame().also { assertEquals(Color.BLUE, sample(it, 300f)); it.recycle() }
            val start = SystemClock.uptimeMillis()
            fun send(action: Int) = compose.runOnIdle {
                val bounds = requireNotNull(view.pageBoundsInView(model.page!!.id))
                val props = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS })
                val coords = arrayOf(MotionEvent.PointerCoords().apply { x = bounds.left + bounds.width() / 3f; y = bounds.top + bounds.height() / 6f; pressure = 1f })
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1, props, coords, 0,
                    if (sideButton) MotionEvent.BUTTON_STYLUS_PRIMARY else 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN)
            repeat(4) {
                frame().also { assertEquals("Unrelated blue ink cannot flash blank during erase", Color.BLUE, sample(it, 300f)); it.recycle() }
                send(MotionEvent.ACTION_MOVE)
            }
            assertEquals(toolbar, compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { assertSame(view, find(compose.activity.window.decorView)); assertEquals(0, model.pendingSaves) }
            send(MotionEvent.ACTION_UP); await(model); frame().recycle(); waitRender(view)
            frame().also {
                assertEquals(Color.WHITE, sample(it, 100f)); assertEquals(Color.BLUE, sample(it, 300f))
                val dir = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "verification").apply { mkdirs() }
                File(dir, if (sideButton) "eraser-side-button.png" else "eraser-toolbar.png").outputStream().use { output -> it.compress(Bitmap.CompressFormat.PNG, 100, output) }
                it.recycle()
            }
            compose.runOnIdle {
                assertEquals(2, model.page!!.elements.size)
                assertSame(view, find(compose.activity.window.decorView))
                assertEquals(if (sideButton) DrawingTool.PEN else DrawingTool.ERASER, model.config.tool)
                assertFalse(view.isTemporaryEraserActive)
                model.undo()
            }; await(model)
            compose.runOnIdle { assertEquals(3, model.page!!.elements.size) }
        } finally {
            compose.runOnIdle { model.config = oldConfig; if (oldBook != null && oldPage != null) model.openPage(oldBook, oldPage) else model.closeNotebook() }; await(model)
        }
    }
    private fun waitRender(view: NotebookCanvas) {
        compose.waitUntil(30_000) { var done = false; compose.runOnIdle { done = view.pendingPageRenders == 0 }; done }
    }
    private fun await(model: DefterViewModel) {
        compose.waitUntil(30_000) { !model.loading && !model.busy && !model.pageWindowLoading && model.pendingSaves == 0 }
        compose.runOnIdle { assertNull(model.error) }
    }
    private fun find(view: View): NotebookCanvas? {
        if (view is NotebookCanvas && !view.isReadOnly) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
}
