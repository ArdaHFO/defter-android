package dev.defter.app.audio

import dev.defter.app.model.TranscriptSegment
import java.util.Locale

object TranscriptTools {
    fun clock(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }

    fun plain(segments: List<TranscriptSegment>): String = segments.joinToString("\n\n") {
        "[${clock(it.startMs)}] ${it.text}"
    }

    fun srt(segments: List<TranscriptSegment>): String = segments.filter { it.text.isNotBlank() }.mapIndexed { index, segment ->
        "${index + 1}\n${srtTime(segment.startMs)} --> ${srtTime(segment.endMs)}\n${segment.text.trim()}\n"
    }.joinToString("\n")

    private fun srtTime(milliseconds: Long): String {
        val value = milliseconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", value / 3_600_000, value / 60_000 % 60, value / 1000 % 60, value % 1000)
    }
}
