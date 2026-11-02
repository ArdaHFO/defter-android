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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, qualifiers = "mdpi")
class NotebookCanvasTest {
    private lateinit var view: NotebookCanvas
    private val committed = mutableListOf<PageDocument>()
    private var time = 100L

    private data class Pointer(val id: Int, val type: Int, val x: Float, val y: Float, val pressure: Float = 0.7f)

    @Before fun setUp() {
        view = NotebookCanvas(RuntimeEnvironment.getApplication())
        view.document = PageDocument(id = "page", width = 600f, height = 800f)
        view.layout(0, 0, 632, 832) // 16px margin => 1 screen pixel per document unit.
        view.onDocumentChanged = { committed.add(it) }
    }

    @Test fun completedStrokePreservesLogicalCoordinatesPressureAndImmutablePoints() {
        send(MotionEvent.ACTION_DOWN, pen(7, 116f, 216f, 0.2f))
        send(MotionEvent.ACTION_MOVE, pen(7, 216f, 316f, 0.9f))
        send(MotionEvent.ACTION_UP, pen(7, 316f, 416f, 0.6f))
        val stroke = committed.single().elements.single() as StrokeElement
        assertEquals(InkPoint(100f, 200f, 0.2f), stroke.points.first())
        assertEquals(InkPoint(300f, 400f, 0.6f), stroke.points.last())
        assertEquals(1L, committed.single().revision)
        // Beginning another gesture clears the transient list, never the committed stroke.
        send(MotionEvent.ACTION_DOWN, pen(3, 116f, 116f))
        send(MotionEvent.ACTION_CANCEL, pen(3, 116f, 116f))
        assertEquals(3, stroke.points.size)
    }

    @Test fun landscapeViewportFitsWidthAndStartsAtTop() {
        view.layout(0, 0, 1232, 832) // 1200 usable pixels / 600 document units => scale 2.
        send(MotionEvent.ACTION_DOWN, pen(7, 216f, 216f, 0.5f))
        send(MotionEvent.ACTION_UP, pen(7, 416f, 416f, 0.5f))
        val stroke = committed.single().elements.single() as StrokeElement
        assertEquals(InkPoint(100f, 100f, 0.5f), stroke.points.first())
        assertEquals(InkPoint(200f, 200f, 0.5f), stroke.points.last())
    }

    @Test fun samePageUpdatePreservesPanPosition() {
        view.layout(0, 0, 1232, 832)
        view.config = DrawingConfig(touchDrawing = false)
        send(MotionEvent.ACTION_DOWN, finger(0, 216f, 216f))
        send(MotionEvent.ACTION_MOVE, finger(0, 216f, 16f))
        send(MotionEvent.ACTION_UP, finger(0, 216f, 16f))
        view.document = view.document!!.copy(template = PaperTemplate.GRID)
        send(MotionEvent.ACTION_DOWN, pen(7, 216f, 216f, 0.5f))
        send(MotionEvent.ACTION_UP, pen(7, 416f, 416f, 0.5f))
        val stroke = committed.single().elements.single() as StrokeElement
        assertEquals(InkPoint(100f, 200f, 0.5f), stroke.points.first())
        assertEquals(InkPoint(200f, 300f, 0.5f), stroke.points.last())
    }

    @Test fun recompositionDuringStrokeAndReentrantCommitKeepPoints() {
        view.onDocumentChanged = { changed ->
            committed.add(changed)
            view.document = changed.copy(revision = changed.revision + 1)
        }
        send(MotionEvent.ACTION_DOWN, pen(7, 116f, 216f, 0.2f))
        // AndroidView update can supply equivalent properties during unrelated recomposition.
        view.document = view.document!!.copy()
        view.config = view.config.copy()
        view.assets = emptyMap()
        send(MotionEvent.ACTION_MOVE, pen(7, 216f, 316f, 0.9f))
        send(MotionEvent.ACTION_UP, pen(7, 316f, 416f, 0.6f))
        val saved = view.document!!.elements.single() as StrokeElement
        assertEquals(3, saved.points.size)
        assertEquals(InkPoint(100f, 200f, 0.2f), saved.points.first())
        assertEquals(InkPoint(300f, 400f, 0.6f), saved.points.last())
        view.document = view.document!!.copy()
        assertEquals(saved, view.document!!.elements.single())
    }

