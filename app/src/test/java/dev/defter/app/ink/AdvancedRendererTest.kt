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

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdvancedRendererTest {
    @Test fun pdfBitmapRendersUnderEditableInkWithoutBecomingAnElement() {
        val background = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val output = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val page = PageDocument(width = 100f, height = 100f, template = PaperTemplate.BLANK,
                pdfBackground = PdfBackground(assetId = "pdf", pageIndex = 2, widthPoints = 100f, heightPoints = 100f),
                elements = listOf(StrokeElement(points = listOf(InkPoint(10f, 50f), InkPoint(90f, 50f)), color = Color.BLACK, width = 8f)))
            PageRenderer.draw(Canvas(output), page, mapOf("pdf:pdf:2" to background))
            assertEquals(Color.YELLOW, output.getPixel(20, 20))
            assertEquals(Color.BLACK, output.getPixel(50, 50))
            assertEquals(1, page.elements.size)
        } finally { background.recycle(); output.recycle() }
    }

    @Test fun croppedRotatedImageUsesTheChosenSourcePixelsAndRestoresCanvasState() {
        val asset = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            for (y in 0 until 10) for (x in 10 until 20) setPixel(x, y, Color.BLUE)
        }
        val output = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        try {
            val image = ImageElement(assetId = "image", x = 50f, y = 60f, width = 100f, height = 40f,
                rotationDegrees = 90f, cropLeft = .5f, cropRight = 1f)
            val point = StrokeElement(points = listOf(InkPoint(20f, 20f)), width = 8f, color = Color.BLACK)
            val page = PageDocument(width = 200f, height = 200f, template = PaperTemplate.BLANK, backgroundColor = Color.WHITE, elements = listOf(image, point))
            PageRenderer.draw(Canvas(output), page, mapOf("image" to asset))
            assertEquals(Color.BLUE, output.getPixel(100, 40))
            assertEquals(Color.WHITE, output.getPixel(60, 80))
            assertEquals(Color.BLACK, output.getPixel(20, 20))
        } finally { asset.recycle(); output.recycle() }
    }

    @Test fun partialEraserLeavesVisibleEndsAndAnActuallyBlankGap() {
        val stroke = StrokeElement(points = listOf(InkPoint(10f, 50f), InkPoint(190f, 50f)), color = Color.BLACK, width = 6f)
        val parts = InkEditing.erased(listOf(stroke), 100f, 50f, 10f, EraserMode.PARTIAL, EraserFilter.ALL)
        val output = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        try {
            PageRenderer.draw(Canvas(output), PageDocument(width = 200f, height = 100f, template = PaperTemplate.BLANK, backgroundColor = Color.WHITE, elements = parts), emptyMap())
            assertEquals(Color.WHITE, output.getPixel(100, 50))
            assertEquals(Color.BLACK, output.getPixel(30, 50))
            assertEquals(Color.BLACK, output.getPixel(170, 50))
        } finally { output.recycle() }
    }
}
