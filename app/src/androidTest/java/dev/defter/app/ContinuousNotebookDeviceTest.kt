package dev.defter.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android layout, input routing and persistence. Uses a separate synthetic notebook. */
@RunWith(AndroidJUnit4::class)
class ContinuousNotebookDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var eventTime = 0L
    private var downTime = 0L

    @Test fun visiblePageBoundaryRoutesInkAndAppendsOnlyOnce() {
        val app = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var model: DefterViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[DefterViewModel::class.java] }
        await(model)
        val originalNotebook = model.notebookId
        val originalPage = model.page?.id
        val originalConfig = model.config
        try {
            instrumentation.runOnMainSync {
                model.config = DrawingConfig(touchDrawing = false)
                model.createNotebook("Scroll verification ${newId().take(8)}", PaperTemplate.LINED, 0xFF005AC1.toInt())
            }
            await(model)
            val firstId = requireNotNull(model.page).id
            instrumentation.runOnMainSync {
                model.changePage(model.page!!.copy(width = 1000f, height = 1200f, elements = listOf(
                    TextElement(text = "Page 1 · continuous notebook", x = 64f, y = 60f, size = 34f),
                    TextElement(text = "The next page remains visible below.", x = 64f, y = 1090f, size = 26f)
                )))
            }
            await(model)
            instrumentation.runOnMainSync { model.addPage(PaperTemplate.DOTS) }
            await(model)
            val secondId = requireNotNull(model.page).id
            instrumentation.runOnMainSync {
                model.changePage(model.page!!.copy(width = 1000f, height = 1200f, elements = listOf(
                    TextElement(text = "Page 2 · write on either page", x = 64f, y = 60f, size = 34f),
                    TextElement(text = "Finger scrolls. Stylus writes.", x = 64f, y = 135f, size = 26f)
                )))
                model.requestPageWindow(secondId, listOf(firstId, secondId))
            }
            await(model)
            instrumentation.runOnMainSync {
                val view = findCanvas(activity.window.decorView)!!
                view.resetViewport()
                val start = view.height * .26f
                send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, view.width / 2f, start)
                send(view, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_FINGER, view.width / 2f, start + view.height * .48f)
                val first = requireNotNull(view.pageBoundsInView(firstId))
                val second = requireNotNull(view.pageBoundsInView(secondId))
                assertTrue("Page one must be partially visible", first.top < 0f && first.bottom > 60f)
                assertTrue("Page two must be partially visible", second.top > 0f && second.top < view.height - 60f)
                assertTrue("The page gap must be visible", second.top > first.bottom)
                send(view, MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_FINGER, view.width / 2f, start + view.height * .48f)
            }
            await(model)
            val evidence = File(app.filesDir, "verification/continuous-0.6").apply { mkdirs() }
            // Render the actual laid-out native View independently of the emulator's
            // display compositor. This artifact is a Canvas render, not a device screenshot.
            instrumentation.runOnMainSync {
                val view = requireNotNull(findCanvas(activity.window.decorView))
                val first = requireNotNull(view.pageBoundsInView(firstId))
                val second = requireNotNull(view.pageBoundsInView(secondId))
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    fun interiorPixel(bounds: RectF): Int {
                        val visibleTop = maxOf(0f, bounds.top)
                        val visibleBottom = minOf(view.height.toFloat(), bounds.bottom)
                        assertTrue("A visible paper interior is required", visibleBottom > visibleTop)
                        // The blank left paper margin avoids the fixture's text and template marks.
                        val x = (bounds.left + bounds.width() * .025f).toInt()
                        val y = ((visibleTop + visibleBottom) / 2f).toInt()
                        assertTrue("Paper sample must be inside the view", x in 0 until view.width && y in 0 until view.height)
                        return bitmap.getPixel(x, y)
                    }
                    val firstPaper = interiorPixel(first)
                    val secondPaper = interiorPixel(second)
                    for (paper in listOf(firstPaper, secondPaper)) {
                        assertEquals("Paper must be opaque", 255, Color.alpha(paper))
                        assertTrue("Paper must contain nonblack rendered pixels", minOf(Color.red(paper), Color.green(paper), Color.blue(paper)) > 32)
                    }
                    assertEquals("First page paper must match its document", model.loadedPages.getValue(firstId).backgroundColor, firstPaper)
                    assertEquals("Second page paper must match its document", model.loadedPages.getValue(secondId).backgroundColor, secondPaper)
                    val gapLeft = maxOf(0f, first.left, second.left)
                    val gapRight = minOf(view.width.toFloat(), first.right, second.right)
                    assertTrue("The observed gap must intersect the view", gapRight > gapLeft && first.bottom >= 0f && second.top < view.height && second.top > first.bottom)
                    val gap = bitmap.getPixel(((gapLeft + gapRight) / 2f).toInt(), ((first.bottom + second.top) / 2f).toInt())
                    assertEquals("Gap must be opaque", 255, Color.alpha(gap))
                    assertNotEquals("Gap must differ from first paper", firstPaper, gap)
                    assertNotEquals("Gap must differ from second paper", secondPaper, gap)
                    val channels = listOf(Color.red(gap), Color.green(gap), Color.blue(gap))
                    assertTrue("Gap must be a nonblack neutral gray", channels.min() > 32 && channels.max() - channels.min() <= 24)
                    File(evidence, "continuous-pages-native-canvas.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
            instrumentation.waitForIdleSync()
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(evidence, "continuous-pages-android.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { image.recycle() }

            for ((id, bottom) in listOf(firstId to true, secondId to false)) {
                instrumentation.runOnMainSync {
                    val view = findCanvas(activity.window.decorView)!!
                    val rect = requireNotNull(view.pageBoundsInView(id))
                    val y = if (bottom) rect.bottom - 28f else rect.top + 28f
                    assertTrue(y > 0f && y < view.height)
                    send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, view.width * .65f, y)
                    send(view, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_STYLUS, view.width * .69f, y + 5f)
                    send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, view.width * .70f, y + 7f)
                }
                await(model)
                assertEquals(id, model.page!!.id)
                assertEquals(1, model.loadedPages.getValue(id).elements.filterIsInstance<StrokeElement>().size)
            }
            instrumentation.runOnMainSync { model.undo() }
            await(model)
            assertTrue(model.loadedPages.getValue(secondId).elements.filterIsInstance<StrokeElement>().isEmpty())
            assertEquals(1, model.loadedPages.getValue(firstId).elements.filterIsInstance<StrokeElement>().size)
            instrumentation.runOnMainSync { model.redo() }
            await(model)
            assertEquals(1, model.loadedPages.getValue(secondId).elements.filterIsInstance<StrokeElement>().size)

            var endY = 0f
            instrumentation.runOnMainSync {
                val view = findCanvas(activity.window.decorView)!!
                view.resetViewport(atBottom = true)
                val start = view.height * .7f
                endY = start - 140f * view.resources.displayMetrics.density
                send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, view.width / 2f, start)
                send(view, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_FINGER, view.width / 2f, endY)
                send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_FINGER, view.width / 2f, endY)
            }
            await(model)
            assertEquals(3, model.notebook!!.pageIds.size)
            instrumentation.runOnMainSync {
                val view = findCanvas(activity.window.decorView)!!
                send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_FINGER, view.width / 2f, endY)
            }
            await(model)
            assertEquals(3, model.notebook!!.pageIds.size)
            val bookId = model.notebookId!!
            instrumentation.runOnMainSync { model.closeNotebook() }
            await(model)
            instrumentation.runOnMainSync { model.openPage(bookId, firstId) }
            await(model)
            assertEquals(1, model.page!!.elements.filterIsInstance<StrokeElement>().size)
            instrumentation.runOnMainSync { model.selectPage(secondId) }
            await(model)
            assertEquals(1, model.page!!.elements.filterIsInstance<StrokeElement>().size)
            File(evidence, "result.json").writeText("""{"version":"0.6.0-alpha","simultaneousPages":2,"stylusRoutedToBothPages":true,"undoIsolatedToPage":true,"appendOnce":true,"reopenPreservedInk":true,"syntheticInput":true,"physicalDeviceClaim":false}""")
        } finally {
            instrumentation.runOnMainSync {
                model.config = originalConfig
                if (originalNotebook != null && originalPage != null) model.openPage(originalNotebook, originalPage)
                else model.closeNotebook()
            }
            await(model)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun await(model: DefterViewModel) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (true) {
            instrumentation.waitForIdleSync()
            var idle = false
            instrumentation.runOnMainSync {
                model.error?.let { throw AssertionError(it) }
                idle = !model.loading && !model.busy && !model.pageWindowLoading && model.pendingSaves == 0
            }
            if (idle) return
            check(SystemClock.uptimeMillis() < deadline) { "Notebook operation timed out" }
            SystemClock.sleep(25)
        }
    }

    private fun findCanvas(view: View): NotebookCanvas? {
        if (view is NotebookCanvas && !view.isReadOnly) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findCanvas(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun send(view: NotebookCanvas, action: Int, tool: Int, x: Float, y: Float) {
        eventTime = maxOf(eventTime + 20, SystemClock.uptimeMillis())
        if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .8f; size = .1f })
        val source = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        val event = MotionEvent.obtain(downTime, eventTime, action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0, source, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
