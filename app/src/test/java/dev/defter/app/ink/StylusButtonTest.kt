package dev.defter.app.ink

import android.view.InputDevice
import android.view.MotionEvent
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE, qualifiers = "mdpi")
class StylusButtonTest {
    private lateinit var view: NotebookCanvas
    private val commits = mutableListOf<PageDocument>()
    private val indicators = mutableListOf<Boolean>()
    private var time = 100L
    private val primary = MotionEvent.BUTTON_STYLUS_PRIMARY
    private val secondary = MotionEvent.BUTTON_STYLUS_SECONDARY
    private val target = StrokeElement(id = "target", points = listOf(InkPoint(300f, 400f)), width = 4f)

    private data class Pointer(val id: Int = 7, val type: Int = MotionEvent.TOOL_TYPE_STYLUS,
        val x: Float, val y: Float, val pressure: Float = .7f)

    @Before fun setup() {
        view = NotebookCanvas(RuntimeEnvironment.getApplication())
        view.document = PageDocument(id = "page", width = 600f, height = 800f, elements = listOf(target))
        view.layout(0, 0, 632, 832)
        view.onDocumentChanged = { commits.add(it) }
        view.onTemporaryEraserChanged = { indicators.add(it) }
    }

    @Test fun heldPrimaryButtonErasesThenRestoresExactHighlighterSettings() {
        val configured = DrawingConfig(tool = DrawingTool.HIGHLIGHTER, color = 0xff7956cd.toInt(), width = 9f, touchDrawing = false)
        view.config = configured
        touch(MotionEvent.ACTION_DOWN, pen(300f, 400f), buttons = primary)
        assertTrue(view.isTemporaryEraserActive)
        assertEquals(listOf(target), view.document!!.elements) // Preview is not a save.
        touch(MotionEvent.ACTION_UP, pen(300f, 400f), buttons = primary)
        assertTrue(commits.single().elements.isEmpty())
        assertFalse(view.isTemporaryEraserActive)
        assertEquals(configured, view.config)
        touch(MotionEvent.ACTION_DOWN, pen(100f, 100f))
        touch(MotionEvent.ACTION_UP, pen(200f, 100f))
        val stroke = commits.last().elements.single() as StrokeElement
        assertEquals(InkKind.HIGHLIGHTER, stroke.kind)
        assertEquals(configured.color, stroke.color)
        assertEquals(configured.width, stroke.width, 0f)
        assertEquals(listOf(true, false), indicators)
    }

    @Test fun secondarySideButtonAlsoErasesWithoutMutatingTheConfiguredTool() {
        view.config = DrawingConfig(tool = DrawingTool.RECTANGLE, width = 6f)
        val original = view.config
        touch(MotionEvent.ACTION_DOWN, pen(300f, 400f), buttons = secondary)
        touch(MotionEvent.ACTION_UP, pen(300f, 400f), buttons = secondary)
        assertTrue(commits.single().elements.isEmpty())
        assertEquals(original, view.config)
    }

    @Test fun physicalEraserTipStillErasesEvenWithHandToolSelected() {
        view.config = DrawingConfig(tool = DrawingTool.HAND)
        val eraser = pen(300f, 400f).copy(type = MotionEvent.TOOL_TYPE_ERASER)
        touch(MotionEvent.ACTION_DOWN, eraser)
        touch(MotionEvent.ACTION_UP, eraser)
        assertTrue(commits.single().elements.isEmpty())
        assertFalse(view.isTemporaryEraserActive)
        assertEquals(DrawingTool.HAND, view.config.tool)
    }

    @Test fun genericAndHoverEventsUpdateIndicatorWithoutEditingThePage() {
        generic(MotionEvent.ACTION_HOVER_ENTER, pen(300f, 400f))
        generic(MotionEvent.ACTION_BUTTON_PRESS, pen(300f, 400f), buttons = primary, actionButton = primary)
        assertTrue(view.isTemporaryEraserActive)
        assertTrue(commits.isEmpty())
        generic(MotionEvent.ACTION_BUTTON_RELEASE, pen(300f, 400f), actionButton = primary)
        assertFalse(view.isTemporaryEraserActive)
        generic(MotionEvent.ACTION_HOVER_MOVE, pen(300f, 400f), buttons = primary)
        assertTrue(view.isTemporaryEraserActive)
        generic(MotionEvent.ACTION_HOVER_EXIT, pen(300f, 400f), buttons = primary)
        assertFalse(view.isTemporaryEraserActive)
        assertEquals(listOf(target), view.document!!.elements)
        assertTrue(commits.isEmpty())
        assertEquals(listOf(true, false, true, false), indicators)
    }

