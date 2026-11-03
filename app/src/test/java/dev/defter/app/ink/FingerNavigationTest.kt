package dev.defter.app.ink

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
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class FingerNavigationTest {
    private lateinit var view: NotebookCanvas
    private val pages = mutableListOf<PageEdgeDirection>()
    private val progress = mutableListOf<PageEdgeProgress?>()
    private val menus = mutableListOf<Pair<Float, Float>>()
    private val commits = mutableListOf<PageDocument>()
    private var time = 100L

    private data class Pointer(val id: Int, val type: Int, val x: Float, val y: Float)

    @Before fun setup() {
        view = NotebookCanvas(RuntimeEnvironment.getApplication())
        view.document = PageDocument(id = "page", width = 600f, height = 1600f)
        view.layout(0, 0, 632, 832) // Width-fit scale1, top16, bottom offset-792.
        view.config = DrawingConfig(touchDrawing = false)
        view.edgeNavigationEnabled = true
        view.canNavigatePrevious = true
        view.canNavigateNext = true
        view.onDocumentChanged = { commits.add(it) }
        view.onContextMenuRequested = { x, y -> menus.add(x to y) }
        view.onPageEdgeGesture = { pages.add(it) }
        view.onPageEdgeProgress = { progress.add(it) }
    }

    @Test fun fingerPansWhileStylusStillWritesTheSelectedTool() {
        touch(MotionEvent.ACTION_DOWN, finger(200f, 700f))
        touch(MotionEvent.ACTION_UP, finger(200f, 300f))
        assertTrue(commits.isEmpty())
        assertTrue(pages.isEmpty())
        touch(MotionEvent.ACTION_DOWN, pen(216f, 216f))
        touch(MotionEvent.ACTION_UP, pen(316f, 316f))
        val stroke = commits.single().elements.single() as StrokeElement
        assertEquals(200f, stroke.points.first().x, .001f)
        assertEquals(600f, stroke.points.first().y, .001f)
        assertEquals(DrawingTool.PEN, view.config.tool)
    }

    @Test fun fingerDrawingModeWritesWithoutLongPressOrEdgePaging() {
        view.config = view.config.copy(touchDrawing = true)
        view.resetViewport(atBottom = true)
        touch(MotionEvent.ACTION_DOWN, finger(200f, 500f))
        waitLongPress()
        touch(MotionEvent.ACTION_UP, finger(200f, 350f))
        assertEquals(1, commits.size)
        assertTrue(menus.isEmpty())
        assertTrue(pages.isEmpty())
    }

    @Test fun normalPanAndItsLargeConsumedDistanceDoNotChangePage() {
        touch(MotionEvent.ACTION_DOWN, finger(200f, 700f))
        touch(MotionEvent.ACTION_UP, finger(200f, -150f))
        // 808px pans the page, only42px passes the bottom. The850px gesture is not an edge trigger.
        assertTrue(pages.isEmpty())
        assertTrue(commits.isEmpty())
        assertTrue(progress.filterNotNull().all { !it.armed })
    }

    @Test fun additionalUpwardPullAtBottomTriggersNextExactlyOnceOnUp() {
        view.resetViewport(atBottom = true)
        touch(MotionEvent.ACTION_DOWN, finger(200f, 500f))
        touch(MotionEvent.ACTION_MOVE, finger(200f, 380f))
        assertTrue(pages.isEmpty())
        assertTrue(progress.last()!!.armed)
        touch(MotionEvent.ACTION_UP, finger(200f, 380f))
        touch(MotionEvent.ACTION_UP, finger(200f, 380f))
        assertEquals(listOf(PageEdgeDirection.NEXT), pages)
        assertNull(progress.last())
    }

    @Test fun topDownwardPullTriggersPreviousAndBottomLandingUsesCorrectCoordinates() {
        touch(MotionEvent.ACTION_DOWN, finger(200f, 300f))
        touch(MotionEvent.ACTION_UP, finger(200f, 420f))
        assertEquals(listOf(PageEdgeDirection.PREVIOUS), pages)
        view.resetViewport(atBottom = true)
        touch(MotionEvent.ACTION_DOWN, pen(216f, 208f))
        touch(MotionEvent.ACTION_UP, pen(216f, 208f))
        val point = (commits.single().elements.single() as StrokeElement).points.single()
        assertEquals(200f, point.x, .001f)
        assertEquals(1000f, point.y, .001f)
    }

    @Test fun lastPageRequiresExplicitAddPermission() {
        view.canNavigateNext = false
        view.canAddPageAtEnd = false
        view.resetViewport(atBottom = true)
        touch(MotionEvent.ACTION_DOWN, finger(200f, 500f))
        touch(MotionEvent.ACTION_UP, finger(200f, 350f))
        assertTrue(pages.isEmpty())
        view.canAddPageAtEnd = true
        touch(MotionEvent.ACTION_DOWN, finger(200f, 500f))
        touch(MotionEvent.ACTION_UP, finger(200f, 350f))
        assertEquals(listOf(PageEdgeDirection.NEXT), pages)
        assertTrue(commits.isEmpty()) // Creating the page is delegated to the repository/UI.
    }

    @Test fun firstPageAndDisabledEdgeSettingPreventNavigation() {
        view.canNavigatePrevious = false
        touch(MotionEvent.ACTION_DOWN, finger(200f, 300f))
        touch(MotionEvent.ACTION_UP, finger(200f, 500f))
        view.resetViewport(atBottom = true)
        view.edgeNavigationEnabled = false
        touch(MotionEvent.ACTION_DOWN, finger(200f, 500f))
        touch(MotionEvent.ACTION_UP, finger(200f, 350f))
        assertTrue(pages.isEmpty())
    }

    @Test fun withdrawingAnArmedPullBelowReleaseThresholdCancelsNavigation() {
        view.resetViewport(atBottom = true)
        touch(MotionEvent.ACTION_DOWN, finger(200f, 500f))
        touch(MotionEvent.ACTION_MOVE, finger(200f, 380f)) //120px arms.
        touch(MotionEvent.ACTION_MOVE, finger(200f, 450f)) //70px refund leaves50px.
        assertFalse(progress.last()!!.armed)
        touch(MotionEvent.ACTION_UP, finger(200f, 450f))
        assertTrue(pages.isEmpty())
    }

    @Test fun diagonalPanDoesNotAccidentallyTurnPages() {
        view.resetViewport(atBottom = true)
        touch(MotionEvent.ACTION_DOWN, finger(100f, 500f))
        touch(MotionEvent.ACTION_UP, finger(400f, 350f))
        assertTrue(pages.isEmpty())
    }

    @Test fun cancelAndCanceledUpDiscardArmedPagePull() {
        view.resetViewport(atBottom = true)
        touch(MotionEvent.ACTION_DOWN, finger(200f, 500f))
        touch(MotionEvent.ACTION_MOVE, finger(200f, 350f))
        touch(MotionEvent.ACTION_CANCEL, finger(200f, 350f))
        touch(MotionEvent.ACTION_DOWN, finger(200f, 500f))
        touch(MotionEvent.ACTION_MOVE, finger(200f, 350f))
        touch(MotionEvent.ACTION_UP, finger(200f, 350f), flags = MotionEvent.FLAG_CANCELED)
        assertTrue(pages.isEmpty())
        assertNull(progress.last())
    }

    @Test fun pinchCancelsBothPendingMenuAndArmedPagePull() {
        view.resetViewport(atBottom = true)
        touch(MotionEvent.ACTION_DOWN, finger(200f, 500f))
        touch(MotionEvent.ACTION_MOVE, finger(200f, 350f))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            finger(200f, 350f), finger(400f, 550f, 1))
        waitLongPress()
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            finger(200f, 350f), finger(400f, 550f, 1))
        touch(MotionEvent.ACTION_UP, finger(200f, 350f))
        assertTrue(menus.isEmpty())
        assertTrue(pages.isEmpty())
        assertNull(progress.last())
    }

    @Test fun stylusContactPreemptsFingerMenuAndEdgePull() {
        view.resetViewport(atBottom = true)
        touch(MotionEvent.ACTION_DOWN, finger(200f, 500f))
        touch(MotionEvent.ACTION_MOVE, finger(200f, 350f))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            finger(200f, 350f), pen(316f, 400f))
        waitLongPress()
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            finger(200f, 350f), pen(316f, 400f))
        touch(MotionEvent.ACTION_UP, finger(200f, 350f))
        assertTrue(pages.isEmpty())
        assertTrue(menus.isEmpty())
        assertEquals(1, commits.size)
    }

    @Test fun longPressSelectsObjectWithoutChangingPenAndUpDoesNotDriftOrCommit() {
        val image = ImageElement(id = "image", assetId = "asset", x = 50f, y = 50f, width = 200f, height = 150f)
        view.document = view.document!!.copy(elements = listOf(image))
        touch(MotionEvent.ACTION_DOWN, finger(116f, 116f))
        waitLongPress()
        assertEquals(listOf(100f to 100f), menus)
        assertEquals(setOf("image"), view.selectedIds)
        assertEquals(DrawingTool.PEN, view.config.tool)
        view.config = view.config.copy() // Equivalent recomposition preserves context selection.
        assertEquals(setOf("image"), view.selectedIds)
        touch(MotionEvent.ACTION_MOVE, finger(316f, 316f))
        touch(MotionEvent.ACTION_UP, finger(316f, 316f))
        assertTrue(commits.isEmpty())
        assertTrue(pages.isEmpty())
        assertEquals(listOf(image), view.document!!.elements)
        touch(MotionEvent.ACTION_DOWN, pen(116f, 116f))
        touch(MotionEvent.ACTION_UP, pen(116f, 116f))
        val point = (commits.single().elements.last() as StrokeElement).points.single()
        assertEquals(100f, point.y, .001f)
    }

    @Test fun longPressPreservesSelectedGroupAndBlankAreaClearsIt() {
        val a = ImageElement(id = "a", assetId = "asset", x = 50f, y = 50f, width = 150f, height = 150f)
        val b = a.copy(id = "b", x = 300f)
        view.document = view.document!!.copy(elements = listOf(a, b))
        view.selectElements(setOf("a", "b"))
        touch(MotionEvent.ACTION_DOWN, finger(116f, 116f))
        waitLongPress()
        touch(MotionEvent.ACTION_UP, finger(116f, 116f))
        assertEquals(setOf("a", "b"), view.selectedIds)
        touch(MotionEvent.ACTION_DOWN, finger(516f, 416f))
        waitLongPress()
        touch(MotionEvent.ACTION_UP, finger(516f, 416f))
        assertTrue(view.selectedIds.isEmpty())
        assertEquals(2, menus.size)
    }

    @Test fun movementBeyondSlopCancelsLongPressEvenIfFingerReturns() {
        touch(MotionEvent.ACTION_DOWN, finger(216f, 216f))
        touch(MotionEvent.ACTION_MOVE, finger(216f, 316f))
        touch(MotionEvent.ACTION_MOVE, finger(216f, 216f))
        waitLongPress()
        touch(MotionEvent.ACTION_UP, finger(216f, 216f))
        assertTrue(menus.isEmpty())
    }

    @Test fun disabledDetachedCanceledAndNewPageContactsCannotOpenLateMenus() {
        touch(MotionEvent.ACTION_DOWN, finger(216f, 216f))
        view.isEnabled = false
        waitLongPress()
        view.isEnabled = true
        touch(MotionEvent.ACTION_DOWN, finger(216f, 216f))
        ReflectionHelpers.callInstanceMethod<Any?>(view, "onDetachedFromWindow")
        waitLongPress()
        touch(MotionEvent.ACTION_DOWN, finger(216f, 216f))
        touch(MotionEvent.ACTION_CANCEL, finger(216f, 216f))
        waitLongPress()
        touch(MotionEvent.ACTION_DOWN, finger(216f, 216f))
        view.document = view.document!!.copy(id = "next-page")
        waitLongPress()
        assertTrue(menus.isEmpty())
    }

    @Test fun longPressOutsidePaperAndStylusLongPressDoNotOpenMenus() {
        touch(MotionEvent.ACTION_DOWN, finger(2f, 2f))
        waitLongPress()
        touch(MotionEvent.ACTION_UP, finger(2f, 2f))
        touch(MotionEvent.ACTION_DOWN, pen(216f, 216f))
        waitLongPress()
        touch(MotionEvent.ACTION_UP, pen(216f, 216f))
        assertTrue(menus.isEmpty())
        assertEquals(1, commits.size)
    }

    private fun waitLongPress() = shadowOf(Looper.getMainLooper()).idleFor(
        Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))

    private fun finger(x: Float, y: Float, id: Int = 0) = Pointer(id, MotionEvent.TOOL_TYPE_FINGER, x, y)
    private fun pen(x: Float, y: Float) = Pointer(7, MotionEvent.TOOL_TYPE_STYLUS, x, y)

    private fun touch(action: Int, vararg pointers: Pointer, flags: Int = 0) {
        time += 20
        val properties = pointers.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = p.type } }.toTypedArray()
        val coordinates = pointers.map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = .7f; size = .1f } }.toTypedArray()
        val event = MotionEvent.obtain(100, time, action, pointers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
}
