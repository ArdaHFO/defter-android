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
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE, qualifiers = "mdpi")
class AdvancedCanvasTest {
    private lateinit var view: NotebookCanvas
    private val commits = mutableListOf<PageDocument>()
    private var time = 100L

    @Before fun setup() {
        view = NotebookCanvas(RuntimeEnvironment.getApplication())
        view.document = PageDocument(id = "page", width = 600f, height = 800f)
        view.layout(0, 0, 632, 832)
        view.onDocumentChanged = { commits.add(it) }
    }

    @Test fun lassoGroupCanMoveCopyRecolorAndDeleteAsSingleEdits() {
        val a = StrokeElement(id = "a", points = listOf(InkPoint(100f, 100f), InkPoint(200f, 100f)))
        val b = TextElement(id = "b", text = "Group", x = 100f, y = 140f, size = 20f)
        val outside = TextElement(id = "outside", text = "Other", x = 450f, y = 400f)
        view.document = view.document!!.copy(elements = listOf(a, b, outside))
        view.config = DrawingConfig(tool = DrawingTool.LASSO)
        send(MotionEvent.ACTION_DOWN, 60f, 60f)
        send(MotionEvent.ACTION_MOVE, 300f, 60f)
        send(MotionEvent.ACTION_MOVE, 300f, 240f)
        send(MotionEvent.ACTION_MOVE, 60f, 240f)
        send(MotionEvent.ACTION_UP, 60f, 60f)
        assertEquals(setOf("a", "b"), view.selectedIds)
        assertTrue(commits.isEmpty())
        send(MotionEvent.ACTION_DOWN, 120f, 100f)
        send(MotionEvent.ACTION_UP, 140f, 130f)
        assertEquals(1, commits.size)
        assertEquals(InkPoint(120f, 130f), (view.document!!.elements[0] as StrokeElement).points.first())
        assertEquals(120f, (view.document!!.elements[1] as TextElement).x, 0f)
        assertEquals(outside, view.document!!.elements[2])
        view.copySelection()
        assertEquals(2, commits.size)
        assertEquals(5, view.document!!.elements.size)
        assertTrue(view.selectedIds.none { it in setOf("a", "b") })
        view.recolorSelection(0xFFCC3344.toInt())
        assertEquals(3, commits.size)
        assertTrue(view.selectedElements.all { (it as? StrokeElement)?.color == 0xFFCC3344.toInt() || (it as? TextElement)?.color == 0xFFCC3344.toInt() })
        view.deleteSelection()
        assertEquals(4, commits.size)
        assertEquals(3, view.document!!.elements.size)
        assertTrue(view.selectedIds.isEmpty())
    }

    @Test fun canceledPartialEraserDiscardsEveryFragmentAndSuccessfulGestureCommitsOnce() {
        val stroke = StrokeElement(points = listOf(InkPoint(0f, 100f), InkPoint(400f, 100f)))
        view.document = view.document!!.copy(elements = listOf(stroke))
        view.config = DrawingConfig(tool = DrawingTool.ERASER, eraserMode = EraserMode.PARTIAL)
        send(MotionEvent.ACTION_DOWN, 100f, 100f)
        send(MotionEvent.ACTION_MOVE, 200f, 100f)
        send(MotionEvent.ACTION_CANCEL, 200f, 100f)
        assertEquals(listOf(stroke), view.document!!.elements)
        assertTrue(commits.isEmpty())
        send(MotionEvent.ACTION_DOWN, 100f, 100f)
        send(MotionEvent.ACTION_MOVE, 200f, 100f)
        send(MotionEvent.ACTION_UP, 200f, 100f)
        assertEquals(1, commits.size)
        assertEquals(2, view.document!!.elements.size)
        assertTrue(view.document!!.elements.none { ElementGeometry.hitTest(it, 150f, 100f, 0f) })
    }

    @Test fun sideButtonPartialEraserRemainsOneCancelableContact() {
        val oldStroke = StrokeElement(points = listOf(InkPoint(0f, 100f), InkPoint(400f, 100f)))
        view.document = view.document!!.copy(elements = listOf(oldStroke))
        view.config = DrawingConfig(eraserMode = EraserMode.PARTIAL)
        send(MotionEvent.ACTION_DOWN, 100f, 200f)
        send(MotionEvent.ACTION_MOVE, 150f, 200f)
        send(MotionEvent.ACTION_MOVE, 200f, 100f, MotionEvent.BUTTON_STYLUS_PRIMARY)
        send(MotionEvent.ACTION_MOVE, 250f, 100f, MotionEvent.BUTTON_STYLUS_PRIMARY)
        send(MotionEvent.ACTION_CANCEL, 250f, 100f)
        assertEquals(listOf(oldStroke), view.document!!.elements)
        assertTrue(commits.isEmpty())
        assertFalse(view.isTemporaryEraserActive)
    }