    @Test fun canceledStrokeNeverCreatesPersistentEdit() {
        send(MotionEvent.ACTION_DOWN, pen(4, 100f, 100f))
        send(MotionEvent.ACTION_MOVE, pen(4, 200f, 200f))
        send(MotionEvent.ACTION_CANCEL, pen(4, 200f, 200f))
        assertTrue(committed.isEmpty())
        assertTrue(view.document!!.elements.isEmpty())
    }

    @Test fun canceledPointerUpNeverCreatesPersistentEdit() {
        send(MotionEvent.ACTION_DOWN, pen(5, 100f, 100f))
        send(MotionEvent.ACTION_MOVE, pen(5, 200f, 200f))
        send(MotionEvent.ACTION_UP, pen(5, 200f, 200f), flags = MotionEvent.FLAG_CANCELED)
        assertTrue(committed.isEmpty())
        assertTrue(view.document!!.elements.isEmpty())
    }

    @Test fun disablingEditorCancelsPendingInkAndRejectsNewInput() {
        send(MotionEvent.ACTION_DOWN, pen(5, 100f, 100f))
        send(MotionEvent.ACTION_MOVE, pen(5, 200f, 200f))
        view.isEnabled = false
        send(MotionEvent.ACTION_UP, pen(5, 200f, 200f))
        send(MotionEvent.ACTION_DOWN, pen(5, 100f, 100f))
        send(MotionEvent.ACTION_UP, pen(5, 200f, 200f))
        assertTrue(committed.isEmpty())
        assertTrue(view.document!!.elements.isEmpty())
    }

