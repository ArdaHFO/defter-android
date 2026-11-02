package dev.defter.app.data

import android.graphics.Bitmap
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DocumentRepositoryTest {
    private lateinit var repository: DocumentRepository

    @Before fun setup() {
        repository = DocumentRepository(RuntimeEnvironment.getApplication())
        repository.root.deleteRecursively()
    }

    @Test fun nestedFoldersAndNotebookSurviveNewRepository() = runBlocking {
        val folder = repository.createFolder("Dersler", null).folders.single()
        val nested = repository.createFolder("Matematik", folder.id).folders.last()
        val notebook = repository.createNotebook("Analiz", nested.id, PaperTemplate.GRID, 0xFF3D7566.toInt())
        val reopened = DocumentRepository(RuntimeEnvironment.getApplication())
        assertEquals(listOf(folder, nested), reopened.loadLibrary().folders)
        assertEquals(notebook, reopened.loadLibrary().notebooks.single())
        assertEquals(PaperTemplate.GRID, reopened.loadPage(notebook.pageIds.single()).template)
    }

    @Test fun allElementTypesAndPressureRoundTrip() {
        val page = PageDocument(elements = listOf(
            StrokeElement(points = listOf(InkPoint(2.5f, 3f, 0.2f), InkPoint(5f, 7f, 0.9f))),
            StrokeElement(points = listOf(InkPoint(9f, 10f)), kind = InkKind.HIGHLIGHTER),
            ShapeElement(shape = ShapeKind.ARROW, startX = 10f, startY = 10f, endX = 90f, endY = 130f),
            ImageElement(assetId = newId(), x = 20f, y = 30f, width = 250f, height = 500f),
            TextElement(text = "Türkçe\nDers notu", x = 45f, y = 60f)
        ), revision = 7)
        assertEquals(page, DocumentJson.pageFromJson(JSONObject(DocumentJson.pageToJson(page).toString())))
    }

    @Test fun saveAndUndoRevisionRejectStaleOverwrite() = runBlocking {
        val book = makeNotebook()
        val original = repository.loadPage(book.pageIds.single())
        val edited = original.copy(revision = 1, elements = listOf(TextElement(text = "Kalıcı", x = 10f, y = 20f)))
        repository.savePage(edited)
        expectFailure<IllegalArgumentException> { repository.savePage(original) }
        assertEquals(edited, repository.loadPage(original.id))
        val undo = original.copy(revision = 2)
        repository.savePage(undo)
        assertEquals(undo, repository.loadPage(original.id))
    }

    @Test fun duplicatePageHasIndependentIdentityAndCorrectOrder() = runBlocking {
        val book = makeNotebook()
        val first = repository.loadPage(book.pageIds.single())
        repository.savePage(first.copy(revision = 1, elements = listOf(TextElement(text = "Örnek", x = 10f, y = 20f))))
        val second = repository.addPage(book.id, PaperTemplate.LINED)
        val duplicate = repository.duplicatePage(book.id, first.id)
        assertEquals(listOf(first.id, duplicate.id, second.id), repository.loadLibrary().notebooks.single().pageIds)
        assertNotEquals(repository.loadPage(first.id).elements.single().id, duplicate.elements.single().id)
        assertEquals("Örnek", (duplicate.elements.single() as TextElement).text)
    }

    @Test fun trashCanBeRestoredWithoutDeletingPages() = runBlocking {
        val book = makeNotebook()
        assertNotNull(repository.setNotebookDeleted(book.id, true).notebooks.single().deletedAt)
        assertNotNull(repository.loadPage(book.pageIds.single()))
        assertNull(repository.setNotebookDeleted(book.id, false).notebooks.single().deletedAt)
    }

    @Test fun corruptedLibraryFailsWithoutReplacingIt() = runBlocking {
        makeNotebook()
        val file = File(repository.root, "library.json")
        file.writeText("not a document")
        expectFailure<IOException> { repository.loadLibrary() }
        assertEquals("not a document", file.readText())
        expectFailure<IOException> { repository.createFolder("Yeni", null) }
        assertEquals("not a document", file.readText())
    }

    @Test fun atomicBackupRecoversInterruptedLibraryWrite() = runBlocking {
        val book = makeNotebook()
        val file = File(repository.root, "library.json")
        file.copyTo(File(repository.root, "library.json.bak"))
        file.writeText("{partial")
        assertEquals(book, repository.loadLibrary().notebooks.single())
    }

    @Test fun failedAtomicReplacementThrowsAndPreservesExistingDestination() {
        val destination = File(repository.root, "blocked.json").apply { mkdirs() }
        val preserved = File(destination, "preserved.txt").apply { writeText("keep existing data") }
        val atomic = AtomicSnapshotFile(destination)
        val stream = atomic.startWrite()
        try {
            stream.write("new snapshot".toByteArray())
            expectFailureBlocking<IOException> { atomic.finishWrite(stream) }
        } finally {
            atomic.failWrite(stream)
        }
        assertTrue(destination.isDirectory)
        assertEquals("keep existing data", preserved.readText())
        assertFalse(File(destination.path + ".new").exists())
    }

    @Test fun futureSchemaAndInvalidNumbersAreRejected() {
        val json = DocumentJson.pageToJson(PageDocument()).put("version", 999)
        expectFailureBlocking<IOException> { DocumentJson.pageFromJson(json) }
        expectFailureBlocking<IllegalArgumentException> {
            DocumentJson.pageToJson(PageDocument(elements = listOf(StrokeElement(points = listOf(InkPoint(Float.NaN, 1f))))))
        }
    }

    @Test fun deeplyNestedDocumentIsRejectedBeforeRecursiveParsing() = runBlocking {
        makeNotebook()
        val file = File(repository.root, "library.json")
        val malicious = "{\"version\":1,\"folders\":[] ,\"notebooks\":[],\"extra\":" +
            "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
        file.writeText(malicious)
        expectFailure<IOException> { repository.loadLibrary() }
        assertEquals(malicious, file.readText())
    }

    @Test fun backupRestoreMergesAndRebindsAllReferences() = runBlocking {
        val parent = repository.createFolder("Dersler", null).folders.single()
        val child = repository.createFolder("Fizik", parent.id).folders.last()
        val book = repository.createNotebook("Optik", child.id, PaperTemplate.DOTS, 0xFF3D7566.toInt())
        val original = repository.loadPage(book.pageIds.single())
        val page = original.copy(revision = 1, elements = listOf(TextElement(text = "Mercek", x = 10f, y = 20f)))
        repository.savePage(page)
        val backup = ByteArrayOutputStream().also { repository.exportBackup(it) }.toByteArray()
        val merged = repository.importBackup(ByteArrayInputStream(backup))
        assertEquals(2, merged.notebooks.size)
        assertEquals(4, merged.folders.size)
        val imported = merged.notebooks.last()
        assertNotEquals(book.id, imported.id)
        assertNotEquals(book.folderId, imported.folderId)
        assertNotEquals(book.pageIds, imported.pageIds)
        val importedPage = repository.loadPage(imported.pageIds.single())
        assertNotEquals(page.elements.single().id, importedPage.elements.single().id)
        assertEquals("Mercek", (importedPage.elements.single() as TextElement).text)
        assertEquals(page, repository.loadPage(page.id))
        val importedFolder = merged.folders.first { it.id == imported.folderId }
        assertNotEquals(parent.id, importedFolder.parentId)
        assertNotNull(merged.folders.firstOrNull { it.id == importedFolder.parentId })
    }

    @Test fun copiedImageAndBackupSurviveSourceRemoval() = runBlocking {
        val source = File(RuntimeEnvironment.getApplication().cacheDir, "source.png")
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF33AA77.toInt())
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val asset = repository.importImage(Uri.fromFile(source))
        assertEquals(32, asset.width)
        assertEquals(48, asset.height)
        assertTrue(source.delete())
        assertTrue(repository.assetFile(asset.assetId).isFile)
        val book = makeNotebook()
        val page = repository.loadPage(book.pageIds.single())
        repository.savePage(page.copy(revision = 1, elements = listOf(ImageElement(
            assetId = asset.assetId, x = 10f, y = 10f, width = 320f, height = 480f))))
        val backup = ByteArrayOutputStream().also { repository.exportBackup(it) }.toByteArray()
        val merged = repository.importBackup(ByteArrayInputStream(backup))
        val importedPage = repository.loadPage(merged.notebooks.last().pageIds.single())
        val importedImage = importedPage.elements.single() as ImageElement
        assertNotEquals(asset.assetId, importedImage.assetId)
        assertArrayEquals(repository.assetFile(asset.assetId).readBytes(), repository.assetFile(importedImage.assetId).readBytes())
    }

    @Test fun traversalArchiveCannotEscapeOrAlterExistingLibrary() = runBlocking {
        val existing = makeNotebook()
        val malicious = zipOf(mapOf("../escaped.txt" to "evil".toByteArray()))
        expectFailure<IOException> { repository.importBackup(ByteArrayInputStream(malicious)) }
        assertEquals(listOf(existing), repository.loadLibrary().notebooks)
        assertFalse(File(repository.root, "escaped.txt").exists())
        assertFalse(File(repository.root.parentFile, "escaped.txt").exists())
    }

    @Test fun checksumMismatchLeavesLibraryAndPageUntouched() = runBlocking {
        val book = makeNotebook()
        val page = repository.loadPage(book.pageIds.single())
        val bytes = ByteArrayOutputStream().also { repository.exportBackup(it) }.toByteArray()
        val entries = unzip(bytes).toMutableMap()
        entries["pages/${page.id}.json"] = "tampered".toByteArray()
        expectFailure<IOException> { repository.importBackup(ByteArrayInputStream(zipOf(entries))) }
        assertEquals(listOf(book), repository.loadLibrary().notebooks)
        assertEquals(page, repository.loadPage(page.id))
        assertTrue(repository.root.listFiles().orEmpty().none { it.name.startsWith("staging-") })
    }

    @Test fun missingImageCannotBeCommitted() = runBlocking {
        val book = makeNotebook()
        val page = repository.loadPage(book.pageIds.single())
        expectFailure<IllegalArgumentException> {
            repository.savePage(page.copy(revision = 1, elements = listOf(ImageElement(
                assetId = newId(), x = 1f, y = 1f, width = 100f, height = 100f))))
        }
        assertEquals(page, repository.loadPage(page.id))
    }

    private suspend fun makeNotebook() = repository.createNotebook("Ders defteri", null, PaperTemplate.DOTS, 0xFF3D7566.toInt())

    private suspend inline fun <reified T : Throwable> expectFailure(crossinline block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    private inline fun <reified T : Throwable> expectFailureBlocking(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
                zip.closeEntry()
            }
        }
    }
}
