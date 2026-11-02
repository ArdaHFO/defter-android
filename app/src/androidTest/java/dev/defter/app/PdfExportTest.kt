package dev.defter.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires real native PDF creation and rendering. No mocked PDF bytes or shadow-only assertions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PdfExportTest {
    private val scheduler = TestCoroutineScheduler()
    private val store = ViewModelStore()
    private lateinit var app: Application
    private lateinit var repository: DocumentRepository
    private lateinit var vm: DefterViewModel
    private lateinit var testDirectory: File

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        repository = DocumentRepository(app)
        testDirectory = File(app.cacheDir, "pdf-verification-${newId()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun exportedPdfRendersImageInkTextAndBothPages() {
        val existingLibrary = runBlocking { repository.loadLibrary() }
        val red = Color.rgb(235, 35, 45)
        val green = Color.rgb(20, 180, 80)
        val blue = Color.rgb(20, 40, 245)
        val source = File(testDirectory, "pdf-source-image.png")
        Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(red)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val book = runBlocking {
            val created = repository.createNotebook("PDF doğrulama ${System.currentTimeMillis()}", null, PaperTemplate.BLANK, green)
            val asset = repository.importImage(Uri.fromFile(source))
            val first = repository.loadPage(created.pageIds.single())
            repository.savePage(first.copy(width = 595f, height = 842f, backgroundColor = Color.WHITE, revision = 1,
                elements = listOf(
                    ImageElement(assetId = asset.assetId, x = 50f, y = 60f, width = 120f, height = 80f),
                    StrokeElement(points = listOf(InkPoint(50f, 200f), InkPoint(300f, 200f)), color = blue, width = 12f),
                    TextElement(text = "PDF verification", x = 50f, y = 250f, size = 24f, color = Color.BLACK)
                )))
            val second = repository.addPage(created.id, PaperTemplate.BLANK)
            repository.savePage(second.copy(width = 595f, height = 842f, backgroundColor = green, revision = 1))
            created
        }
        vm = DefterViewModel(app)
        store.put("pdf", vm)
        awaitIdle()
        vm.openNotebook(book.id)
        awaitIdle()
        assertNull(vm.error)
        val output = File(testDirectory, "verified-export.pdf")
        vm.exportPdf(Uri.fromFile(output))
        awaitIdle()
        assertNull("Native PDF export must complete: ${vm.error}", vm.error)
        assertTrue("A real PDF must be written", output.length() > 1000)
        val bytes = output.readBytes()
        assertEquals("%PDF-", bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII))
        assertTrue(bytes.takeLast(1024).toByteArray().toString(Charsets.US_ASCII).contains("%%EOF"))

        val descriptor = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        try {
            assertEquals(2, renderer.pageCount)
            val first = render(renderer, 0)
            try {
                assertColorClose(red, first.getPixel(100, 100))
                assertColorClose(blue, first.getPixel(150, 200))
                assertColorClose(Color.WHITE, first.getPixel(500, 500))
                var darkTextPixels = 0
                for (y in 250 until 285) for (x in 50 until 350) {
                    val pixel = first.getPixel(x, y)
                    if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) darkTextPixels++
                }
                assertTrue("Text must be visible in the exported PDF", darkTextPixels > 50)
                run {
                    val folder = File(app.filesDir, "verification").apply { mkdirs() }
                    File(folder, "pdf-export-page1.png").outputStream().use {
                        assertTrue(first.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    output.copyTo(File(folder, "verified-export.pdf"), overwrite = true)
                }
            } finally { first.recycle() }
            val second = render(renderer, 1)
            try { assertColorClose(green, second.getPixel(500, 700)) } finally { second.recycle() }
        } finally {
            renderer.close()
            descriptor.close()
        }
        val finalLibrary = runBlocking { repository.loadLibrary() }
        assertEquals(existingLibrary.folders, finalLibrary.folders)
        existingLibrary.notebooks.forEach { existing ->
            assertEquals(existing, finalLibrary.notebooks.first { it.id == existing.id })
        }
    }

    private fun render(renderer: PdfRenderer, index: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            return Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally { page.close() }
    }

    private fun assertColorClose(expected: Int, actual: Int) {
        assertEquals(Color.red(expected).toDouble(), Color.red(actual).toDouble(), 5.0)
        assertEquals(Color.green(expected).toDouble(), Color.green(actual).toDouble(), 5.0)
        assertEquals(Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), 5.0)
    }

    private fun awaitIdle() {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (!vm.loading && !vm.busy && vm.pendingSaves == 0) return
            Thread.sleep(5)
        }
        fail("PDF operation did not complete. error=${vm.error}")
    }
}
