package dev.defter.app.ink

import android.graphics.*
import android.os.Looper
import dev.defter.app.data.DocumentJson
import dev.defter.app.model.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PageFrameCacheTest {
    private class Worker : Executor {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() {
            repeat(20) {
                val task = tasks.poll() ?: return
                var failure: Throwable? = null
                val thread = Thread { try { task.run() } catch (error: Throwable) { failure = error } }
                thread.start(); thread.join(30_000)
                assertFalse("Background render must complete", thread.isAlive)
                failure?.let { throw it }
                shadowOf(Looper.getMainLooper()).idle()
            }
            fail("Render queue did not drain")
        }
    }
    private fun dense(color: Int = Color.BLACK, id: String = newId()) = PageDocument(id = id,
        width = 600f, height = 600f, template = PaperTemplate.BLANK, backgroundColor = Color.WHITE,
        elements = listOf(StrokeElement(color = color, width = 8f,
            points = List(6_000) { InkPoint(50f + it % 500, 100f) })))
    private fun draw(cache: PageFrameCache, page: PageDocument, scale: Float = 1f, detail: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.scale(scale, scale)
        cache.draw(canvas, page, emptyMap(), scale, detail)
        return bitmap
    }

    @Test fun denseInkIsNeverTraversedOnMainAndThirtyScrollFramesReuseOneSnapshot() {
        val reads = AtomicInteger()
        val guarded = object : AbstractList<InkPoint>() {
            override val size = 50_000
            override fun get(index: Int): InkPoint {
                check(Thread.currentThread() !== Looper.getMainLooper().thread) { "Old ink was replayed on Main" }
                reads.incrementAndGet(); return InkPoint(50f + index % 500, 100f)
            }
        }
        val page = dense().copy(elements = listOf(StrokeElement(points = guarded, width = 8f)))
        val worker = Worker(); val cache = PageFrameCache(onReady = {}, executor = worker)
        repeat(30) { draw(cache, page, 1f + it * .01f).recycle() }
        assertEquals(0, reads.get()); assertEquals(1, cache.pendingJobs); assertEquals(0, cache.synchronousRenders)
        worker.drain(); assertTrue(reads.get() > 0)
        val count = reads.get()
        repeat(30) { draw(cache, page, 1f + it * .01f).recycle() }
        assertEquals("Cached scroll must not touch old points", count, reads.get())
        assertEquals(1, cache.backgroundRenders); cache.clear()
    }

    @Test fun obsoleteRenderCannotPublishOverNewPaperOrUndo() {
        val worker = Worker(); val cache = PageFrameCache(onReady = {}, executor = worker)
        val old = dense(); draw(cache, old).recycle()
        val changed = old.copy(backgroundColor = Color.BLUE, revision = 1)
        draw(cache, changed).recycle(); worker.drain()
        draw(cache, changed).also { assertEquals(Color.BLUE, it.getPixel(200, 300)); it.recycle() }
        assertEquals(1, cache.backgroundRenders)
        val undo = changed.copy(elements = emptyList(), backgroundColor = Color.WHITE, revision = 2)
        draw(cache, undo).also { assertEquals("Undo cannot retain the old ink", Color.WHITE, it.getPixel(200, 100)); it.recycle() }
        cache.clear()
    }

    @Test fun snapshotsStayBoundedAndDetailDoesNotRebuildOnEveryStationaryFrame() {
        val worker = Worker(); val cache = PageFrameCache(360_000.0, onReady = {}, executor = worker)
        repeat(6) { val page = dense(); draw(cache, page).recycle(); worker.drain() }
        assertEquals(2, cache.size); assertTrue(cache.allocatedBytes <= 1_500_000)
        val page = dense(); draw(cache, page).recycle(); worker.drain()
        draw(cache, page, 3f, true).recycle(); worker.drain()
        val count = cache.backgroundRenders
        repeat(20) { draw(cache, page, 3f, true).recycle() }
        assertEquals(count, cache.backgroundRenders); assertEquals(0, cache.pendingJobs)
        assertTrue(cache.allocatedBytes <= 3_000_000); cache.clear()
    }

    @Test fun clearingViewCancelsQueuedWorkAndNeverPostsAStaleInvalidation() {
        val worker = Worker(); var callbacks = 0
        val cache = PageFrameCache(onReady = { callbacks++ }, executor = worker)
        draw(cache, dense()).recycle(); cache.clear(); worker.drain()
        assertEquals(0, callbacks); assertEquals(0L, cache.allocatedBytes); assertEquals(0, cache.pendingJobs)
    }

    @Test fun legacyV1AndV2TwoPageInkRemainsUnchangedByRenderingAndScrolling() {
        val worker = Worker(); val cache = PageFrameCache(onReady = {}, executor = worker)
        val json = listOf(1, 2).map { version -> DocumentJson.pageToJson(dense()).put("version", version) }
        val before = json.map { it.toString() }
        val pages = json.map(DocumentJson::pageFromJson)
        repeat(20) { pages.forEach { draw(cache, it, 1.1f).recycle() } }
        worker.drain()
        pages.forEach { page -> draw(cache, page, 1.1f).also { assertEquals(Color.BLACK, it.getPixel(200, 110)); it.recycle() } }
        assertEquals(before, json.map { it.toString() })
        pages.forEach { assertEquals(6_000, (it.elements.single() as StrokeElement).points.size) }
        cache.clear()
    }

    @Test fun cancelledLongStrokeStopsBeforeProcessingAllItsSegments() {
        val page = dense(); var checks = 0
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        try {
            PageRenderer.drawCancellable(Canvas(bitmap), page, emptyMap()) { ++checks >= 4 }
            fail("Obsolete render must stop cooperatively")
        } catch (_: java.util.concurrent.CancellationException) {
            assertEquals(4, checks)
        } finally { bitmap.recycle() }
    }

    @Test fun transparentPaperIsNotCompositedTwiceOrAppliedAsInkOpacity() {
        val cache = PageFrameCache(onReady = {})
        val page = PageDocument(width = 600f, height = 600f, template = PaperTemplate.BLANK,
            backgroundColor = 0x40ff0000, elements = listOf(StrokeElement(color = Color.BLACK, width = 10f,
                points = listOf(InkPoint(50f, 100f), InkPoint(400f, 100f)))))
        draw(cache, page).also {
            assertEquals(0x40ff0000, it.getPixel(200, 300))
            assertEquals(Color.BLACK, it.getPixel(200, 100)); it.recycle()
        }
        cache.clear()
    }

    @Test fun bindingReloadedOrDuplicatedInkDoesNotDeepComparePointsOnMain() {
        fun guarded() = object : AbstractList<InkPoint>() {
            override val size = 50_000
            override fun get(index: Int): InkPoint = error("Binding traversed old ink on Main")
        }
        val first = StrokeElement(points = guarded())
        val page = dense().copy(elements = listOf(first))
        val view = NotebookCanvas(RuntimeEnvironment.getApplication())
        view.document = page
        view.document = page.copy(revision = 1, elements = listOf(first.copy(points = guarded())))
        assertEquals(1L, view.document!!.revision)
    }
}