    @Test fun imageCropCanBeResetAndRotationPreservesCenter() {
        val image = ImageElement(id = "image", assetId = "asset", x = 100f, y = 100f, width = 200f, height = 100f)
        view.document = view.document!!.copy(elements = listOf(image))
        view.config = DrawingConfig(tool = DrawingTool.SELECT)
        view.selectElements(setOf(image.id))
        view.cropSelectedImage(.25f, 0f, .75f, 1f)
        var changed = view.selectedElements.single() as ImageElement
        assertEquals(100f, changed.width, .01f)
        assertEquals(150f, changed.x, .01f)
        view.rotateSelection()
        changed = view.selectedElements.single() as ImageElement
        assertEquals(90f, changed.rotationDegrees, 0f)
        assertEquals(200f, ElementGeometry.bounds(changed).centerX(), .01f)
        view.cropSelectedImage(0f, 0f, 1f, 1f)
        changed = view.selectedElements.single() as ImageElement
        assertEquals(image.width, changed.width, .01f)
        assertEquals(image.height, changed.height, .01f)
        assertEquals(image.x, changed.x, .01f)
        val count = commits.size
        view.cropSelectedImage(.8f, 0f, .2f, 1f)
        assertEquals(count, commits.size)
    }

    @Test fun selectedTextCanBeEditedAndDisabledCanvasRejectsMutations() {
        val text = TextElement(id = "text", text = "Before", x = 20f, y = 30f)
        view.document = view.document!!.copy(elements = listOf(text))
        view.selectElements(setOf(text.id))
        view.updateSelectedText("After", 40f, 0xFF334455.toInt())
        assertEquals("After", (view.selectedElements.single() as TextElement).text)
        assertEquals(1, commits.size)
        view.isEnabled = false
        view.updateSelectedText("Rejected")
        view.deleteSelection()
        view.copySelection()
        view.scaleSelection(1.1f)
        assertEquals(1, commits.size)
        assertEquals("After", (view.document!!.elements.single() as TextElement).text)
    }

    @Test fun externalRemovalPrunesOnlyMissingSelectionIds() {
        val a = TextElement(id = "a", text = "a", x = 0f, y = 0f)
        val b = a.copy(id = "b", x = 100f)
        view.document = view.document!!.copy(elements = listOf(a, b))
        view.selectElements(setOf(a.id, b.id))
        val callbacks = mutableListOf<Set<String>>()
        view.onSelectionIdsChanged = { callbacks.add(it) }
        view.document = view.document!!.copy(elements = listOf(b))
        assertEquals(setOf("b"), view.selectedIds)
        assertEquals(listOf(setOf("b")), callbacks)
    }

    @Test fun readOnlyReferenceAllowsPanButRejectsStylusEraserAndSelectionMutations() {
        val original = StrokeElement(id = "ink", points = listOf(InkPoint(50f, 100f), InkPoint(400f, 100f)))
        view.document = view.document!!.copy(elements = listOf(original))
        view.layout(0, 0, 632, 432)
        view.selectElements(setOf(original.id))
        view.isReadOnly = true
        view.selectElements(setOf(original.id))
        view.copySelection(); view.deleteSelection(); view.scaleSelection(1.5f); view.recolorSelection(0)
        send(MotionEvent.ACTION_DOWN, 100f, 200f, MotionEvent.BUTTON_STYLUS_PRIMARY)
        send(MotionEvent.ACTION_MOVE, 100f, 100f, MotionEvent.BUTTON_STYLUS_PRIMARY)
        send(MotionEvent.ACTION_UP, 100f, 100f, MotionEvent.BUTTON_STYLUS_PRIMARY)
        send(MotionEvent.ACTION_DOWN, 100f, 100f, type = MotionEvent.TOOL_TYPE_ERASER)
        send(MotionEvent.ACTION_UP, 100f, 100f, type = MotionEvent.TOOL_TYPE_ERASER)
        assertEquals(listOf(original), view.document!!.elements)
        assertTrue(commits.isEmpty())
        assertFalse(view.isTemporaryEraserActive)
        assertTrue(view.selectedIds.isEmpty())
        view.isReadOnly = false
        send(MotionEvent.ACTION_DOWN, 100f, 100f)
        send(MotionEvent.ACTION_UP, 110f, 100f)
        assertEquals(200f, (commits.single().elements.last() as StrokeElement).points.first().y, .01f)
    }

