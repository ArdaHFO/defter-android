package dev.defter.app.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.data.DocumentRepository
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.ink.PageEdgeDirection
import dev.defter.app.localization.TurkishTestApplication
import dev.defter.app.model.*
import java.time.Duration
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

/** Real Compose controls plus the hosted native canvas, on a narrow phone layout. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TurkishTestApplication::class, sdk = [33], qualifiers = "w393dp-h873dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class InputNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var eventClock = 100L
    private var downTime = 100L

    @Test fun fingerModeControlIsVisibleOnPhoneAndStylusWritesInBothModes() {
        val model = openNotebook()
        val control = compose.onNodeWithText("Parmak: taşı").assertIsDisplayed().assertIsEnabled()
        val bounds = control.fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Finger mode must not require scrolling a toolbar", bounds.left >= rootBounds.left && bounds.right <= rootBounds.right)
        assertFalse(model.config.touchDrawing)

        compose.runOnIdle {
            val view = canvas()
            view.resetViewport()
            send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, view.width / 2f, view.height / 2f)
            send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_FINGER, view.width / 2f, view.height / 2f - 45f)
        }
        awaitIdle(model)
        assertTrue(model.page!!.elements.isEmpty())
        drawLine(MotionEvent.TOOL_TYPE_STYLUS, 100f, 100f, 250f, 180f)
        awaitIdle(model)
        assertEquals(1, model.page!!.elements.filterIsInstance<StrokeElement>().size)

        control.performClick()
        compose.onNodeWithText("Parmak: çiz").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(model.config.touchDrawing)
            assertEquals(DrawingTool.PEN, model.config.tool)
            assertTrue(model.getApplication<Application>().getSharedPreferences("editor_preferences", Context.MODE_PRIVATE)
                .getBoolean("finger_draws", false))
        }
        drawLine(MotionEvent.TOOL_TYPE_FINGER, 100f, 250f, 250f, 330f)
        drawLine(MotionEvent.TOOL_TYPE_STYLUS, 100f, 400f, 250f, 480f)
        awaitIdle(model)
        assertEquals(3, model.page!!.elements.filterIsInstance<StrokeElement>().size)
        assertEquals(1, model.notebook!!.pageIds.size)
        compose.onNodeWithText("Parmak: çiz").performClick()
        compose.onNodeWithText("Parmak: taşı").assertIsDisplayed()
        compose.runOnIdle { assertFalse(model.config.touchDrawing) }
    }

    @Test fun longPressSelectionSurvivesAndroidViewUpdateAndCopiesEditableGroupAcrossPages() {
        val model = openNotebook()
        val originalStroke = StrokeElement(id = newId(), points = listOf(InkPoint(100f, 100f), InkPoint(280f, 100f)),
            color = 0xFF7162C9.toInt(), width = 8f)
        val originalText = TextElement(id = newId(), text = "Kısa not", x = 100f, y = 280f, size = 30f)
        compose.runOnIdle { model.changePage(model.page!!.copy(elements = listOf(originalStroke, originalText))) }
        awaitIdle(model)
        val originalPageId = model.page!!.id

        longPress(180f, 100f)
        compose.onNodeWithText("Sayfa işlemleri").assertIsDisplayed()
        compose.onNodeWithText("Kopyala").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(setOf(originalStroke.id), canvas().selectedIds)
            assertEquals(DrawingTool.PEN, model.config.tool)
            assertFalse(canvas().isEnabled) // Recomposition disables input without losing selection.
        }
        compose.onNodeWithText("Tümünü seç").performClick()
        compose.runOnIdle { assertEquals(setOf(originalStroke.id, originalText.id), canvas().selectedIds) }
        capture("input-context-phone.png", dialog = true)
        compose.onNodeWithText("Kopyala").performClick()
        compose.onNodeWithText("Sayfa işlemleri").assertDoesNotExist()
        compose.onNodeWithContentDescription("Yeni sayfa").performClick()
        awaitIdle(model)
        assertNotEquals(originalPageId, model.page!!.id)

        longPress(450f, 450f)
        compose.onNodeWithText("Kopyala").assertIsNotEnabled()
        compose.onNodeWithText("Yapıştır").assertIsEnabled().performClick()
        awaitIdle(model)
        compose.runOnIdle {
            val copiedStroke = model.page!!.elements.filterIsInstance<StrokeElement>().single()
            val copiedText = model.page!!.elements.filterIsInstance<TextElement>().single()
            assertNotEquals(originalStroke.id, copiedStroke.id)
            assertNotEquals(originalText.id, copiedText.id)
            assertEquals(originalStroke.color, copiedStroke.color)
            assertEquals(originalStroke.width, copiedStroke.width, 0f)
            assertEquals(originalText.text, copiedText.text)
            assertEquals(180f, copiedStroke.points.last().x - copiedStroke.points.first().x, .001f)
            assertTrue(copiedStroke.points.first().x > 400f)
            assertTrue(copiedStroke.points.first().y > 400f)
        }
        compose.onNodeWithContentDescription("Önceki sayfa").performClick()
        awaitIdle(model)
        compose.runOnIdle {
            assertEquals(originalPageId, model.page!!.id)
            assertEquals(listOf(originalStroke, originalText), model.page!!.elements)
        }
    }

    @Test fun contextCutAndDeleteWorkWhileCanvasInputIsDisabledAndUndoRestoresContent() {
        val model = openNotebook()
        val original = TextElement(id = newId(), text = "Kesilecek not", x = 100f, y = 100f, size = 40f)
        compose.runOnIdle { model.changePage(model.page!!.copy(elements = listOf(original))) }
        awaitIdle(model)

        longPress(150f, 120f)
        compose.runOnIdle { assertFalse(canvas().isEnabled) }
        compose.onNodeWithText("Kes").assertIsEnabled().performClick()
        awaitIdle(model)
        compose.runOnIdle {
            assertTrue("Cut must remove the selected element, not only copy it", model.page!!.elements.isEmpty())
            val clipboard = model.getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(original.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        longPress(300f, 300f)
        compose.onNodeWithText("Yapıştır").performClick()
        awaitIdle(model)
        val pasted = model.page!!.elements.single() as TextElement
        assertNotEquals(original.id, pasted.id)
        assertEquals(original.text, pasted.text)

        longPress(pasted.x + 10f, pasted.y + 10f)
        compose.onNodeWithText("Seçimi sil").assertIsEnabled().performClick()
        awaitIdle(model)
        assertTrue(model.page!!.elements.isEmpty())
        compose.onNodeWithText("Geri al").assertIsEnabled().performClick()
        awaitIdle(model)
        assertEquals(listOf(pasted), model.page!!.elements)
    }

    @Test fun continuousPagesOverlapAndStylusEditsTheTouchedPageAfterOneAppend() {
        val model = openNotebook()
        val firstId = model.page!!.id
        val appendTouch = beginEdgePull(PageEdgeDirection.NEXT)
        compose.onNodeWithText("Yeni sayfa eklemek için bırak").assertIsDisplayed()
        capture("input-add-page-phone.png")
        release(appendTouch)
        awaitIdle(model)
        assertEquals(2, model.notebook!!.pageIds.size)
        val secondId = model.page!!.id
        assertNotEquals(firstId, secondId)
        release(appendTouch)
        awaitIdle(model)
        assertEquals("A duplicate release must never append again", 2, model.notebook!!.pageIds.size)
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.loadedPages.keys.containsAll(listOf(firstId, secondId)) }
        compose.runOnIdle {
            val view = canvas()
            view.resetViewport()
            val x = view.width / 2f
            val start = view.height * .30f
            send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, x, start)
            send(view, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_FINGER, x, start + view.height * .48f)
            val first = requireNotNull(view.pageBoundsInView(firstId))
            val second = requireNotNull(view.pageBoundsInView(secondId))
            assertTrue("The lower part of page one must be on screen", first.bottom > 60f)
            assertTrue("The upper part of page two must be on screen", second.top < view.height - 60f)
            assertTrue("Separate papers must have a visible gap", second.top > first.bottom)
            send(view, MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_FINGER, x, start + view.height * .48f)
        }
        awaitIdle(model)
        compose.onNodeWithText("Önceki sayfa için bırak").assertDoesNotExist()
        compose.onNodeWithText("Sonraki sayfa için bırak").assertDoesNotExist()
        capture("continuous-pages-phone.png")
        for ((id, nearBottom) in listOf(firstId to true, secondId to false)) {
            compose.runOnIdle {
                val view = canvas()
                val rect = requireNotNull(view.pageBoundsInView(id))
                val y = if (nearBottom) rect.bottom - 30f else rect.top + 30f
                assertTrue(y in 0f..view.height.toFloat())
                send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, view.width / 2f, y)
                send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, view.width / 2f + 15f, y + 5f)
            }
            awaitIdle(model)
            assertEquals(id, model.page!!.id)
            assertEquals(1, model.loadedPages.getValue(id).elements.filterIsInstance<StrokeElement>().size)
        }
        // Long-pressing another visible page must activate it without immediately
        // dismissing the context menu during the resulting Compose update.
        compose.runOnIdle {
            val view = canvas()
            val rect = requireNotNull(view.pageBoundsInView(firstId))
            send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, view.width * .3f, rect.bottom - 35f)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
        compose.waitForIdle()
        compose.onNodeWithText("Sayfa işlemleri").assertIsDisplayed()
        assertEquals(firstId, model.page!!.id)
        compose.onNodeWithText("Kapat").performClick()
        compose.runOnIdle { model.selectPage(secondId) }
        awaitIdle(model)
        compose.onNodeWithContentDescription("Önceki sayfa").performClick()
        awaitIdle(model)
        assertEquals(firstId, model.page!!.id)
        val landedView = canvas()
        val landedBounds = requireNotNull(landedView.pageBoundsInView(firstId))
        val appliedToken = ReflectionHelpers.getField<Long?>(landedView, "lastNavigationToken")
        assertTrue("Explicit page navigation starts at its top; bounds=$landedBounds " +
            "viewPage=${landedView.document?.id} modelPage=${model.page?.id} " +
            "modelToken=${model.explicitNavigationToken} canvasToken=$appliedToken", landedBounds.top >= 0f)
        assertEquals(2, model.notebook!!.pageIds.size)
    }

    @Test fun failedPageNavigationDoesNotLoseEditsOrOffsetLaterNavigation() {
        val model = openNotebook()
        compose.runOnIdle { model.changePage(model.page!!.copy(height = 4000f)) }
        awaitIdle(model)
        val firstId = model.page!!.id
        compose.onNodeWithContentDescription("Yeni sayfa").performClick()
        awaitIdle(model)
        val secondId = model.page!!.id
        val repository = ReflectionHelpers.getField<DocumentRepository>(model, "repository")
        val metadata = File(repository.root, "library.json")
        val intactMetadata = metadata.readBytes()
        var restored = false
        try {
            compose.runOnIdle {
                // This repository is the verified synthetic store created by resetSyntheticLibrary.
                metadata.writeText("invalid synthetic metadata")
                model.addText("Kaydı bekleyen not")
            }
            compose.waitUntil(20_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.pendingSaves == 0 && model.unsaved && !model.busy
            }
            compose.runOnIdle {
                assertNotNull(model.error)
                // Explicit navigation must preserve the active page when its save has failed.
                model.selectPage(firstId)
            }
            compose.waitUntil(20_000) {
                shadowOf(Looper.getMainLooper()).idle()
                !model.busy && model.error != null
            }
            compose.waitForIdle()
            assertEquals(secondId, model.page!!.id)
            metadata.writeBytes(intactMetadata)
            restored = true
            compose.runOnIdle { model.dismissError(); model.retrySave() }
            awaitIdle(model)

            compose.onNodeWithContentDescription("Önceki sayfa").performClick()
            awaitIdle(model)
            assertEquals(firstId, model.page!!.id)
            // Adding the failed-save fixture selected its text. Restore the pen explicitly
            // so this probe measures the viewport instead of performing an empty selection.
            compose.onNodeWithContentDescription("Kalem").performClick()
            compose.runOnIdle {
                val view = canvas()
                assertEquals(DrawingTool.PEN, view.config.tool)
                assertTrue("Viewport probe needs an enabled canvas", view.isEnabled)
                send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, view.width / 2f, 40f)
                send(view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, view.width / 2f, 40f)
            }
            awaitIdle(model)
            val strokes = model.page!!.elements.filterIsInstance<StrokeElement>()
            assertEquals("The stylus viewport probe must create one stroke", 1, strokes.size)
            val point = strokes.single().points.single()
            assertTrue("Manual navigation after a failed save must start at page top", point.y < 300f)
        } finally {
            if (!restored) metadata.writeBytes(intactMetadata)
        }
    }

    private fun openNotebook(): DefterViewModel {
        val model = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
        awaitIdle(model)
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        awaitIdle(model)
        compose.runOnIdle {
            model.config = DrawingConfig(touchDrawing = false)
            model.createNotebook("Dokunma testleri", PaperTemplate.DOTS, 0xFF7162C9.toInt())
        }
        awaitIdle(model)
        compose.runOnIdle { assertTrue(canvas().width > 0); assertTrue(canvas().height > 0) }
        return model
    }

    private fun drawLine(tool: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
        compose.runOnIdle {
            val view = canvas()
            view.resetViewport()
            val a = pagePoint(view, x1, y1)
            val b = pagePoint(view, x2, y2)
            send(view, MotionEvent.ACTION_DOWN, tool, a.first, a.second)
            send(view, MotionEvent.ACTION_UP, tool, b.first, b.second)
        }
    }

    private fun longPress(pageX: Float, pageY: Float) {
        lateinit var target: NotebookCanvas
        var point = 0f to 0f
        compose.runOnIdle {
            target = canvas()
            target.resetViewport()
            point = pagePoint(target, pageX, pageY)
            send(target, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, point.first, point.second)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
        compose.waitForIdle()
        compose.runOnIdle { send(target, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_FINGER, point.first, point.second) }
        compose.onNodeWithText("Sayfa işlemleri").assertIsDisplayed()
    }

    private data class EdgeTouch(val view: NotebookCanvas, val x: Float, val y: Float)

    private fun beginEdgePull(direction: PageEdgeDirection): EdgeTouch = compose.runOnIdle {
        val view = canvas()
        view.resetViewport(atBottom = direction == PageEdgeDirection.NEXT)
        val x = view.width / 2f
        val start = view.height / 2f
        val distance = 128f * view.resources.displayMetrics.density
        val end = start + if (direction == PageEdgeDirection.NEXT) -distance else distance
        send(view, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, x, start)
        send(view, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_FINGER, x, end)
        EdgeTouch(view, x, end)
    }

    private fun release(touch: EdgeTouch) = compose.runOnIdle {
        send(touch.view, MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_FINGER, touch.x, touch.y)
    }

    private fun pagePoint(view: NotebookCanvas, x: Float, y: Float): Pair<Float, Float> {
        val page = requireNotNull(view.document)
        val bounds = requireNotNull(view.pageBoundsInView(page.id))
        val scale = bounds.width() / page.width
        return bounds.left + x * scale to bounds.top + y * scale
    }

    private fun send(view: NotebookCanvas, action: Int, tool: Int, x: Float, y: Float) {
        eventClock = maxOf(eventClock + 20, SystemClock.uptimeMillis())
        if (action == MotionEvent.ACTION_DOWN) downTime = eventClock
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = if (tool == MotionEvent.TOOL_TYPE_STYLUS) 7 else 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .7f; size = .1f })
        val source = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        val event = MotionEvent.obtain(downTime, eventClock, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun canvas(): NotebookCanvas = findCanvas(compose.activity.window.decorView) ?: error("Editable canvas not found")
    private fun findCanvas(view: View): NotebookCanvas? {
        if (view is NotebookCanvas && !view.isReadOnly) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findCanvas(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun awaitIdle(model: DefterViewModel) {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.error?.let { throw AssertionError("Input workflow failed: $it") }
            !model.loading && !model.busy && !model.pageWindowLoading && model.pendingSaves == 0
        }
        compose.runOnIdle { assertNull(model.error); assertFalse(model.unsaved) }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val decor = if (dialog) {
                val current = requireNotNull(ShadowDialog.getLatestDialog()) { "No native dialog window to capture" }
                check(current.isShowing)
                requireNotNull(current.window).decorView
            } else compose.activity.window.decorView
            check(decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 60)) {
            for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 60)) colors += bitmap.getPixel(x, y)
        }
        assertTrue("Capture must contain real rendered UI", colors.size > 12)
        val directory = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
