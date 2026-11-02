package dev.defter.app.data

import android.content.Context
import android.content.ContextWrapper
import dev.defter.app.model.*
import kotlinx.coroutines.runBlocking
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
class SyncRepositoryTest {
    private lateinit var source: DocumentRepository
    private lateinit var target: DocumentRepository
    @Before fun setup() {
        source = repository("source"); target = repository("target")
        source.root.deleteRecursively(); target.root.deleteRecursively()
    }

    @Test fun firstDownloadAndRepeatedSafeReplacementKeepOneStableNotebookAndFolderTree() = runBlocking {
        val parent = source.createFolder("Dersler", null).folders.single()
        val child = source.createFolder("Matematik", parent.id).folders.last()
        val book = source.createNotebook("Analiz", child.id, PaperTemplate.DOTS, 0)
        write(source, book, "İlk not")
        val first = target.importSyncBackup(ByteArrayInputStream(archive(source, book.id)), null, book.id)
        assertEquals(book.id, first.notebookId); assertFalse(first.conflict)
        assertEquals(first.fingerprint, target.getNotebookFingerprint(book.id))
        val priorPageId = target.loadLibrary().notebooks.single().pageIds.single()
        write(source, book, "Yeni not")
        val second = target.importSyncBackup(ByteArrayInputStream(archive(source, book.id)), first.fingerprint, book.id)
        assertFalse(second.conflict); assertEquals(book.id, second.notebookId)
        val library = target.loadLibrary()
        assertEquals(1, library.notebooks.size); assertEquals(2, library.folders.size)
        assertNotEquals(priorPageId, library.notebooks.single().pageIds.single())
        assertEquals("Yeni not", text(target, library.notebooks.single()))
        assertEquals(second.fingerprint, target.getNotebookFingerprint(book.id))
    }

    @Test fun localEditCreatesVisibleConflictCopyWithoutReplacingOriginal() = runBlocking {
        val book = source.createNotebook("Ders", null, PaperTemplate.DOTS, 0)
        write(source, book, "Başlangıç")
        val baseline = target.importSyncBackup(ByteArrayInputStream(archive(source, book.id)), null, book.id)
        val local = target.loadLibrary().notebooks.single()
        write(target, local, "Yerel değişiklik")
        write(source, book, "Uzak değişiklik")
        val result = target.importSyncBackup(ByteArrayInputStream(archive(source, book.id)), baseline.fingerprint, book.id)
        assertTrue(result.conflict); assertNotEquals(book.id, result.notebookId)
        val library = target.loadLibrary()
        assertEquals(2, library.notebooks.size)
        assertEquals("Yerel değişiklik", text(target, library.notebooks.first { it.id == book.id }))
        val copy = library.notebooks.first { it.id == result.notebookId }
        assertTrue(copy.title.contains("eşitleme çakışması")); assertNull(copy.deletedAt)
        assertEquals("Uzak değişiklik", text(target, copy))
    }

    @Test fun incomingNotebookIdMustMatchRequestedRemoteFile() = runBlocking {
        val book = source.createNotebook("Ders", null, PaperTemplate.DOTS, 0)
        try {
            target.importSyncBackup(ByteArrayInputStream(archive(source, book.id)), null, newId())
            fail("Mismatched notebook accepted")
        } catch (_: IOException) { }
        assertTrue(target.loadLibrary().notebooks.isEmpty())
    }

    @Test fun multiNotebookBackupCannotBeUsedAsSingleNotebookSyncFile() = runBlocking {
        source.createNotebook("Bir", null, PaperTemplate.DOTS, 0)
        source.createNotebook("İki", null, PaperTemplate.DOTS, 0)
        val bytes = ByteArrayOutputStream().also { source.exportBackup(it) }.toByteArray()
        try { target.importSyncBackup(ByteArrayInputStream(bytes), null); fail("Multi-book sync accepted") } catch (_: IOException) { }
        assertTrue(target.loadLibrary().notebooks.isEmpty())
    }

