package dev.defter.app.ink

import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.atan2

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AdvancedInkEditingTest {
    @Test fun partialEraserCutsSparseStrokeIntoTwoIndependentFragments() {
        val original = StrokeElement(id = "line", points = listOf(InkPoint(0f, 100f), InkPoint(200f, 100f)), width = 4f)
        val result = InkEditing.erased(listOf(original), 100f, 100f, 10f, EraserMode.PARTIAL, EraserFilter.ALL).filterIsInstance<StrokeElement>()
        assertEquals(2, result.size)
        assertEquals("line", result.first().id)
        assertEquals(2, result.map { it.id }.distinct().size)
        assertTrue(result.all { it.isPolyline })
        assertEquals(88f, result.first().points.last().x, .01f)
        assertEquals(112f, result.last().points.first().x, .01f)
        assertTrue(result.none { ElementGeometry.hitTest(it, 100f, 100f, 0f) })
        assertEquals(2, original.points.size)
        assertFalse(original.isPolyline)
    }

    @Test fun partialEraserUsesTheRenderedCurveInsteadOfItsControlPolygon() {
        val curve = StrokeElement(points = listOf(InkPoint(0f, 100f), InkPoint(100f, 0f), InkPoint(200f, 100f)), width = 4f)
        assertTrue(ElementGeometry.hitTest(curve, 100f, 25f, 1f))
        val result = InkEditing.erased(listOf(curve), 100f, 25f, 10f, EraserMode.PARTIAL, EraserFilter.ALL)
        assertEquals(2, result.size)
        assertTrue(result.none { ElementGeometry.hitTest(it, 100f, 25f, 0f) })
        assertTrue(result.any { ElementGeometry.hitTest(it, 0f, 100f, 0f) })
        assertTrue(result.any { ElementGeometry.hitTest(it, 200f, 100f, 0f) })
    }

    @Test fun eraserFiltersProtectOtherKindsImagesAndText() {
        val pen = StrokeElement(id = "pen", points = listOf(InkPoint(0f, 10f), InkPoint(100f, 10f)))
        val highlight = pen.copy(id = "highlight", kind = InkKind.HIGHLIGHTER)
        val shape = ShapeElement(id = "shape", shape = ShapeKind.LINE, startX = 0f, startY = 10f, endX = 100f, endY = 10f)
        val image = ImageElement(id = "image", assetId = "image-asset", x = 0f, y = 0f, width = 100f, height = 100f)
        val text = TextElement(id = "text", text = "A", x = 0f, y = 0f)
        val original = listOf(pen, highlight, shape, image, text)
        assertEquals(setOf("pen", "shape", "image", "text"), InkEditing.erased(original, 50f, 10f, 10f, EraserMode.STROKE, EraserFilter.HIGHLIGHTER).map { it.id }.toSet())
        assertEquals(setOf("highlight", "shape", "image", "text"), InkEditing.erased(original, 50f, 10f, 10f, EraserMode.STROKE, EraserFilter.PEN).map { it.id }.toSet())
        assertEquals(listOf(image, text), InkEditing.erased(original, 50f, 10f, 10f, EraserMode.STROKE, EraserFilter.ALL))
    }

    @Test fun partialShapeFragmentsRemainEligibleForShapeFilter() {
        val shape = ShapeElement(shape = ShapeKind.LINE, startX = 0f, startY = 100f, endX = 200f, endY = 100f)
        val parts = InkEditing.erased(listOf(shape), 100f, 100f, 10f, EraserMode.PARTIAL, EraserFilter.SHAPES)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it is StrokeElement && it.isShapeFragment })
        assertEquals(parts, InkEditing.erased(parts, 20f, 100f, 15f, EraserMode.STROKE, EraserFilter.PEN))
        assertEquals(1, InkEditing.erased(parts, 20f, 100f, 15f, EraserMode.STROKE, EraserFilter.SHAPES).size)
    }

    @Test fun missedEraserKeepsOriginalIdentityAndCurveData() {
        val stroke = StrokeElement(points = listOf(InkPoint(0f, 0f, .2f), InkPoint(50f, 80f, .9f)))
        val list = listOf(stroke)
        assertSame(list, InkEditing.erased(list, 1000f, 1000f, 10f, EraserMode.PARTIAL, EraserFilter.ALL))
        assertSame(list, InkEditing.erased(list, Float.NaN, 0f, 10f, EraserMode.PARTIAL, EraserFilter.ALL))
    }

    @Test fun lassoSelectsCrossingAndEnclosedElementsButNotEmptyBoundingArea() {
        val inside = TextElement(id = "text", text = "a", x = 40f, y = 40f, size = 10f)
        val crossing = StrokeElement(id = "cross", points = listOf(InkPoint(0f, 50f), InkPoint(200f, 50f)))
        val outside = ShapeElement(id = "outside", shape = ShapeKind.RECTANGLE, startX = 0f, startY = 0f, endX = 200f, endY = 200f)
        val polygon = listOf(InkPoint(20f, 20f), InkPoint(80f, 20f), InkPoint(80f, 80f), InkPoint(20f, 80f))
        assertEquals(setOf("text", "cross"), InkEditing.lassoSelection(listOf(inside, crossing, outside), polygon))
        assertTrue(InkEditing.lassoSelection(listOf(inside), polygon.take(2)).isEmpty())
    }

    @Test fun minimumImageAndMaximumTextSizeDoNotDriftOrDistort() {
        val image = ImageElement(assetId = "image", x = 40f, y = 50f, width = 10f, height = 20f)
        assertEquals(listOf(image), InkEditing.scaled(listOf(image), .9f, 600f, 800f))
        val text = TextElement(text = "Size limit", x = 100f, y = 200f, size = 200f)
        assertEquals(listOf(text), InkEditing.scaled(listOf(text), 1.1f, 600f, 800f))
    }

    @Test fun groupResizeUsesOneFactorForEveryElementAndKeepsCenter() {
        val image = ImageElement(assetId = "asset", x = 0f, y = 0f, width = 20f, height = 40f)
        val text = TextElement(text = "Text", x = 100f, y = 100f, size = 10f)
        val original = listOf(image, text)
        val result = InkEditing.scaled(original, .5f, 600f, 800f)
        val resized = result[0] as ImageElement
        assertEquals(16f, resized.width, .001f) // Text's minimum of 8 controls both elements.
        assertEquals(32f, resized.height, .001f)
        assertEquals(8f, (result[1] as TextElement).size, .001f)
        assertEquals(InkEditing.groupBounds(original)!!.centerX(), InkEditing.groupBounds(result)!!.centerX(), .01f)
        assertEquals(InkEditing.groupBounds(original)!!.centerY(), InkEditing.groupBounds(result)!!.centerY(), .01f)
    }

    @Test fun imageRotationHasMatchingHitRegionAndBounds() {
        val image = ImageElement(assetId = "asset", x = 100f, y = 100f, width = 200f, height = 40f, rotationDegrees = 90f)
        val bounds = ElementGeometry.bounds(image)
        assertEquals(40f, bounds.width(), .001f)
        assertEquals(200f, bounds.height(), .001f)
        assertTrue(ElementGeometry.hitTest(image, 200f, 30f, 0f))
        assertFalse(ElementGeometry.hitTest(image, 110f, 120f, 0f))
    }

    @Test fun mathSnappingSupportsAngleStepsAndEqualAxes() {
        val line = ShapeElement(shape = ShapeKind.LINE, startX = 10f, startY = 20f, endX = 10f, endY = 20f)
        val snapped = InkEditing.snapped(line, 110f, 40f, true, 15f)
        assertEquals(15.0, Math.toDegrees(atan2((snapped.endY - snapped.startY).toDouble(), (snapped.endX - snapped.startX).toDouble())), .001)
        val square = InkEditing.snapped(line.copy(shape = ShapeKind.RECTANGLE), -30f, 100f, true, 15f)
        assertEquals(-70f, square.endX, 0f)
        assertEquals(100f, square.endY, 0f)
        val circle = InkEditing.snapped(line.copy(shape = ShapeKind.ELLIPSE), 50f, 100f, true, 15f)
        assertEquals(circle.endX - circle.startX, circle.endY - circle.startY, 0f)
    }

    @Test fun copyOffsetKeepsPageSizedAndImportedOffPageGroupsWithinPageWherePossible() {
        val fullPage = ImageElement(assetId = "full", x = 0f, y = 0f, width = 600f, height = 800f)
        assertEquals(0f to 0f, InkEditing.copyOffset(listOf(fullPage), 600f, 800f))
        val imported = ImageElement(assetId = "offpage", x = 1_000f, y = -500f, width = 100f, height = 100f)
        val (dx, dy) = InkEditing.copyOffset(listOf(imported), 600f, 800f)
        val copied = InkEditing.copied(imported, dx, dy)
        val box = ElementGeometry.bounds(copied)
        assertTrue(box.left >= 0f && box.right <= 600f && box.top >= 0f && box.bottom <= 800f)
        assertEquals(100f, box.width(), 0f)
        assertEquals(100f, box.height(), 0f)
    }

    @Test fun oversizedCopyGroupRetainsVisibleOverlapAndEveryRelativePosition() {
        val first = ImageElement(assetId = "wide", x = 590f, y = 790f, width = 900f, height = 1_100f)
        val second = ImageElement(assetId = "inside", x = 690f, y = 890f, width = 50f, height = 60f)
        val original = listOf(first, second)
        val (dx, dy) = InkEditing.copyOffset(original, 600f, 800f)
        val copies = original.map { InkEditing.copied(it, dx, dy) as ImageElement }
        val bounds = InkEditing.groupBounds(copies)!!
        assertTrue(bounds.left <= 576f && bounds.right >= 24f)
        assertTrue(bounds.top <= 776f && bounds.bottom >= 24f)
        assertEquals(second.x - first.x, copies[1].x - copies[0].x, 0f)
        assertEquals(second.y - first.y, copies[1].y - copies[0].y, 0f)
        assertEquals(first.width, copies[0].width, 0f)
        assertEquals(first.height, copies[0].height, 0f)
    }
}
