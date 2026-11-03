package dev.defter.app.audio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [35], manifest = Config.NONE)
class WhisperTranscriberTest {
    @Test fun nativeUtf8TextPreservesTurkishAndEmojiWhileOffsetsAreClampedToChunk() {
        val result = WhisperTranscriber.parseSegments("""[
          {"startMs":-20,"endMs":2000,"text":"  İğne, ölçüm ve ışık 😀  "},
          {"startMs":2500,"endMs":9000,"text":"ikinci"},
          {"startMs":3000,"endMs":9000,"text":"decoder padding"},
          {"startMs":400,"endMs":400,"text":"zero duration"},
          {"startMs":400,"endMs":300,"text":"invalid"},
          {"startMs":0,"endMs":20,"text":"  "}
        ]""".toByteArray(), 30_000, 33_000)
        assertEquals(2, result.size)
        assertEquals(WhisperSegment(30_000, 32_000, "İğne, ölçüm ve ışık 😀"), result[0])
        assertEquals(WhisperSegment(32_500, 33_000, "ikinci"), result[1])
    }

    @Test fun silentChunkRemainsAnEmptyTranscript() {
        assertTrue(WhisperTranscriber.parseSegments("[]".toByteArray(), 30_000, 60_000).isEmpty())
    }
}
