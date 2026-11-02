package dev.defter.app.recognition

import dev.defter.app.model.InkKind
import dev.defter.app.model.InkPoint
import dev.defter.app.model.StrokeElement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HandwritingRecognizerTest {
    @Test fun preservesStrokeAndPointOrderButExcludesHighlighter() {
        val first = StrokeElement(points = listOf(InkPoint(11f, 22f), InkPoint(33f, 44f)))
        val marker = first.copy(id = "marker", kind = InkKind.HIGHLIGHTER)
        val last = StrokeElement(points = listOf(InkPoint(55f, 66f)))
        val ink = MlKitHandwritingRecognizer.buildInk(listOf(first, marker, last))
        assertEquals(2, ink.strokes.size)
        assertEquals(11f, ink.strokes[0].pointsInGlobalCoordinates[0].x, 0f)
        assertEquals(44f, ink.strokes[0].pointsInGlobalCoordinates[1].y, 0f)
        assertEquals(55f, ink.strokes[1].pointsInGlobalCoordinates[0].x, 0f)
    }

    @Test fun rejectsEmptyNonFiniteAndOversizedInkWithoutCallingTheModel() {
        assertThrows(IllegalArgumentException::class.java) { MlKitHandwritingRecognizer.buildInk(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { MlKitHandwritingRecognizer.buildInk(listOf(StrokeElement(points = listOf(InkPoint(Float.NaN, 1f))))) }
        assertThrows(IllegalArgumentException::class.java) { MlKitHandwritingRecognizer.buildInk(listOf(StrokeElement(points = List(200_001) { InkPoint(1f, 1f) }))) }
    }
}
