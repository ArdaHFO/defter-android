package dev.defter.app.ink

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.Process
import dev.defter.app.localization.tr
import dev.defter.app.model.*
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** Scroll frames only blit bounded bitmaps. Complex vector work never runs on Main. */
internal class PageFrameCache(
    private val maxPixels: Double = 4_000_000.0,
    private val onReady: () -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val executor: Executor? = null,
    private val renderer: (Canvas, PageDocument, Map<String, Bitmap>, () -> Boolean) -> Unit =
        { canvas, page, assets, cancelled -> PageRenderer.drawCancellable(canvas, page, assets, cancelled) }
) {
    private class Key(val page: PageDocument, val assets: Map<String, Bitmap>, val locale: String) {
        fun matches(other: Key) = page.id == other.page.id && page.elements === other.page.elements &&
            assets === other.assets && locale == other.locale && page.width == other.page.width &&
            page.height == other.page.height && page.template == other.page.template &&
            page.backgroundColor == other.page.backgroundColor && page.pdfBackground == other.page.pdfBackground
        fun sameSurface(other: Key) = page.id == other.page.id && locale == other.locale &&
            page.width == other.page.width && page.height == other.page.height &&
            page.template == other.page.template && page.backgroundColor == other.page.backgroundColor &&
            page.pdfBackground == other.page.pdfBackground
    }
    private class Request(val key: Key, val region: Rect, val factor: Float, val detail: Boolean, val editSession: Long? = null) {
        @Volatile var cancelled = false
        fun matches(other: Request) = detail == other.detail && key.matches(other.key) && region == other.region && factor == other.factor
    }
    private class Entry(val request: Request, val bitmap: Bitmap)
    private val pages = linkedMapOf<String, Entry>()
    private var detail: Entry? = null
    private val queue = ArrayList<Request>()
    private var active: Request? = null
    private var worker: ExecutorService? = null
    private data class Desired(val key: Key, val editSession: Long?)
    private val desired = mutableMapOf<String, Desired>()
    private var baseline: Entry? = null
    private var baselineSession: Long? = null
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val failed = mutableMapOf<String, Key>()
    internal var synchronousRenders = 0; private set
    internal var backgroundRenders = 0; private set
    internal val pendingJobs get() = queue.size + if (active == null) 0 else 1
    internal val size get() = pages.size
    internal val allocatedBytes get() = (pages.values.toList() + listOfNotNull(detail, baseline))
        .distinctBy { it.bitmap }.sumOf { it.bitmap.byteCount.toLong() }

    fun draw(canvas: Canvas, page: PageDocument, assets: Map<String, Bitmap>, scale: Float, allowDetail: Boolean, editSession: Long? = null) {
        val key = Key(page, assets, tr("KONU / TARİH"))
        desired[page.id] = Desired(key, editSession)
        if (editSession != null && (baselineSession != editSession || baseline?.request?.key?.page?.id != page.id)) {
            baseline = pages[page.id]?.takeIf { it.request.key.sameSurface(key) }
            baselineSession = editSession
        }
        // Cancel/config change restores the exact contact-start frame immediately.
        if (editSession == null && baseline?.request?.key?.matches(key) == true) {
            pages[page.id] = baseline!!
        }
        cancelObsolete(key, editSession)
        var entry = pages[page.id]
        if (entry == null || !entry.request.key.matches(key)) {
            val region = Rect(0, 0, ceil(page.width).toInt(), ceil(page.height).toInt())
            val request = Request(key, region, factor(region, scale, maxPixels / 2), false, editSession)
            if (isSmall(page)) {
                val bitmap = render(request)
                if (bitmap != null) { entry = Entry(request, bitmap); publish(entry); synchronousRenders++ }
            } else enqueue(request)
        }
        val exact = entry?.request?.key?.matches(key) == true
        if (exact) {
            drawEntry(canvas, entry!!)
            pages.remove(page.id); pages[page.id] = entry
        } else if (entry != null && canOverlay(entry.request.key, key)) {
            drawEntry(canvas, entry)
            for (index in entry.request.key.page.elements.size until page.elements.size)
                PageRenderer.drawElement(canvas, page.elements[index], assets)
        } else if (entry != null && entry.request.key.sameSurface(key)) {
            // Present the last complete frame while edits render. Never blank unrelated ink.
            drawEntry(canvas, entry)
        } else {
            paint.color = page.backgroundColor
            canvas.drawRect(0f, 0f, page.width, page.height, paint)
            paint.color = if (Color.luminance(page.backgroundColor) < .4f) Color.LTGRAY else Color.DKGRAY
            paint.textSize = 20f
            canvas.drawText(tr("Hazırlanıyor…"), 32f, 52f, paint)
        }
        val patch = detail
        val opaque = Color.alpha(page.backgroundColor) == 255
        if (exact && opaque && patch?.request?.key?.matches(key) == true) drawEntry(canvas, patch)
        if (exact && opaque && allowDetail && editSession == null && scale > entry!!.request.factor * 1.4f) {
            val clip = Rect()
            if (canvas.getClipBounds(clip) && clip.intersect(0, 0, ceil(page.width).toInt(), ceil(page.height).toInt())) {
                val request = Request(key, clip, factor(clip, scale, maxPixels), true)
                if (patch == null || !patch.request.matches(request)) enqueue(request)
            }
        }
        val retained = pages.keys + queue.map { it.key.page.id } + listOfNotNull(active?.key?.page?.id, baseline?.request?.key?.page?.id, page.id)
        desired.keys.retainAll(retained.toSet())
    }

    private fun drawEntry(canvas: Canvas, entry: Entry) {
        paint.alpha = 255
        canvas.drawBitmap(entry.bitmap, null, RectF(entry.request.region), paint)
    }

    private fun factor(region: Rect, desired: Float, pixels: Double): Float =
        min(desired, min(sqrt(pixels / (region.width().toDouble() * region.height())).toFloat(),
            4096f / maxOf(region.width(), region.height()))).coerceAtLeast(0.00001f)

    /** Count points without visiting them. Include template work in the synchronous budget. */
    private fun isSmall(page: PageDocument): Boolean {
        var work = when (page.template) {
            PaperTemplate.DOTS -> ((page.width / 44).toLong() * (page.height / 44).toLong())
            PaperTemplate.GRID -> ((page.width + page.height) / 44).toLong()
            else -> (page.height / 44).toLong()
        }
        if (work > MAIN_WORK_LIMIT) return false
        for (element in page.elements) {
            work += cost(element)
            if (work > MAIN_WORK_LIMIT) return false
        }
        return true
    }

    private fun cost(element: PageElement): Long = when (element) {
        is StrokeElement -> element.points.size.toLong()
        is TextElement -> element.text.length.toLong()
        else -> 32L
    }

    private fun canOverlay(old: Key, next: Key): Boolean {
        val a = old.page; val b = next.page
        if (old.assets !== next.assets || old.locale != next.locale || a.id != b.id ||
            a.width != b.width || a.height != b.height || a.template != b.template ||
            a.backgroundColor != b.backgroundColor || a.pdfBackground != b.pdfBackground || a.elements.size > b.elements.size) return false
        var work = 0L
        for (i in a.elements.size until b.elements.size) {
            work += cost(b.elements[i]); if (work > MAIN_WORK_LIMIT) return false
        }
        return a.elements.indices.all { a.elements[it] === b.elements[it] }
    }

    private fun cancelObsolete(key: Key, editSession: Long?) {
        active?.takeIf { it.key.page.id == key.page.id && !it.key.matches(key) &&
            !(editSession != null && editSession == it.editSession && !it.detail && it.key.sameSurface(key)) }?.cancelled = true
        queue.removeAll { it.key.page.id == key.page.id && !it.key.matches(key) }
        failed[key.page.id]?.takeUnless { it.matches(key) }?.let { failed.remove(key.page.id) }
    }

    private fun enqueue(request: Request) {
        cancelObsolete(request.key, request.editSession)
        if (failed[request.key.page.id]?.matches(request.key) == true) return
        fun sameWork(known: Request) = !known.cancelled &&
            if (!known.detail && !request.detail) known.key.matches(request.key) else known.matches(request)
        if (active?.let(::sameWork) == true || queue.any(::sameWork)) return
        queue.removeAll { it.key.page.id == request.key.page.id && it.detail == request.detail }
        while (queue.size >= 3) queue.removeAt(queue.lastIndex)
        queue.add(request)
        startNext()
    }

    private fun startNext() {
        if (active != null || queue.isEmpty()) return
        val request = queue.removeAt(0); active = request
        val target = executor ?: worker ?: Executors.newSingleThreadExecutor { task ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "Defter-page-render").apply { isDaemon = true }
        }.also { worker = it }
        target.execute {
            var bitmap: Bitmap? = null
            var failure: Throwable? = null
            try { bitmap = render(request) }
            catch (_: CancellationException) { }
            catch (error: Exception) { failure = error }
            catch (error: OutOfMemoryError) { failure = error }
            main.post {
                if (active === request) {
                    active = null
                    val target = desired[request.key.page.id]
                    val presentable = target != null && (request.key.matches(target.key) ||
                        (!request.detail && request.editSession != null && request.editSession == target.editSession &&
                            request.key.sameSurface(target.key) && pages[request.key.page.id]?.request?.key?.matches(target.key) != true))
                    if (!request.cancelled && presentable && bitmap != null) {
                        publish(Entry(request, bitmap!!)); backgroundRenders++; onReady()
                    } else bitmap?.recycle() // never published to a display list
                    if (!request.cancelled && presentable && failure != null) { failed[request.key.page.id] = request.key; onError(failure!!) }
                    startNext()
                } else bitmap?.recycle()
            }
        }
    }

    private fun render(request: Request): Bitmap? {
        if (request.cancelled) return null
        val bitmap = Bitmap.createBitmap(ceil(request.region.width() * request.factor).toInt().coerceAtLeast(1),
            ceil(request.region.height() * request.factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(request.factor, request.factor)
            canvas.translate(-request.region.left.toFloat(), -request.region.top.toFloat())
            renderer(canvas, request.key.page, request.key.assets) { request.cancelled || Thread.currentThread().isInterrupted }
            if (request.cancelled) { bitmap.recycle(); return null }
            return bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }

    private fun publish(entry: Entry) {
        if (entry.request.detail) { detail = entry; return }
        pages.remove(entry.request.key.page.id)
        while (pages.size >= 2) pages.remove(pages.keys.first())
        pages[entry.request.key.page.id] = entry
        detail?.takeIf { !it.request.key.matches(entry.request.key) }?.let { detail = null }
    }

    fun retainPages(ids: Set<String>) {
        pages.keys.retainAll(ids); failed.keys.retainAll(ids)
        desired.keys.retainAll(ids)
        if (baseline?.request?.key?.page?.id !in ids) { baseline = null; baselineSession = null }
        if (detail?.request?.key?.page?.id !in ids) detail = null
        queue.removeAll { it.key.page.id !in ids }
        active?.takeIf { it.key.page.id !in ids }?.cancelled = true
    }

    fun clear() {
        active?.cancelled = true; active = null; queue.clear()
        pages.clear(); detail = null; failed.clear()
        desired.clear(); baseline = null; baselineSession = null
        worker?.shutdownNow(); worker = null
    }

    companion object { private const val MAIN_WORK_LIMIT = 2048L }
}
