package dev.defter.app.ink

/** Lightweight geometry only. Documents and bitmaps remain in the bounded caller-owned window. */
internal class PageStripLayout {
    data class Size(val width: Float, val height: Float)
    data class Slot(val id: String, val top: Float, val height: Float, val size: Size) {
        val bottom: Float get() = top + height
        val localScale: Float get() = WIDTH / size.width
    }
    data class Anchor(val id: String, val fraction: Float, val screenY: Float)
    private var slots: List<Slot> = emptyList()
    private var indices: Map<String, Int> = emptyMap()
    val height: Float get() = slots.lastOrNull()?.bottom ?: 0f

    fun rebuild(ids: List<String>, sizes: Map<String, Size>) {
        var top = 0f
        slots = ids.distinct().map { id ->
            val size = sizes[id]?.takeIf { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }
                ?: Size(WIDTH, DEFAULT_HEIGHT)
            Slot(id, top, WIDTH * size.height / size.width, size).also { top = it.bottom + GAP }
        }
        indices = slots.mapIndexed { index, slot -> slot.id to index }.toMap()
    }
    fun slot(id: String): Slot? = indices[id]?.let(slots::get)
    fun index(id: String): Int = indices[id] ?: -1
    fun at(y: Float): Slot? {
        val index = firstEndingAfter(y)
        return slots.getOrNull(index)?.takeIf { y >= it.top && y <= it.bottom }
    }
    fun nearest(y: Float): Slot? {
        val index = firstEndingAfter(y).coerceAtMost(slots.lastIndex)
        if (index < 0) return null
        val next = slots[index]
        val previous = slots.getOrNull(index - 1)
        return if (previous != null && y < next.top && y - previous.bottom < next.top - y) previous else next
    }
    fun visible(top: Float, bottom: Float): List<Slot> {
        if (bottom < top || slots.isEmpty()) return emptyList()
        val result = ArrayList<Slot>()
        var index = firstEndingAfter(top)
        while (index < slots.size && slots[index].top <= bottom) result += slots[index++]
        return result
    }
    private fun firstEndingAfter(y: Float): Int {
        var low = 0
        var high = slots.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (slots[mid].bottom < y) low = mid + 1 else high = mid
        }
        return low
    }
    companion object {
        const val WIDTH = 1240f
        const val DEFAULT_HEIGHT = 1754f
        const val GAP = 48f
    }
}
