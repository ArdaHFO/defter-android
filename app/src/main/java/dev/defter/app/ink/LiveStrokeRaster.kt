package dev.defter.app.ink

import android.graphics.*
import dev.defter.app.model.StrokeElement
import kotlin.math.ceil
import kotlin.math.sqrt

/** Only stable quadratics enter the transparent overlay. The moving end is drawn separately. */
internal class LiveStrokeRaster(private val maxPixels: Double = 4_000_000.0) {
    private var bitmap: Bitmap? = null
    private var region = Rect()
    private var factor = 0f
    private var strokeId: String? = null
    private var rendered = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    internal var segmentsRendered = 0; private set

    fun draw(canvas: Canvas, stroke: StrokeElement, scale: Float) {
        val clip = Rect()
        if (!canvas.getClipBounds(clip)) return
        val pixels = clip.width().toDouble() * clip.height() * scale * scale
        val nextFactor = scale * if (pixels > maxPixels) sqrt(maxPixels / pixels).toFloat() else 1f
        val changedRegion = bitmap == null || clip != region || nextFactor != factor
        if (changedRegion) {
            region = Rect(clip); factor = nextFactor
            bitmap = Bitmap.createBitmap(ceil(clip.width() * factor).toInt().coerceAtLeast(1),
                ceil(clip.height() * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }
        val target = bitmap ?: return
        if (changedRegion || stroke.id != strokeId || stroke.points.size - 1 < rendered) {
            target.eraseColor(Color.TRANSPARENT); rendered = 0; strokeId = stroke.id
        }
        val targetCanvas = Canvas(target)
        targetCanvas.scale(factor, factor); targetCanvas.translate(-region.left.toFloat(), -region.top.toFloat())
        PageRenderer.drawPenRange(targetCanvas, stroke, rendered + 1, stroke.points.size, false)
        segmentsRendered += (stroke.points.size - 1 - rendered).coerceAtLeast(0)
        rendered = (stroke.points.size - 1).coerceAtLeast(0)
        canvas.drawBitmap(target, null, RectF(region), paint)
        if (stroke.points.size == 1) PageRenderer.drawElement(canvas, stroke, emptyMap())
        else PageRenderer.drawPenRange(canvas, stroke, stroke.points.size, stroke.points.size, true)
    }

    fun clear() { bitmap = null; strokeId = null; rendered = 0 }
}
