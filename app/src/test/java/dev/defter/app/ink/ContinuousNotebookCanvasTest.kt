package dev.defter.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import dev.defter.app.model.*
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuousNotebookCanvasTest {
    private lateinit var view: NotebookCanvas
    private val pages = linkedMapOf<String, PageDocument>()
    private val commits = mutableListOf<PageDocument>()
    private val activations = mutableListOf<String>()
    private val edges = mutableListOf<PageEdgeDirection>()
    private var time = 100L
    private var downTime = 100L
    private data class Pointer(val id: Int, val type: Int, val x: Float, val y: Float)

    @Before fun setup() {
        pages["a"] = PageDocument(id = "a", width = 600f, height = 600f, template = PaperTemplate.BLANK, backgroundColor = 0xffffcccc.toInt())
        pages["b"] = PageDocument(id = "b", width = 600f, height = 600f, template = PaperTemplate.BLANK, backgroundColor = 0xffccccff.toInt())
        view = NotebookCanvas(RuntimeEnvironment.getApplication())
        view.document = pages.getValue("a")
        view.layout(0, 0, 632, 832)
        view.config = DrawingConfig(touchDrawing = false)
        view.onPageActivationRequested = { activations += it; it in pages }
        view.onDocumentChanged = { commits += it; pages[it.id] = it }
        view.onPageEdgeGesture = { edges += it }
        view.edgeNavigationEnabled = true
        view.canAddPageAtEnd = true
        window()
    }

    @Test fun bothPagesRenderAtOnceWithARealGapAndClipToTheView() {
        val bitmap = Bitmap.createBitmap(700, 900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff00aa00.toInt())
        view.draw(Canvas(bitmap))
        val first = view.pageBoundsInView("a")!!
        val second = view.pageBoundsInView("b")!!
        assertTrue(first.bottom < 832 && second.top < 832)
        assertEquals(pages.getValue("a").backgroundColor, bitmap.getPixel(100, 100))
        assertEquals(pages.getValue("b").backgroundColor, bitmap.getPixel(100, (second.top + 40).toInt()))
        assertEquals(0xffeef1f6.toInt(), bitmap.getPixel(100, ((first.bottom + second.top) / 2).toInt()))
        assertEquals(0xff00aa00.toInt(), bitmap.getPixel(680, 880))
        bitmap.recycle()
    }

    @Test fun stylusActivatesTouchedPageAndCommitsItsLocalCoordinatesWithoutJumping() {
        val bounds = view.pageBoundsInView("b")!!
        dot(bounds.left + 120f, bounds.top + 80f)
        assertEquals(listOf("b"), activations)
        assertEquals("b", commits.single().id)
        val point = (commits.single().elements.single() as StrokeElement).points.single()
        assertEquals(120f, point.x, .01f)
        assertEquals(80f, point.y, .01f)
        assertEquals(bounds, view.pageBoundsInView("b"))
        assertTrue(pages.getValue("a").elements.isEmpty())
    }

    @Test fun adjacentPageInkKeepsUndoTargetThroughIdleAndCacheRefreshUntilActualPan() {
        val b = view.pageBoundsInView("b")!!
        dot(b.left + 120f, b.top + 80f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The just-written page must remain the Undo target", "b", view.document!!.id)
        window()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("b", view.document!!.id)
        touch(MotionEvent.ACTION_DOWN, finger(300f, 300f))
        touch(MotionEvent.ACTION_MOVE, finger(300f, 400f))
        touch(MotionEvent.ACTION_CANCEL, finger(300f, 400f))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Actual scrolling can focus the centered page", "a", view.document!!.id)
    }

    @Test fun gapUnloadedAndRejectedActivationCannotProduceInk() {
        val a = view.pageBoundsInView("a")!!
        val b = view.pageBoundsInView("b")!!
        dot(200f, (a.bottom + b.top) / 2)
        view.setPageWindow(listOf("a", "b"), mapOf("a" to pages.getValue("a")), emptyMap())
        dot(200f, b.top + 40)
        window()
        view.onPageActivationRequested = { false }
        dot(200f, b.top + 40)
        assertTrue(commits.isEmpty())
        assertEquals("a", view.document!!.id)
    }

    @Test fun fingerScrollCrossesPagesWithoutPagingAndEquivalentNavigationTokenDoesNotJump() {
        view.navigateToPage("a", 7)
        touch(MotionEvent.ACTION_DOWN, finger(300f, 700f))
        touch(MotionEvent.ACTION_MOVE, finger(300f, 400f))
        val during = view.pageBoundsInView("a")!!.top
        assertTrue(during < 0f)
        view.document = pages.getValue("b") // ordinary active model update while panning
        view.navigateToPage("b", 7)
        assertEquals(during, view.pageBoundsInView("a")!!.top, .001f)
        touch(MotionEvent.ACTION_CANCEL, finger(300f, 400f))
        assertTrue(edges.isEmpty())
        assertTrue(commits.isEmpty())
    }

    @Test fun newExplicitNavigationReturnsToTopAfterAnAdjacentPageEdit() {
        view.navigateToPage("b", 1)
        val first = view.pageBoundsInView("a")!!
        assertTrue(first.top < 0f && first.bottom > 30f)
        dot(first.left + 100, first.bottom - 25)
        assertEquals("a", view.document!!.id)
        view.document = pages.getValue("b")
        view.navigateToPage("b", 2)
        view.document = pages.getValue("a")
        view.navigateToPage("a", 3)
        assertTrue(view.pageBoundsInView("a")!!.top >= 0f)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.pageBoundsInView("a")!!.top >= 0f)
        assertEquals("a", view.document!!.id)
    }

    @Test fun loadingEarlierPageGeometryPreservesCurrentPagePositionAndEvictionKeepsItsSize() {
        view.setPageWindow(listOf("unknown", "a", "b"), pages, emptyMap())
        view.navigateToPage("b", 1)
        val before = view.pageBoundsInView("b")!!
        val earlier = PageDocument(id = "unknown", width = 600f, height = 2200f)
        view.setPageWindow(listOf("unknown", "a", "b"), pages + ("unknown" to earlier), emptyMap())
        assertEquals(before.top, view.pageBoundsInView("b")!!.top, .01f)
        view.setPageWindow(listOf("unknown", "a", "b"), pages, emptyMap())
        assertEquals(before.top, view.pageBoundsInView("b")!!.top, .01f)
    }

    @Test fun bottomPullAppendsOnlyOnceAndCanceledPullDoesNotAppend() {
        view.navigateToPage("b", 1, atBottom = true)
        touch(MotionEvent.ACTION_DOWN, finger(300f, 500f))
        touch(MotionEvent.ACTION_MOVE, finger(300f, 350f))
        assertTrue(edges.isEmpty())
        touch(MotionEvent.ACTION_UP, finger(300f, 350f))
        touch(MotionEvent.ACTION_UP, finger(300f, 350f))
        assertEquals(listOf(PageEdgeDirection.NEXT), edges)
        assertEquals("b", view.document!!.id)
        touch(MotionEvent.ACTION_DOWN, finger(300f, 500f))
        touch(MotionEvent.ACTION_MOVE, finger(300f, 350f))
        touch(MotionEvent.ACTION_UP, finger(300f, 350f), flags = MotionEvent.FLAG_CANCELED)
        assertEquals(1, edges.size)
    }

    @Test fun longPressUsesTouchedPageCoordinatesAndSelectionSurvivesModelUpdate() {
        val text = TextElement(id = "note", text = "Second page", x = 60f, y = 60f, size = 30f)
        pages["b"] = pages.getValue("b").copy(elements = listOf(text))
        window()
        var request: Pair<Float, Float>? = null
        view.onContextMenuRequested = { x, y -> request = x to y }
        val b = view.pageBoundsInView("b")!!
        touch(MotionEvent.ACTION_DOWN, finger(b.left + 80, b.top + 75))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
        assertEquals("b", view.document!!.id)
        assertEquals(80f, request!!.first, .01f)
        assertEquals(75f, request!!.second, .01f)
        assertEquals(setOf("note"), view.selectedIds)
        view.document = pages.getValue("b")
        view.config = view.config.copy()
        touch(MotionEvent.ACTION_UP, finger(b.left + 80, b.top + 75))
        assertEquals(setOf("note"), view.selectedIds)
        assertTrue(commits.isEmpty())
    }

    @Test fun canceledStylusAndPalmCannotCommitOrMoveTheStrip() {
        val before = view.pageBoundsInView("a")!!
        touch(MotionEvent.ACTION_DOWN, pen(200f, 200f))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pen(200f, 200f), finger(400f, 500f))
        touch(MotionEvent.ACTION_MOVE, pen(250f, 250f), finger(450f, 700f))
        touch(MotionEvent.ACTION_CANCEL, pen(250f, 250f))
        assertEquals(before, view.pageBoundsInView("a"))
        assertTrue(commits.isEmpty())
    }

    @Test fun pinchAnchorsTheSameLogicalPointAndCancelsPendingFingerInk() {
        view.config = view.config.copy(touchDrawing = true)
        val before = view.pageBoundsInView("a")!!
        val fraction = (300f - before.top) / before.height()
        touch(MotionEvent.ACTION_DOWN, finger(216f, 300f))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), finger(216f, 300f), finger(416f, 300f, 1))
        touch(MotionEvent.ACTION_MOVE, finger(166f, 300f), finger(466f, 300f, 1))
        touch(MotionEvent.ACTION_MOVE, finger(116f, 300f), finger(516f, 300f, 1))
        val after = view.pageBoundsInView("a")!!
        assertTrue(after.width() > before.width())
        assertEquals(fraction, (300f - after.top) / after.height(), .01f)
        touch(MotionEvent.ACTION_CANCEL, finger(116f, 300f))
        assertTrue(commits.isEmpty())
    }

    @Test fun readOnlyCanvasFallsBackToSinglePageAndDoesNotActivateOtherPages() {
        view.isReadOnly = true
        view.resetViewport()
        dot(200f, 700f)
        assertTrue(activations.isEmpty())
        assertTrue(commits.isEmpty())
    }

    @Test fun clearingContinuousWindowRestoresSinglePageCoordinates() {
        view.navigateToPage("b", 9, atBottom = true)
        view.setPageWindow(emptyList(), emptyMap(), emptyMap())
        dot(116f, 116f)
        val point = (commits.single().elements.single() as StrokeElement).points.single()
        assertEquals("a", commits.single().id)
        assertEquals(100f, point.x, .01f)
        assertEquals(100f, point.y, .01f)
    }

    @Test fun fingerReleaseFlingsButNewStylusContactStopsMomentum() {
        touch(MotionEvent.ACTION_DOWN, finger(300f, 700f))
        touch(MotionEvent.ACTION_MOVE, finger(300f, 550f))
        touch(MotionEvent.ACTION_UP, finger(300f, 400f))
        val released = view.pageBoundsInView("a")!!.top
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
        view.computeScroll()
        val flung = view.pageBoundsInView("a")!!.top
        assertTrue("Finger release should continue smoothly through the strip", flung < released)
        touch(MotionEvent.ACTION_DOWN, pen(200f, 300f))
        val stopped = view.pageBoundsInView("a")!!.top
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
        view.computeScroll()
        assertEquals(stopped, view.pageBoundsInView("a")!!.top, .001f)
        touch(MotionEvent.ACTION_CANCEL, pen(200f, 300f))
        assertTrue(commits.isEmpty())
    }

    private fun window() = view.setPageWindow(pages.keys.toList(), pages.toMap(), emptyMap())
    private fun dot(x: Float, y: Float) { touch(MotionEvent.ACTION_DOWN, pen(x, y)); touch(MotionEvent.ACTION_UP, pen(x, y)) }
    private fun finger(x: Float, y: Float, id: Int = 0) = Pointer(id, MotionEvent.TOOL_TYPE_FINGER, x, y)
    private fun pen(x: Float, y: Float) = Pointer(7, MotionEvent.TOOL_TYPE_STYLUS, x, y)
    private fun touch(action: Int, vararg pointers: Pointer, flags: Int = 0) {
        time += 20
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        val properties = pointers.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = p.type } }.toTypedArray()
        val coords = pointers.map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = .7f; size = .1f } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, time, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
}
