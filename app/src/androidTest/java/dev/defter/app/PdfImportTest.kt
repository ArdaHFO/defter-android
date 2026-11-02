package dev.defter.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.defter.app.data.DocumentRepository
import dev.defter.app.ink.PageRenderer
import dev.defter.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Real Android PdfRenderer/PdfDocument integration. Requires device/emulator; JVM tests cannot prove PDFium. */
@RunWith(AndroidJUnit4::class)
class PdfImportTest {
    @Test fun nativePdfImportInkOverlayTrashAndBackupPreserveBothOrientations() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = DocumentRepository(app)
        val before = repository.loadLibrary()
        val source = File(app.cacheDir, "pdf-import-${newId()}.pdf")
        val pdf = PdfDocument()
        try {
            val portrait = pdf.startPage(PdfDocument.PageInfo.Builder(300, 500, 1).create())
            portrait.canvas.drawColor(Color.RED); pdf.finishPage(portrait)
            val landscape = pdf.startPage(PdfDocument.PageInfo.Builder(500, 300, 2).create())
            landscape.canvas.drawColor(Color.BLUE); pdf.finishPage(landscape)
            source.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        val imported = repository.importPdf(Uri.fromFile(source), "PDF içe aktarma testi ${System.currentTimeMillis()}", null)
        assertEquals(2, imported.pageIds.size)
        assertTrue(source.delete())
        val first = repository.loadPage(imported.pageIds.first())
        val second = repository.loadPage(imported.pageIds.last())
        assertEquals(600f, first.width, 0f); assertEquals(1000f, first.height, 0f)
        assertEquals(1000f, second.width, 0f); assertEquals(600f, second.height, 0f)
        val firstBitmap = repository.renderPdfBackground(first.pdfBackground!!, 512)
        try { assertEquals(Color.RED, firstBitmap.getPixel(firstBitmap.width / 2, firstBitmap.height / 2)) } finally { firstBitmap.recycle() }
        val secondBitmap = repository.renderPdfBackground(second.pdfBackground!!, 512)
        try { assertEquals(Color.BLUE, secondBitmap.getPixel(secondBitmap.width / 2, secondBitmap.height / 2)) } finally { secondBitmap.recycle() }

        val annotated = first.copy(revision = 1, elements = listOf(StrokeElement(points = listOf(InkPoint(80f, 200f), InkPoint(500f, 200f)), color = Color.GREEN, width = 20f)))
        repository.savePage(annotated, 0)
        val background = repository.renderPdfBackground(first.pdfBackground!!, 1024)
        val rendered = Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888)
        try {
            PageRenderer.draw(Canvas(rendered), annotated, mapOf("pdf:${first.pdfBackground!!.assetId}:${first.pdfBackground!!.pageIndex}" to background))
            assertEquals(Color.GREEN, rendered.getPixel(300, 200))
            assertEquals(Color.RED, rendered.getPixel(300, 500))
        } finally { background.recycle(); rendered.recycle() }

        repository.deletePage(imported.id, second.id)
        val archive = ByteArrayOutputStream().also { repository.exportNotebookBackup(imported.id, it) }.toByteArray()
        val merged = repository.importBackup(ByteArrayInputStream(archive))
        val copy = merged.notebooks.last()
        assertEquals(1, copy.pageIds.size); assertEquals(1, copy.trashedPageIds.size)
        val restoredPage = repository.loadPage(copy.trashedPageIds.single())
        assertNotEquals(second.pdfBackground!!.assetId, restoredPage.pdfBackground!!.assetId)
        val restoredBitmap = repository.renderPdfBackground(restoredPage.pdfBackground!!, 256)
        try { assertEquals(Color.BLUE, restoredBitmap.getPixel(restoredBitmap.width / 2, restoredBitmap.height / 2)) } finally { restoredBitmap.recycle() }
        repository.restorePage(copy.id, restoredPage.id)
        assertEquals(2, repository.loadLibrary().notebooks.first { it.id == copy.id }.pageIds.size)
        before.notebooks.forEach { original -> assertEquals(original, repository.loadLibrary().notebooks.first { it.id == original.id }) }
        assertEquals(before.folders, repository.loadLibrary().folders)
    }
}