    @Test fun geometryLimitCancelsEntirePartialEraseContactAndReportsIt() {
        val small = StrokeElement(points = listOf(InkPoint(0f, 300f), InkPoint(400f, 300f)))
        val dense = StrokeElement(points = (0..33_000).map { InkPoint(if (it % 2 == 0) 0f else 400f, 100f) }, isPolyline = true)
        val original = listOf(small, dense)
        view.document = view.document!!.copy(elements = original)
        view.config = DrawingConfig(tool = DrawingTool.ERASER, eraserMode = EraserMode.PARTIAL)
        val errors = mutableListOf<String>()
        view.onEditingError = { errors.add(it) }
        send(MotionEvent.ACTION_DOWN, 100f, 300f)
        send(MotionEvent.ACTION_MOVE, 200f, 100f)
        send(MotionEvent.ACTION_UP, 200f, 100f)
        assertEquals(original, view.document!!.elements)
        assertTrue(commits.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("Değişiklik yapılmadı"))
    }

    @Test fun geometryLimitDuringLassoReturnsNoPartialSelectionAndKeepsDocument() {
        val dense = StrokeElement(id = "dense", points = (0..33_000).map { InkPoint(if (it % 2 == 0) 0f else 400f, 100f) }, isPolyline = true)
        val normal = TextElement(id = "normal", text = "Before heavy curve", x = 100f, y = 100f, size = 8f)
        val original = listOf(normal, dense)
        view.document = view.document!!.copy(elements = original)
        view.config = DrawingConfig(tool = DrawingTool.LASSO)
        val errors = mutableListOf<String>()
        view.onEditingError = { errors.add(it) }
        send(MotionEvent.ACTION_DOWN, 50f, 50f)
        send(MotionEvent.ACTION_MOVE, 200f, 50f)
        send(MotionEvent.ACTION_MOVE, 200f, 150f)
        send(MotionEvent.ACTION_MOVE, 50f, 150f)
        send(MotionEvent.ACTION_UP, 50f, 50f)
        assertEquals(original, view.document!!.elements)
        assertTrue(view.selectedIds.isEmpty())
        assertTrue(commits.isEmpty())
        assertEquals(1, errors.size)
    }

    @Test fun copyingAtRightBottomEdgeKeepsGroupVisibleAndPreservesRelativePositions() {
        val stroke = StrokeElement(id = "edge", points = listOf(InkPoint(590f, 790f), InkPoint(596f, 796f)), width = 4f)
        val image = ImageElement(id = "image", assetId = "asset", x = 570f, y = 770f, width = 10f, height = 10f)
        val original = listOf(stroke, image)
        view.document = view.document!!.copy(elements = original)
        view.selectElements(setOf(stroke.id, image.id))
        view.copySelection()
        assertEquals(1, commits.size)
        assertEquals(original, view.document!!.elements.take(2))
        val copies = view.selectedElements
        assertEquals(2, copies.size)
        assertTrue(copies.none { it.id == stroke.id || it.id == image.id })
        val bounds = InkEditing.groupBounds(copies)!!
        assertTrue(bounds.left >= 0f && bounds.right <= 600f && bounds.top >= 0f && bounds.bottom <= 800f)
        val copiedStroke = copies[0] as StrokeElement
        val copiedImage = copies[1] as ImageElement
        assertEquals(566f, copiedStroke.points.first().x, 0f)
        assertEquals(766f, copiedStroke.points.first().y, 0f)
        assertEquals(stroke.points.first().x - image.x, copiedStroke.points.first().x - copiedImage.x, 0f)
        assertEquals(stroke.points.first().y - image.y, copiedStroke.points.first().y - copiedImage.y, 0f)
    }

    private fun send(action: Int, x: Float, y: Float, buttons: Int = 0, type: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        time += 20
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 7; toolType = type })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x + 16f; this.y = y + 16f; pressure = 1f; size = .1f })
        val event = MotionEvent.obtain(100L, time, action, 1, properties, coordinates, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
}