    @Test fun fingerprintTracksPageOrderTrashAndNotebookDeletionAndExportReturnsExactSnapshot() = runBlocking {
        val book = source.createNotebook("Ders", null, PaperTemplate.DOTS, 0)
        val first = source.getNotebookFingerprint(book.id)
        val second = source.addPage(book.id, PaperTemplate.GRID)
        val added = source.getNotebookFingerprint(book.id)
        assertNotEquals(first, added)
        source.reorderPages(book.id, listOf(second.id, book.pageIds.single()))
        val reordered = source.getNotebookFingerprint(book.id)
        assertNotEquals(added, reordered)
        source.deletePage(book.id, second.id)
        val trashed = source.getNotebookFingerprint(book.id)
        assertNotEquals(reordered, trashed)
        source.setNotebookDeleted(book.id, true)
        val deleted = source.getNotebookFingerprint(book.id)
        assertNotEquals(trashed, deleted)
        val output = ByteArrayOutputStream()
        assertEquals(deleted, source.exportNotebookBackup(book.id, output))
        val result = target.importSyncBackup(ByteArrayInputStream(output.toByteArray()), null, book.id)
        assertFalse(result.conflict)
        val copy = target.loadLibrary().notebooks.single()
        assertNotNull(copy.deletedAt); assertEquals(1, copy.trashedPageIds.size)
    }

    @Test fun fingerprintIsStableAcrossRepeatedReadsAndMissingNotebookIsNull() = runBlocking {
        val book = source.createNotebook("Ders", null, PaperTemplate.DOTS, 0)
        val first = source.getNotebookFingerprint(book.id)
        repeat(3) { assertEquals(first, source.getNotebookFingerprint(book.id)) }
        assertNull(source.getNotebookFingerprint(newId()))
    }

    @Test fun safeReplacementRebindsExternalAudioBookmarksAtomicallyWhenPageIdentityMatches() = runBlocking {
        val book = source.createNotebook("Ders", null, PaperTemplate.DOTS, 0)
        write(source, book, "Bağlı sayfa")
        val audio = source.createAudioRecording("Bağımsız işaretler")
        source.addAudioBookmark(audio.id, 0, "Sayfaya bağ", book.pageIds.single())
        val originalRecording = source.loadAudioRecording(audio.id)
        val baseline = source.loadLibrary()
        val fingerprint = source.getNotebookFingerprint(book.id)
        val bytes = archive(source, book.id)
        val obstruction = File(source.root, "library.json.new").apply { mkdir(); resolve("block").writeText("keep") }
        try {
            source.importSyncBackup(ByteArrayInputStream(bytes), fingerprint, book.id)
            fail("Failed library publication accepted")
        } catch (_: IOException) { }
        assertEquals(baseline, source.loadLibrary())
        assertEquals(originalRecording, source.loadAudioRecording(audio.id))
        obstruction.deleteRecursively()
        val result = source.importSyncBackup(ByteArrayInputStream(bytes), fingerprint, book.id)
        assertFalse(result.conflict)
        val library = source.loadLibrary()
        assertEquals(1, library.notebooks.size)
        val rebound = source.loadAudioRecording(audio.id)
        assertEquals(library.notebooks.single().pageIds.single(), rebound.bookmarks.single().pageId)
        assertNotEquals(book.pageIds.single(), rebound.bookmarks.single().pageId)
        assertEquals(originalRecording.revision + 1, rebound.revision)
        assertEquals("Bağlı sayfa", text(source, library.notebooks.single()))
    }

