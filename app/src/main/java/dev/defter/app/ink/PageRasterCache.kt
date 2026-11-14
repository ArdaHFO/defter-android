package dev.defter.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import dev.defter.app.localization.tr
import dev.defter.app.model.*
import kotlin.math.ceil
import kotlin.math.sqrt

/** Per-View, viewport-sized render cache. Never changes persisted vector documents. */
internal class PageRasterCache(private val maxPixels: Double = 4_000_000.0) {
    private data class Entry(val page: PageDocument, val assets: Map<String, Bitmap>, val locale: String,
        val clip: Rect, val scale: Float, val bitmap: Bitmap)
    private val entries = linkedMapOf<String, Entry>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    internal var fullRenders = 0; private set
    internal var appendedElements = 0; private set
    internal val allocatedBytes: Int get() = entries.values.sumOf { it.bitmap.byteCount }

    fun draw(canvas: Canvas, page: PageDocument, assets: Map<String, Bitmap>, scale: Float) {
        val clip = Rect()
        if (!canvas.getClipBounds(clip) || !clip.intersect(0, 0, ceil(page.width).toInt(), ceil(page.height).toInt())) return
        val pixels = clip.width().toDouble() * clip.height() * scale * scale
        val rasterScale = scale * if (pixels > maxPixels) sqrt(maxPixels / pixels).toFloat() else 1f
        val locale = tr("KONU / TARİH")
        var entry = entries[page.id]
        val sameSurface = entry != null && entry.clip == clip && entry.scale == rasterScale &&
            entry.assets === assets && entry.locale == locale && entry.page.width == page.width &&
            entry.page.height == page.height && entry.page.template == page.template &&
            entry.page.backgroundColor == page.backgroundColor && entry.page.pdfBackground == page.pdfBackground
        if (sameSurface && entry.page !== page && entry.page.elements !== page.elements) {
            val old = entry.page.elements
            val appendOnly = page.elements.size >= old.size && old.indices.all { old[it] === page.elements[it] }
            if (appendOnly) {
                val target = Canvas(entry.bitmap)
                target.scale(rasterScale, rasterScale); target.translate(-clip.left.toFloat(), -clip.top.toFloat())
                target.clipRect(0f, 0f, page.width, page.height)
                for (index in old.size until page.elements.size) {
                    PageRenderer.drawElement(target, page.elements[index], assets); appendedElements++
                }
                entry = entry.copy(page = page)
            } else entry = null
        } else if (!sameSurface) entry = null
        if (entry == null) {
            // Bound retention before allocating. Published bitmaps may still be referenced by
            // Android's previous display list, so release references rather than recycle them.
            entries.remove(page.id)
            val width = ceil(clip.width() * rasterScale).toInt().coerceAtLeast(1)
            val height = ceil(clip.height() * rasterScale).toInt().coerceAtLeast(1)
            val bytes = width.toLong() * height * 4
            while (entries.isNotEmpty() && (entries.size >= 2 || allocatedBytes + bytes > maxPixels * 8))
                entries.remove(entries.keys.first())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val target = Canvas(bitmap)
            target.scale(rasterScale, rasterScale); target.translate(-clip.left.toFloat(), -clip.top.toFloat())
            PageRenderer.draw(target, page, assets)
            entry = Entry(page, assets, locale, Rect(clip), rasterScale, bitmap)
            fullRenders++
        }
        entries.remove(page.id); entries[page.id] = entry
        canvas.drawBitmap(entry.bitmap, null, RectF(clip), paint)
    }

    fun clear() { entries.clear() }
}
