package dev.defter.app.ink

import dev.defter.app.model.ImageElement
import dev.defter.app.model.InkKind
import dev.defter.app.model.InkPoint
import dev.defter.app.model.ShapeElement
import dev.defter.app.model.ShapeKind
import dev.defter.app.model.StrokeElement
import dev.defter.app.model.TextElement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE)
class ElementGeometryTest {
    @Test fun hitsMiddleOfSparseStrokeButNotDistantPoint() {
        val stroke = StrokeElement(points = listOf(InkPoint(0f, 0f), InkPoint(100f, 0f)), width = 4f)
        assertTrue(ElementGeometry.hitTest(stroke, 50f, 3f, 2f))
        assertFalse(ElementGeometry.hitTest(stroke, 50f, 8f, 2f))
        assertFalse(ElementGeometry.hitTest(stroke, 120f, 0f, 2f))
    }

    @Test fun emptyAndSinglePointStrokesAreSafe() {
        assertFalse(ElementGeometry.hitTest(StrokeElement(points = emptyList()), 0f, 0f, 50f))
        val dot = StrokeElement(points = listOf(InkPoint(-8f, -4f)), width = 8f)
        assertTrue(ElementGeometry.hitTest(dot, -8f, -1f, 0f))
        assertFalse(ElementGeometry.hitTest(dot, 2f, 2f, 0f))
        assertTrue(ElementGeometry.bounds(StrokeElement(points = emptyList())).isEmpty)
    }

    @Test fun rectangleHitsBorderAndDoesNotEraseEmptyInterior() {
        val rectangle = ShapeElement(shape = ShapeKind.RECTANGLE, startX = 100f, startY = 80f, endX = -100f, endY = -80f, width = 4f)
        assertTrue(ElementGeometry.hitTest(rectangle, -101f, 0f, 1f))
        assertTrue(ElementGeometry.hitTest(rectangle, 0f, -79f, 1f))
        assertFalse(ElementGeometry.hitTest(rectangle, 0f, 0f, 10f))
        assertEquals(-102f, ElementGeometry.bounds(rectangle).left, 0.001f)
    }

    @Test fun ellipseHitsBoundaryAndNotInterior() {
        val ellipse = ShapeElement(shape = ShapeKind.ELLIPSE, startX = 100f, startY = 40f, endX = -100f, endY = -40f, width = 2f)
        assertTrue(ElementGeometry.hitTest(ellipse, 0f, 40f, 2f))
        assertTrue(ElementGeometry.hitTest(ellipse, -100f, 0f, 2f))
        assertFalse(ElementGeometry.hitTest(ellipse, 0f, 0f, 5f))
        assertFalse(ElementGeometry.hitTest(ellipse, 100f, 40f, 3f))
    }

    @Test fun arrowIncludesItsHeadInHitTestAndBounds() {
        val arrow = ShapeElement(shape = ShapeKind.ARROW, startX = 0f, startY = 0f, endX = 100f, endY = 0f, width = 4f)
        val (a, b) = ElementGeometry.arrowHeadPoints(arrow)
        assertTrue(ElementGeometry.hitTest(arrow, a.x, a.y, 0f))
        assertTrue(ElementGeometry.hitTest(arrow, b.x, b.y, 0f))
        val bounds = ElementGeometry.bounds(arrow)
        assertTrue(bounds.top < b.y)
        assertTrue(bounds.bottom > a.y)
    }

    @Test fun translationPreservesIdentityAppearanceAndOriginalData() {
        val stroke = StrokeElement(id = "stroke", points = listOf(InkPoint(-3f, 8f, 0.23f)), color = 0x556677, width = 11f, kind = InkKind.HIGHLIGHTER)
        val moved = ElementGeometry.translated(stroke, 100f, -20f) as StrokeElement
        assertEquals(stroke.id, moved.id)
        assertEquals(stroke.color, moved.color)
        assertEquals(stroke.kind, moved.kind)
        assertEquals(stroke.width, moved.width, 0f)
        assertEquals(InkPoint(97f, -12f, 0.23f), moved.points.single())
        assertEquals(InkPoint(-3f, 8f, 0.23f), stroke.points.single())
    }

    @Test fun imagesAndTextMoveWithoutChangingContents() {
        val image = ImageElement(id = "image", assetId = "asset", x = -20f, y = 10f, width = 300f, height = 200f)
        assertTrue(ElementGeometry.hitTest(image, 0f, 30f, 0f))
        val movedImage = ElementGeometry.translated(image, 3f, 4f) as ImageElement
        assertEquals("asset", movedImage.assetId)
        assertEquals(-17f, movedImage.x, 0f)
        val text = TextElement(id = "text", text = "Birinci\nİkinci", x = 20f, y = 30f, size = 16f)
        val movedText = ElementGeometry.translated(text, -10f, -10f) as TextElement
        assertEquals(text.text, movedText.text)
        assertEquals(10f, movedText.x, 0f)
        assertEquals(40f, ElementGeometry.bounds(text).height(), 0.01f)
    }
}