    @Test fun replacementPreservesUnmatchedBookmarkedPagesAndTheirTrashStateWithoutGuessingIdentity() = runBlocking {
        val book = source.createNotebook("Ders", null, PaperTemplate.DOTS, 0)
        write(source, book, "İlk sürüm")
        val trashed = source.addPage(book.id, PaperTemplate.GRID)
        source.savePage(trashed.copy(revision = 1, elements = listOf(TextElement(text = "Çöpteki not", x = 1f, y = 1f))))
        source.deletePage(book.id, trashed.id)
        val first = target.importSyncBackup(ByteArrayInputStream(archive(source, book.id)), null, book.id)
        val local = target.loadLibrary().notebooks.single()
        val audio = target.createAudioRecording("Bağımsız işaretler")
        target.addAudioBookmark(audio.id, 0, "Eski not", local.pageIds.single())
        target.addAudioBookmark(audio.id, 0, "Çöpteki not", local.trashedPageIds.single())
        val originalMarks = target.loadAudioRecording(audio.id).bookmarks
        write(source, book, "Uzak yeni sürüm")
        val result = target.importSyncBackup(ByteArrayInputStream(archive(source, book.id)), first.fingerprint, book.id)
        assertFalse(result.conflict)
        val library = target.loadLibrary()
        val replacement = library.notebooks.first { it.id == book.id }
        val preserved = library.notebooks.single { it.id != book.id }
        assertTrue(preserved.title.startsWith("Ses işaretleri"))
        assertEquals(local.pageIds, preserved.pageIds)
        assertEquals(local.trashedPageIds, preserved.trashedPageIds)
        assertEquals(originalMarks, target.loadAudioRecording(audio.id).bookmarks)
        assertEquals("İlk sürüm", text(target, preserved))
        assertEquals("Uzak yeni sürüm", text(target, replacement))
        val completeBackup = ByteArrayOutputStream().also { target.exportBackup(it) }.toByteArray()
        val restored = target.importBackup(ByteArrayInputStream(completeBackup))
        val importedAudio = target.loadAudioRecording(restored.audioItems.last().id)
        val allPages = restored.notebooks.flatMap { it.pageIds + it.trashedPageIds }.toSet()
        assertTrue(importedAudio.bookmarks.all { it.pageId in allPages })
    }

    @Test fun preservingOnlyTrashedReferencedPagesAddsBlankActivePageWithoutRestoringTrash() = runBlocking {
        val book = source.createNotebook("Ders", null, PaperTemplate.DOTS, 0)
        val page = source.addPage(book.id, PaperTemplate.GRID)
        source.deletePage(book.id, page.id)
        val first = target.importSyncBackup(ByteArrayInputStream(archive(source, book.id)), null, book.id)
        val local = target.loadLibrary().notebooks.single()
        val audio = target.createAudioRecording("İşaret")
        target.addAudioBookmark(audio.id, 0, "Çöp", local.trashedPageIds.single())
        target.importSyncBackup(ByteArrayInputStream(archive(source, book.id)), first.fingerprint, book.id)
        val recovered = target.loadLibrary().notebooks.single { it.id != book.id }
        assertEquals(local.trashedPageIds, recovered.trashedPageIds)
        assertEquals(1, recovered.pageIds.size)
        assertTrue(target.loadPage(recovered.pageIds.single()).elements.isEmpty())
        assertNotEquals(local.pageIds.single(), recovered.pageIds.single())
        assertEquals(local.trashedPageIds.single(), target.loadAudioRecording(audio.id).bookmarks.single().pageId)
    }

    private fun repository(name: String): DocumentRepository {
        val base = RuntimeEnvironment.getApplication()
        val files = File(base.cacheDir, "sync-$name-${newId()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        return DocumentRepository(context)
    }
    private suspend fun archive(repository: DocumentRepository, id: String): ByteArray =
        ByteArrayOutputStream().also { repository.exportNotebookBackup(id, it) }.toByteArray()
    private suspend fun write(repository: DocumentRepository, book: Notebook, text: String) {
        val current = repository.loadPage(book.pageIds.single())
        repository.savePage(current.copy(revision = current.revision + 1,
            elements = listOf(TextElement(text = text, x = 1f, y = 1f))), current.revision)
    }
    private suspend fun text(repository: DocumentRepository, book: Notebook): String =
        (repository.loadPage(book.pageIds.single()).elements.single() as TextElement).text
}