    @Test fun hoverHeldButtonIsRecognizedWhenTipTouchesThePage() {
        generic(MotionEvent.ACTION_HOVER_ENTER, pen(300f, 400f), buttons = primary)
        assertTrue(view.isTemporaryEraserActive)
        // Android can end hovering immediately before delivering DOWN.
        generic(MotionEvent.ACTION_HOVER_EXIT, pen(300f, 400f), buttons = primary)
        touch(MotionEvent.ACTION_DOWN, pen(300f, 400f), buttons = primary)
        touch(MotionEvent.ACTION_UP, pen(300f, 400f), buttons = primary)
        assertTrue(commits.single().elements.isEmpty())
    }

    @Test fun pressAndReleaseDuringContactCommitOneTransactionWithoutInkBridges() {
        val configured = DrawingConfig(width = 9f, color = 0xff7956cd.toInt())
        view.config = configured
        beginMixedContact()
        assertTrue(commits.isEmpty())
        assertEquals(listOf(target), view.document!!.elements)
        touch(MotionEvent.ACTION_UP, pen(500f, 550f))
        val result = commits.single()
        assertEquals(1L, result.revision)
        val strokes = result.elements.filterIsInstance<StrokeElement>()
        assertEquals(2, strokes.size)
        assertEquals(listOf(100f, 200f), strokes[0].points.map { it.x })
        assertEquals(400f, strokes[1].points.first().x, 0f)
        assertEquals(500f, strokes[1].points.first().y, 0f)
        assertTrue(result.elements.none { it.id == target.id })
        assertTrue(strokes.all { it.width == 9f && it.color == configured.color })
        assertEquals(configured, view.config)
    }

    @Test fun cancelDiscardsEveryPenAndEraserSegmentOfTheContact() {
        beginMixedContact()
        touch(MotionEvent.ACTION_CANCEL, pen(500f, 500f))
        assertTrue(commits.isEmpty())
        assertEquals(listOf(target), view.document!!.elements)
        assertFalse(view.isTemporaryEraserActive)
        touch(MotionEvent.ACTION_DOWN, pen(50f, 50f))
        touch(MotionEvent.ACTION_UP, pen(60f, 60f))
        assertEquals(2, commits.single().elements.size)
        assertTrue(commits.single().elements.any { it == target })
    }

    @Test fun canceledUpAlsoDiscardsAllStagedSegments() {
        beginMixedContact()
        touch(MotionEvent.ACTION_UP, pen(500f, 500f), flags = MotionEvent.FLAG_CANCELED)
        assertTrue(commits.isEmpty())
        assertEquals(listOf(target), view.document!!.elements)
    }

    @Test fun releaseReportedOnlyOnUpDoesNotLeaveAPenDot() {
        touch(MotionEvent.ACTION_DOWN, pen(300f, 400f), buttons = primary)
        touch(MotionEvent.ACTION_UP, pen(300f, 400f))
        assertTrue(commits.single().elements.isEmpty())
        assertFalse(view.isTemporaryEraserActive)
    }

    @Test fun stationaryMidContactButtonChangesDoNotCreateInkDots() {
        touch(MotionEvent.ACTION_DOWN, pen(100f, 100f))
        generic(MotionEvent.ACTION_BUTTON_PRESS, pen(100f, 100f), buttons = primary, actionButton = primary)
        generic(MotionEvent.ACTION_BUTTON_RELEASE, pen(100f, 100f), actionButton = primary)
        touch(MotionEvent.ACTION_UP, pen(100f, 100f))
        assertTrue(commits.isEmpty())
        assertEquals(listOf(target), view.document!!.elements)
    }

