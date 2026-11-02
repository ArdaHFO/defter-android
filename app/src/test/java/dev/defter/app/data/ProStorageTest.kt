package dev.defter.app.data

import android.net.Uri
import dev.defter.app.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ProStorageTest {
    private lateinit var repository: DocumentRepository
    @Before fun setup() {
        repository = DocumentRepository(RuntimeEnvironment.getApplication())
        repository.root.deleteRecursively()
    }

    @Test fun sameRevisionDifferentWriterIsRejectedButIdenticalRetrySucceeds() = runBlocking {
        val original = repository.loadPage(book().pageIds.single())
        val first = original.copy(revision = 1, elements = listOf(TextElement(text = "Birinci oturum", x = 1f, y = 1f)))
        repository.savePage(first, 0)
        repository.savePage(first, 0)
        rejected { repository.savePage(original.copy(revision = 1, backgroundColor = 123)) }
        assertEquals(first, repository.loadPage(original.id))
    }

    @Test fun expectedRevisionPreventsNewerNumberFromOverwritingAnotherWriter() = runBlocking {
        val original = repository.loadPage(book().pageIds.single())
        val first = original.copy(revision = 1, backgroundColor = 123)
        repository.savePage(first, 0)
        rejected { repository.savePage(original.copy(revision = 5, backgroundColor = 456), 0) }
        assertEquals(first, repository.loadPage(original.id))
    }

    @Test fun trashOrderRestoreAndBackupKeepAllPageContent() = runBlocking {
        val book = book()
        val first = book.pageIds.single()
        val second = repository.addPage(book.id, PaperTemplate.LINED)
        repository.savePage(second.copy(revision = 1, textRecognition = "Silinen sayfanın notu", recognitionRevision = 1))
        val third = repository.addPage(book.id, PaperTemplate.GRID)
        repository.reorderPages(book.id, listOf(third.id, first, second.id))
        repository.deletePage(book.id, second.id)
        val deletedBook = repository.loadLibrary().notebooks.single()
        assertEquals(listOf(third.id, first), deletedBook.pageIds)
        assertEquals(listOf(second.id), deletedBook.trashedPageIds)
        val bytes = ByteArrayOutputStream().also { repository.exportBackup(it) }.toByteArray()
        val imported = repository.importBackup(ByteArrayInputStream(bytes)).notebooks.last()
        assertEquals(1, imported.trashedPageIds.size)
        assertEquals("Silinen sayfanın notu", repository.loadPage(imported.trashedPageIds.single()).textRecognition)
        repository.restorePage(book.id, second.id, third.id)
        val restored = repository.loadLibrary().notebooks.first { it.id == book.id }
        assertEquals(listOf(third.id, second.id, first), restored.pageIds)
        assertTrue(restored.trashedPageIds.isEmpty())
    }

    @Test fun invalidOrderingAndLastPageRemovalLeaveLibraryUntouched() = runBlocking {
        val book = book()
        val baseline = repository.loadLibrary()
        rejected { repository.reorderPages(book.id, emptyList()) }
        rejected { repository.reorderPages(book.id, listOf(book.pageIds.single(), book.pageIds.single())) }
        rejected { repository.deletePage(book.id, book.pageIds.single()) }
        assertEquals(baseline, repository.loadLibrary())
    }

    @Test fun movingAndCopyingPagesPreservesOwnershipAndIndependentCopies() = runBlocking {
        val source = book("Kaynak")
        val original = repository.loadPage(source.pageIds.single())
        repository.savePage(original.copy(revision = 1, elements = listOf(TextElement(text = "Taşınan", x = 2f, y = 3f))))
        val extra = repository.addPage(source.id, PaperTemplate.DOTS)
        val target = book("Hedef")
        val moved = repository.transferPage(source.id, original.id, target.id, copy = false, afterPageId = target.pageIds.single())
        assertEquals(original.id, moved.id)
        assertEquals(listOf(extra.id), repository.loadLibrary().notebooks.first { it.id == source.id }.pageIds)
        val copied = repository.transferPage(target.id, moved.id, source.id, copy = true)
        assertNotEquals(moved.id, copied.id)
        assertNotEquals(moved.elements.single().id, copied.elements.single().id)
        assertEquals("Taşınan", (copied.elements.single() as TextElement).text)
    }

    @Test fun lastPageCanBeCopiedButNotMovedToAnotherNotebook() = runBlocking {
        val source = book("Kaynak")
        val target = book("Hedef")
        rejected { repository.transferPage(source.id, source.pageIds.single(), target.id, false) }
        val copy = repository.transferPage(source.id, source.pageIds.single(), target.id, true)
        assertNotEquals(source.pageIds.single(), copy.id)
        assertEquals(1, repository.loadLibrary().notebooks.first { it.id == source.id }.pageIds.size)
    }

    @Test fun staleRecognitionDoesNotBecomeFreshWhenPageIsCopied() = runBlocking {
        val book = book()
        val original = repository.loadPage(book.pageIds.single())
        repository.savePage(original.copy(revision = 3, textRecognition = "Eski", recognitionRevision = 1))
        val duplicate = repository.duplicatePage(book.id, original.id)
        assertNull(duplicate.recognitionRevision)
        val fresh = original.copy(revision = 4, textRecognition = "Güncel", recognitionRevision = 4)
        repository.savePage(fresh)
        val freshCopy = repository.duplicatePage(book.id, original.id)
        assertEquals(0L, freshCopy.recognitionRevision)
    }

    @Test fun v1DocumentsMigrateToV2WithoutInventingFeatures() {
        val page = DocumentJson.pageToJson(PageDocument()).put("version", 1)
        page.remove("pdfBackground"); page.remove("textRecognition"); page.remove("recognitionRevision"); page.remove("recognitionLanguage")
        val decoded = DocumentJson.pageFromJson(page)
        assertNull(decoded.pdfBackground); assertEquals("", decoded.textRecognition); assertNull(decoded.recognitionRevision); assertNull(decoded.recognitionLanguage)
        val owner = Notebook(title = "Eski defter", pageIds = listOf(decoded.id))
        val library = DocumentJson.libraryToJson(LibraryData(notebooks = listOf(owner))).put("version", 1)
        library.getJSONArray("notebooks").getJSONObject(0).remove("trashedPageIds")
        val migrated = DocumentJson.libraryFromJson(library)
        assertEquals(DocumentJson.VERSION, migrated.version)
        assertEquals(owner, migrated.notebooks.single())
        assertEquals(DocumentJson.VERSION, DocumentJson.libraryToJson(migrated).getInt("version"))
    }

    @Test fun newPdfImageInkAndRecognitionFieldsRoundTrip() {
        val page = PageDocument(revision = 4, pdfBackground = PdfBackground(newId(), 2, 595f, 842f),
            textRecognition = "Türkçe matematik", recognitionRevision = 4, recognitionLanguage = "tr",
            elements = listOf(
                ImageElement(assetId = newId(), x = 1f, y = 2f, width = 200f, height = 300f,
                    rotationDegrees = 90f, cropLeft = .1f, cropTop = .2f, cropRight = .8f, cropBottom = .9f),
                StrokeElement(points = listOf(InkPoint(1f, 2f), InkPoint(3f, 4f)), isPolyline = true, isShapeFragment = true)
            ))
        assertEquals(page, DocumentJson.pageFromJson(JSONObject(DocumentJson.pageToJson(page).toString())))
    }

    @Test fun existingV1FilesAreReadableAndUpgradeOnlyOnSuccessfulWrite() = runBlocking {
        val book = book("Eski defter")
        val first = repository.loadPage(book.pageIds.single())
        val libraryFile = File(repository.root, "library.json")
        val oldLibrary = JSONObject(libraryFile.readText()).put("version", 1)
        oldLibrary.getJSONArray("notebooks").getJSONObject(0).remove("trashedPageIds")
        libraryFile.writeText(oldLibrary.toString())
        val pageFile = File(repository.root, "pages/${first.id}.json")
        val oldPage = JSONObject(pageFile.readText()).put("version", 1)
        oldPage.remove("pdfBackground"); oldPage.remove("textRecognition"); oldPage.remove("recognitionRevision"); oldPage.remove("recognitionLanguage")
        pageFile.writeText(oldPage.toString())
        val reopened = DocumentRepository(RuntimeEnvironment.getApplication())
        assertEquals(book, reopened.loadLibrary().notebooks.single())
        assertEquals(first, reopened.loadPage(first.id))
        assertEquals(1, JSONObject(pageFile.readText()).getInt("version"))
        reopened.savePage(first.copy(revision = 1, elements = listOf(TextElement(text = "Korunan", x = 1f, y = 1f))), 0)
        assertEquals(DocumentJson.VERSION, JSONObject(pageFile.readText()).getInt("version"))
        assertEquals(DocumentJson.VERSION, JSONObject(libraryFile.readText()).getInt("version"))
        assertEquals("Korunan", (reopened.loadPage(first.id).elements.single() as TextElement).text)
    }

    @Test fun invalidCropRotationRecognitionAndDuplicateTrashReferencesAreRejected() {
        val image = ImageElement(assetId = newId(), x = 1f, y = 1f, width = 5f, height = 5f)
        invalid { DocumentJson.validatePage(PageDocument(elements = listOf(image.copy(cropLeft = .8f, cropRight = .2f)))) }
        invalid { DocumentJson.validatePage(PageDocument(elements = listOf(image.copy(rotationDegrees = Float.NaN)))) }
        invalid { DocumentJson.validatePage(PageDocument(recognitionRevision = 1)) }
        invalid { DocumentJson.validatePage(PageDocument(recognitionLanguage = "../tr")) }
        val id = newId()
        invalid { DocumentJson.validateLibrary(LibraryData(notebooks = listOf(Notebook(title = "X", pageIds = listOf(id), trashedPageIds = listOf(id))))) }
    }

    @Test fun malformedPdfImportDoesNotCreateNotebookOrDamageExistingData() = runBlocking {
        val existing = book()
        val source = File(RuntimeEnvironment.getApplication().cacheDir, "not-pdf.txt").apply { writeText("not a pdf document") }
        rejected { repository.importPdf(Uri.fromFile(source), "PDF", null) }
        assertEquals(listOf(existing), repository.loadLibrary().notebooks)
        assertTrue(repository.root.listFiles().orEmpty().none { it.name.startsWith("staging-") })
    }

    @Test fun missingPdfAssetCannotBeCommitted() = runBlocking {
        val page = repository.loadPage(book().pageIds.single())
        rejected { repository.savePage(page.copy(revision = 1, pdfBackground = PdfBackground(newId(), 0, 595f, 842f))) }
        assertEquals(page, repository.loadPage(page.id))
    }

    @Test fun savingToTrashedPageOrNotebookIsRejected() = runBlocking {
        val book = book()
        val page = repository.loadPage(book.pageIds.single())
        repository.addPage(book.id, PaperTemplate.DOTS)
        repository.deletePage(book.id, page.id)
        rejected { repository.savePage(page.copy(revision = 1)) }
        repository.restorePage(book.id, page.id)
        repository.setNotebookDeleted(book.id, true)
        rejected { repository.savePage(page.copy(revision = 1)) }
    }

    @Test fun recoveryPublishesFullCopyAndNormalizesRecognitionWithoutChangingOriginal() = runBlocking {
        val book = book()
        val original = repository.loadPage(book.pageIds.single())
        val draft = original.copy(revision = 9, textRecognition = "Tanındı", recognitionRevision = 9, recognitionLanguage = "tr",
            elements = listOf(TextElement(text = "Kaydedilemeyen not", x = 1f, y = 2f)))
        val recovery = repository.recoverPageCopy(draft, book.id)
        assertEquals(book.id, recovery.notebookId)
        assertNotEquals(original.id, recovery.page.id)
        assertEquals(listOf(original.id, recovery.page.id), repository.loadLibrary().notebooks.single().pageIds)
        assertEquals(original, repository.loadPage(original.id))
        assertEquals(recovery.page, repository.loadPage(recovery.page.id))
        assertEquals(0L, recovery.page.revision); assertEquals(0L, recovery.page.recognitionRevision)
        assertEquals("Tanındı", recovery.page.textRecognition)
        assertNotEquals(draft.elements.single().id, recovery.page.elements.single().id)
        val before = repository.loadLibrary()
        rejected { repository.recoverPageCopy(draft.copy(width = Float.NaN), book.id) }
        assertEquals(before, repository.loadLibrary())
    }

    @Test fun draftFromTrashedNotebookIsRecoveredToNewNotebookWithoutRestoringOriginal() = runBlocking {
        val book = book()
        val original = repository.loadPage(book.pageIds.single())
        repository.setNotebookDeleted(book.id, true)
        val deletedBook = repository.loadLibrary().notebooks.single()
        val recovery = repository.recoverPageCopy(original.copy(revision = 3, textRecognition = "Eski", recognitionRevision = 1), book.id)
        assertNotEquals(book.id, recovery.notebookId)
        assertNull(recovery.page.recognitionRevision)
        assertEquals(deletedBook, repository.loadLibrary().notebooks.first { it.id == book.id })
        assertNull(repository.loadLibrary().notebooks.first { it.id == recovery.notebookId }.deletedAt)
        assertEquals(original, repository.loadPage(original.id))
        val missingOwner = repository.recoverPageCopy(original, null)
        assertNotEquals(book.id, missingOwner.notebookId)
        assertEquals("Kurtarılan notlar", repository.loadLibrary().notebooks.first { it.id == missingOwner.notebookId }.title)
    }

    @Test fun failedRecoveryCommitNeverPublishesAnEmptyPlaceholderPage() = runBlocking {
        val book = book()
        val source = repository.loadPage(book.pageIds.single())
        val before = repository.loadLibrary()
        val blocker = File(repository.root, "library.json.new").apply { mkdirs() }
        val marker = File(blocker, "test-owned-blocker").apply { writeText("prevent atomic replacement") }
        try {
            rejected { repository.recoverPageCopy(source.copy(revision = 1, elements = listOf(TextElement(text = "Taslak", x = 1f, y = 1f))), book.id) }
            assertEquals(before, repository.loadLibrary())
            assertEquals(source, repository.loadPage(source.id))
        } finally { check(marker.delete()); check(blocker.delete()) }
    }

    private suspend fun book(title: String = "Defter") = repository.createNotebook(title, null, PaperTemplate.DOTS, 0)
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Operation should reject invalid input") } catch (error: Exception) {
            if (error !is IllegalArgumentException && error !is IOException) throw error
        }
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Invalid value accepted") } catch (_: IllegalArgumentException) { }
    }
}
