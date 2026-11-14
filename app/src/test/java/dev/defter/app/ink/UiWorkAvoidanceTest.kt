package dev.defter.app.ink

import android.graphics.*
import android.os.Looper
import dev.defter.app.model.*
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
class UiWorkAvoidanceTest {
    @Test fun unchangedComposeBindingsDoNotInvalidateTheDrawingSurface() {
        val view = NotebookCanvas(RuntimeEnvironment.getApplication())
        val page = PageDocument(template = PaperTemplate.BLANK)
        view.document = page; view.layout(0, 0, 632, 832)
        val ids = listOf(page.id); val docs = mapOf(page.id to page)
        val assets = mapOf(page.id to emptyMap<String, Bitmap>())
        view.setPageWindow(ids, docs, assets)
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(view); shadow.clearWasInvalidated()
        repeat(100) {
            view.document = page; view.config = view.config; view.assets = view.assets
            view.isReadOnly = false; view.isEnabled = true
            view.setPageWindow(ids, docs, assets)
        }
        assertFalse("Unchanged toolbar/session state must not redraw or rebuild the page", shadow.wasInvalidated())
        view.config = view.config.copy(color = Color.RED)
        assertTrue("Real input configuration changes must still redraw", shadow.wasInvalidated())
    }

    @Test fun newWindowContentStillInvalidatesAndCanBeRendered() {
        val view = NotebookCanvas(RuntimeEnvironment.getApplication())
        val page = PageDocument(width = 600f, height = 600f, template = PaperTemplate.BLANK, backgroundColor = Color.WHITE)
        val ids = listOf(page.id); val assets = mapOf(page.id to emptyMap<String, Bitmap>())
        view.document = page; view.layout(0, 0, 632, 832)
        view.setPageWindow(ids, mapOf(page.id to page), assets)
        shadowOf(Looper.getMainLooper()).idle(); shadowOf(view).clearWasInvalidated()
        val changed = page.copy(backgroundColor = Color.BLUE, revision = 1)
        view.setPageWindow(ids, mapOf(page.id to changed), assets); view.document = changed
        assertTrue(shadowOf(view).wasInvalidated())
        val bitmap = Bitmap.createBitmap(632, 832, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap)); assertEquals(Color.BLUE, bitmap.getPixel(100, 100)); bitmap.recycle()
    }

    @Test fun scrollingReusesBitmapsAcrossDifferentViewportTransforms() {
        val cache = PageFrameCache(onReady = {})
        val page = PageDocument(width = 600f, height = 600f, template = PaperTemplate.DOTS,
            elements = List(500) { i -> StrokeElement(points = listOf(InkPoint(20f, i.toFloat()), InkPoint(500f, i.toFloat()))) })
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        repeat(30) { frame ->
            val canvas = Canvas(bitmap); canvas.translate(0f, -frame.toFloat()); canvas.scale(1.2f, 1.2f)
            cache.draw(canvas, page, emptyMap(), 1.2f, false)
        }
        assertEquals("Panning must not replay every page element each frame", 1, cache.synchronousRenders)
        bitmap.recycle()
    }

    @Test fun recordedPagesKeepPixelsAndInvalidateForUndoPaperAndEviction() {
        val cache = PageFrameCache(onReady = {})
        val page = PageDocument(width = 600f, height = 600f, template = PaperTemplate.BLANK, backgroundColor = Color.WHITE)
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        cache.draw(Canvas(bitmap), page, emptyMap(), 1f, false); assertEquals(Color.WHITE, bitmap.getPixel(100, 100))
        val changed = page.copy(backgroundColor = Color.RED)
        cache.draw(Canvas(bitmap), changed, emptyMap(), 1f, false); assertEquals(Color.RED, bitmap.getPixel(100, 100))
        cache.draw(Canvas(bitmap), page, emptyMap(), 1f, false); assertEquals(Color.WHITE, bitmap.getPixel(100, 100))
        assertEquals(3, cache.synchronousRenders)
        repeat(10) { cache.draw(Canvas(bitmap), page.copy(id = newId()), emptyMap(), 1f, false) }
        assertEquals(2, cache.size)
        cache.retainPages(setOf(page.id)); assertEquals(0, cache.size)
        bitmap.recycle()
    }
}
