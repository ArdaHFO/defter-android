package dev.defter.app.ink

import android.graphics.*
import android.view.*
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RasterLatencyTest {
    private fun paper(elements: List<PageElement> = emptyList()) = PageDocument(width = 600f, height = 600f,
        template = PaperTemplate.BLANK, backgroundColor = Color.WHITE, elements = elements)
    private fun line(id: String = newId(), y: Float = 80f) = StrokeElement(id = id,
        points = listOf(InkPoint(50f, y), InkPoint(300f, y)), color = Color.BLACK, width = 8f)
    private fun draw(cache: PageRasterCache, page: PageDocument, assets: Map<String, Bitmap> = emptyMap()): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        cache.draw(Canvas(bitmap), page, assets, 1f)
        return bitmap
    }

    @Test fun repeatedFramesOfDensePageDoNotReplayEveryCompletedStroke() {
        val page = paper(List(1000) { line(y = (it % 500).toFloat()) })
        val cache = PageRasterCache()
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        repeat(3) { PageRenderer.draw(canvas, page, emptyMap()) }
        cache.draw(canvas, page, emptyMap(), 1f)
        val before = kotlin.system.measureNanoTime { repeat(30) { PageRenderer.draw(canvas, page, emptyMap()) } }
        val after = kotlin.system.measureNanoTime { repeat(30) { cache.draw(canvas, page, emptyMap(), 1f) } }
        println("RASTER_BENCH native software canvas, 1000 strokes, 30 frames: baselineNs=$before cachedNs=$after")
        bitmap.recycle()
        assertEquals("Committed page must rasterize once, not once per pen frame", 1, cache.fullRenders)
    }

    @Test fun appendDrawsOnlyNewInkAndUndoRebuildsWithoutGhostMarks() {
        val cache = PageRasterCache(); val first = paper(listOf(line()))
        draw(cache, first).recycle()
        val second = first.copy(revision = 1, elements = first.elements + line(y = 200f))
        draw(cache, second).also { assertEquals(Color.BLACK, it.getPixel(150, 200)); it.recycle() }
        assertEquals(1, cache.fullRenders); assertEquals(1, cache.appendedElements)
        draw(cache, first).also { assertEquals(Color.WHITE, it.getPixel(150, 200)); it.recycle() }
        assertEquals(2, cache.fullRenders)
    }

    @Test fun paperAssetAndViewportChangesCannotReuseStalePixels() {
        val cache = PageRasterCache(); val first = paper()
        draw(cache, first).recycle()
        draw(cache, first.copy(backgroundColor = Color.RED)).also { assertEquals(Color.RED, it.getPixel(100, 100)); it.recycle() }
        draw(cache, first, mapOf("unused" to Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))).recycle()
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.scale(.5f, .5f)
        cache.draw(canvas, first, emptyMap(), .5f)
        assertEquals(Color.WHITE, bitmap.getPixel(100, 100)); bitmap.recycle()
        assertEquals(4, cache.fullRenders)
    }

    @Test fun cacheRetentionStaysBoundedAcrossManyPages() {
        val cache = PageRasterCache()
        repeat(20) { draw(cache, paper(listOf(line()))).recycle() }
        assertTrue(cache.allocatedBytes <= 32_000_000)
        cache.clear(); assertEquals(0, cache.allocatedBytes)
    }

    @Test fun growingPenOverlayProcessesEachStableSegmentOnceAndMatchesFinalInk() {
        val points = mutableListOf(InkPoint(30f, 100f, .5f))
        val stroke = StrokeElement(points = points, color = Color.BLACK, width = 12f)
        val cache = LiveStrokeRaster()
        val actual = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        repeat(200) { index ->
            points.add(InkPoint(32f + index * 2f, 100f + (index % 20), .3f + (index % 7) * .1f))
            actual.eraseColor(Color.TRANSPARENT); cache.draw(Canvas(actual), stroke, 1f)
        }
        assertEquals(200, cache.segmentsRendered)
        val expected = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        PageRenderer.drawElement(Canvas(expected), stroke, emptyMap())
        val a = IntArray(360000); val b = IntArray(360000)
        actual.getPixels(a, 0, 600, 0, 0, 600, 600); expected.getPixels(b, 0, 600, 0, 0, 600, 600)
        assertArrayEquals("Incremental preview must preserve final pressure/smoothing pixels", b, a)
        actual.recycle(); expected.recycle()
    }

    @Test fun blankPageShowsInkBeforePenUpAndCancelLeavesNoPersistentInk() {
        val view = NotebookCanvas(RuntimeEnvironment.getApplication())
        view.document = paper(); view.config = DrawingConfig(width = 8f, color = Color.BLACK)
        view.layout(0, 0, 632, 832)
        var commits = 0; view.onDocumentChanged = { commits++ }
        fun event(action: Int, x: Float, y: Float, time: Long) {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
            val e = MotionEvent.obtain(100, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { assertTrue(view.dispatchTouchEvent(e)) } finally { e.recycle() }
        }
        fun pixel(): Int {
            val bitmap = Bitmap.createBitmap(632, 832, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap)); val pixel = bitmap.getPixel(150, 100); bitmap.recycle(); return pixel
        }
        assertEquals(Color.WHITE, pixel())
        event(MotionEvent.ACTION_DOWN, 100f, 100f, 100)
        event(MotionEvent.ACTION_MOVE, 200f, 100f, 120)
        assertEquals("Visible ink cannot wait for ACTION_UP/save", Color.BLACK, pixel())
        assertEquals(0, commits)
        event(MotionEvent.ACTION_CANCEL, 200f, 100f, 130)
        assertEquals(Color.WHITE, pixel()); assertEquals(0, commits)
    }
}
