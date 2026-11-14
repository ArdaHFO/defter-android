package dev.defter.app.ink

import android.os.Looper
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class NotebookViewportTest {
    @Test fun tenThousandPageMapGeometryDoesNotReadAnyInkOrExpandTheDocumentWindow() {
        val ids = List(10_000) { "page-$it" }
        val guarded = object : AbstractList<InkPoint>() {
            override val size = 50_000
            override fun get(index: Int): InkPoint = error("Viewport reporting traversed ink")
        }
        val pages = ids.take(3).associateWith { PageDocument(id = it, elements = listOf(StrokeElement(points = guarded))) }
        val view = NotebookCanvas(RuntimeEnvironment.getApplication())
        view.document = pages[ids.first()]
        view.setPageWindow(ids, pages, emptyMap()); view.layout(0, 0, 632, 832)
        val frames = mutableListOf<NotebookViewport>()
        view.onViewportChanged = frames::add
        shadowOf(Looper.getMainLooper()).idle()
        val result = frames.last()
        assertEquals(ids.first(), result.centerId)
        assertTrue(result.pages.size in 1..3)
        result.pages.forEach { assertTrue(it.from in 0f..1f); assertTrue(it.to in it.from..1f) }
        assertEquals(0f, result.left, .01f); assertEquals(1f, result.right, .01f)
        assertEquals(3, pages.size)
        view.onViewportChanged = null
    }
}
