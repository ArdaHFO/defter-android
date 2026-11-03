package dev.defter.app.audio

import dev.defter.app.localization.tr

/** One continuous playback clock over separately committed recording parts. */
internal class AudioTimeline(durationsMs: List<Long>) {
    private val durations = durationsMs.toList()
    private val starts = LongArray(durations.size)
    val durationMs: Long

    init {
        var total = 0L
        durations.forEachIndexed { index, duration ->
            require(duration > 0) { tr("Ses parçasının süresi geçersiz.") }
            starts[index] = total
            total = Math.addExact(total, duration)
        }
        durationMs = total
    }

    data class Position(val partIndex: Int, val partPositionMs: Long)

    fun locate(positionMs: Long): Position? {
        if (durations.isEmpty()) return null
        val position = positionMs.coerceIn(0, durationMs)
        // At an internal boundary, select the next part, not the preceding part's EOF.
        var low = 0
        var high = starts.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (starts[middle] <= position) low = middle else high = middle - 1
        }
        return Position(low, (position - starts[low]).coerceAtMost(durations[low]))
    }

    fun globalPosition(partIndex: Int, positionMs: Long): Long =
        starts[partIndex] + positionMs.coerceIn(0, durations[partIndex])
}

internal fun validatedPlaybackSpeed(value: Float): Float {
    require(value.isFinite() && value in 0.5f..2f) { tr("Oynatma hızı 0,5–2 arasında olmalı.") }
    return value
}

/** Monotonic recording time. Pauses and wall-clock changes never add recorded audio. */
internal class RecordingClock {
    private var completedMs = 0L
    private var startedAtMs: Long? = null

    fun start(completedDurationMs: Long, nowMs: Long) {
        require(completedDurationMs >= 0)
        completedMs = completedDurationMs
        startedAtMs = nowMs
    }

    fun settle(completedDurationMs: Long) {
        require(completedDurationMs >= 0)
        completedMs = completedDurationMs
        startedAtMs = null
    }

    fun elapsed(nowMs: Long): Long = completedMs +
        (startedAtMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L)
}
