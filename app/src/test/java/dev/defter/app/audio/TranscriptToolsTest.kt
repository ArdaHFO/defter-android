package dev.defter.app.audio

import dev.defter.app.model.TranscriptSegment
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TranscriptToolsTest {
    @Test fun srtPreservesUnicodeAndPreciseHourTimestampsWithContinuousNumbering() {
        val segments = listOf(
            TranscriptSegment(startMs = 0, endMs = 900, text = "  "),
            TranscriptSegment(startMs = 3_661_007, endMs = 3_665_123, text = "  Öğretmen: çözüm doğru. 📝  "),
            TranscriptSegment(startMs = 7_200_000, endMs = 7_201_999, text = "İkinci satır\nAlt satır")
        )
        assertEquals("1\n01:01:01,007 --> 01:01:05,123\nÖğretmen: çözüm doğru. 📝\n\n2\n02:00:00,000 --> 02:00:01,999\nİkinci satır\nAlt satır\n", TranscriptTools.srt(segments))
        assertEquals("  Öğretmen: çözüm doğru. 📝  ", segments[1].text)
    }

    @Test fun plainTextIncludesReadableTimestampsAndDoesNotAlterManualText() {
        val segment = TranscriptSegment(startMs = 3_661_007, endMs = 3_665_123, text = "İşlem x² + y²\nElle düzeltilmiş", edited = true)
        assertEquals("[1:01:01] İşlem x² + y²\nElle düzeltilmiş", TranscriptTools.plain(listOf(segment)))
        assertTrue(segment.edited)
    }

    @Test fun timestampsRemainStableAcrossLocalesAndBeyondTwentyFourHours() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("00:00", TranscriptTools.clock(-1))
            assertEquals("59:59", TranscriptTools.clock(3_599_999))
            assertEquals("1:00:00", TranscriptTools.clock(3_600_000))
            assertEquals("25:01:01", TranscriptTools.clock(90_061_000))
            assertTrue(TranscriptTools.srt(listOf(TranscriptSegment(startMs = 90_061_001, endMs = 90_062_002, text = "Test")))
                .contains("25:01:01,001 --> 25:01:02,002"))
        } finally { Locale.setDefault(original) }
    }

    @Test fun emptyTranscriptProducesEmptyExports() {
        assertEquals("", TranscriptTools.srt(emptyList()))
        assertEquals("", TranscriptTools.plain(emptyList()))
    }
}
