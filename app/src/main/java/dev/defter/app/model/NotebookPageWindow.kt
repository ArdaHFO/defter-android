package dev.defter.app.model

/** Ordered, bounded working set. Viewport pages precede speculative neighbor prefetch. */
internal object NotebookPageWindow {
    const val CAPACITY = 5

    fun select(pageIds: List<String>, centerId: String, visibleIds: List<String>, activeId: String?): List<String> {
        if (pageIds.isEmpty()) return emptyList()
        val center = pageIds.indexOf(centerId).takeIf { it >= 0 } ?: 0
        val selected = linkedSetOf<String>()
        activeId?.takeIf { it in pageIds }?.let(selected::add)
        selected.add(pageIds[center])
        // Allocate only for the small viewport set, never a full notebook-sized index map
        // on every pan. Outward scanning orders visible candidates by distance without sorting.
        val visible = visibleIds.toMutableSet().apply { removeAll(selected) }
        var visibleDistance = 1
        while (visible.isNotEmpty() && selected.size < CAPACITY && visibleDistance < pageIds.size) {
            pageIds.getOrNull(center - visibleDistance)?.let { if (visible.remove(it)) selected.add(it) }
            pageIds.getOrNull(center + visibleDistance)?.let { if (selected.size < CAPACITY && visible.remove(it)) selected.add(it) }
            visibleDistance++
        }
        var distance = 1
        while (selected.size < minOf(CAPACITY, pageIds.size) && distance < pageIds.size) {
            pageIds.getOrNull(center - distance)?.let { if (selected.size < CAPACITY) selected.add(it) }
            pageIds.getOrNull(center + distance)?.let { if (selected.size < CAPACITY) selected.add(it) }
            distance++
        }
        return selected.toList()
    }
}
