package dev.defter.app.ink

import android.graphics.Paint
import android.graphics.RectF
import dev.defter.app.model.ImageElement
import dev.defter.app.model.InkKind
import dev.defter.app.model.InkPoint
import dev.defter.app.model.PageElement
import dev.defter.app.model.ShapeElement
import dev.defter.app.model.ShapeKind
import dev.defter.app.model.StrokeElement
import dev.defter.app.model.TextElement
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometry uses document coordinates, independently of the view's current zoom. */
object ElementGeometry {
    fun bounds(element: PageElement): RectF = when (element) {
        is StrokeElement -> {
            if (element.points.isEmpty()) RectF()
            else {
                val radius = element.width.coerceAtLeast(0f) / 2f
                RectF(
                    element.points.minOf { it.x } - radius,
                    element.points.minOf { it.y } - radius,
                    element.points.maxOf { it.x } + radius,
                    element.points.maxOf { it.y } + radius
                )
            }
        }
        is ShapeElement -> {
            val result = normalizedRect(element.startX, element.startY, element.endX, element.endY)
            if (element.shape == ShapeKind.ARROW) {
                val (a, b) = arrowHeadPoints(element)
                result.left = min(result.left, min(a.x, b.x))
                result.top = min(result.top, min(a.y, b.y))
                result.right = max(result.right, max(a.x, b.x))
                result.bottom = max(result.bottom, max(a.y, b.y))
            }
            val radius = element.width.coerceAtLeast(0f) / 2f
            result.inset(-radius, -radius)
            result
        }
        is ImageElement -> imageCorners(element).let { corners -> RectF(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y }) }
        is TextElement -> {
            val lines = element.text.split('\n')
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = element.size }
            val textWidth = max(element.size * 0.5f, lines.maxOfOrNull { paint.measureText(it) } ?: 0f)
            RectF(element.x, element.y, element.x + textWidth, element.y + lines.size * element.size * 1.25f)
        }
    }

    /** Returns true near the visible ink, rather than the whole shape bounding box. */
    fun hitTest(element: PageElement, x: Float, y: Float, tolerance: Float): Boolean {
        val margin = tolerance.coerceAtLeast(0f)
        return when (element) {
            is StrokeElement -> {
                if (element.points.isEmpty()) return false
                val points = element.points
                if (points.size == 1) {
                    distance(x, y, points[0].x, points[0].y) <= margin + strokeWidth(element, points[0].pressure) / 2f
                } else {
                    visitStrokeSegments(element) { ax, ay, controlX, controlY, bx, by, width, line ->
                        val radius = margin + width / 2f
                        if (line) segmentDistance(x, y, ax, ay, bx, by) <= radius
                        else hitQuadratic(x.toDouble(), y.toDouble(), ax.toDouble(), ay.toDouble(),
                            controlX.toDouble(), controlY.toDouble(), bx.toDouble(), by.toDouble(), radius.toDouble())
                    }
                }
            }
            is ShapeElement -> hitShape(element, x, y, margin + element.width.coerceAtLeast(0f) / 2f)
            is ImageElement -> {
                val angle = -element.rotationDegrees * PI / 180.0
                val cx = element.x + element.width / 2f
                val cy = element.y + element.height / 2f
                val localX = cx + (x - cx) * cos(angle).toFloat() - (y - cy) * sin(angle).toFloat()
                val localY = cy + (x - cx) * sin(angle).toFloat() + (y - cy) * cos(angle).toFloat()
                localX >= element.x - margin && localX <= element.x + element.width + margin &&
                    localY >= element.y - margin && localY <= element.y + element.height + margin
            }
            is TextElement -> {
                val box = bounds(element)
                x >= box.left - margin && x <= box.right + margin &&
                    y >= box.top - margin && y <= box.bottom + margin
            }
        }
    }

    fun imageCorners(element: ImageElement): List<InkPoint> {
        val cx = element.x + element.width / 2f
        val cy = element.y + element.height / 2f
        val angle = element.rotationDegrees * PI / 180.0
        val cosine = cos(angle).toFloat()
        val sine = sin(angle).toFloat()
        return listOf(element.x to element.y, element.x + element.width to element.y,
            element.x + element.width to element.y + element.height, element.x to element.y + element.height).map { (x, y) ->
            InkPoint(cx + (x - cx) * cosine - (y - cy) * sine, cy + (x - cx) * sine + (y - cy) * cosine)
        }
    }

    fun translated(element: PageElement, dx: Float, dy: Float): PageElement = when (element) {
        is StrokeElement -> element.copy(points = element.points.map { it.copy(x = it.x + dx, y = it.y + dy) })
        is ShapeElement -> element.copy(
            startX = element.startX + dx, startY = element.startY + dy,
            endX = element.endX + dx, endY = element.endY + dy
        )
        is ImageElement -> element.copy(x = element.x + dx, y = element.y + dy)
        is TextElement -> element.copy(x = element.x + dx, y = element.y + dy)
    }

    /** Shared arrow geometry keeps drawing, selection and erasing consistent. */
    fun arrowHeadPoints(element: ShapeElement): Pair<InkPoint, InkPoint> {
        val dx = element.endX - element.startX
        val dy = element.endY - element.startY
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0.0001f) {
            val end = InkPoint(element.endX, element.endY)
            return end to end
        }
        val headLength = min(max(18f, element.width * 5f), length * 0.6f)
        val ux = dx / length
        val uy = dy / length
        val along = headLength * 0.8660254f
        val across = headLength * 0.5f
        return InkPoint(element.endX - ux * along - uy * across, element.endY - uy * along + ux * across) to
            InkPoint(element.endX - ux * along + uy * across, element.endY - uy * along - ux * across)
    }

    internal fun strokeWidth(stroke: StrokeElement, pressure: Float): Float {
        val factor = if (stroke.kind == InkKind.HIGHLIGHTER) 1f else 0.35f + 0.65f * pressure.coerceIn(0f, 1f)
        return stroke.width.coerceAtLeast(0f) * factor
    }

    /**
     * The same midpoint quadratics and pressure samples feed rendering and hit testing.
     * The final segment is straight. Returning true from visit stops the traversal.
     * Inline traversal avoids allocating a segment or boxing coordinates for every ink point.
     */
    internal inline fun visitStrokeSegments(
        stroke: StrokeElement,
        visit: (startX: Float, startY: Float, controlX: Float, controlY: Float,
                endX: Float, endY: Float, width: Float, line: Boolean) -> Boolean
    ): Boolean {
        if (stroke.points.size < 2) return false
        if (stroke.isPolyline) {
            for (index in 1 until stroke.points.size) {
                val previous = stroke.points[index - 1]
                val current = stroke.points[index]
                if (visit(previous.x, previous.y, current.x, current.y, current.x, current.y,
                        strokeWidth(stroke, (previous.pressure + current.pressure) / 2f), true)) return true
            }
            return false
        }
        var startX = stroke.points.first().x
        var startY = stroke.points.first().y
        for (index in 1 until stroke.points.size) {
            val previous = stroke.points[index - 1]
            val current = stroke.points[index]
            val endX = (previous.x + current.x) / 2f
            val endY = (previous.y + current.y) / 2f
            val width = strokeWidth(stroke, (previous.pressure + current.pressure) / 2f)
            if (visit(startX, startY, previous.x, previous.y, endX, endY, width, false)) return true
            startX = endX
            startY = endY
        }
        val last = stroke.points.last()
        return visit(startX, startY, last.x, last.y, last.x, last.y, strokeWidth(stroke, last.pressure), true)
    }

    /** Distance to the actual quadratic, with at most 0.02 document units of flattening error. */
    private fun hitQuadratic(
        px: Double, py: Double, ax: Double, ay: Double, cx: Double, cy: Double,
        bx: Double, by: Double, radius: Double, depth: Int = 0
    ): Boolean {
        // A Bezier remains inside its control hull. Reject before subdivision, which is
        // especially important when an eraser queries thousands of distant segments.
        if (px < min(ax, min(cx, bx)) - radius || px > max(ax, max(cx, bx)) + radius ||
            py < min(ay, min(cy, by)) - radius || py > max(ay, max(cy, by)) + radius) return false

        val chordDistance = segmentDistance(px, py, ax, ay, bx, by)
        val deviation = segmentDistance(cx, cy, ax, ay, bx, by)
        if (chordDistance > radius + deviation) return false
        if (chordDistance <= radius - deviation) return true
        // The safety bound also guarantees termination for imported extreme coordinates.
        // Double intermediates avoid losing the small hit radius at large coordinates.
        if (deviation <= 0.02 || depth >= 20) return chordDistance <= radius + min(deviation, 0.02)

        val acX = (ax + cx) / 2.0
        val acY = (ay + cy) / 2.0
        val cbX = (cx + bx) / 2.0
        val cbY = (cy + by) / 2.0
        val middleX = (acX + cbX) / 2.0
        val middleY = (acY + cbY) / 2.0
        return hitQuadratic(px, py, ax, ay, acX, acY, middleX, middleY, radius, depth + 1) ||
            hitQuadratic(px, py, middleX, middleY, cbX, cbY, bx, by, radius, depth + 1)
    }

    private fun hitShape(shape: ShapeElement, x: Float, y: Float, margin: Float): Boolean {
        val left = min(shape.startX, shape.endX)
        val top = min(shape.startY, shape.endY)
        val right = max(shape.startX, shape.endX)
        val bottom = max(shape.startY, shape.endY)
        return when (shape.shape) {
            ShapeKind.LINE -> segmentDistance(x, y, shape.startX, shape.startY, shape.endX, shape.endY) <= margin
            ShapeKind.ARROW -> {
                val (a, b) = arrowHeadPoints(shape)
                segmentDistance(x, y, shape.startX, shape.startY, shape.endX, shape.endY) <= margin ||
                    segmentDistance(x, y, shape.endX, shape.endY, a.x, a.y) <= margin ||
                    segmentDistance(x, y, shape.endX, shape.endY, b.x, b.y) <= margin
            }
            ShapeKind.RECTANGLE -> {
                segmentDistance(x, y, left, top, right, top) <= margin ||
                    segmentDistance(x, y, right, top, right, bottom) <= margin ||
                    segmentDistance(x, y, right, bottom, left, bottom) <= margin ||
                    segmentDistance(x, y, left, bottom, left, top) <= margin
            }
            ShapeKind.ELLIPSE -> {
                val rx = (right - left) / 2f
                val ry = (bottom - top) / 2f
                if (rx < 0.0001f || ry < 0.0001f) {
                    segmentDistance(x, y, left, top, right, bottom) <= margin
                } else {
                    val cx = (left + right) / 2f
                    val cy = (top + bottom) / 2f
                    // Approximate the perimeter with a dense polyline. This respects even
                    // narrow ellipses, unlike a normalized radial-distance test.
                    var ax = cx + rx
                    var ay = cy
                    var hit = false
                    for (step in 1..128) {
                        val angle = 2.0 * PI * step / 128
                        val bx = cx + rx * cos(angle).toFloat()
                        val by = cy + ry * sin(angle).toFloat()
                        if (segmentDistance(x, y, ax, ay, bx, by) <= margin) {
                            hit = true
                            break
                        }
                        ax = bx
                        ay = by
                    }
                    hit
                }
            }
        }
    }

    private fun normalizedRect(x1: Float, y1: Float, x2: Float, y2: Float) =
        RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0000001f) return distance(px, py, ax, ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return distance(px, py, ax + t * dx, ay + t * dy)
    }

    private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val deltaX = px - (ax + t * dx)
        val deltaY = py - (ay + t * dy)
        return sqrt(deltaX * deltaX + deltaY * deltaY)
    }
}
