package dev.defter.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native rendered pixels are the oracle, independently of the segment visitor. */
@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmoothedHitGeometryTest {
    private fun curvedStroke(kind: InkKind) = StrokeElement(points = listOf(
        InkPoint(100f, 100f), InkPoint(300f, 500f), InkPoint(500f, 100f)
    ), color = Color.BLACK, width = 4f, kind = kind)

    @Test fun visibleSmoothedPenCurveCanBeSelectedAndErased() = assertVisibleCurveIsHittable(InkKind.PEN)

    @Test fun visibleSmoothedHighlighterCurveCanBeSelectedAndErased() = assertVisibleCurveIsHittable(InkKind.HIGHLIGHTER)

    @Test fun invisibleControlVertexCannotEraseAStroke() {
        val element = curvedStroke(InkKind.PEN)
        rendered(element) { bitmap ->
            assertEquals("The middle input is a control vertex, not visible ink", 0, Color.alpha(bitmap.getPixel(300, 500)))
            assertFalse(ElementGeometry.hitTest(element, 300f, 500f, 12f))
        }
    }

    @Test fun pressureHitRadiusMatchesRenderedSegmentAndTailWidths() {
        val element = StrokeElement(points = listOf(InkPoint(100f, 100f, 0f), InkPoint(500f, 100f, 1f)),
            color = Color.BLACK, width = 100f)
        rendered(element) { bitmap ->
            assertTrue(Color.alpha(bitmap.getPixel(150, 125)) > 0)
            assertTrue(ElementGeometry.hitTest(element, 150f, 125f, 0f))
            assertEquals("Segment width uses the mean pressure, not the widest input", 0, Color.alpha(bitmap.getPixel(150, 140)))
            assertFalse(ElementGeometry.hitTest(element, 150f, 140f, 0f))
            assertTrue("The tail uses the final sample pressure", Color.alpha(bitmap.getPixel(450, 145)) > 0)
            assertTrue(ElementGeometry.hitTest(element, 450f, 145f, 0f))
        }
    }

    @Test fun singleDotUsesTheSamePressureInDrawingAndHitTesting() {
        val dot = StrokeElement(points = listOf(InkPoint(100f, 100f, 0f)), color = Color.BLACK, width = 100f)
        rendered(dot) { bitmap ->
            assertTrue(Color.alpha(bitmap.getPixel(100, 115)) > 0)
            assertTrue(ElementGeometry.hitTest(dot, 100f, 115f, 0f))
            assertEquals(0, Color.alpha(bitmap.getPixel(100, 120)))
            assertFalse(ElementGeometry.hitTest(dot, 100f, 120f, 0f))
        }
    }

    @Test fun extremeValidCoordinatesRemainFiniteAndDoNotHitTheControlVertex() {
        val stroke = StrokeElement(points = listOf(
            InkPoint(-1_000_000f, -1_000_000f), InkPoint(0f, 1_000_000f), InkPoint(1_000_000f, -1_000_000f)
        ), width = 1f)
        // The second quadratic's midpoint is exactly (0, 500000).
        assertTrue(ElementGeometry.hitTest(stroke, 0f, 500_000f, 1f))
        assertFalse(ElementGeometry.hitTest(stroke, 0f, 1_000_000f, 1f))
        assertFalse(ElementGeometry.hitTest(stroke, 900_000f, 900_000f, 1f))
    }

    private fun assertVisibleCurveIsHittable(kind: InkKind) {
        val element = curvedStroke(kind)
        rendered(element) { bitmap ->
            assertTrue("Native renderer must confirm actual ink", Color.alpha(bitmap.getPixel(300, 400)) > 0)
            assertTrue("Hit follows the rendered quadratic", ElementGeometry.hitTest(element, 300f, 400f, 12f))
        }
    }

    private fun rendered(stroke: StrokeElement, assertions: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        try {
            PageRenderer.drawElement(Canvas(bitmap), stroke, emptyMap())
            assertions(bitmap)
        } finally { bitmap.recycle() }
    }
}
