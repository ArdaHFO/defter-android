package dev.defter.app.ink

import android.graphics.*
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EraserFrameContinuityTest {
    private class Worker : Executor {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun next() {
            val task = tasks.poll() ?: error("No pending render")
            var failure: Throwable? = null
            val thread = Thread { try { task.run() } catch (e: Throwable) { failure = e } }
            thread.start(); thread.join(15_000)
            assertFalse("Render must finish", thread.isAlive)
            failure?.let { throw it }
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun drain() { repeat(12) { if (tasks.isEmpty()) return; next() }; error("Unbounded render queue") }
    }
    private val assets = emptyMap<String, Bitmap>()
    private fun ink(color: Int, y: Float) = StrokeElement(color = color, width = 8f,
        points = List(6_000) { InkPoint(50f + it % 500, y) })
    private fun page() = PageDocument(width = 600f, height = 600f, template = PaperTemplate.BLANK,
        backgroundColor = Color.WHITE, elements = listOf(ink(Color.RED, 100f), ink(Color.BLUE, 300f), ink(Color.GREEN, 500f)))
    private fun draw(cache: PageFrameCache, page: PageDocument, session: Long? = null): Bitmap =
        Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also { cache.draw(Canvas(it), page, assets, 1f, false, session) }
    private fun checkFrame(cache: PageFrameCache, page: PageDocument, session: Long?, red: Int, blue: Int, green: Int = Color.GREEN) {
        draw(cache, page, session).also {
            try { assertEquals(red, it.getPixel(200, 100)); assertEquals(blue, it.getPixel(200, 300)); assertEquals(green, it.getPixel(200, 500)) }
            finally { it.recycle() }
        }
    }

    @Test fun erasingNeverBlanksUnrelatedInkWhileTheWorkerIsPending() {
        val worker = Worker(); val cache = PageFrameCache(onReady = {}, executor = worker)
        val original = page(); draw(cache, original).recycle(); worker.drain()
        val erased = original.copy(elements = original.elements.drop(1))
        checkFrame(cache, erased, 1, Color.RED, Color.BLUE)
        assertEquals(1, cache.pendingJobs)
        worker.drain()
        checkFrame(cache, erased, 1, Color.WHITE, Color.BLUE)
        assertEquals(0, cache.synchronousRenders); cache.clear()
    }

    @Test fun continuousEraserPublishesProgressAndCoalescesIntermediateMoves() {
        val worker = Worker(); val cache = PageFrameCache(onReady = {}, executor = worker)
        val original = page(); draw(cache, original).recycle(); worker.drain()
        val first = original.copy(elements = original.elements.drop(1))
        draw(cache, first, 2).recycle()
        val latest = original.copy(elements = original.elements.drop(2))
        repeat(20) { draw(cache, latest.copy(revision = it.toLong()), 2).recycle() }
        assertEquals("One active render and one latest target", 2, cache.pendingJobs)
        worker.next()
        checkFrame(cache, latest, 2, Color.WHITE, Color.BLUE)
        worker.next()
        checkFrame(cache, latest, 2, Color.WHITE, Color.WHITE)
        assertEquals(3, cache.backgroundRenders); cache.clear()
    }

    @Test fun cancellingAfterAProgressFrameRestoresTheOriginalImmediately() {
        val worker = Worker(); var callbacks = 0
        val cache = PageFrameCache(onReady = { callbacks++ }, executor = worker)
        val original = page(); draw(cache, original).recycle(); worker.drain()
        val first = original.copy(elements = original.elements.drop(1))
        draw(cache, first, 3).recycle(); worker.next()
        checkFrame(cache, first, 3, Color.WHITE, Color.BLUE)
        draw(cache, original.copy(elements = original.elements.drop(2)), 3).recycle()
        checkFrame(cache, original, null, Color.RED, Color.BLUE)
        val before = callbacks; worker.drain()
        assertEquals("Cancelled preview cannot invalidate or publish", before, callbacks)
        checkFrame(cache, original, null, Color.RED, Color.BLUE); cache.clear()
    }

    @Test fun finalSmallFrameCannotBeOverwrittenByAnOlderProgressRender() {
        val worker = Worker(); val cache = PageFrameCache(onReady = {}, executor = worker)
        val original = page(); draw(cache, original).recycle(); worker.drain()
        draw(cache, original.copy(elements = original.elements.drop(1)), 4).recycle()
        val blank = original.copy(elements = emptyList())
        checkFrame(cache, blank, 4, Color.WHITE, Color.WHITE, Color.WHITE)
        val renders = cache.backgroundRenders; worker.drain()
        assertEquals("An exact newer frame takes precedence", renders, cache.backgroundRenders)
        checkFrame(cache, blank, null, Color.WHITE, Color.WHITE, Color.WHITE); cache.clear()
    }

    @Test fun committingOrUndoingRejectsAnOlderInFlightPreview() {
        val worker = Worker(); val cache = PageFrameCache(onReady = {}, executor = worker)
        val original = page(); draw(cache, original).recycle(); worker.drain()
        draw(cache, original.copy(elements = original.elements.drop(1)), 5).recycle()
        val committed = original.copy(elements = original.elements.drop(2))
        draw(cache, committed).recycle(); worker.drain()
        checkFrame(cache, committed, null, Color.WHITE, Color.WHITE)
        checkFrame(cache, original, null, Color.RED, Color.BLUE)
        worker.drain(); checkFrame(cache, original, null, Color.RED, Color.BLUE); cache.clear()
    }

    @Test fun partialEraseKeepsTheUnaffectedStrokeAndCancellationDropsPendingPixels() {
        val worker = Worker(); val cache = PageFrameCache(onReady = {}, executor = worker)
        val original = page(); draw(cache, original).recycle(); worker.drain()
        val red = original.elements[0] as StrokeElement
        val partial = original.copy(elements = listOf(red.copy(points = red.points.filter { it.x < 150f })) + original.elements.drop(1))
        checkFrame(cache, partial, 6, Color.RED, Color.BLUE)
        worker.drain(); checkFrame(cache, partial, 6, Color.WHITE, Color.BLUE)
        checkFrame(cache, original, null, Color.RED, Color.BLUE); cache.clear()
    }

    @Test fun droppingThePageWindowAlsoReleasesTheContactStartFrame() {
        val worker = Worker(); val cache = PageFrameCache(onReady = {}, executor = worker)
        val original = page(); draw(cache, original).recycle(); worker.drain()
        draw(cache, original.copy(elements = original.elements.drop(1)), 7).recycle(); worker.drain()
        assertTrue(cache.allocatedBytes > 0)
        cache.retainPages(emptySet()); worker.drain()
        assertEquals(0, cache.pendingJobs); assertEquals(0L, cache.allocatedBytes); cache.clear()
    }

    @Test fun holdingAMovedSelectionStillDoesNotStartAnEndlessRasterLoop() {
        val worker = Worker(); val cache = PageFrameCache(onReady = {}, executor = worker)
        val view = NotebookCanvas(org.robolectric.RuntimeEnvironment.getApplication())
        view.document = page()
        view.setPageWindow(listOf(view.document!!.id), mapOf(view.document!!.id to view.document!!), emptyMap())
        view.layout(0, 0, 632, 632)
        view.config = DrawingConfig(tool = DrawingTool.SELECT)
        org.robolectric.util.ReflectionHelpers.getField<PageFrameCache>(view, "frameCache").clear()
        org.robolectric.util.ReflectionHelpers.setField(view, "frameCache", cache)
        val target = view.document!!.elements.first().id
        view.selectElements(setOf(target))
        var committed: PageDocument? = null
        view.onDocumentChanged = { committed = it }
        fun send(action: Int, x: Float, y: Float) {
            val bounds = requireNotNull(view.pageBoundsInView(view.document!!.id))
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = bounds.left + x * bounds.width() / 600f; this.y = bounds.top + y * bounds.height() / 600f; pressure = 1f })
            val event = MotionEvent.obtain(100, 120, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 200f, 100f); send(MotionEvent.ACTION_MOVE, 240f, 140f)
        val bitmap = Bitmap.createBitmap(632, 632, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap)); worker.drain()
            val completed = cache.backgroundRenders
            repeat(20) { view.draw(Canvas(bitmap)) }
            assertEquals("Holding the pen still must reuse the completed selection frame", 0, cache.pendingJobs)
            assertEquals(completed, cache.backgroundRenders)
            send(MotionEvent.ACTION_UP, 240f, 140f)
            assertEquals(90f, (requireNotNull(committed).elements.first() as StrokeElement).points.first().x, .1f)
        } finally { bitmap.recycle(); cache.clear() }
    }
}
