package dev.defter.app.ink

import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE)
class GeometryBudgetTest {
    private val smallLasso = listOf(InkPoint(0f, 0f), InkPoint(100f, 0f), InkPoint(100f, 100f), InkPoint(0f, 100f))

    @Test fun distantPathologicalCurveIsRejectedBeforeFlattening() {
        val far = StrokeElement(id = "far", points = (0..33_000).map { InkPoint(10_000f + (it % 2) * 1_000, 10_000f + (it % 3) * 1_000) })
        assertThrows(GeometryLimitException::class.java) { InkEditing.flattened(far) }
        val near = StrokeElement(id = "near", points = listOf(InkPoint(20f, 20f), InkPoint(40f, 40f)))
        assertEquals(setOf("near"), InkEditing.lassoSelection(listOf(far, near), smallLasso))
        assertFalse(far.isPolyline)
        assertEquals(33_001, far.points.size)
    }

    @Test fun renderedEndpointCanSelectComplexCurveWithoutFlatteningControlPoints() {
        val curve = StrokeElement(id = "curve", points = listOf(InkPoint(20f, 20f)) +
            (1..33_000).map { InkPoint(10_000f + (it % 2) * 1_000, 10_000f + (it % 3) * 1_000) })
        assertEquals(setOf("curve"), InkEditing.lassoSelection(listOf(curve), smallLasso))
    }

    @Test fun densePolygonAndPathStopAtAComparisonBudgetInsteadOfQuadraticWork() {
        val polygon = (0 until 2_048).map { val angle = 2 * PI * it / 2_048; InkPoint(100f + 100f * cos(angle).toFloat(), 100f + 100f * sin(angle).toFloat()) }
        // Inside the polygon bounding box but outside its circular outline.
        val outside = StrokeElement(points = (0..1_000).map { InkPoint(190f + it % 2, 190f) }, isPolyline = true)
        assertThrows(GeometryLimitException::class.java) { InkEditing.lassoSelection(listOf(outside), polygon) }
        assertEquals(1_001, outside.points.size)
    }

    @Test fun excessiveLassoInputIsRejectedBeforeVisitingElements() {
        val polygon = List(InkEditing.MAX_LASSO_POINTS + 1) { InkPoint(it.toFloat(), 0f) }
        assertThrows(GeometryLimitException::class.java) { InkEditing.lassoSelection(emptyList(), polygon) }
    }
}
