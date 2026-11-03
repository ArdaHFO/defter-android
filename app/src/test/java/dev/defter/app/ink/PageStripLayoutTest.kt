package dev.defter.app.ink

import org.junit.Assert.*
import org.junit.Test

class PageStripLayoutTest {
    @Test fun adjacentPagesShareWidthAndGapNeverHitsPaper() {
        val layout = PageStripLayout()
        layout.rebuild(listOf("a", "b"), mapOf("a" to PageStripLayout.Size(600f, 600f), "b" to PageStripLayout.Size(300f, 600f)))
        assertEquals(1240f, layout.slot("a")!!.height, 0f)
        assertEquals(2480f, layout.slot("b")!!.height, 0f)
        assertNull(layout.at(1260f))
        assertEquals("b", layout.at(1300f)!!.id)
        assertEquals(listOf("a", "b"), layout.visible(1200f, 1400f).map { it.id })
    }
    @Test fun unknownGeometryUsesPlaceholderAndOnlyIntersectingPagesAreReturned() {
        val layout = PageStripLayout()
        layout.rebuild((0..9999).map { "p$it" }, emptyMap())
        assertEquals(1754f, layout.slot("p0")!!.height, 0f)
        assertEquals(listOf("p5000"), layout.visible(5000f * 1802f + 100, 5000f * 1802f + 200).map { it.id })
        assertTrue(layout.visible(layout.height + 100, layout.height + 500).isEmpty())
    }
    @Test fun rememberedSizesKeepGeometryStableAfterDocumentsLeaveCache() {
        val layout = PageStripLayout()
        val sizes = mapOf("a" to PageStripLayout.Size(600f, 1200f), "b" to PageStripLayout.Size(1200f, 800f))
        layout.rebuild(listOf("a", "b"), sizes)
        val before = layout.slot("b")
        layout.rebuild(listOf("a", "b", "c"), sizes)
        assertEquals(before, layout.slot("b"))
    }
}
