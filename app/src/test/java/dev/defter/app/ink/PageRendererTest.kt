package dev.defter.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.defter.app.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageRendererTest {
    @Test fun rendersPaperInkHighlightShapesAndImagesWithoutChangingDocument() {
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF406E60.toInt()) }
        val page = PageDocument(id = "render", width = 600f, height = 800f, template = PaperTemplate.BLANK,
            elements = listOf(
                TextElement(text = "Ders notlarım", x = 50f, y = 45f, size = 35f),
                StrokeElement(points = listOf(InkPoint(50f, 125f), InkPoint(430f, 125f)), color = 0xFFF5CA4C.toInt(), width = 26f, kind = InkKind.HIGHLIGHTER),
                TextElement(text = "Küçük adımlar, kalıcı bilgiler.", x = 50f, y = 103f, size = 22f),
                ShapeElement(shape = ShapeKind.RECTANGLE, startX = 50f, startY = 200f, endX = 250f, endY = 330f, color = 0xFF406E60.toInt(), width = 5f),
                ShapeElement(shape = ShapeKind.ARROW, startX = 280f, startY = 265f, endX = 370f, endY = 265f, width = 5f),
                ShapeElement(shape = ShapeKind.ELLIPSE, startX = 400f, startY = 200f, endX = 530f, endY = 330f, color = 0xFFBF7252.toInt(), width = 5f),
                ImageElement(assetId = "image", x = 60f, y = 390f, width = 150f, height = 120f),
                StrokeElement(points = (0..70).map { i -> InkPoint(55f + i * 6.5f, 600f + kotlin.math.sin(i * 0.17f) * 28f, i / 70f) }, color = 0xFF3B597C.toInt(), width = 9f)
            ))
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        PageRenderer.draw(Canvas(bitmap), page, mapOf("image" to image))
        assertEquals(page.backgroundColor, bitmap.getPixel(590, 790))
        assertEquals(0xFF406E60.toInt(), bitmap.getPixel(100, 440))
        assertNotEquals(page.backgroundColor, bitmap.getPixel(50, 250))
        assertNotEquals(page.backgroundColor, bitmap.getPixel(300, 125))
        assertEquals(0L, page.revision)
        assertEquals(8, page.elements.size)
        System.getProperty("defter.screenshotDir")?.let { directory ->
            val target = File(directory).apply { mkdirs() }
            File(target, "page-renderer.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        image.recycle()
        bitmap.recycle()
    }

    @Test fun exportClipsMarksToPageAndKeepsOutsideCanvasUntouched() {
        val page = PageDocument(width = 100f, height = 100f, template = PaperTemplate.BLANK,
            elements = listOf(StrokeElement(points = listOf(InkPoint(50f, 50f), InkPoint(180f, 50f)), width = 8f)))
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFCC1122.toInt())
        PageRenderer.draw(Canvas(bitmap), page, emptyMap())
        assertEquals(0xFFCC1122.toInt(), bitmap.getPixel(130, 50))
        assertNotEquals(page.backgroundColor, bitmap.getPixel(75, 50))
        bitmap.recycle()
    }

    @Test fun darkPaperUsesSubtleVisibleGridAndDots() {
        val background = 0xFF202D27.toInt()
        for (template in listOf(PaperTemplate.GRID, PaperTemplate.DOTS)) {
            val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            PageRenderer.draw(Canvas(bitmap), PageDocument(width = 200f, height = 200f,
                template = template, backgroundColor = background), emptyMap())
            val mark = bitmap.getPixel(64, if (template == PaperTemplate.GRID) 90 else 64)
            assertTrue("Template must remain visible", Color.luminance(mark) > Color.luminance(background))
            assertTrue("Template must not overwhelm dark paper", Color.luminance(mark) < 0.35f)
            assertEquals(background, bitmap.getPixel(190, 190))
            bitmap.recycle()
        }
    }

    @Test fun notebookViewportDoesNotPaintOutsideItsViewBounds() {
        val view = NotebookCanvas(RuntimeEnvironment.getApplication())
        val page = PageDocument(width = 200f, height = 300f, template = PaperTemplate.DOTS)
        view.document = page
        // Width-fit produces a 300px-tall page in a 132px-tall viewport.
        view.layout(0, 0, 232, 132)
        val marker = 0xFFCC1122.toInt()
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(marker)
        view.draw(Canvas(bitmap))
        assertEquals(page.backgroundColor, bitmap.getPixel(100, 100))
        assertEquals("Page must not overlap a footer below AndroidView", marker, bitmap.getPixel(100, 160))
        assertEquals("Page must not overlap a sibling beside AndroidView", marker, bitmap.getPixel(250, 100))
        assertEquals(marker, bitmap.getPixel(100, 350))
        bitmap.recycle()
    }
}
