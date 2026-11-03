package dev.defter.app.model

import org.junit.Assert.*
import org.junit.Test

class NotebookPageWindowTest {
    @Test fun longNotebookKeepsActiveAndNearestVisiblePagesWithinTheFivePageBoundAfterReorder() {
        val ids = List(100_000) { "page-$it" }
        val center = ids[99_997]
        val visible = ids.takeLast(5)
        for (ordered in listOf(ids, ids.asReversed())) {
            val selected = NotebookPageWindow.select(ordered, center, visible, ids.first())
            assertEquals(5, selected.size)
            assertEquals(selected.size, selected.toSet().size)
            assertTrue(ids.first() in selected)
            assertTrue(center in selected)
            assertTrue(ids[99_996] in selected)
            assertTrue(ids[99_998] in selected)
            assertTrue(selected.all { it == ids.first() || it in visible })
        }
    }

    @Test fun invalidAndRepeatedViewportIdsNeverEnterTheWindowOrExceedAvailablePages() {
        val ids = listOf("a", "b", "c")
        assertEquals(ids.toSet(), NotebookPageWindow.select(ids, "b", listOf("missing", "b", "b", "c"), "missing-active").toSet())
        assertTrue(NotebookPageWindow.select(emptyList(), "missing", listOf("missing"), null).isEmpty())
    }
}
