package dev.defter.app.audio

import org.junit.Assert.*
import org.junit.Test

class AudioTimelineTest {
    @Test fun seekAcrossTenMinutePartsUsesGlobalContinuousTime() {
        val timeline = AudioTimeline(listOf(600_000, 600_000, 37_450))
        assertEquals(1_237_450L, timeline.durationMs)
        assertEquals(AudioTimeline.Position(0, 599_999), timeline.locate(599_999))
        assertEquals(AudioTimeline.Position(1, 0), timeline.locate(600_000))
        assertEquals(AudioTimeline.Position(2, 12_345), timeline.locate(1_212_345))
        assertEquals(1_212_345L, timeline.globalPosition(2, 12_345))
    }

    @Test fun seekingOutsideRecordingClampsWithoutLosingFinalPart() {
        val timeline = AudioTimeline(listOf(100, 200))
        assertEquals(AudioTimeline.Position(0, 0), timeline.locate(Long.MIN_VALUE))
        assertEquals(AudioTimeline.Position(1, 200), timeline.locate(Long.MAX_VALUE))
        assertEquals(300L, timeline.globalPosition(1, 999))
        assertEquals(100L, timeline.globalPosition(1, -999))
    }

    @Test fun emptyRecordingHasNoSeekTarget() {
        val timeline = AudioTimeline(emptyList())
        assertEquals(0L, timeline.durationMs)
        assertNull(timeline.locate(0))
    }

    @Test fun timelineDoesNotOverflowForLongRecordings() {
        val timeline = AudioTimeline(listOf(Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong()))
        assertEquals(4_294_967_294L, timeline.durationMs)
        assertEquals(AudioTimeline.Position(1, 10), timeline.locate(Int.MAX_VALUE.toLong() + 10))
        assertThrows(ArithmeticException::class.java) { AudioTimeline(listOf(Long.MAX_VALUE, 1)) }
    }

    @Test fun invalidOrZeroDurationPartsAreRejectedRatherThanShiftAllTimestamps() {
        assertThrows(IllegalArgumentException::class.java) { AudioTimeline(listOf(100, 0, 300)) }
        assertThrows(IllegalArgumentException::class.java) { AudioTimeline(listOf(-1)) }
    }

    @Test fun playbackSpeedRejectsNonfiniteValuesAndUnsupportedRange() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f, 2.01f).forEach {
            assertThrows(IllegalArgumentException::class.java) { validatedPlaybackSpeed(it) }
        }
        assertEquals(.5f, validatedPlaybackSpeed(.5f), 0f)
        assertEquals(2f, validatedPlaybackSpeed(2f), 0f)
    }

    @Test fun recordingTimeExcludesPausesAndReconcilesToActualSavedDuration() {
        val clock = RecordingClock()
        clock.start(0, 1_000)
        assertEquals(500L, clock.elapsed(1_500))
        clock.settle(480) // Encoded duration is authoritative after stop.
        assertEquals(480L, clock.elapsed(30_000))
        clock.start(480, 30_000)
        assertEquals(980L, clock.elapsed(30_500))
    }

    @Test fun clockNeverSubtractsCompletedAudioIfTimestampGoesBackwards() {
        val clock = RecordingClock()
        clock.start(10_000, 5_000)
        assertEquals(10_000L, clock.elapsed(4_000))
    }

    @Test fun pendingFinalizationRemainsBusyButInterruptedErrorCanStartNewRecording() {
        assertTrue(AudioRecorderState(phase = AudioRecorderPhase.FINALIZING).isBusy)
        assertTrue(AudioRecorderState(phase = AudioRecorderPhase.PAUSED).isBusy)
        assertFalse(AudioRecorderState(phase = AudioRecorderPhase.ERROR).isBusy)
        assertFalse(AudioRecorderState(phase = AudioRecorderPhase.IDLE).isRecording)
    }
}
