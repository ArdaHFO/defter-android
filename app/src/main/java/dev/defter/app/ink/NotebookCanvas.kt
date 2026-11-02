package dev.defter.app.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.defter.app.model.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/** Native document-coordinate editor. Only completed gestures become persistent edits. */
class NotebookCanvas(context: Context) : View(context) {
    var document: PageDocument? = null
        set(value) {
            val changedPage = field?.id != value?.id
            val changedContent = field?.elements != value?.elements
            field = value
            if (changedPage) {
                cancelGesture()
                select(null)
                resetViewport()
            } else {
                if (changedContent && pointerId >= 0) {
                    // An undo or another editor update must not be overwritten by staged input.
                    cancelGesture()
                }
                val existingIds = value?.elements?.mapTo(hashSetOf()) { it.id }.orEmpty()
                selectMany(selection.filterTo(linkedSetOf()) { it in existingIds })
            }
            invalidate()
        }

    var config: DrawingConfig = DrawingConfig()
        set(value) {
            if (field != value) cancelGesture()
            field = value
            if (value.tool != DrawingTool.SELECT && value.tool != DrawingTool.LASSO) select(null)
            invalidate()
        }

    var assets: Map<String, Bitmap> = emptyMap()
        set(value) { field = value; invalidate() }

    var isReadOnly: Boolean = false
        set(value) {
            if (field != value) cancelGesture()
            field = value
            if (value) select(null)
            invalidate()
        }

