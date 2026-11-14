package dev.defter.app.ui

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class PointMenuPositionTest {
    @Test fun edgeContactsFlipAndStayInsideTheWindow() {
        for (point in listOf(IntOffset(0, 0), IntOffset(319, 719), IntOffset(319, 0), IntOffset(0, 719), IntOffset(160, 360))) {
            val result = PointMenuPosition(point, 8).calculatePosition(IntRect(0, 0, 0, 0), IntSize(320, 720), LayoutDirection.Ltr, IntSize(280, 400))
            assertTrue(result.x >= 8 && result.x + 280 <= 312)
            assertTrue(result.y >= 8 && result.y + 400 <= 712)
        }
    }
    @Test fun rightToLeftMenusPreferTheOtherSideOfTheContact() {
        val point = IntOffset(500, 200)
        val result = PointMenuPosition(point, 8).calculatePosition(IntRect(0, 0, 0, 0), IntSize(800, 800), LayoutDirection.Rtl, IntSize(280, 400))
        assertEquals(220, result.x); assertEquals(208, result.y)
    }
    @Test fun cutoutsAndSystemBarsRemainOutsideTheMenu() {
        val result = PointMenuPosition(IntOffset(799, 399), 8, 48, 24, 0, 24)
            .calculatePosition(IntRect(0, 0, 0, 0), IntSize(800, 400), LayoutDirection.Ltr, IntSize(280, 300))
        assertTrue(result.x >= 56 && result.x + 280 <= 792)
        assertTrue(result.y >= 32 && result.y + 300 <= 368)
    }
}
