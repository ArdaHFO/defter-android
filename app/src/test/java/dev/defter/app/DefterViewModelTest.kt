package dev.defter.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real repository IO + deterministic Main dispatcher: these exercise the actual editor save queue. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DefterViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var app: Application
    private lateinit var repository: DocumentRepository
    private lateinit var vm: DefterViewModel
    private var store = ViewModelStore()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        repository = DocumentRepository(app)
        repository.root.deleteRecursively()
        createViewModel()
    }

    @After fun tearDown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun createEditUndoAddPageAndReopenKeepSavedContent() {
        vm.createFolder("Dersler")
        awaitIdle()
        vm.navigateFolder(vm.library.folders.single().id)
        vm.createNotebook("Fizik", PaperTemplate.GRID, 0xFF3D7566.toInt())
        awaitIdle()
        val bookId = vm.notebookId!!
        val firstPageId = vm.page!!.id
        vm.addText("Korunan ilk not")
        awaitIdle()
        assertEquals("Korunan ilk not", (readPage(firstPageId).elements.single() as TextElement).text)
        vm.addText("Geri alınacak")
        awaitIdle()
        vm.undo()
        awaitIdle()
        assertEquals(1, vm.page!!.elements.size)
        assertTrue(vm.canRedo)
        vm.redo()
        awaitIdle()
        assertEquals(2, readPage(firstPageId).elements.size)
        vm.undo()
        awaitIdle()
        vm.addPage(PaperTemplate.LINED)
        awaitIdle()
        val secondPageId = vm.page!!.id
        assertNotEquals(firstPageId, secondPageId)
        assertEquals(PaperTemplate.LINED, vm.page!!.template)
        vm.addText("İkinci sayfa")
        awaitIdle()
        vm.closeNotebook()
        awaitIdle()
        assertNull(vm.notebookId)
        createViewModel()
        vm.openNotebook(bookId)
        awaitIdle()
        assertEquals(firstPageId, vm.page!!.id)
        assertEquals("Korunan ilk not", (vm.page!!.elements.single() as TextElement).text)
        assertEquals(listOf(firstPageId, secondPageId), vm.notebook!!.pageIds)
        vm.selectPage(secondPageId)
        awaitIdle()
        assertEquals("İkinci sayfa", (vm.page!!.elements.single() as TextElement).text)
        assertNull(vm.error)
        assertFalse(vm.unsaved)
    }

    @Test fun rapidQueuedEditsAreSavedInOrderBeforeNavigation() {
        createNotebook()
        val firstId = vm.page!!.id
        repeat(20) { vm.addText("Not $it") }
        vm.addPage(PaperTemplate.BLANK)
        awaitIdle()
        assertNotEquals(firstId, vm.page!!.id)
        val saved = readPage(firstId)
        assertEquals(20, saved.elements.size)
        assertEquals((0 until 20).map { "Not $it" }, saved.elements.map { (it as TextElement).text })
        assertEquals(20L, saved.revision)
        assertFalse(vm.unsaved)
    }

    @Test fun imageImportFailureDoesNotChangeCurrentPageOrHistory() {
        createNotebook()
        vm.addText("Mevcut not")
        awaitIdle()
        val original = vm.page!!
        val canUndo = vm.canUndo
        val source = File(app.cacheDir, "not-an-image.txt").apply { writeText("plain text") }
        vm.importImage(Uri.fromFile(source))
        awaitIdle()
        assertNotNull(vm.error)
        assertEquals(original, vm.page)
        assertEquals(original, readPage(original.id))
        assertEquals(canUndo, vm.canUndo)
        assertEquals(0, vm.pendingSaves)
        assertFalse(vm.unsaved)
    }

    @Test fun failedSaveBlocksLeavingEditorAndRetryPersistsTheNote() {
        createNotebook()
        val bookId = vm.notebookId
        val libraryFile = File(repository.root, "library.json")
        val savedLibrary = libraryFile.readBytes()
        libraryFile.writeText("broken metadata")
        vm.addText("Kurtarılacak not")
        awaitIdle()
        assertTrue(vm.unsaved)
        assertNotNull(vm.error)
        val edited = vm.page!!
        vm.closeNotebook()
        awaitIdle()
        assertEquals(bookId, vm.notebookId)
        assertEquals(edited, vm.page)
        libraryFile.writeBytes(savedLibrary)
        vm.dismissError()
        vm.retrySave()
        awaitIdle()
        assertFalse(vm.unsaved)
        assertEquals(edited, readPage(edited.id))
        vm.closeNotebook()
        awaitIdle()
        assertNull(vm.page)
    }

    @Test fun trashAndRestoreRetainNotebookContent() {
        createNotebook()
        val bookId = vm.notebookId!!
        vm.addText("Çöp kutusundan geri gelecek")
        awaitIdle()
        vm.closeNotebook()
        awaitIdle()
        vm.trashNotebook(bookId, true)
        awaitIdle()
        assertNotNull(vm.library.notebooks.single().deletedAt)
        vm.trashNotebook(bookId, false)
        awaitIdle()
        assertNull(vm.library.notebooks.single().deletedAt)
        vm.openNotebook(bookId)
        awaitIdle()
        assertEquals("Çöp kutusundan geri gelecek", (vm.page!!.elements.single() as TextElement).text)
    }

    @Test fun imageDecodeFailureOpeningNotebookKeepsCurrentNotebookSelected() {
        createNotebook()
        val originalBookId = vm.notebookId!!
        val originalPage = vm.page!!
        val otherBook = runBlocking { repository.createNotebook("Bozuk", null, PaperTemplate.BLANK, 0xFF112233.toInt()) }
        val assetId = newId()
        repository.assetFile(assetId).writeText("broken PNG")
        runBlocking {
            val otherPage = repository.loadPage(otherBook.pageIds.single())
            repository.savePage(otherPage.copy(revision = 1, elements = listOf(ImageElement(
                assetId = assetId, x = 0f, y = 0f, width = 100f, height = 100f))))
        }
        // Refresh library without abandoning the original page and notebook context.
        vm.renameNotebook(originalBookId, "Asıl defter")
        awaitIdle()
        vm.openNotebook(otherBook.id)
        awaitIdle()
        assertNotNull(vm.error)
        assertEquals(originalBookId, vm.notebookId)
        assertEquals(originalPage, vm.page)
    }

    private fun createNotebook() {
        vm.createNotebook("Ders", PaperTemplate.DOTS, 0xFF3D7566.toInt())
        awaitIdle()
        assertNotNull(vm.page)
        assertNull(vm.error)
    }

    private fun createViewModel() {
        store.clear()
        scheduler.runCurrent()
        store = ViewModelStore()
        vm = DefterViewModel(app)
        store.put("test", vm)
        awaitIdle()
    }

    private fun readPage(id: String) = runBlocking { repository.loadPage(id) }

    private fun awaitIdle() {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (!vm.loading && !vm.busy && vm.pendingSaves == 0) return
            Thread.sleep(5)
        }
        fail("ViewModel did not become idle. loading=${vm.loading}, busy=${vm.busy}, pending=${vm.pendingSaves}, error=${vm.error}")
    }
}