    var onDocumentChanged: ((PageDocument) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null
    var onSelectionChanged: ((String?) -> Unit)? = null
    var onSelectionIdsChanged: ((Set<String>) -> Unit)? = null
    var onEditingError: ((String) -> Unit)? = null
    var onTemporaryEraserChanged: ((Boolean) -> Unit)? = null

    val selectedElementId: String? get() = selectionId
    val selectedIds: Set<String> get() = selection.toSet()
    val selectedElements: List<PageElement> get() = document?.elements?.filter { it.id in selection }.orEmpty()
    var isTemporaryEraserActive: Boolean = false
        private set

    private enum class Mode { NONE, INK, SHAPE, ERASE, DRAG, LASSO, PAN }
    private var mode = Mode.NONE
    private var pointerId = -1
    private var stylusActive = false
    private var multiTouch = false
    private var baseScale = 1f
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private val scale: Float get() = baseScale * zoom
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var startX = 0f
    private var startY = 0f
    private var lastInkX = 0f
    private var lastInkY = 0f
    private var gestureConfig = DrawingConfig()
    private var gestureTool = DrawingTool.PEN
    // Button changes split a contact into previews, never separate persistent edits.
    // A canceled contact discards every pen/eraser segment together.
    private var stagedElements: List<PageElement>? = null
    private var segmentAllowsTap = true
    private val points = ArrayList<InkPoint>()
    private var previewStroke: StrokeElement? = null
    private var previewShape: ShapeElement? = null
    private var selection = linkedSetOf<String>()
    private val selectionId: String? get() = selection.firstOrNull()
    private var draggedOriginals: List<PageElement> = emptyList()
    private var draggedPreviews: List<PageElement> = emptyList()
    private val lassoPath = Path()
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6558D9.toInt()
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 9f), 0f)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = !stylusActive
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (stylusActive) return false
            val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
            val logicalX = (detector.focusX - offsetX) / scale
            val logicalY = (detector.focusY - offsetY) / scale
            zoom = newZoom
            offsetX = detector.focusX - logicalX * scale
            offsetY = detector.focusY - logicalY * scale
            constrainViewport()
            onZoomChanged?.invoke(zoom)
            invalidate()
            return true
        }
    })

    init {
        isFocusable = true
        contentDescription = "Defter sayfası. Kalemle yazın, iki parmakla yakınlaştırın."
        setBackgroundColor(0xFFEEF1F6.toInt())
        setDefaultFocusHighlightEnabled(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelGesture()
        resetViewport()
    }

    fun resetViewport() {
        val page = document ?: return
        if (width <= 0 || height <= 0) return
        val padding = 16f * resources.displayMetrics.density
        // 100% means fit page width. In landscape this keeps handwriting readable;
        // the rest of the page is reached by vertical panning rather than shrinking it.
        baseScale = ((width - padding * 2f) / page.width).coerceAtLeast(0.01f)
        zoom = 1f
        offsetX = (width - page.width * scale) / 2f
        offsetY = padding
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = document ?: return
        val saved = canvas.save()
        // AndroidView may share an unclipped Compose drawing surface with sibling controls.
        // Clip in view coordinates before applying the document viewport transform.
        canvas.clipRect(0, 0, width, height)
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, page.width, page.height)
        val previewPage = if (draggedPreviews.isNotEmpty()) {
            val replacements = draggedPreviews.associateBy { it.id }
            page.copy(elements = (stagedElements ?: page.elements).map { replacements[it.id] ?: it })
        } else stagedElements?.let { page.copy(elements = it) } ?: page
        PageRenderer.draw(canvas, previewPage, assets)
        previewStroke?.let { PageRenderer.drawElement(canvas, it, assets) }
        previewShape?.let { PageRenderer.drawElement(canvas, it, assets) }
        val selectedBounds = if (selection.isEmpty()) null else InkEditing.groupBounds(previewPage.elements.filter { it.id in selection })
        if (selectedBounds != null && (config.tool == DrawingTool.SELECT || config.tool == DrawingTool.LASSO) && !isTemporaryEraserActive) {
            val bounds = selectedBounds
            bounds.inset(-8f / scale, -8f / scale)
            selectionPaint.strokeWidth = 1.5f / scale
            canvas.drawRect(bounds, selectionPaint)
        }
        if (mode == Mode.LASSO && points.isNotEmpty()) {
            lassoPath.rewind()
            lassoPath.moveTo(points.first().x, points.first().y)
            for (index in 1 until points.size) lassoPath.lineTo(points[index].x, points[index].y)
            lassoPath.close()
            selectionPaint.strokeWidth = 1.5f / scale
            canvas.drawPath(lassoPath, selectionPaint)
        }
        canvas.restoreToCount(saved)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = guardEditing { handleTouchEvent(event) }

    private inline fun guardEditing(action: () -> Boolean): Boolean = try {
        action()
    } catch (error: GeometryLimitException) {
        cancelGesture()
        onEditingError?.invoke(error.message ?: "Çizim işlemi tamamlanamadı.")
        invalidate()
        true
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            cancelGesture()
            return false
        }
        if (document == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelGesture()
            scaleDetector.onTouchEvent(event)
            invalidate()
            return true
        }
        val actionIndex = event.actionIndex
        val down = event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        val toolType = event.getToolType(actionIndex)
        val newStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

        // A pen takes precedence over fingers. Palm pointers cannot change the pen's viewport.
        if (down && newStylus && !stylusActive) {
            cancelGesture()
            beginGesture(event, actionIndex, true)
            invalidate()
            return true
        }
        if (stylusActive) {
            handleActivePointer(event)
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelGesture()
            scaleDetector.onTouchEvent(event)
            beginGesture(event, actionIndex, false)
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            // The initial finger line remains transient, so pinch never leaves a stray mark.
            cancelGesture()
            multiTouch = true
            val focus = focus(event)
            lastFocusX = focus.first
            lastFocusY = focus.second
            scaleDetector.onTouchEvent(event)
            invalidate()
            return true
        }
        if (multiTouch) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val focus = focus(event)
                offsetX += focus.first - lastFocusX
                offsetY += focus.second - lastFocusY
                lastFocusX = focus.first
                lastFocusY = focus.second
                scaleDetector.onTouchEvent(event)
                constrainViewport()
                invalidate()
            } else {
                scaleDetector.onTouchEvent(event)
                val excluding = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) actionIndex else -1
                val focus = focus(event, excluding)
                lastFocusX = focus.first
                lastFocusY = focus.second
                if (event.actionMasked == MotionEvent.ACTION_UP) cancelGesture()
            }
            return true
        }
        scaleDetector.onTouchEvent(event)
        handleActivePointer(event)
        return true
    }

    private fun beginGesture(event: MotionEvent, index: Int, isStylus: Boolean) {
        pointerId = event.getPointerId(index)
        stylusActive = isStylus
        gestureConfig = config
        beginSegment(event, index, allowsTap = true)
    }

    private fun beginSegment(event: MotionEvent, index: Int, allowsTap: Boolean) {
        segmentAllowsTap = allowsTap
        updateTemporaryEraser(event, index)
        gestureTool = effectiveTool(event, index)
        lastFocusX = event.getX(index)
        lastFocusY = event.getY(index)
        startX = (lastFocusX - offsetX) / scale
        startY = (lastFocusY - offsetY) / scale
        lastInkX = startX
        lastInkY = startY
        if (gestureTool == DrawingTool.HAND || (!stylusActive && !gestureConfig.touchDrawing)) {
            mode = Mode.PAN
            return
        }
        val page = document ?: return
        if (!startX.isFinite() || !startY.isFinite() || startX < 0 || startY < 0 || startX > page.width || startY > page.height) {
            mode = Mode.NONE
            return
        }
        val tool = gestureTool
        when (tool) {
            DrawingTool.PEN, DrawingTool.HIGHLIGHTER -> {
                mode = Mode.INK
                points.add(InkPoint(startX, startY, pressure(event, index)))
                previewStroke = StrokeElement(
                    points = points,
                    color = gestureConfig.color,
                    width = gestureConfig.width,
                    kind = if (tool == DrawingTool.HIGHLIGHTER) InkKind.HIGHLIGHTER else InkKind.PEN
                )
            }
            DrawingTool.ERASER -> {
                mode = Mode.ERASE
                eraseAt(startX, startY)
            }
            DrawingTool.LINE, DrawingTool.ARROW, DrawingTool.RECTANGLE, DrawingTool.ELLIPSE -> {
                mode = Mode.SHAPE
                val shape = when (tool) {
                    DrawingTool.ARROW -> ShapeKind.ARROW
                    DrawingTool.RECTANGLE -> ShapeKind.RECTANGLE
                    DrawingTool.ELLIPSE -> ShapeKind.ELLIPSE
                    else -> ShapeKind.LINE
                }
                previewShape = ShapeElement(shape = shape, startX = startX, startY = startY,
                    endX = startX, endY = startY, color = gestureConfig.color, width = gestureConfig.width)
            }
            DrawingTool.SELECT, DrawingTool.LASSO -> {
                val elements = stagedElements ?: page.elements
                val element = elements.lastOrNull { ElementGeometry.hitTest(it, startX, startY, 12f / scale) }
                val selected = elements.filter { it.id in selection }
                val groupBounds = InkEditing.groupBounds(selected)
                val inGroup = element?.id in selection || (tool == DrawingTool.LASSO && groupBounds?.contains(startX, startY) == true)
                if (inGroup && selected.isNotEmpty()) {
                    draggedOriginals = selected
                    draggedPreviews = selected
                    mode = Mode.DRAG
                } else if (tool == DrawingTool.LASSO) {
                    select(null)
                    points.add(InkPoint(startX, startY))
                    mode = Mode.LASSO
                } else {
                    select(element?.id)
                    draggedOriginals = listOfNotNull(element)
                    draggedPreviews = draggedOriginals
                    mode = if (element == null) Mode.NONE else Mode.DRAG
                }
            }
            DrawingTool.HAND -> mode = Mode.PAN
        }
        invalidate()
    }

    private fun handleActivePointer(event: MotionEvent) {
        if (pointerId < 0) return
        val index = event.findPointerIndex(pointerId)
        if (index < 0) {
            cancelGesture()
            invalidate()
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!switchToolSegment(event, index)) updateGesture(event, index)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != pointerId) return
                val canceled = Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0
                if (!canceled) {
                    // A release reported only on UP must not create a new pen dot or
                    // connect ink across the preceding eraser segment.
                    if (effectiveTool(event, index) == gestureTool) updateGesture(event, index)
                    finishGesture()
                    performClick()
                }
                cancelGesture()
                invalidate()
            }
        }
    }

    private fun sideButtonHeld(event: MotionEvent, index: Int): Boolean {
        if (event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS) return false
        val mask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        val buttons = when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> event.buttonState or event.actionButton
            MotionEvent.ACTION_BUTTON_RELEASE -> event.buttonState and event.actionButton.inv()
            else -> event.buttonState
        }
        return buttons and mask != 0
    }

    private fun effectiveTool(event: MotionEvent, index: Int): DrawingTool =
        if (isReadOnly) DrawingTool.HAND
        else if (stylusActive && (event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER || sideButtonHeld(event, index)))
            DrawingTool.ERASER else gestureConfig.tool

    private fun setTemporaryEraser(active: Boolean) {
        if (isTemporaryEraserActive == active) return
        isTemporaryEraserActive = active
        onTemporaryEraserChanged?.invoke(active)
        invalidate()
    }

    private fun updateTemporaryEraser(event: MotionEvent, index: Int) {
        setTemporaryEraser(!isReadOnly && sideButtonHeld(event, index))
    }

    /** Returns true when this sample begins a new tool segment. Its history belongs
     * to an unknown button state, so do not draw/erase a bridge across the change. */
    private fun switchToolSegment(event: MotionEvent, index: Int): Boolean {
        if (!stylusActive) return false
        updateTemporaryEraser(event, index)
        if (effectiveTool(event, index) == gestureTool) return false
        val page = document ?: return false
        stagedElements = elementsAfterSegment(stagedElements ?: page.elements, allowSinglePoint = false)
        clearSegment()
        beginSegment(event, index, allowsTap = false)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = guardEditing { handleGenericMotion(event) }

    private fun handleGenericMotion(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            if (handleStylusHoverOrButton(event)) return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean = guardEditing {
        if (handleStylusHoverOrButton(event)) true else super.onHoverEvent(event)
    }

    private fun handleStylusHoverOrButton(event: MotionEvent): Boolean {
        if (!isEnabled || document == null) {
            setTemporaryEraser(false)
            return false
        }
        val index = if (stylusActive) event.findPointerIndex(pointerId) else event.actionIndex
        if (index < 0 || index >= event.pointerCount || event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS) return false
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            if (!stylusActive) setTemporaryEraser(false)
        } else if (stylusActive) {
            switchToolSegment(event, index)
        } else {
            updateTemporaryEraser(event, index)
        }
        return true
    }

    private fun updateGesture(event: MotionEvent, index: Int) {
        val screenX = event.getX(index)
        val screenY = event.getY(index)
        val x = (screenX - offsetX) / scale
        val y = (screenY - offsetY) / scale
        when (mode) {
            Mode.INK -> {
                for (history in 0 until event.historySize) {
                    appendPoint((event.getHistoricalX(index, history) - offsetX) / scale,
                        (event.getHistoricalY(index, history) - offsetY) / scale,
                        if (stylusActive) event.getHistoricalPressure(index, history).coerceIn(0f, 1f) else 1f)
                }
                appendPoint(x, y, pressure(event, index))
            }
            Mode.SHAPE -> if (x.isFinite() && y.isFinite()) previewShape = previewShape?.let { InkEditing.snapped(it, x, y, gestureConfig.shapeSnap, gestureConfig.snapAngleDegrees) }
            Mode.ERASE -> {
                for (history in 0 until event.historySize) {
                    eraseTo((event.getHistoricalX(index, history) - offsetX) / scale,
                        (event.getHistoricalY(index, history) - offsetY) / scale)
                }
                eraseTo(x, y)
            }
            Mode.DRAG -> if (x.isFinite() && y.isFinite()) draggedPreviews = draggedOriginals.map { ElementGeometry.translated(it, x - startX, y - startY) }
            Mode.LASSO -> {
                for (history in 0 until event.historySize) {
                    appendPoint((event.getHistoricalX(index, history) - offsetX) / scale,
                        (event.getHistoricalY(index, history) - offsetY) / scale, 1f)
                }
                appendPoint(x, y, 1f)
            }
            Mode.PAN -> {
                offsetX += screenX - lastFocusX
                offsetY += screenY - lastFocusY
                constrainViewport()
            }
            Mode.NONE -> Unit
        }
        lastFocusX = screenX
        lastFocusY = screenY
        invalidate()
    }

    private fun eraseTo(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        val distance = hypot(x - lastInkX, y - lastInkY)
        val steps = ceil(distance / max(6f, gestureConfig.width)).toInt().coerceIn(1, 512)
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            eraseAt(lastInkX + (x - lastInkX) * t, lastInkY + (y - lastInkY) * t)
        }
        lastInkX = x
        lastInkY = y
    }

    private fun appendPoint(x: Float, y: Float, pressure: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        val last = points.lastOrNull()
        if (last == null || abs(last.x - x) + abs(last.y - y) > 0.2f) {
            points.add(InkPoint(x, y, if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 1f))
        }
    }

    private fun eraseAt(x: Float, y: Float) {
        val page = document ?: return
        val tolerance = max(12f, gestureConfig.width * 2f)
        stagedElements = InkEditing.erased(stagedElements ?: page.elements, x, y, tolerance, gestureConfig.eraserMode, gestureConfig.eraserFilter)
    }

    private fun finishGesture() {
        val page = document ?: return
        if (mode == Mode.LASSO) selectMany(InkEditing.lassoSelection(stagedElements ?: page.elements, points))
        val elements = elementsAfterSegment(stagedElements ?: page.elements, segmentAllowsTap)
        if (elements != page.elements) commit(page.copy(elements = elements, revision = page.revision + 1))
    }

    private fun elementsAfterSegment(base: List<PageElement>, allowSinglePoint: Boolean): List<PageElement> =
        when (mode) {
            Mode.INK -> {
                val stroke = previewStroke
                if (stroke == null || (!allowSinglePoint && points.size < 2)) base
                else base + stroke.copy(points = points.toList())
            }
            Mode.SHAPE -> {
                val shape = previewShape
                if (shape == null || hypot(shape.endX - shape.startX, shape.endY - shape.startY) < 1f) base
                else base + shape
            }
            Mode.ERASE -> base
            Mode.DRAG -> {
                if (draggedPreviews == draggedOriginals) base
                else draggedPreviews.associateBy { it.id }.let { moved -> base.map { moved[it.id] ?: it } }
            }
            else -> base
        }

    private fun commit(updated: PageDocument) {
        if (isReadOnly) return
        document = updated
        onDocumentChanged?.invoke(updated)
    }

    fun deleteSelection() {
        if (!isEnabled || isReadOnly || selection.isEmpty()) return
        val selected = selection.toSet()
        val page = document ?: return
        cancelGesture()
        select(null)
        commit(page.copy(elements = page.elements.filterNot { it.id in selected }, revision = page.revision + 1))
    }

    fun scaleSelection(factor: Float) {
        if (!isEnabled || isReadOnly || !factor.isFinite() || factor <= 0f) return
        val page = document ?: return
        val elements = selectedElements
        if (elements.isEmpty()) return
        cancelGesture()
        replaceSelection(InkEditing.scaled(elements, factor, page.width, page.height))
    }

    private fun select(id: String?) {
        selectMany(if (id == null) emptySet() else setOf(id))
    }

    private fun selectMany(ids: Set<String>) {
        if (selection == ids) return
        selection = LinkedHashSet(ids)
        onSelectionChanged?.invoke(selectionId)
        onSelectionIdsChanged?.invoke(selection.toSet())
        invalidate()
    }

    fun selectElements(ids: Set<String>) {
        if (isReadOnly) return
        cancelGesture()
        val existing = document?.elements?.mapTo(hashSetOf()) { it.id }.orEmpty()
        selectMany(ids.filterTo(linkedSetOf()) { it in existing })
    }

    fun copySelection() {
        if (!isEnabled || isReadOnly) return
        val page = document ?: return
        val selected = selectedElements
        val (dx, dy) = InkEditing.copyOffset(selected, page.width, page.height)
        val copies = selected.map { InkEditing.copied(it, dx, dy) }
        if (copies.isEmpty()) return
        cancelGesture()
        commit(page.copy(elements = page.elements + copies, revision = page.revision + 1))
        selectMany(copies.mapTo(linkedSetOf()) { it.id })
    }

    fun recolorSelection(color: Int) = replaceSelection(selectedElements.map { InkEditing.recolored(it, color) })

    fun rotateSelection(degrees: Float = 90f) {
        if (!degrees.isFinite()) return
        replaceSelection(selectedElements.map { if (it is ImageElement) it.copy(rotationDegrees = ((it.rotationDegrees + degrees) % 360f + 360f) % 360f) else it })
    }

    /** Crop coordinates are normalized to the original asset, so crops can be reset to 0,0,1,1. */
    fun cropSelectedImage(left: Float, top: Float, right: Float, bottom: Float) {
        if (!listOf(left, top, right, bottom).all { it.isFinite() } || left < 0f || top < 0f || right > 1f || bottom > 1f || right - left < .009999f || bottom - top < .009999f) return
        val image = selectedElements.singleOrNull() as? ImageElement ?: return
        val width = image.width * (right - left) / (image.cropRight - image.cropLeft)
        val height = image.height * (bottom - top) / (image.cropBottom - image.cropTop)
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f || width > 100_000f || height > 100_000f) return
        replaceSelection(listOf(image.copy(x = image.x + (image.width - width) / 2f, y = image.y + (image.height - height) / 2f,
            width = width, height = height, cropLeft = left, cropTop = top, cropRight = right, cropBottom = bottom)))
    }

    fun updateSelectedText(text: String, size: Float? = null, color: Int? = null) {
        val original = selectedElements.singleOrNull() as? TextElement ?: return
        if (text.isBlank() || (size != null && !size.isFinite())) return
        val nextSize = size?.let { if (it == original.size) it else it.coerceIn(8f, 200f) } ?: original.size
        replaceSelection(listOf(original.copy(text = text.take(10_000), size = nextSize, color = color ?: original.color)))
    }

    private fun replaceSelection(replacements: List<PageElement>) {
        if (!isEnabled || isReadOnly || replacements.isEmpty()) return
        val page = document ?: return
        val byId = replacements.associateBy { it.id }
        val elements = page.elements.map { byId[it.id] ?: it }
        if (elements == page.elements) return
        cancelGesture()
        commit(page.copy(elements = elements, revision = page.revision + 1))
    }

    private fun cancelGesture() {
        pointerId = -1
        stylusActive = false
        multiTouch = false
        stagedElements = null
        clearSegment()
        setTemporaryEraser(false)
    }

    private fun clearSegment() {
        mode = Mode.NONE
        points.clear()
        previewStroke = null
        previewShape = null
        draggedOriginals = emptyList()
        draggedPreviews = emptyList()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            cancelGesture()
            invalidate()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            cancelGesture()
            invalidate()
        }
    }

    private fun constrainViewport() {
        val page = document ?: return
        val margin = 24f * resources.displayMetrics.density
        val pageWidth = page.width * scale
        val pageHeight = page.height * scale
        offsetX = if (pageWidth <= width - margin * 2) (width - pageWidth) / 2f
            else offsetX.coerceIn(width - pageWidth - margin, margin)
        offsetY = if (pageHeight <= height - margin * 2) 16f * resources.displayMetrics.density
            else offsetY.coerceIn(height - pageHeight - margin, margin)
    }

    private fun pressure(event: MotionEvent, index: Int): Float =
        if (stylusActive) event.getPressure(index).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f } else 1f

    private fun focus(event: MotionEvent, excluding: Int = -1): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var count = 0
        for (index in 0 until event.pointerCount) {
            if (index == excluding) continue
            x += event.getX(index)
            y += event.getY(index)
            count++
        }
        return if (count == 0) lastFocusX to lastFocusY else x / count to y / count
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }
}