    @Test fun penIsTrackedByIdWhenPalmChangesPointerOrder() {
        send(MotionEvent.ACTION_DOWN, pen(7, 116f, 116f))
        send(MotionEvent.ACTION_POINTER_DOWN, finger(0, 500f, 700f), pen(7, 116f, 116f))
        send(MotionEvent.ACTION_MOVE, finger(0, 520f, 720f), pen(7, 216f, 216f))
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            finger(0, 520f, 720f), pen(7, 216f, 216f))
        val stroke = committed.single().elements.single() as StrokeElement
        assertEquals(100f, stroke.points.first().x, 0.01f)
        assertEquals(200f, stroke.points.last().x, 0.01f)
        assertEquals(200f, stroke.points.last().y, 0.01f)
    }

    @Test fun twoFingerGestureDiscardsInitialFingerMark() {
        send(MotionEvent.ACTION_DOWN, finger(0, 116f, 116f))
        send(MotionEvent.ACTION_MOVE, finger(0, 146f, 146f))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            finger(0, 146f, 146f), finger(1, 300f, 300f))
        send(MotionEvent.ACTION_MOVE, finger(0, 166f, 166f), finger(1, 340f, 340f))
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            finger(0, 166f, 166f), finger(1, 340f, 340f))
        send(MotionEvent.ACTION_UP, finger(0, 166f, 166f))
        assertTrue(committed.isEmpty())
        assertTrue(view.document!!.elements.isEmpty())
    }

    @Test fun penOnlyModeAllowsPenAndDoesNotDrawFinger() {
        view.config = DrawingConfig(touchDrawing = false)
        send(MotionEvent.ACTION_DOWN, finger(0, 116f, 116f))
        send(MotionEvent.ACTION_MOVE, finger(0, 216f, 216f))
        send(MotionEvent.ACTION_UP, finger(0, 216f, 216f))
        assertTrue(committed.isEmpty())
        send(MotionEvent.ACTION_DOWN, pen(4, 116f, 116f))
        send(MotionEvent.ACTION_UP, pen(4, 216f, 216f))
        assertEquals(1, committed.size)
    }

    @Test fun canceledEraserRestoresAllPreviewRemovedStrokes() {
        val stroke = StrokeElement(id = "original", points = listOf(InkPoint(50f, 100f), InkPoint(350f, 100f)))
        view.document = view.document!!.copy(elements = listOf(stroke))
        view.config = DrawingConfig(tool = DrawingTool.ERASER)
        send(MotionEvent.ACTION_DOWN, pen(3, 216f, 116f))
        send(MotionEvent.ACTION_CANCEL, pen(3, 216f, 116f))
        assertTrue(committed.isEmpty())
        assertEquals(listOf(stroke), view.document!!.elements)
    }

    @Test fun eraserHitsSparseStrokeMiddleAndLeavesImage() {
        val stroke = StrokeElement(id = "original", points = listOf(InkPoint(50f, 100f), InkPoint(350f, 100f)))
        val image = ImageElement(assetId = "asset", x = 100f, y = 50f, width = 200f, height = 200f)
        view.document = view.document!!.copy(elements = listOf(stroke, image))
        view.config = DrawingConfig(tool = DrawingTool.ERASER)
        send(MotionEvent.ACTION_DOWN, pen(3, 216f, 116f))
        send(MotionEvent.ACTION_UP, pen(3, 216f, 116f))
        assertEquals(listOf(image), committed.single().elements)
    }

    @Test fun canceledSelectionDragDoesNotModifyOriginal() {
        val image = ImageElement(id = "image", assetId = "asset", x = 100f, y = 100f, width = 200f, height = 200f)
        view.document = view.document!!.copy(elements = listOf(image))
        view.config = DrawingConfig(tool = DrawingTool.SELECT)
        send(MotionEvent.ACTION_DOWN, pen(3, 166f, 166f))
        send(MotionEvent.ACTION_MOVE, pen(3, 266f, 266f))
        send(MotionEvent.ACTION_CANCEL, pen(3, 266f, 266f))
        assertEquals(listOf(image), view.document!!.elements)
        assertTrue(committed.isEmpty())
    }

    @Test fun completedSelectionDragCanBeDeleted() {
        val image = ImageElement(id = "image", assetId = "asset", x = 100f, y = 100f, width = 200f, height = 200f)
        view.document = view.document!!.copy(elements = listOf(image))
        view.config = DrawingConfig(tool = DrawingTool.SELECT)
        send(MotionEvent.ACTION_DOWN, pen(3, 166f, 166f))
        send(MotionEvent.ACTION_UP, pen(3, 266f, 266f))
        val moved = committed.single().elements.single() as ImageElement
        assertEquals(200f, moved.x, 0f)
        assertEquals(200f, moved.y, 0f)
        assertEquals("image", view.selectedElementId)
        view.deleteSelection()
        assertTrue(committed.last().elements.isEmpty())
        assertEquals(2L, committed.last().revision)
    }

    @Test fun switchingPagesCancelsInProgressStroke() {
        send(MotionEvent.ACTION_DOWN, pen(3, 116f, 116f))
        send(MotionEvent.ACTION_MOVE, pen(3, 216f, 216f))
        view.document = PageDocument(id = "another", width = 600f, height = 800f)
        send(MotionEvent.ACTION_UP, pen(3, 316f, 316f))
        assertEquals("another", view.document!!.id)
        assertTrue(view.document!!.elements.isEmpty())
        assertTrue(committed.isEmpty())
    }

    @Test fun shapeCommitKeepsDocumentCoordinates() {
        view.config = DrawingConfig(tool = DrawingTool.RECTANGLE, color = 0xFF227755.toInt(), width = 5f)
        send(MotionEvent.ACTION_DOWN, pen(3, 116f, 116f))
        send(MotionEvent.ACTION_UP, pen(3, 316f, 416f))
        val shape = committed.single().elements.single() as ShapeElement
        assertEquals(ShapeKind.RECTANGLE, shape.shape)
        assertEquals(100f, shape.startX, 0f)
        assertEquals(300f, shape.endX, 0f)
        assertEquals(400f, shape.endY, 0f)
        assertEquals(5f, shape.width, 0f)
    }

    private fun pen(id: Int, x: Float, y: Float, pressure: Float = 0.7f) = Pointer(id, MotionEvent.TOOL_TYPE_STYLUS, x, y, pressure)
    private fun finger(id: Int, x: Float, y: Float) = Pointer(id, MotionEvent.TOOL_TYPE_FINGER, x, y)

    private fun send(action: Int, vararg pointers: Pointer, flags: Int = 0) {
        time += 20
        val properties = pointers.map { pointer -> MotionEvent.PointerProperties().apply {
            id = pointer.id
            toolType = pointer.type
        } }.toTypedArray()
        val coordinates = pointers.map { pointer -> MotionEvent.PointerCoords().apply {
            x = pointer.x
            y = pointer.y
            pressure = pointer.pressure
            size = 0.1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(100L, time, action, pointers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, flags)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
}
