package dev.defter.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import dev.defter.app.data.DocumentRepository
import dev.defter.app.ink.PageEdgeDirection
import dev.defter.app.ink.PageRenderer
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Editor gestures, clipboard and trash integration against the real file-backed save queue. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorNavigationViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var app: Application
    private lateinit var repository: DocumentRepository
    private lateinit var vm: DefterViewModel
    private val stores = mutableListOf<ViewModelStore>()
    private val clipboard: ClipboardManager get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        app = RuntimeEnvironment.getApplication()
        repository = DocumentRepository(app)
        repository.root.deleteRecursively()
        app.getSharedPreferences("editor_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        clipboard.setPrimaryClip(ClipData.newPlainText("test reset", ""))
        vm = newViewModel()
    }

    @After fun teardown() {
        stores.forEach { it.clear() }
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun nextSwipeWaitsForInkThenNavigatesExistingPageAndAppendsOnlyOnceAtTheEnd() {
        createNotebook(PaperTemplate.GRID)
        val first = vm.page!!.id
        vm.addPage(PaperTemplate.LINED)
        awaitIdle()
        val second = vm.page!!.id
        vm.selectPage(first)
        awaitIdle()
        val firstStroke = stroke()
        vm.changePage(vm.page!!.copy(elements = listOf(firstStroke)))
        vm.navigatePageBySwipe(first, PageEdgeDirection.NEXT)
        awaitIdle()
        assertEquals(second, vm.page!!.id)
        assertEquals(listOf(first, second), vm.notebook!!.pageIds)
        assertEquals(listOf(firstStroke), readPage(first).elements)

        val secondStroke = stroke()
        vm.changePage(vm.page!!.copy(elements = listOf(secondStroke)))
        vm.navigatePageBySwipe(second, PageEdgeDirection.NEXT)
        vm.navigatePageBySwipe(second, PageEdgeDirection.NEXT) // Duplicate callback while operation is busy.
        awaitIdle()
        val appended = vm.page!!.id
        assertNotEquals(second, appended)
        assertEquals(PaperTemplate.LINED, vm.page!!.template)
        assertEquals(listOf(first, second, appended), vm.notebook!!.pageIds)
        assertEquals(listOf(secondStroke), readPage(second).elements)
        assertTrue(vm.page!!.elements.isEmpty())
        vm.navigatePageBySwipe(second, PageEdgeDirection.NEXT) // Delayed callback after activation.
        awaitIdle()
        assertEquals(appended, vm.page!!.id)
        assertEquals(3, vm.notebook!!.pageIds.size)
        assertFalse(vm.unsaved)
        assertNull(vm.error)
    }

    @Test fun previousSwipeAtFirstPageDoesNotCreateOrModifyAnyPage() {
        createNotebook()
        val original = vm.page!!
        vm.navigatePageBySwipe(original.id, PageEdgeDirection.PREVIOUS)
        awaitIdle()
        assertEquals(original, vm.page)
        assertEquals(original, readPage(original.id))
        assertEquals(listOf(original.id), vm.notebook!!.pageIds)
        assertNull(vm.error)
    }

    @Test fun failedSaveBlocksBothExistingPageNavigationAndLastPageAppendUntilRetry() {
        createNotebook()
        val first = vm.page!!.id
        vm.addPage(PaperTemplate.GRID)
        awaitIdle()
        val second = vm.page!!.id
        for (source in listOf(first, second)) {
            vm.selectPage(source)
            awaitIdle()
            val obstruction = File(repository.root, "pages/$source.json.new").apply {
                mkdir(); resolve("block").writeText("synthetic IO failure")
            }
            try {
                vm.addText("Pending $source")
                awaitIdle()
                assertTrue(vm.unsaved)
                vm.navigatePageBySwipe(source, PageEdgeDirection.NEXT)
                awaitIdle()
                assertEquals(source, vm.page!!.id)
                assertEquals(listOf(first, second), vm.notebook!!.pageIds)
                assertEquals(2, runBlocking { repository.loadLibrary() }.notebooks.single().pageIds.size)
            } finally {
                obstruction.deleteRecursively()
            }
            vm.dismissError()
            vm.retrySave()
            awaitIdle()
            assertFalse(vm.unsaved)
            assertEquals("Pending $source", (readPage(source).elements.single() as TextElement).text)
        }
    }

    @Test fun fingerNavigationIsDefaultAndBothDrawingModesSurviveNewViewModels() {
        assertFalse(vm.config.touchDrawing)
        vm.config = vm.config.copy(touchDrawing = true)
        val drawing = newViewModel()
        assertTrue(drawing.config.touchDrawing)
        drawing.config = drawing.config.copy(touchDrawing = false)
        val navigation = newViewModel()
        assertFalse(navigation.config.touchDrawing)
    }

    @Test fun internalClipboardPastesIndependentElementsAcrossPagesAndSupportsUndo() {
        createNotebook()
        val originalStroke = stroke()
        val originalText = TextElement(text = "Copied lesson", x = 100f, y = 120f, size = 24f)
        vm.changePage(vm.page!!.copy(elements = listOf(originalStroke, originalText)))
        awaitIdle()
        val source = vm.page!!
        assertTrue(vm.copySelection(source.elements.map { it.id }.toSet()))
        vm.addPage(PaperTemplate.BLANK)
        awaitIdle()
        val destination = vm.page!!.id
        vm.paste(250f, 300f)
        awaitIdle()
        val pasted = readPage(destination).elements
        assertEquals(2, pasted.size)
        assertTrue(pasted.none { copy -> source.elements.any { it.id == copy.id } })
        val copiedStroke = pasted.filterIsInstance<StrokeElement>().single()
        assertEquals(originalStroke.width, copiedStroke.width, 0f)
        assertEquals(originalStroke.color, copiedStroke.color)
        assertEquals(originalStroke.points.map { it.pressure }, copiedStroke.points.map { it.pressure })
        assertNotEquals(originalStroke.points, copiedStroke.points)
        assertEquals(originalStroke.points[1].x - originalStroke.points[0].x,
            copiedStroke.points[1].x - copiedStroke.points[0].x, .001f)
        assertEquals(originalText.text, pasted.filterIsInstance<TextElement>().single().text)
        assertEquals(source, readPage(source.id))
        vm.undo()
        awaitIdle()
        assertTrue(readPage(destination).elements.isEmpty())
        assertEquals(source, readPage(source.id))
        vm.redo()
        awaitIdle()
        assertEquals(pasted, readPage(destination).elements)
        assertNull(vm.error)
    }

    @Test fun newlyCopiedExternalTextSupersedesInternalSelectionAndUsesRequestedPosition() {
        createNotebook()
        vm.addText("Old internal selection")
        awaitIdle()
        assertTrue(vm.copySelection(setOf(vm.page!!.elements.single().id)))
        vm.addPage(PaperTemplate.BLANK)
        awaitIdle()
        clipboard.setPrimaryClip(ClipData.newPlainText("External source", "Fresh external text"))
        vm.paste(345f, 678f)
        awaitIdle()
        val text = readPage(vm.page!!.id).elements.single() as TextElement
        assertEquals("Fresh external text", text.text)
        assertEquals(345f, text.x, 0f)
        assertEquals(678f, text.y, 0f)
        assertNull(vm.error)
    }

    @Test fun clipboardImageIsCopiedToAnAssetAndRendersAfterOriginalFileIsRemoved() {
        createNotebook()
        val color = 0xFF339955.toInt()
        val source = File(app.cacheDir, "${newId()}.png")
        val original = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        original.recycle()
        clipboard.setPrimaryClip(ClipData.newRawUri("External image", Uri.fromFile(source)))
        vm.paste(123f, 456f)
        awaitIdle()
        assertNull(vm.error)
        val image = vm.page!!.elements.single() as ImageElement
        assertEquals(123f, image.x, 0f)
        assertEquals(456f, image.y, 0f)
        assertTrue(repository.assetFile(image.assetId).isFile)
        assertTrue(source.delete())
        assertEquals(image, readPage(vm.page!!.id).elements.single())
        assertNotNull(vm.assets[image.assetId])
        val rendered = Bitmap.createBitmap(vm.page!!.width.toInt(), vm.page!!.height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            PageRenderer.draw(Canvas(rendered), vm.page!!, vm.assets)
            assertEquals(color, rendered.getPixel((image.x + image.width / 2).toInt(), (image.y + image.height / 2).toInt()))
        } finally { rendered.recycle() }
    }

    @Test fun permanentDeletionClearsStaleTabsReferenceAndOwnedClipboardWithoutResurrectingContent() {
        createNotebook()
        val removedBook = vm.notebookId!!
        val removedPage = vm.page!!.id
        vm.addText("Permanently removed text")
        awaitIdle()
        assertTrue(vm.copySelection(setOf(vm.page!!.elements.single().id)))
        createNotebook()
        val survivor = vm.notebookId!!
        vm.openReference(removedBook)
        awaitIdle()
        assertTrue(removedBook in vm.openTabs)
        assertEquals(removedBook, vm.referenceNotebookId)
        // Another editor can trash it while this one still has tabs/reference state.
        runBlocking { repository.setNotebookDeleted(removedBook, true) }
        vm.permanentlyDeleteNotebooks(setOf(removedBook))
        awaitIdle()
        assertNull(vm.error)
        assertFalse(removedBook in vm.openTabs)
        assertNull(vm.referenceNotebookId)
        assertNull(vm.referencePage)
        assertEquals(survivor, vm.notebookId)
        assertFalse(File(repository.root, "pages/$removedPage.json").exists())
        vm.paste(100f, 100f)
        awaitIdle()
        assertTrue(readPage(vm.page!!.id).elements.isEmpty())
        assertFalse(runBlocking { repository.loadLibrary() }.notebooks.any { it.id == removedBook })

        runBlocking { repository.setNotebookDeleted(survivor, true) }
        vm.permanentlyDeleteNotebooks(setOf(survivor))
        awaitIdle()
        assertNull(vm.page)
        assertNull(vm.notebookId)
        assertTrue(vm.openTabs.isEmpty())
        vm.paste()
        awaitIdle()
        assertTrue(runBlocking { repository.loadLibrary() }.notebooks.isEmpty())
    }

    @Test fun permanentDeletionDoesNotClearNewExternalClipboardContent() {
        createNotebook()
        val removed = vm.notebookId!!
        vm.addText("Old selected text")
        awaitIdle()
        assertTrue(vm.copySelection(setOf(vm.page!!.elements.single().id)))
        createNotebook()
        clipboard.setPrimaryClip(ClipData.newPlainText("Unrelated clipboard", "Keep external text"))
        runBlocking { repository.setNotebookDeleted(removed, true) }
        vm.permanentlyDeleteNotebooks(setOf(removed))
        awaitIdle()
        vm.paste(30f, 40f)
        awaitIdle()
        val pasted = readPage(vm.page!!.id).elements.single() as TextElement
        assertEquals("Keep external text", pasted.text)
        assertEquals(30f, pasted.x, 0f)
        assertEquals(40f, pasted.y, 0f)
        assertNull(vm.error)
    }

    private fun stroke() = StrokeElement(points = listOf(InkPoint(40f, 50f, .3f), InkPoint(90f, 100f, .8f)), width = 4f)
    private fun createNotebook(template: PaperTemplate = PaperTemplate.BLANK) {
        vm.createNotebook("Editor ${newId()}", template, 0xFF3D7566.toInt())
        awaitIdle()
        assertNotNull(vm.page)
        assertNull(vm.error)
    }
    private fun newViewModel(): DefterViewModel {
        val model = DefterViewModel(app)
        stores += ViewModelStore().also { it.put("editor", model) }
        awaitIdle(model)
        return model
    }
    private fun readPage(id: String) = runBlocking { repository.loadPage(id) }
    private fun awaitIdle(model: DefterViewModel = vm) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (!model.loading && !model.busy && model.pendingSaves == 0) return
            Thread.sleep(5)
        }
        fail("Editor did not become idle: loading=${model.loading}, busy=${model.busy}, saves=${model.pendingSaves}, error=${model.error}")
    }
}
