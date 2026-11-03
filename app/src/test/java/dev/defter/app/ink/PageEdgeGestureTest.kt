package dev.defter.app.ink

import org.junit.Assert.*
import org.junit.Test

class PageEdgeGestureTest {
    @Test fun onlyAdditionalDistanceAtThresholdArmsNavigation() {
        val pull = PageEdgePull(96f)
        pull.addUnconsumed(-95f, canPrevious = true, canNext = true)
        assertFalse(pull.progress!!.armed)
        assertNull(pull.finish())
        pull.addUnconsumed(-96f, canPrevious = true, canNext = true)
        assertTrue(pull.progress!!.armed)
        assertEquals(PageEdgeDirection.NEXT, pull.finish())
        assertNull(pull.finish())
        assertNull(pull.progress)
    }

    @Test fun hysteresisKeepsSmallPullbackArmedButLargerPullbackCancels() {
        val pull = PageEdgePull(96f)
        pull.addUnconsumed(120f, true, true)
        assertEquals(0f, pull.consumeReversal(-30f), 0f)
        assertTrue(pull.progress!!.armed) // 90px remains, above the64px release point.
        assertEquals(0f, pull.consumeReversal(-30f), 0f)
        assertFalse(pull.progress!!.armed)
        assertNull(pull.finish())
    }

    @Test fun reversalRefundsPullBeforePanningContent() {
        val pull = PageEdgePull(96f)
        pull.addUnconsumed(-100f, true, true)
        assertEquals(0f, pull.consumeReversal(40f), 0f)
        assertEquals(60f / 96f, pull.progress!!.fraction, .001f)
        assertEquals(20f, pull.consumeReversal(80f), .001f)
        assertNull(pull.progress)
    }

    @Test fun availabilityAndResetPreventUnexpectedNavigation() {
        val pull = PageEdgePull(96f)
        pull.addUnconsumed(140f, canPrevious = false, canNext = true)
        assertNull(pull.finish())
        pull.addUnconsumed(-140f, canPrevious = true, canNext = false)
        assertNull(pull.finish())
        pull.addUnconsumed(-140f, true, true)
        pull.reset()
        assertNull(pull.finish())
    }

    @Test fun pullIsCappedAndCanBeWithdrawnWithoutHundredsOfPixels() {
        val pull = PageEdgePull(96f)
        pull.addUnconsumed(-10_000f, true, true)
        assertEquals(1f, pull.progress!!.fraction, 0f)
        assertEquals(6f, pull.consumeReversal(150f), .001f)
        assertNull(pull.finish())
    }

    @Test fun directionChangesDoNotReuseAnArmedPreviousPull() {
        val pull = PageEdgePull(96f)
        pull.addUnconsumed(120f, true, true)
        pull.addUnconsumed(-30f, true, true)
        assertEquals(PageEdgeDirection.NEXT, pull.progress!!.direction)
        assertFalse(pull.progress!!.armed)
        assertNull(pull.finish())
    }

    @Test fun nonFiniteSamplesCancelInsteadOfTriggering() {
        val pull = PageEdgePull(96f)
        pull.addUnconsumed(120f, true, true)
        pull.addUnconsumed(Float.NaN, true, true)
        assertNull(pull.finish())
        pull.addUnconsumed(-120f, true, true)
        assertEquals(0f, pull.consumeReversal(Float.POSITIVE_INFINITY), 0f)
        assertNull(pull.finish())
    }
}
