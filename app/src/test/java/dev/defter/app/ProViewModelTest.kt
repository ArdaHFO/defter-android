package dev.defter.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.*
import dev.defter.app.recognition.HandwritingRecognizer
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
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Actual repository + ViewModel integration. Only the downloaded recognition model is replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var app: Application
    private lateinit var repository: DocumentRepository
    private lateinit var vm: DefterViewModel
    private var store = ViewModelStore()
    private var state = SavedStateHandle()
    private var fake = FakeRecognizer()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        repository = DocumentRepository(app)
        check(repository.root.canonicalFile == File(app.filesDir, "defter").canonicalFile)
        check(!repository.root.exists() || repository.root.deleteRecursively())
        createViewModel()
    }

    @After fun tearDown() { store.clear(); scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun recognitionRequiresExplicitDownloadAndPersistsSearchableTurkishText() {
        createNotebook("Türkçe")
        writeInk()
        val before = vm.page!!
        vm.recognizeHandwriting()
        awaitIdle()
        assertNotNull(vm.error)
        assertEquals(before, vm.page)
        assertEquals(0, fake.downloadCalls)
        assertTrue(fake.calls.isEmpty())
        vm.dismissError()
        vm.downloadHandwritingModel()
        awaitIdle()
        assertEquals(1, fake.downloadCalls)
        vm.recognizeHandwriting()
        awaitIdle()
        assertNull(vm.error)
        val saved = readPage(before.id)
        assertEquals("İntegral notları", saved.textRecognition)
        assertEquals(saved.revision, saved.recognitionRevision)
        assertEquals("tr-TR", saved.recognitionLanguage)
        assertEquals(1, fake.calls.single().size)
        vm.searchPages("İNTEGRAL")
        awaitSearch()
        assertEquals(before.id, vm.searchResults.single().pageId)
    }

    @Test fun selectedInkConversionPreservesOtherElementsAndSupportsUndoRedo() {
        createNotebook("Seçim")
        val first = stroke(20f)
        val other = stroke(80f)
        val existingText = TextElement(text = "Mevcut metin", x = 10f, y = 200f)
        vm.changePage(vm.page!!.copy(elements = listOf(first, other, existingText)))
        awaitIdle()
        fake.downloaded = true
        vm.recognizeHandwriting(setOf(first.id))
        awaitIdle()
        assertEquals(listOf(first.id), fake.calls.single().map { it.id })
        vm.useRecognizedText(vm.recognitionCandidates.first(), replaceInk = true)
        awaitIdle()
        assertFalse(vm.page!!.elements.any { it.id == first.id })
        assertTrue(vm.page!!.elements.contains(other))
        assertTrue(vm.page!!.elements.contains(existingText))
        assertEquals(2, vm.page!!.elements.filterIsInstance<TextElement>().size)
        vm.undo()
        awaitIdle()
        assertEquals(listOf(first, other, existingText), readPage(vm.page!!.id).elements)
        vm.redo()
        awaitIdle()
        assertFalse(readPage(vm.page!!.id).elements.any { it.id == first.id })
        assertFalse(vm.unsaved)
    }

    @Test fun changedPageCannotReceiveAStaleRecognitionCandidate() {
        createNotebook("Güncel not")
        writeInk()
        fake.downloaded = true
        vm.recognizeHandwriting(setOf(vm.page!!.elements.filterIsInstance<StrokeElement>().first().id))
        awaitIdle()
        val candidate = vm.recognitionCandidates.first()
        vm.addText("Tanımadan sonra eklenen")
        awaitIdle()
        val newer = vm.page!!
        vm.useRecognizedText(candidate, replaceInk = true)
        awaitIdle()
        assertEquals(newer, vm.page)
        assertEquals(newer, readPage(newer.id))
    }

    @Test fun libraryIndexSkipsUnchangedPagesButReindexesWhenLanguageChanges() {
        createNotebook("Birinci")
        writeInk()
        val firstId = vm.page!!.id
        createNotebook("İkinci")
        writeInk()
        val secondId = vm.page!!.id
        fake.downloaded = true
        vm.indexHandwriting()
        awaitIdle()
        assertEquals(2, fake.calls.size)
        assertEquals("tr-TR", readPage(firstId).recognitionLanguage)
        assertEquals("tr-TR", readPage(secondId).recognitionLanguage)
        vm.indexHandwriting()
        awaitIdle()
        assertEquals(2, fake.calls.size)
        vm.recognitionLanguage = "en-US"
        vm.indexHandwriting()
        awaitIdle()
        assertNull(vm.error)
        assertEquals(4, fake.calls.size)
        assertEquals("en-US", readPage(firstId).recognitionLanguage)
        assertEquals("Integral notes", readPage(firstId).textRecognition)
        vm.searchPages("integral")
        awaitSearch()
        assertEquals(setOf(firstId, secondId), vm.searchResults.map { it.pageId }.toSet())
    }

    @Test fun editingInvalidatesRecognizedSearchTextUntilReindexing() {
        createNotebook("Dizin")
        writeInk()
        fake.downloaded = true
        vm.indexHandwriting()
        awaitIdle()
        vm.searchPages("İntegral")
        awaitSearch()
        assertEquals(1, vm.searchResults.size)
        vm.changePage(vm.page!!.copy(elements = emptyList()))
        awaitIdle()
        vm.searchPages("İntegral")
        awaitSearch()
        assertTrue(vm.searchResults.isEmpty())
        assertNull(readPage(vm.page!!.id).recognitionRevision)
    }

    @Test fun savedSessionRestoresTabsPagesReferenceAndPendingStyledText() {
        createNotebook("Matematik")
        val mathId = vm.notebookId!!
        vm.addPage(PaperTemplate.GRID)
        awaitIdle()
        val mathPage = vm.page!!.id
        createNotebook("Fizik")
        val physicsId = vm.notebookId!!
        val physicsPage = vm.page!!.id
        vm.openReference(mathId)
        awaitIdle()
        vm.acceptShare(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SpannableString("Paylaşılan not") as CharSequence))
        createViewModel(state)
        assertEquals(listOf(mathId, physicsId), vm.openTabs)
        assertEquals(physicsId, vm.notebookId)
        assertEquals(physicsPage, vm.page!!.id)
        assertEquals(mathId, vm.referenceNotebookId)
        assertEquals(mathPage, vm.referencePage!!.id)
        assertEquals("Paylaşılan not", vm.pendingSharedText)
        vm.openNotebook(mathId)
        awaitIdle()
        assertEquals(mathPage, vm.page!!.id)
        vm.swapPanes()
        awaitIdle()
        assertNull(vm.error)
    }

    @Test fun closingTabsSelectsRemainingTabAndThenReturnsToLibrary() {
        createNotebook("A")
        val a = vm.notebookId!!
        vm.addPage(PaperTemplate.LINED)
        awaitIdle()
        val lastA = vm.page!!.id
        createNotebook("B")
        val b = vm.notebookId!!
        vm.openNotebook(a)
        awaitIdle()
        assertEquals(lastA, vm.page!!.id)
        vm.closeTab(a)
        awaitIdle()
        assertEquals(b, vm.notebookId)
        assertEquals(listOf(b), vm.openTabs)
        assertNull(state.get<String>("tab-$a"))
        vm.closeTab(b)
        awaitIdle()
        assertNull(vm.notebookId)
        assertNull(vm.page)
        assertTrue(vm.openTabs.isEmpty())
        assertNull(state.get<String>("tab-$b"))
    }

    @Test fun newerTextShareReplacesOldImageAndMalformedParcelableDoesNotCrash() {
        createNotebook("Paylaşım")
        vm.acceptShare(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example/image")))
        assertNotNull(vm.pendingSharedImage)
        vm.acceptShare(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SpannableString("Biçimli not") as CharSequence))
        assertNull(vm.pendingSharedImage)
        vm.insertSharedContent()
        awaitIdle()
        assertEquals("Biçimli not", (vm.page!!.elements.single() as TextElement).text)
        assertNull(vm.pendingSharedText)
        vm.acceptShare(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, Bundle()))
        assertNotNull(vm.error)
        assertNull(vm.pendingSharedImage)
        assertNull(vm.pendingSharedText)
        assertEquals("Biçimli not", (readPage(vm.page!!.id).elements.single() as TextElement).text)
    }

    @Test fun twoEditorsCannotOverwriteEachOtherAndUnsavedWorkCanBeRecoveredAsACopy() {
        createNotebook("Çakışma")
        val bookId = vm.notebookId!!
        val pageId = vm.page!!.id
        val other = DefterViewModel(app)
        store.put("other", other)
        await { !other.loading }
        other.openNotebook(bookId)
        await { !other.busy }
        vm.addText("İlk editör")
        awaitIdle()
        other.addText("İkinci editör")
        await { other.pendingSaves == 0 }
        assertTrue(other.unsaved)
        assertEquals("İlk editör", (readPage(pageId).elements.single() as TextElement).text)
        other.recoverUnsavedAsCopies()
        await { !other.busy && other.pendingSaves == 0 }
        assertFalse(other.unsaved)
        assertNull(other.error)
        val ids = runBlocking { repository.loadLibrary() }.notebooks.single().pageIds
        assertEquals(2, ids.size)
        val texts = ids.flatMap { readPage(it).elements.filterIsInstance<TextElement>().map(TextElement::text) }
        assertEquals(setOf("İlk editör", "İkinci editör"), texts.toSet())
    }

    @Test fun retryOfOldFailureCannotResurrectAnOlderSnapshotAfterANewEdit() {
        createNotebook("Yeniden deneme")
        val library = File(repository.root, "library.json")
        val bytes = library.readBytes()
        library.writeText("broken")
        vm.addText("İlk not")
        awaitIdle()
        assertTrue(vm.unsaved)
        library.writeBytes(bytes)
        vm.dismissError()
        vm.addText("Yeni not")
        vm.retrySave()
        awaitIdle()
        assertFalse(vm.unsaved)
        assertNull(vm.error)
        assertEquals(listOf("İlk not", "Yeni not"), readPage(vm.page!!.id).elements.filterIsInstance<TextElement>().map(TextElement::text))
    }

    private fun createNotebook(title: String) { vm.createNotebook(title, PaperTemplate.DOTS, 0xFF6254D9.toInt()); awaitIdle(); assertNull(vm.error) }
    private fun stroke(x: Float) = StrokeElement(points = listOf(InkPoint(x, 20f), InkPoint(x + 20f, 60f)))
    private fun writeInk() {
        vm.changePage(vm.page!!.copy(elements = listOf(stroke(10f), stroke(50f).copy(kind = InkKind.HIGHLIGHTER), stroke(90f).copy(isShapeFragment = true))))
        awaitIdle()
    }
    private fun createViewModel(savedState: SavedStateHandle = state) {
        store.clear(); scheduler.runCurrent(); store = ViewModelStore()
        state = savedState
        vm = DefterViewModel(app, savedState)
        fake = FakeRecognizer()
        ReflectionHelpers.setField(vm, "recognizerInstance", fake)
        store.put("test", vm)
        awaitIdle()
    }
    private fun readPage(id: String) = runBlocking { repository.loadPage(id) }
    private fun awaitIdle() = await { !vm.loading && !vm.busy && vm.pendingSaves == 0 }
    private fun awaitSearch() = await { !vm.searching }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) { scheduler.runCurrent(); if (condition()) return; Thread.sleep(5) }
        fail("ViewModel timeout. busy=${vm.busy}, pending=${vm.pendingSaves}, error=${vm.error}")
    }
    private class FakeRecognizer : HandwritingRecognizer {
        var downloaded = false
        var downloadCalls = 0
        val calls = mutableListOf<List<StrokeElement>>()
        override suspend fun isModelDownloaded(languageTag: String) = downloaded
        override suspend fun downloadModel(languageTag: String) { downloadCalls++; downloaded = true }
        override suspend fun recognize(strokes: List<StrokeElement>, languageTag: String, preContext: String): List<String> {
            check(downloaded)
            calls += strokes
            return listOf(if (languageTag == "tr-TR") "İntegral notları" else "Integral notes")
        }
        override fun close() {}
    }
}