    @Test fun motionButtonStateChangesWorkWithoutGenericButtonEvents() {
        touch(MotionEvent.ACTION_DOWN, pen(100f, 100f))
        touch(MotionEvent.ACTION_MOVE, pen(200f, 100f))
        touch(MotionEvent.ACTION_MOVE, pen(300f, 400f), buttons = primary)
        assertTrue(view.isTemporaryEraserActive)
        touch(MotionEvent.ACTION_MOVE, pen(400f, 500f))
        assertFalse(view.isTemporaryEraserActive)
        touch(MotionEvent.ACTION_UP, pen(500f, 500f))
        val result = commits.single().elements.filterIsInstance<StrokeElement>()
        assertEquals(2, result.size)
        assertEquals(200f, result[0].points.last().x, 0f)
        assertEquals(400f, result[1].points.first().x, 0f)
    }

    @Test fun mouseAndFingerButtonsNeverActivateStylusEraser() {
        for (type in listOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_MOUSE)) {
            view.document = view.document!!.copy(elements = listOf(target))
            val pointer = pen(300f, 400f).copy(type = type)
            generic(MotionEvent.ACTION_BUTTON_PRESS, pointer, buttons = primary or MotionEvent.BUTTON_SECONDARY,
                actionButton = primary)
            assertFalse(view.isTemporaryEraserActive)
            touch(MotionEvent.ACTION_DOWN, pointer, buttons = primary or MotionEvent.BUTTON_SECONDARY)
            touch(MotionEvent.ACTION_UP, pointer, buttons = primary or MotionEvent.BUTTON_SECONDARY)
            assertFalse(view.isTemporaryEraserActive)
            assertEquals(2, view.document!!.elements.size)
            assertTrue(view.document!!.elements.any { it == target })
        }
    }

    @Test fun batchedEraserFollowsHistoricalPathAndPreservesUntouchedShortcutInk() {
        val actual = StrokeElement(id = "actual", points = listOf(InkPoint(300f, 500f)))
        val shortcut = StrokeElement(id = "shortcut", points = listOf(InkPoint(300f, 100f)))
        view.document = view.document!!.copy(elements = listOf(actual, shortcut))
        touch(MotionEvent.ACTION_DOWN, pen(100f, 100f), buttons = primary)
        val move = event(MotionEvent.ACTION_MOVE, arrayOf(pen(300f, 500f)), primary)
        time += 10
        move.addBatch(time, arrayOf(coordinates(pen(500f, 100f))), 0)
        try {
            assertEquals(1, move.historySize)
            view.onTouchEvent(move)
        } finally { move.recycle() }
        touch(MotionEvent.ACTION_UP, pen(500f, 100f), buttons = primary)
        assertEquals(listOf(shortcut), commits.single().elements)
    }

    @Test fun palmPointerChangesCannotReleaseTheStylusButtonOrOwnTheGesture() {
        touch(MotionEvent.ACTION_DOWN, pen(300f, 400f), buttons = primary)
        val palm = Pointer(id = 0, type = MotionEvent.TOOL_TYPE_FINGER, x = 500f, y = 700f)
        touch(MotionEvent.ACTION_POINTER_DOWN, palm, pen(300f, 400f))
        assertTrue(view.isTemporaryEraserActive)
        touch(MotionEvent.ACTION_MOVE, palm, pen(310f, 400f), buttons = primary)
        touch(MotionEvent.ACTION_POINTER_UP, palm, pen(310f, 400f))
        assertTrue(view.isTemporaryEraserActive)
        touch(MotionEvent.ACTION_UP, pen(310f, 400f), buttons = primary)
        assertTrue(commits.single().elements.isEmpty())
    }

    @Test fun pageChangeDisableAndFocusLossClearTransientButtonAndCancelEdits() {
        touch(MotionEvent.ACTION_DOWN, pen(300f, 400f), buttons = primary)
        view.document = PageDocument(id = "next", width = 600f, height = 800f, elements = listOf(target))
        assertFalse(view.isTemporaryEraserActive)
        touch(MotionEvent.ACTION_UP, pen(300f, 400f), buttons = primary)
        assertTrue(commits.isEmpty())
        touch(MotionEvent.ACTION_DOWN, pen(300f, 400f), buttons = primary)
        view.isEnabled = false
        assertFalse(view.isTemporaryEraserActive)
        view.isEnabled = true
        touch(MotionEvent.ACTION_UP, pen(300f, 400f), buttons = primary)
        assertTrue(commits.isEmpty())
        touch(MotionEvent.ACTION_DOWN, pen(300f, 400f), buttons = primary)
        view.onWindowFocusChanged(false)
        assertFalse(view.isTemporaryEraserActive)
        touch(MotionEvent.ACTION_UP, pen(300f, 400f), buttons = primary)
        assertTrue(commits.isEmpty())
        assertEquals(listOf(target), view.document!!.elements)
    }

    @Test fun temporaryEraserDeletingSelectedObjectClearsSelectionImmediately() {
        val selections = mutableListOf<String?>()
        view.onSelectionChanged = { selections.add(it) }
        view.config = DrawingConfig(tool = DrawingTool.SELECT)
        touch(MotionEvent.ACTION_DOWN, pen(300f, 400f))
        touch(MotionEvent.ACTION_UP, pen(300f, 400f))
        assertEquals(target.id, view.selectedElementId)
        assertTrue(commits.isEmpty())
        touch(MotionEvent.ACTION_DOWN, pen(300f, 400f), buttons = primary)
        touch(MotionEvent.ACTION_UP, pen(300f, 400f), buttons = primary)
        assertTrue(commits.single().elements.isEmpty())
        assertNull(view.selectedElementId)
        assertEquals(listOf(target.id, null), selections)
        assertEquals(DrawingTool.SELECT, view.config.tool)
    }

    private fun beginMixedContact() {
        touch(MotionEvent.ACTION_DOWN, pen(100f, 100f))
        touch(MotionEvent.ACTION_MOVE, pen(200f, 100f))
        generic(MotionEvent.ACTION_BUTTON_PRESS, pen(300f, 400f), buttons = primary, actionButton = primary)
        assertTrue(view.isTemporaryEraserActive)
        generic(MotionEvent.ACTION_BUTTON_RELEASE, pen(400f, 500f), actionButton = primary)
        touch(MotionEvent.ACTION_MOVE, pen(500f, 500f))
    }

    private fun pen(x: Float, y: Float) = Pointer(x = x, y = y)

    private fun coordinates(pointer: Pointer) = MotionEvent.PointerCoords().apply {
        x = pointer.x + 16f; y = pointer.y + 16f
        pressure = pointer.pressure; size = .1f
    }

    private fun event(action: Int, pointers: Array<out Pointer>, buttons: Int = 0, flags: Int = 0): MotionEvent {
        time += 20
        val properties = pointers.map { pointer -> MotionEvent.PointerProperties().apply {
            id = pointer.id; toolType = pointer.type
        } }.toTypedArray()
        val coords = pointers.map(::coordinates).toTypedArray()
        val source = when (pointers.first().type) {
            MotionEvent.TOOL_TYPE_MOUSE -> InputDevice.SOURCE_MOUSE
            MotionEvent.TOOL_TYPE_FINGER -> InputDevice.SOURCE_TOUCHSCREEN
            else -> InputDevice.SOURCE_STYLUS
        }
        return MotionEvent.obtain(100L, time, action, pointers.size, properties, coords,
            0, buttons, 1f, 1f, 0, 0, source, flags)
    }

    private fun touch(action: Int, vararg pointers: Pointer, buttons: Int = 0, flags: Int = 0) {
        val motion = event(action, pointers, buttons, flags)
        try { view.onTouchEvent(motion) } finally { motion.recycle() }
    }

    private fun generic(action: Int, pointer: Pointer, buttons: Int = 0, actionButton: Int = 0) {
        val motion = event(action, arrayOf(pointer), buttons)
        // Android sets this hidden native field when delivering generic button events.
        ReflectionHelpers.callInstanceMethod<Void>(motion, "setActionButton", ClassParameter.from(Integer.TYPE, actionButton))
        try { view.dispatchGenericMotionEvent(motion) } finally { motion.recycle() }
    }
}
