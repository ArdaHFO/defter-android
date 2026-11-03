package dev.defter.app.ink

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

enum class PageEdgeDirection { PREVIOUS, NEXT }

/** Fraction describes the additional pull beyond the page edge, independent of page zoom. */
data class PageEdgeProgress(val direction: PageEdgeDirection, val fraction: Float, val armed: Boolean)

/** Accumulates only pan distance that the viewport could not consume at an edge. */
internal class PageEdgePull(
    private val thresholdPx: Float,
    private val releasePx: Float = thresholdPx * 2f / 3f
) {
    init {
        require(thresholdPx.isFinite() && thresholdPx > 0f)
        require(releasePx.isFinite() && releasePx > 0f && releasePx <= thresholdPx)
    }

    private var distancePx = 0f // Positive is a pull down at the top, negative is up at the bottom.
    private var armed = false
    val progress: PageEdgeProgress?
        get() = if (distancePx == 0f) null else PageEdgeProgress(
            if (distancePx > 0f) PageEdgeDirection.PREVIOUS else PageEdgeDirection.NEXT,
            (abs(distancePx) / thresholdPx).coerceIn(0f, 1f), armed
        )

    fun reset() {
        distancePx = 0f
        armed = false
    }

    /** Pulling back first refunds the accumulated edge pull; the remainder can pan the page. */
    fun consumeReversal(deltaY: Float): Float {
        if (!deltaY.isFinite()) { reset(); return 0f }
        if (distancePx == 0f || deltaY == 0f || sign(deltaY) == sign(distancePx)) return deltaY
        val direction = sign(distancePx)
        val refund = min(abs(deltaY), abs(distancePx))
        distancePx -= direction * refund
        updateArmed()
        return deltaY + direction * refund
    }

    fun addUnconsumed(deltaY: Float, canPrevious: Boolean, canNext: Boolean) {
        if (!deltaY.isFinite()) { reset(); return }
        if (deltaY == 0f) return
        if ((deltaY > 0f && !canPrevious) || (deltaY < 0f && !canNext)) { reset(); return }
        if (distancePx != 0f && sign(deltaY) != sign(distancePx)) reset()
        val cap = thresholdPx * 1.5f
        distancePx = (distancePx + deltaY).coerceIn(-cap, cap)
        updateArmed()
    }

    /** Consume the result before calling external navigation code, so duplicate UP cannot repeat it. */
    fun finish(): PageEdgeDirection? {
        val direction = progress?.takeIf { it.armed }?.direction
        reset()
        return direction
    }

    private fun updateArmed() {
        val distance = abs(distancePx)
        if (distance == 0f || distance < releasePx) armed = false
        else if (distance >= thresholdPx) armed = true
    }
}
