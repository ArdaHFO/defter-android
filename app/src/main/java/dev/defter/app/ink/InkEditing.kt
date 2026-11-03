package dev.defter.app.ink

import dev.defter.app.localization.tr

import android.graphics.RectF
import dev.defter.app.model.*
import kotlin.math.*

class GeometryLimitException : IllegalArgumentException(tr("Bu işlemde çok fazla çizgi ayrıntısı var. Daha küçük bir alanla yeniden dene. Değişiklik yapılmadı."))

/** Document-space editing shared by gestures and regression tests. */
object InkEditing {
    internal const val MAX_FLATTEN_POINTS = 32_768
    internal const val MAX_GEOMETRY_CHECKS = 1_000_000
    internal const val MAX_LASSO_POINTS = 4_096
    internal class Budget {
        private var points = 0
        private var checks = 0
        fun points(count: Int = 1) {
            if (count > MAX_FLATTEN_POINTS - points) throw GeometryLimitException()
            points += count
        }
        fun checks(count: Int = 1) {
            if (count > MAX_GEOMETRY_CHECKS - checks) throw GeometryLimitException()
            checks += count
        }
    }

    fun groupBounds(elements: List<PageElement>): RectF? {
        if (elements.isEmpty()) return null
        val boxes = elements.map(ElementGeometry::bounds)
        return RectF(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    fun recolored(element: PageElement, color: Int): PageElement = when (element) {
        is StrokeElement -> element.copy(color = color)
        is ShapeElement -> element.copy(color = color)
        is TextElement -> element.copy(color = color)
        is ImageElement -> element
    }

    fun copied(element: PageElement, dx: Float = 24f, dy: Float = 24f): PageElement {
        val copy = when (element) {
            is StrokeElement -> element.copy(id = newId(), points = element.points.toList())
            is ShapeElement -> element.copy(id = newId())
            is ImageElement -> element.copy(id = newId())
            is TextElement -> element.copy(id = newId())
        }
        return ElementGeometry.translated(copy, dx, dy)
    }

    /** Keep the whole group on the page when it fits, otherwise retain a visible intersection. */
    fun copyOffset(elements: List<PageElement>, pageWidth: Float, pageHeight: Float): Pair<Float, Float> {
        val bounds = groupBounds(elements) ?: return 0f to 0f
        fun offset(start: Float, end: Float, pageSize: Float): Float {
            if (!start.isFinite() || !end.isFinite() || !pageSize.isFinite() || pageSize <= 0f) return 0f
            val minimum = -start
            val maximum = pageSize - end
            if (minimum <= maximum) {
                if (24f in minimum..maximum) return 24f
                if (-24f in minimum..maximum) return -24f
                return 24f.coerceIn(minimum, maximum)
            }
            // A group wider/taller than its page cannot fully fit without distortion.
            // Keep at least a small viewport overlap, preserving its size and arrangement.
            val margin = min(24f, pageSize / 4f)
            return 24f.coerceIn(margin - end, pageSize - margin - start)
        }
        return offset(bounds.left, bounds.right, pageWidth) to offset(bounds.top, bounds.bottom, pageHeight)
    }

    /** One effective factor for the whole group preserves ratios, spacing and its center. */
    fun scaled(elements: List<PageElement>, requested: Float, pageWidth: Float, pageHeight: Float): List<PageElement> {
        if (!requested.isFinite() || requested <= 0f || !pageWidth.isFinite() || !pageHeight.isFinite() || pageWidth <= 0f || pageHeight <= 0f || elements.isEmpty()) return elements
        val bounds = groupBounds(elements) ?: return elements
        var lower = .01f
        var upper = 100f
        fun limit(size: Float, minimum: Float, maximum: Float) {
            if (size <= 0f || !size.isFinite()) return
            lower = max(lower, minimum / size)
            upper = min(upper, maximum / size)
        }
        for (element in elements) when (element) {
            is StrokeElement -> limit(element.width, .5f, 120f)
            is ShapeElement -> limit(element.width, .5f, 120f)
            is TextElement -> limit(element.size, 8f, 200f)
            is ImageElement -> {
                limit(element.width, 10f, pageWidth * 4f)
                limit(element.height, 10f, pageHeight * 4f)
            }
        }
        // Existing imported elements outside current UI limits must never jump when resized.
        lower = min(lower, 1f)
        upper = max(upper, 1f)
        val factor = requested.coerceIn(.5f, 2f).coerceIn(lower, upper)
        if (abs(factor - 1f) < .000001f) return elements
        fun sx(x: Float) = bounds.centerX() + (x - bounds.centerX()) * factor
        fun sy(y: Float) = bounds.centerY() + (y - bounds.centerY()) * factor
        return elements.map { element -> when (element) {
            is StrokeElement -> element.copy(points = element.points.map { it.copy(x = sx(it.x), y = sy(it.y)) }, width = element.width * factor)
            is ShapeElement -> element.copy(startX = sx(element.startX), startY = sy(element.startY), endX = sx(element.endX), endY = sy(element.endY), width = element.width * factor)
            is ImageElement -> element.copy(x = sx(element.x), y = sy(element.y), width = element.width * factor, height = element.height * factor)
            is TextElement -> element.copy(x = sx(element.x), y = sy(element.y), size = element.size * factor)
        } }
    }

    fun snapped(shape: ShapeElement, x: Float, y: Float, enabled: Boolean, angleStep: Float): ShapeElement {
        if (!enabled) return shape.copy(endX = x, endY = y)
        val dx = x - shape.startX
        val dy = y - shape.startY
        return when (shape.shape) {
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> {
                val side = max(abs(dx), abs(dy))
                shape.copy(endX = shape.startX + if (dx < 0) -side else side, endY = shape.startY + if (dy < 0) -side else side)
            }
            ShapeKind.LINE, ShapeKind.ARROW -> {
                val step = (if (angleStep.isFinite()) angleStep else 15f).coerceIn(1f, 90f) * PI / 180.0
                val angle = round(atan2(dy.toDouble(), dx.toDouble()) / step) * step
                val length = hypot(dx, dy)
                shape.copy(endX = shape.startX + cos(angle).toFloat() * length, endY = shape.startY + sin(angle).toFloat() * length)
            }
        }
    }

    fun matchesFilter(element: PageElement, filter: EraserFilter): Boolean = when (element) {
        is ShapeElement -> filter == EraserFilter.ALL || filter == EraserFilter.SHAPES
        is StrokeElement -> when (filter) {
            EraserFilter.ALL -> true
            EraserFilter.PEN -> element.kind == InkKind.PEN && !element.isShapeFragment
            EraserFilter.HIGHLIGHTER -> element.kind == InkKind.HIGHLIGHTER && !element.isShapeFragment
            EraserFilter.SHAPES -> element.isShapeFragment
        }
        else -> false
    }

    fun erased(elements: List<PageElement>, x: Float, y: Float, radius: Float, mode: EraserMode, filter: EraserFilter): List<PageElement> {
        if (!x.isFinite() || !y.isFinite() || !radius.isFinite() || radius <= 0f) return elements
        val budget = Budget()
        val eraserBounds = RectF(x - radius, y - radius, x + radius, y + radius)
        var changed = false
        val result = ArrayList<PageElement>(elements.size)
        for (element in elements) {
            if (!matchesFilter(element, filter)) {
                result.add(element)
                continue
            }
            budget.checks(if (element is StrokeElement) max(1, element.points.size) else 1)
            if (!overlaps(ElementGeometry.bounds(element), eraserBounds) || !ElementGeometry.hitTest(element, x, y, radius)) {
                result.add(element)
                continue
            }
            val replacement = if (mode == EraserMode.STROKE) emptyList() else when (element) {
                is StrokeElement -> cutStroke(element, x, y, radius, budget)
                is ShapeElement -> shapeStrokes(element).flatMap { cutStroke(it, x, y, radius, budget) }
                else -> listOf(element)
            }
            if (replacement != listOf(element)) changed = true
            result.addAll(replacement)
        }
        return if (changed) result else elements
    }

    /** Flatten only touched curves. New fragments explicitly render as polylines. */
    internal fun flattened(stroke: StrokeElement, budget: Budget = Budget()): List<InkPoint> {
        if (stroke.isPolyline || stroke.points.size < 2) { budget.points(stroke.points.size); return stroke.points }
        val result = ArrayList<InkPoint>()
        budget.points()
        result.add(stroke.points.first())
        ElementGeometry.visitStrokeSegments(stroke) { ax, ay, cx, cy, bx, by, width, line ->
            val pressure = if (stroke.kind == InkKind.HIGHLIGHTER || stroke.width <= 0f) 1f else ((width / stroke.width - .35f) / .65f).coerceIn(0f, 1f)
            budget.checks()
            if (line) { budget.points(); result.add(InkPoint(bx, by, pressure)) }
            else flattenQuadratic(result, ax, ay, cx, cy, bx, by, pressure, budget)
            false
        }
        return result
    }

    private fun flattenQuadratic(out: MutableList<InkPoint>, ax: Float, ay: Float, cx: Float, cy: Float, bx: Float, by: Float, pressure: Float, budget: Budget, depth: Int = 0) {
        budget.checks()
        val dx = bx - ax; val dy = by - ay
        val lengthSquared = dx.toDouble() * dx + dy.toDouble() * dy
        val projection = if (lengthSquared <= 1e-12) 0.0 else (((cx - ax).toDouble() * dx + (cy - ay).toDouble() * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val deviation = hypot(cx - ax - dx * projection, cy - ay - dy * projection)
        if (deviation <= .25f) {
            budget.points()
            out.add(InkPoint(bx, by, pressure))
            return
        }
        if (depth >= 12) throw GeometryLimitException()
        val acx = (ax + cx) / 2f; val acy = (ay + cy) / 2f
        val cbx = (cx + bx) / 2f; val cby = (cy + by) / 2f
        val mx = (acx + cbx) / 2f; val my = (acy + cby) / 2f
        flattenQuadratic(out, ax, ay, acx, acy, mx, my, pressure, budget, depth + 1)
        flattenQuadratic(out, mx, my, cbx, cby, bx, by, pressure, budget, depth + 1)
    }

    private fun cutStroke(stroke: StrokeElement, x: Float, y: Float, radius: Float, budget: Budget): List<StrokeElement> {
        if (!ElementGeometry.hitTest(stroke, x, y, radius)) return listOf(stroke)
        val points = flattened(stroke, budget)
        if (points.isEmpty()) return listOf(stroke)
        if (points.size == 1) return emptyList()
        val fragments = mutableListOf<List<InkPoint>>()
        var current = mutableListOf<InkPoint>()
        var cut = false
        fun endFragment() { if (current.isNotEmpty()) fragments.add(current.toList()); current = mutableListOf() }
        for (index in 1 until points.size) {
            budget.checks()
            val a = points[index - 1]; val b = points[index]
            val effectiveRadius = radius + max(ElementGeometry.strokeWidth(stroke, a.pressure), ElementGeometry.strokeWidth(stroke, b.pressure)) / 2f
            val intervals = outsideCircle(a, b, x, y, effectiveRadius)
            if (intervals != listOf(0f to 1f)) cut = true
            if (intervals.isEmpty()) { endFragment(); continue }
            for ((start, end) in intervals) {
                val from = interpolate(a, b, start); val to = interpolate(a, b, end)
                if (start > 0f) endFragment()
                if (current.isEmpty() || current.last() != from) current.add(from)
                if (to != current.last()) current.add(to)
                if (end < 1f) endFragment()
            }
        }
        endFragment()
        if (!cut) return listOf(stroke)
        return fragments.filter { it.size >= 2 && (1 until it.size).any { i -> hypot(it[i - 1].x - it[i].x, it[i - 1].y - it[i].y) > .01f } }
            .mapIndexed { index, part -> stroke.copy(id = if (index == 0) stroke.id else newId(), points = part, isPolyline = true) }
    }

    private fun outsideCircle(a: InkPoint, b: InkPoint, x: Float, y: Float, radius: Float): List<Pair<Float, Float>> {
        val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble()
        val ox = (a.x - x).toDouble(); val oy = (a.y - y).toDouble()
        val aa = dx * dx + dy * dy
        val rr = radius.toDouble() * radius
        if (aa <= 1e-12) return if (ox * ox + oy * oy > rr) listOf(0f to 1f) else emptyList()
        val bb = 2 * (ox * dx + oy * dy)
        val cc = ox * ox + oy * oy - rr
        val discriminant = bb * bb - 4 * aa * cc
        if (discriminant <= 0) return if (cc >= 0) listOf(0f to 1f) else emptyList()
        val root = sqrt(discriminant)
        val low = ((-bb - root) / (2 * aa)).coerceIn(0.0, 1.0).toFloat()
        val high = ((-bb + root) / (2 * aa)).coerceIn(0.0, 1.0).toFloat()
        if (high <= low) return listOf(0f to 1f)
        return buildList { if (low > 0f) add(0f to low); if (high < 1f) add(high to 1f) }
    }

    private fun interpolate(a: InkPoint, b: InkPoint, t: Float) = InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.pressure + (b.pressure - a.pressure) * t)

    private fun shapeStrokes(shape: ShapeElement): List<StrokeElement> {
        fun stroke(points: List<InkPoint>, id: String = newId()) = StrokeElement(id = id, points = points, color = shape.color, width = shape.width, isPolyline = true, isShapeFragment = true)
        val a = InkPoint(shape.startX, shape.startY); val b = InkPoint(shape.endX, shape.endY)
        return when (shape.shape) {
            ShapeKind.LINE -> listOf(stroke(listOf(a, b), shape.id))
            ShapeKind.ARROW -> {
                val (left, right) = ElementGeometry.arrowHeadPoints(shape)
                listOf(stroke(listOf(a, b), shape.id), stroke(listOf(left, b, right)))
            }
            ShapeKind.RECTANGLE -> listOf(stroke(listOf(a, InkPoint(b.x, a.y), b, InkPoint(a.x, b.y), a), shape.id))
            ShapeKind.ELLIPSE -> {
                val cx = (a.x + b.x) / 2f; val cy = (a.y + b.y) / 2f
                val rx = abs(b.x - a.x) / 2f; val ry = abs(b.y - a.y) / 2f
                listOf(stroke((0..256).map { val angle = 2 * PI * it / 256; InkPoint(cx + rx * cos(angle).toFloat(), cy + ry * sin(angle).toFloat()) }, shape.id))
            }
        }
    }

    fun lassoSelection(elements: List<PageElement>, polygon: List<InkPoint>): Set<String> {
        if (polygon.size < 3) return emptySet()
        if (polygon.size > MAX_LASSO_POINTS) throw GeometryLimitException()
        val budget = Budget()
        val polygonBounds = RectF(polygon.minOf { it.x }, polygon.minOf { it.y }, polygon.maxOf { it.x }, polygon.maxOf { it.y })
        val result = linkedSetOf<String>()
        for (element in elements) {
            budget.checks(if (element is StrokeElement) max(1, element.points.size) else 1)
            if (!overlaps(ElementGeometry.bounds(element), polygonBounds)) continue
            // Endpoints lie on the rendered curve. Interior control points generally do not.
            if (element is StrokeElement && element.points.isNotEmpty() &&
                (insidePolygon(element.points.first(), polygon, polygonBounds, budget) || insidePolygon(element.points.last(), polygon, polygonBounds, budget))) {
                result.add(element.id)
                continue
            }
            val outline = when (element) {
                is StrokeElement -> listOf(flattened(element, budget))
                is ShapeElement -> shapeStrokes(element).map { it.points }
                is ImageElement -> listOf(ElementGeometry.imageCorners(element).let { it + it.first() })
                is TextElement -> {
                    val b = ElementGeometry.bounds(element)
                    listOf(listOf(InkPoint(b.left, b.top), InkPoint(b.right, b.top), InkPoint(b.right, b.bottom), InkPoint(b.left, b.bottom), InkPoint(b.left, b.top)))
                }
            }
            var selected = false
            for (path in outline) {
                if (pathIntersectsPolygon(path, polygon, polygonBounds, budget)) { selected = true; break }
            }
            // If boundaries never cross, one polygon vertex suffices for filled-object containment.
            if (!selected && (element is ImageElement || element is TextElement)) {
                budget.checks()
                selected = ElementGeometry.hitTest(element, polygon.first().x, polygon.first().y, 0f)
            }
            if (selected) result.add(element.id)
        }
        return result
    }

    private fun pathIntersectsPolygon(path: List<InkPoint>, polygon: List<InkPoint>, polygonBounds: RectF, budget: Budget): Boolean {
        for (index in path.indices) {
            val point = path[index]
            if (insidePolygon(point, polygon, polygonBounds, budget)) return true
            if (index == 0) continue
            val previous = path[index - 1]
            if (max(previous.x, point.x) < polygonBounds.left || min(previous.x, point.x) > polygonBounds.right ||
                max(previous.y, point.y) < polygonBounds.top || min(previous.y, point.y) > polygonBounds.bottom) continue
            for (edge in polygon.indices) {
                budget.checks()
                if (intersects(previous, point, polygon[edge], polygon[(edge + 1) % polygon.size])) return true
            }
        }
        return false
    }

    private fun overlaps(a: RectF, b: RectF): Boolean =
        a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom

    private fun insidePolygon(point: InkPoint, polygon: List<InkPoint>, bounds: RectF, budget: Budget): Boolean {
        if (point.x < bounds.left || point.x > bounds.right || point.y < bounds.top || point.y > bounds.bottom) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            budget.checks()
            val a = polygon[i]; val b = polygon[j]
            if ((a.y > point.y) != (b.y > point.y) && point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    private fun intersects(a: InkPoint, b: InkPoint, c: InkPoint, d: InkPoint): Boolean {
        fun cross(p: InkPoint, q: InkPoint, r: InkPoint) = (q.x - p.x).toDouble() * (r.y - p.y) - (q.y - p.y).toDouble() * (r.x - p.x)
        if (max(a.x, b.x) < min(c.x, d.x) || max(c.x, d.x) < min(a.x, b.x) || max(a.y, b.y) < min(c.y, d.y) || max(c.y, d.y) < min(a.y, b.y)) return false
        return cross(a, b, c) * cross(a, b, d) <= 0 && cross(c, d, a) * cross(c, d, b) <= 0
    }
}
