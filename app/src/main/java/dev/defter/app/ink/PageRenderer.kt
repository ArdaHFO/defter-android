package dev.defter.app.ink

import dev.defter.app.localization.tr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import dev.defter.app.model.*
import kotlin.math.max
import kotlin.math.min

/** Draws in document coordinates. The caller owns viewport/export scaling. */
object PageRenderer {
    private class State {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val path = Path()
        val rect = RectF()
    }

    // Export can run off the UI thread. Never share mutable Paint/Path instances between them.
    private val states = object : ThreadLocal<State>() {
        override fun initialValue() = State()
    }

    @JvmStatic
    @JvmOverloads
    fun draw(
        canvas: Canvas,
        page: PageDocument,
        assets: Map<String, Bitmap>,
        excludedIds: Set<String> = emptySet(),
        replacement: PageElement? = null
    ) {
        val save = canvas.save()
        canvas.clipRect(0f, 0f, page.width, page.height)
        drawPaper(canvas, page)
        page.pdfBackground?.let { background ->
            assets["pdf:${background.assetId}:${background.pageIndex}"]?.takeUnless { it.isRecycled }?.let { bitmap ->
                val paint = states.get()!!.paint
                paint.reset()
                paint.isFilterBitmap = true
                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, page.width, page.height), paint)
            }
        }
        for (element in page.elements) {
            if (element.id !in excludedIds) {
                drawElement(canvas, if (replacement?.id == element.id) replacement else element, assets)
            }
        }
        canvas.restoreToCount(save)
    }

    fun drawPaper(canvas: Canvas, page: PageDocument) {
        val paint = states.get()!!.paint
        paint.reset()
        paint.isAntiAlias = true
        paint.color = page.backgroundColor
        canvas.drawRect(0f, 0f, page.width, page.height, paint)
        val darkPaper = Color.luminance(page.backgroundColor) < 0.4f
        paint.color = if (darkPaper) 0xFF4C6157.toInt() else 0xFFD7DED9.toInt()
        paint.strokeWidth = 1.25f
        val margin = 64f
        val spacing = 44f
        when (page.template) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.LINED -> {
                var y = margin + spacing
                while (y < page.height - margin) {
                    canvas.drawLine(margin, y, page.width - margin, y, paint)
                    y += spacing
                }
            }
            PaperTemplate.GRID -> {
                var x = margin
                while (x < page.width - margin) {
                    canvas.drawLine(x, margin, x, page.height - margin, paint)
                    x += spacing
                }
                var y = margin
                while (y < page.height - margin) {
                    canvas.drawLine(margin, y, page.width - margin, y, paint)
                    y += spacing
                }
            }
            PaperTemplate.DOTS -> {
                paint.color = if (darkPaper) 0xFF73897B.toInt() else 0xFFB8C6BE.toInt()
                var y = margin
                while (y < page.height - margin) {
                    var x = margin
                    while (x < page.width - margin) {
                        canvas.drawCircle(x, y, 1.7f, paint)
                        x += spacing
                    }
                    y += spacing
                }
            }
            PaperTemplate.CORNELL -> {
                val top = 148f
                val bottom = page.height - 300f
                val divider = page.width * 0.27f
                paint.strokeWidth = 2f
                canvas.drawLine(margin, top, page.width - margin, top, paint)
                canvas.drawLine(margin, bottom, page.width - margin, bottom, paint)
                canvas.drawLine(divider, top, divider, bottom, paint)
                paint.strokeWidth = 1f
                var y = top + spacing
                while (y < bottom) {
                    canvas.drawLine(divider + 24f, y, page.width - margin, y, paint)
                    y += spacing
                }
                paint.color = if (darkPaper) 0xFFB4C7BA.toInt() else 0xFF87988D.toInt()
                paint.textSize = 20f
                canvas.drawText(tr("KONU / TARİH"), margin, 85f, paint)
                canvas.drawText(tr("ANAHTAR NOTLAR"), margin, top + 34f, paint)
                canvas.drawText(tr("ÖZET"), margin, bottom + 38f, paint)
            }
        }
    }

    fun drawElement(canvas: Canvas, element: PageElement, assets: Map<String, Bitmap>) {
        val state = states.get()!!
        val paint = state.paint
        paint.reset()
        paint.isAntiAlias = true
        paint.isDither = true
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (element) {
            is StrokeElement -> drawStroke(canvas, element, state)
            is ShapeElement -> {
                paint.color = element.color
                paint.strokeWidth = element.width
                paint.style = Paint.Style.STROKE
                state.rect.set(
                    min(element.startX, element.endX), min(element.startY, element.endY),
                    max(element.startX, element.endX), max(element.startY, element.endY)
                )
                when (element.shape) {
                    ShapeKind.LINE -> canvas.drawLine(element.startX, element.startY, element.endX, element.endY, paint)
                    ShapeKind.ARROW -> {
                        canvas.drawLine(element.startX, element.startY, element.endX, element.endY, paint)
                        val (left, right) = ElementGeometry.arrowHeadPoints(element)
                        canvas.drawLine(left.x, left.y, element.endX, element.endY, paint)
                        canvas.drawLine(right.x, right.y, element.endX, element.endY, paint)
                    }
                    ShapeKind.RECTANGLE -> canvas.drawRect(state.rect, paint)
                    ShapeKind.ELLIPSE -> canvas.drawOval(state.rect, paint)
                }
            }
            is ImageElement -> {
                val save = canvas.save()
                canvas.rotate(element.rotationDegrees, element.x + element.width / 2f, element.y + element.height / 2f)
                state.rect.set(element.x, element.y, element.x + element.width, element.y + element.height)
                val bitmap = assets[element.assetId]
                if (bitmap != null && !bitmap.isRecycled) {
                    paint.isFilterBitmap = true
                    val source = Rect(
                        (element.cropLeft * bitmap.width).toInt().coerceIn(0, bitmap.width - 1),
                        (element.cropTop * bitmap.height).toInt().coerceIn(0, bitmap.height - 1),
                        (element.cropRight * bitmap.width).toInt().coerceIn(1, bitmap.width),
                        (element.cropBottom * bitmap.height).toInt().coerceIn(1, bitmap.height)
                    )
                    source.right = max(source.left + 1, source.right)
                    source.bottom = max(source.top + 1, source.bottom)
                    canvas.drawBitmap(bitmap, source, state.rect, paint)
                } else {
                    paint.color = 0xFFE2E8E1.toInt()
                    canvas.drawRect(state.rect, paint)
                    paint.color = 0xFF617568.toInt()
                    paint.textSize = 22f
                    canvas.drawText(tr("Görsel"), element.x + 16f, element.y + 34f, paint)
                }
                canvas.restoreToCount(save)
            }
            is TextElement -> {
                paint.color = element.color
                paint.textSize = element.size
                var baseline = element.y + element.size
                for (line in element.text.split('\n')) {
                    canvas.drawText(line, element.x, baseline, paint)
                    baseline += element.size * 1.25f
                }
            }
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: StrokeElement, state: State) {
        if (stroke.points.isEmpty()) return
        val paint = state.paint
        paint.color = stroke.color
        paint.style = Paint.Style.STROKE
        if (stroke.kind == InkKind.HIGHLIGHTER) {
            // One path prevents individual overlapping segments from becoming darker.
            paint.alpha = ((stroke.color ushr 24) * 0.32f).toInt()
            paint.strokeWidth = stroke.width
            if (stroke.points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.width / 2f, paint)
                return
            }
            val path = state.path
            path.reset()
            path.moveTo(stroke.points[0].x, stroke.points[0].y)
            ElementGeometry.visitStrokeSegments(stroke) { _, _, controlX, controlY, endX, endY, _, line ->
                if (line) path.lineTo(endX, endY) else path.quadTo(controlX, controlY, endX, endY)
                false
            }
            canvas.drawPath(path, paint)
            return
        }
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            val point = stroke.points.first()
            canvas.drawCircle(point.x, point.y, ElementGeometry.strokeWidth(stroke, point.pressure) / 2f, paint)
            return
        }
        val path = state.path
        ElementGeometry.visitStrokeSegments(stroke) { startX, startY, controlX, controlY, endX, endY, width, line ->
            paint.strokeWidth = width
            if (line) canvas.drawLine(startX, startY, endX, endY, paint)
            else {
                path.reset()
                path.moveTo(startX, startY)
                path.quadTo(controlX, controlY, endX, endY)
                canvas.drawPath(path, paint)
            }
            false
        }
    }
}
