package dev.defter.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContinuousNotebookViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val store = ViewModelStore()
    private lateinit var app: Application
    private lateinit var repository: DocumentRepository
    private lateinit var model: DefterViewModel

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        app = RuntimeEnvironment.getApplication()
        repository = DocumentRepository(app)
        repository.root.deleteRecursively()
        model = DefterViewModel(app)
        store.put("continuous", model)
        awaitSettled()
    }

    @After fun teardown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun loadedNeighborsActivateSynchronouslyWithoutBusyOrNavigationTokenChanges() {
        val book = openBook(8)
        val token = model.explicitNavigationToken
        assertEquals(5, model.loadedPages.size)
        assertEquals(model.loadedPages.keys, model.loadedPageAssets.keys)
        val neighbor = book.pageIds[1]
        assertTrue(neighbor in model.loadedPages)
        model.addText("First page save is still queued")
        assertTrue(model.pendingSaves > 0)
        assertTrue(model.activateLoadedPage(neighbor))
        assertEquals(neighbor, model.page!!.id)
        assertFalse(model.busy)
        assertEquals(token, model.explicitNavigationToken)
        model.requestPageWindow(neighbor, listOf(book.pageIds[0], neighbor))
        awaitSettled()
        assertEquals(token, model.explicitNavigationToken)
        assertTrue(model.loadedPages.size <= 5)
        assertEquals("First page save is still queued", text(book.pageIds[0]).single())
        model.selectPage(book.pageIds[2])
        awaitSettled()
        assertEquals(token + 1, model.explicitNavigationToken)
    }

    @Test fun adjacentEditsAndUndoRedoAlwaysRouteToTheActivatedPage() {
        val book = openBook(3)
        val first = book.pageIds[0]
        val second = book.pageIds[1]
        model.addText("First lesson")
        assertTrue(model.activateLoadedPage(second))
        assertFalse(model.canUndo)
        model.addText("Second lesson")
        awaitSettled()
        assertEquals(listOf("First lesson"), text(first))
        assertEquals(listOf("Second lesson"), text(second))
        assertTrue(model.activateLoadedPage(first))
        assertTrue(model.canUndo)
        model.undo()
        awaitSettled()
        assertTrue(text(first).isEmpty())
        assertEquals(listOf("Second lesson"), text(second))
        assertTrue(model.activateLoadedPage(second))
        model.undo()
        awaitSettled()
        assertTrue(text(second).isEmpty())
        assertTrue(model.activateLoadedPage(first))
        assertTrue(model.canRedo)
        model.redo()
        awaitSettled()
        assertEquals(listOf("First lesson"), text(first))
        assertTrue(text(second).isEmpty())
        assertNull(model.error)
    }

    @Test fun failedSnapshotSurvivesEvictionAndReactivationWhileAdjacentPageCanStillSave() {
        val book = openBook(11)
        val first = book.pageIds[0]
        val second = book.pageIds[1]
        val obstruction = File(repository.root, "pages/$first.json.new").apply {
            mkdir(); resolve("block").writeText("synthetic save failure")
        }
        try {
            model.addText("Unsaved first page")
            awaitSettled()
            assertTrue(model.unsaved)
            assertTrue(model.activateLoadedPage(second))
            model.addText("Saved adjacent page")
            awaitSettled()
            assertEquals(listOf("Saved adjacent page"), text(second))
            assertTrue(text(first).isEmpty())
            model.requestPageWindow(book.pageIds.last())
            awaitSettled()
            assertFalse(first in model.loadedPages)
            assertTrue(model.loadedPages.size <= 5)
            model.requestPageWindow(first)
            awaitSettled()
            assertTrue(model.activateLoadedPage(first))
            assertEquals("Unsaved first page", (model.page!!.elements.single() as TextElement).text)
            assertTrue(model.canUndo)
            assertTrue(model.unsaved)
        } finally { obstruction.deleteRecursively() }
        model.dismissError()
        model.retrySave()
        awaitSettled()
        assertFalse(model.unsaved)
        assertEquals(listOf("Unsaved first page"), text(first))
        assertEquals(listOf("Saved adjacent page"), text(second))
    }

    @Test fun rapidWindowRequestsDiscardObsoleteAsyncResultsAndStayBounded() {
        val book = openBook(12)
        val token = model.explicitNavigationToken
        RepositoryGate().use {
            model.requestPageWindow(book.pageIds[7])
            scheduler.runCurrent()
            assertTrue(model.pageWindowLoading)
            assertFalse(model.busy)
            model.requestPageWindow(book.pageIds[11], listOf(book.pageIds[10], book.pageIds[11]))
            assertFalse(model.busy)
        }
        awaitSettled()
        assertTrue(book.pageIds[11] in model.loadedPages)
        assertFalse(book.pageIds[7] in model.loadedPages)
        assertTrue(book.pageIds[0] in model.loadedPages)
        assertTrue(model.loadedPages.size <= 5)
        assertEquals(model.loadedPages.keys, model.loadedPageAssets.keys)
        assertEquals(token, model.explicitNavigationToken)
        assertNull(model.error)
    }

    @Test fun switchingNotebooksInvalidatesAnOlderWindowBeforeItCanPublish() {
        val first = openBook(10)
        val second = seedBook(3)
        model.renameNotebook(first.id, "First notebook")
        awaitSettled()
        RepositoryGate().use {
            model.requestPageWindow(first.pageIds.last())
            scheduler.runCurrent()
            model.openNotebook(second.id)
            scheduler.runCurrent()
            assertTrue(model.busy)
        }
        awaitSettled()
        assertEquals(second.id, model.notebookId)
        assertEquals(second.pageIds.first(), model.page!!.id)
        assertEquals(second.pageIds.toSet(), model.loadedPages.keys)
        assertFalse(model.loadedPages.keys.any { it in first.pageIds })
        assertFalse(model.activateLoadedPage(first.pageIds.last()))
        assertNull(model.error)
    }

    @Test fun deletingPageDuringPendingWindowLoadCannotLeaveAnEditableDeletedPage() {
        val book = openBook(9)
        val deleted = book.pageIds.last()
        RepositoryGate().use {
            model.requestPageWindow(deleted)
            scheduler.runCurrent()
            model.deletePage(deleted)
            scheduler.runCurrent()
        }
        awaitSettled()
        assertFalse(deleted in model.notebook!!.pageIds)
        assertTrue(deleted in model.notebook!!.trashedPageIds)
        assertFalse(deleted in model.loadedPages)
        assertFalse(deleted in model.loadedPageAssets)
        assertFalse(model.activateLoadedPage(deleted))
        assertEquals(book.pageIds.first(), model.page!!.id)
        assertNull(model.error)
    }

    @Test fun reorderAndRestoreKeepWindowIdentityWithoutTreatingOrderChangesAsNavigation() {
        val book = openBook(4)
        val active = model.page!!.id
        val token = model.explicitNavigationToken
        model.reorderPages(book.pageIds.reversed())
        awaitSettled()
        assertEquals(book.pageIds.reversed(), model.notebook!!.pageIds)
        assertEquals(active, model.page!!.id)
        assertEquals(token, model.explicitNavigationToken)
        val deleted = book.pageIds[1]
        model.deletePage(deleted)
        awaitSettled()
        assertFalse(model.activateLoadedPage(deleted))
        model.restorePage(deleted)
        awaitSettled()
        assertTrue(deleted in model.loadedPages)
        assertTrue(model.activateLoadedPage(deleted))
        assertEquals(deleted, model.page!!.id)
        assertEquals(token, model.explicitNavigationToken)
    }

    @Test fun decodedNeighborCanBeEvictedWithoutRecyclingAnAlreadyPublishedCanvasBitmap() {
        val book = openBook(9)
        val neighbor = book.pageIds[1]
        val source = File(app.cacheDir, "${newId()}.png")
        val original = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF33AA77.toInt()) }
        source.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        original.recycle()
        runBlocking {
            val imported = repository.importImage(Uri.fromFile(source))
            val page = repository.loadPage(neighbor)
            repository.savePage(page.copy(revision = 1, elements = listOf(ImageElement(assetId = imported.assetId,
                x = 10f, y = 20f, width = 16f, height = 24f))), 0)
        }
        // Explicitly open the updated page, then return to the first page before evicting it.
        model.selectPage(neighbor)
        awaitSettled()
        val assetId = (model.page!!.elements.single() as ImageElement).assetId
        val published = model.loadedPageAssets.getValue(neighbor).getValue(assetId)
        assertTrue(model.activateLoadedPage(book.pageIds.first()))
        model.requestPageWindow(book.pageIds.last())
        awaitSettled()
        assertFalse(neighbor in model.loadedPageAssets)
        assertFalse(published.isRecycled)
        assertEquals(0xFF33AA77.toInt(), published.getPixel(8, 12))
        assertTrue(model.loadedPages.size <= 5)
    }

    private fun seedBook(count: Int): Notebook = runBlocking {
        val book = repository.createNotebook("Window ${newId()}", null, PaperTemplate.BLANK, 0)
        repeat(count - 1) { repository.addPage(book.id, PaperTemplate.BLANK) }
        repository.loadLibrary().notebooks.first { it.id == book.id }
    }
    private fun openBook(count: Int): Notebook {
        val book = seedBook(count)
        model.closeNotebook()
        awaitSettled()
        model.openNotebook(book.id)
        awaitSettled()
        return book
    }
    private fun text(id: String) = runBlocking { repository.loadPage(id) }.elements.filterIsInstance<TextElement>().map { it.text }
    private fun awaitSettled() {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (!model.loading && !model.busy && model.pendingSaves == 0 && !model.pageWindowLoading) return
            Thread.sleep(5)
        }
        fail("Notebook did not settle: ${model.error}; window=${model.pageWindowLoading}; saves=${model.pendingSaves}")
    }

    /** A bounded synthetic export holds the shared repository mutex without blocking Main. */
    private inner class RepositoryGate : Closeable {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val job = scope.launch {
            repository.exportBackup(object : OutputStream() {
                private var blocked = false
                override fun write(value: Int) = gate()
                override fun write(bytes: ByteArray, offset: Int, length: Int) = gate()
                private fun gate() {
                    if (!blocked) {
                        blocked = true; entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Synthetic repository gate timed out" }
                    }
                }
            })
        }
        init { check(entered.await(10, TimeUnit.SECONDS)) { "Repository gate did not start" } }
        override fun close() {
            release.countDown()
            runBlocking { withTimeout(10_000) { job.join() } }
            scope.cancel()
        }
    }
}
