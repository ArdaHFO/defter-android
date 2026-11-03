package dev.defter.app.data

import android.graphics.Bitmap
import android.net.Uri
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], manifest = Config.NONE)
class PermanentDeletionTest {
    private lateinit var repository: DocumentRepository

    @Before fun setup() {
        repository = DocumentRepository(RuntimeEnvironment.getApplication())
        // Only Robolectric's disposable application directory is used by these fixtures.
        repository.root.deleteRecursively()
    }

    @Test fun selectedThenAllTrashedNotebooksRemoveTheirPagesButKeepActiveAndUnselectedNotes() = runBlocking {
        val first = book()
        val second = book()
        val active = book()
        val extra = repository.addPage(first.id, PaperTemplate.GRID)
        repository.deletePage(first.id, extra.id)
        val stalePage = repository.loadPage(first.pageIds.single())
        repository.setNotebookDeleted(first.id, true)
        repository.setNotebookDeleted(second.id, true)

        val selected = repository.permanentlyDeleteNotebooks(setOf(first.id))
        assertEquals(setOf(second.id, active.id), selected.notebooks.map { it.id }.toSet())
        assertFalse(pageFile(stalePage.id).exists())
        assertFalse(pageFile(extra.id).exists())
        assertTrue(pageFile(second.pageIds.single()).isFile)
        rejected { repository.savePage(stalePage.copy(revision = 1), expectedRevision = 0) }
        rejected { repository.setNotebookDeleted(first.id, false) }

        val all = repository.permanentlyDeleteNotebooks(selected.notebooks.filter { it.deletedAt != null }.map { it.id }.toSet())
        assertEquals(listOf(active.id), all.notebooks.map { it.id })
        assertTrue(pageFile(active.pageIds.single()).isFile)
        val archive = entries(backup())
        assertFalse("pages/${stalePage.id}.json" in archive)
        assertFalse("pages/${extra.id}.json" in archive)
        assertTrue("pages/${active.pageIds.single()}.json" in archive)
    }

    @Test fun activeOrMissingNotebookInBatchRejectsEveryRemovalAndEmptySelectionIsNoOp() = runBlocking {
        val trashed = book()
        val active = book()
        repository.setNotebookDeleted(trashed.id, true)
        val before = repository.loadLibrary()
        rejected { repository.permanentlyDeleteNotebooks(setOf(trashed.id, active.id)) }
        rejected { repository.permanentlyDeleteNotebooks(setOf(trashed.id, "missing")) }
        assertEquals(before, repository.loadLibrary())
        assertEquals(before, repository.permanentlyDeleteNotebooks(emptySet()))
        assertTrue(pageFile(trashed.pageIds.single()).isFile)
    }

    @Test fun sharedImageAndPdfAreKeptUntilTheirLastIndexedNotebookIsPurged() = runBlocking {
        val first = book()
        val second = book()
        val image = image()
        val pdfId = newId()
        repository.pdfAssetFile(pdfId).writeText("%PDF- synthetic reference-only fixture")
        for (notebook in listOf(first, second)) {
            val page = repository.loadPage(notebook.pageIds.single())
            repository.savePage(page.copy(revision = 1,
                pdfBackground = PdfBackground(pdfId, 0, 100f, 100f),
                elements = listOf(ImageElement(assetId = image.assetId, x = 0f, y = 0f, width = 10f, height = 10f))), 0)
        }
        repository.setNotebookDeleted(first.id, true)
        repository.permanentlyDeleteNotebooks(setOf(first.id))
        assertTrue(repository.assetFile(image.assetId).isFile)
        assertTrue(repository.pdfAssetFile(pdfId).isFile)
        assertEquals(image.assetId, (repository.loadPage(second.pageIds.single()).elements.single() as ImageElement).assetId)
        repository.setNotebookDeleted(second.id, true)
        repository.permanentlyDeleteNotebooks(setOf(second.id))
        assertFalse(repository.assetFile(image.assetId).exists())
        assertFalse(repository.pdfAssetFile(pdfId).exists())
    }

    @Test fun notebookPurgeDetachesLinkedAndIndependentAudioWithoutLosingBookmarksOrTranscript() = runBlocking {
        val removed = book()
        val kept = book()
        val linked = audio(removed.id)
        repository.updateTranscript(linked.id, listOf(TranscriptSegment(startMs = 0, endMs = 500, text = "Preserved lesson")))
        repository.addAudioBookmark(linked.id, 250, "Removed page", removed.pageIds.single())
        val linkedBefore = repository.addAudioBookmark(linked.id, 500, "Kept page", kept.pageIds.single())
        val independent = audio()
        repository.addAudioBookmark(independent.id, 100, "Independent bookmark", removed.pageIds.single())
        repository.setAudioDeleted(independent.id, true)
        val independentBefore = repository.loadAudioRecording(independent.id)
        repository.setNotebookDeleted(removed.id, true)

        repository.permanentlyDeleteNotebooks(setOf(removed.id))
        val linkedAfter = repository.loadAudioRecording(linked.id)
        assertNull(linkedAfter.notebookId)
        assertEquals(linkedBefore.transcript, linkedAfter.transcript)
        assertEquals(linkedBefore.parts, linkedAfter.parts)
        assertEquals(linkedBefore.bookmarks.map { it.copy(pageId = it.pageId?.takeUnless { id -> id == removed.pageIds.single() }) }, linkedAfter.bookmarks)
        assertEquals(linkedBefore.revision + 1, linkedAfter.revision)
        val independentAfter = repository.loadAudioRecording(independent.id)
        assertEquals(independentBefore.deletedAt, independentAfter.deletedAt)
        assertEquals(independentBefore.bookmarks.single().copy(pageId = null), independentAfter.bookmarks.single())
        assertTrue(linkedAfter.parts.all { repository.audioPartFile(it).isFile })
        // A normal backup/restore must remain valid after all removed page links are detached.
        val restored = repository.importBackup(ByteArrayInputStream(backup()))
        assertEquals(4, restored.audioItems.size)
        assertFalse(restored.notebooks.any { it.id == removed.id })
    }

    @Test fun failedNotebookIndexCommitPreservesPagesMediaAndOriginalAudioRevision() = runBlocking {
        val removed = book()
        val recording = audio(removed.id)
        repository.addAudioBookmark(recording.id, 10, "Keep on failure", removed.pageIds.single())
        repository.setNotebookDeleted(removed.id, true)
        val before = repository.loadLibrary()
        val audioBefore = repository.loadAudioRecording(recording.id)
        val obstruction = obstructIndex()
        rejected { repository.permanentlyDeleteNotebooks(setOf(removed.id)) }
        assertEquals(before, repository.loadLibrary())
        assertEquals(audioBefore, repository.loadAudioRecording(recording.id))
        assertTrue(pageFile(removed.pageIds.single()).isFile)
        assertTrue(audioBefore.parts.all { repository.audioPartFile(it).isFile })
        obstruction.deleteRecursively()
        assertTrue(repository.permanentlyDeleteNotebooks(setOf(removed.id)).notebooks.isEmpty())
    }

    @Test fun selectedThenAllTrashedAudioKeepSharedMediaAndPreventStaleWriters() = runBlocking {
        val first = audio()
        val shared = fixtureAudioSharingParts(first)
        val active = audio()
        repository.setAudioDeleted(first.id, true)
        repository.setAudioDeleted(shared.id, true)
        val selected = repository.permanentlyDeleteAudio(setOf(first.id))
        assertEquals(setOf(shared.id, active.id), selected.audioItems.map { it.id }.toSet())
        assertTrue(repository.audioPartFile(first.parts.single()).isFile)
        assertFalse(recordingNames().any { it.startsWith("${first.id}_") })
        rejected { repository.updateTranscript(first.id, emptyList()) }
        rejected { repository.setAudioState(first.id, AudioRecordingState.READY) }
        rejected { repository.commitAudioPart(first.id, wave(), 1000, "audio/wav") }
        rejected { repository.setAudioDeleted(first.id, false) }
        val all = repository.permanentlyDeleteAudio(selected.audioItems.filter { it.deletedAt != null }.map { it.id }.toSet())
        assertEquals(listOf(active.id), all.audioItems.map { it.id })
        assertFalse(repository.audioPartFile(first.parts.single()).exists())
        assertTrue(repository.audioPartFile(active.parts.single()).isFile)
        val archive = entries(backup())
        assertFalse("recordings/${first.id}.json" in archive)
        assertFalse("assets/${first.parts.single().assetId}.audio" in archive)
        assertTrue("recordings/${active.id}.json" in archive)
    }

    @Test fun activeOrMissingAudioInBatchRejectsAllAndEmptySelectionIsNoOp() = runBlocking {
        val trashed = audio()
        val active = audio()
        repository.setAudioDeleted(trashed.id, true)
        val before = repository.loadLibrary()
        rejected { repository.permanentlyDeleteAudio(setOf(trashed.id, active.id)) }
        rejected { repository.permanentlyDeleteAudio(setOf(trashed.id, "missing")) }
        assertEquals(before, repository.loadLibrary())
        assertEquals(before, repository.permanentlyDeleteAudio(emptySet()))
        assertTrue(repository.audioPartFile(trashed.parts.single()).isFile)
        assertNotNull(repository.loadAudioRecording(trashed.id))
    }

    @Test fun failedAudioIndexCommitDoesNotDeleteMetadataOrMedia() = runBlocking {
        val recording = audio()
        repository.setAudioDeleted(recording.id, true)
        val before = repository.loadLibrary()
        val beforeAudio = repository.loadAudioRecording(recording.id)
        val names = recordingNames()
        val obstruction = obstructIndex()
        rejected { repository.permanentlyDeleteAudio(setOf(recording.id)) }
        assertEquals(before, repository.loadLibrary())
        assertEquals(beforeAudio, repository.loadAudioRecording(recording.id))
        assertEquals(names, recordingNames())
        assertTrue(repository.audioPartFile(recording.parts.single()).isFile)
        obstruction.deleteRecursively()
        assertTrue(repository.permanentlyDeleteAudio(setOf(recording.id)).audioItems.isEmpty())
    }

    @Test fun runningTranscriptionEvenInTrashCannotBePermanentlyDeleted() = runBlocking {
        val recording = audio()
        repository.saveTranscription(recording.id, emptyList(), 0, null, "tr", TranscriptionStatus.RUNNING)
        repository.setAudioDeleted(recording.id, true)
        val before = repository.loadLibrary()
        rejected { repository.permanentlyDeleteAudio(setOf(recording.id)) }
        assertEquals(before, repository.loadLibrary())
        assertTrue(repository.audioPartFile(recording.parts.single()).isFile)
    }

    @Test fun linkedRecordingOrPausedCaptureBlocksWholeNotebookPurgeWithoutPublishingRevisions() = runBlocking {
        val linkedBook = book()
        val otherBook = book()
        val capture = repository.createAudioRecording("Live linked capture", notebookId = linkedBook.id)
        repository.setNotebookDeleted(linkedBook.id, true)
        repository.setNotebookDeleted(otherBook.id, true)
        for (state in listOf(AudioRecordingState.RECORDING, AudioRecordingState.PAUSED)) {
            repository.setAudioState(capture.id, state)
            val before = repository.loadLibrary()
            val audioBefore = repository.loadAudioRecording(capture.id)
            val namesBefore = recordingNames()
            rejected { repository.permanentlyDeleteNotebooks(setOf(linkedBook.id, otherBook.id)) }
            assertEquals(before, repository.loadLibrary())
            assertEquals(audioBefore, repository.loadAudioRecording(capture.id))
            assertEquals(namesBefore, recordingNames())
            assertTrue(pageFile(linkedBook.pageIds.single()).isFile)
            assertTrue(pageFile(otherBook.pageIds.single()).isFile)
        }
        repository.setAudioState(capture.id, AudioRecordingState.READY)
        repository.permanentlyDeleteNotebooks(setOf(linkedBook.id, otherBook.id))
        assertTrue(repository.loadLibrary().notebooks.isEmpty())
        assertNull(repository.loadAudioRecording(capture.id).notebookId)
    }

    @Test fun independentlyBookmarkedRunningTranscriptionBlocksWholeNotebookPurge() = runBlocking {
        val referencedBook = book()
        val otherBook = book()
        val transcription = audio()
        repository.addAudioBookmark(transcription.id, 100, "Page needed by live transcription", referencedBook.pageIds.single())
        repository.saveTranscription(transcription.id, emptyList(), 0, null, "tr", TranscriptionStatus.RUNNING)
        repository.setNotebookDeleted(referencedBook.id, true)
        repository.setNotebookDeleted(otherBook.id, true)
        val before = repository.loadLibrary()
        val audioBefore = repository.loadAudioRecording(transcription.id)
        val namesBefore = recordingNames()
        rejected { repository.permanentlyDeleteNotebooks(setOf(referencedBook.id, otherBook.id)) }
        assertEquals(before, repository.loadLibrary())
        assertEquals(audioBefore, repository.loadAudioRecording(transcription.id))
        assertEquals(namesBefore, recordingNames())
        assertTrue(pageFile(referencedBook.pageIds.single()).isFile)
        assertTrue(pageFile(otherBook.pageIds.single()).isFile)
        assertTrue(audioBefore.parts.all { repository.audioPartFile(it).isFile })
        repository.saveTranscription(transcription.id, emptyList(), 0, null, "tr", TranscriptionStatus.PAUSED)
        repository.permanentlyDeleteNotebooks(setOf(referencedBook.id, otherBook.id))
        assertNull(repository.loadAudioRecording(transcription.id).bookmarks.single().pageId)
    }

    @Test fun unrelatedActiveRecordingAndTranscriptionDoNotBlockOrChangeDuringNotebookPurge() = runBlocking {
        val removed = book()
        val kept = book()
        val capture = repository.createAudioRecording("Unrelated active capture", notebookId = kept.id)
        val transcription = audio()
        repository.addAudioBookmark(transcription.id, 200, "Kept page", kept.pageIds.single())
        val beforeTranscription = repository.saveTranscription(transcription.id, emptyList(), 0, null, "tr", TranscriptionStatus.RUNNING)
        repository.setNotebookDeleted(removed.id, true)
        val updated = repository.permanentlyDeleteNotebooks(setOf(removed.id))
        assertEquals(listOf(kept.id), updated.notebooks.map { it.id })
        assertEquals(capture, repository.loadAudioRecording(capture.id))
        assertEquals(beforeTranscription, repository.loadAudioRecording(transcription.id))
        assertTrue(pageFile(kept.pageIds.single()).isFile)
        assertTrue(transcription.parts.all { repository.audioPartFile(it).isFile })
    }

    @Test fun inFlightSyncCannotRecreateAPermanentlyDeletedNotebook() = runBlocking {
        val notebook = book()
        val originalFingerprint = repository.getNotebookFingerprint(notebook.id)
        val archive = ByteArrayOutputStream().also { repository.exportNotebookBackup(notebook.id, it) }.toByteArray()
        repository.setNotebookDeleted(notebook.id, true)
        repository.permanentlyDeleteNotebooks(setOf(notebook.id))
        rejected { repository.importSyncBackup(ByteArrayInputStream(archive), originalFingerprint, notebook.id) }
        assertTrue(repository.loadLibrary().notebooks.isEmpty())
        assertFalse(pageFile(notebook.pageIds.single()).exists())
    }

    private suspend fun book() = repository.createNotebook("Notebook ${newId()}", null, PaperTemplate.BLANK, 0)
    private fun pageFile(id: String) = File(repository.root, "pages/$id.json")
    private fun recordingNames() = File(repository.root, "recordings").listFiles().orEmpty().map { it.name }.toSet()
    private fun obstructIndex() = File(repository.root, "library.json.new").apply { mkdir(); resolve("block").writeText("preserve") }
    private suspend fun backup() = ByteArrayOutputStream().also { repository.exportBackup(it) }.toByteArray()
    private suspend fun audio(notebookId: String? = null): AudioRecording {
        val recording = repository.createAudioRecording("Recording ${newId()}", notebookId = notebookId)
        repository.commitAudioPart(recording.id, wave(), 1000, "audio/wav")
        return repository.setAudioState(recording.id, AudioRecordingState.READY)
    }
    private suspend fun fixtureAudioSharingParts(source: AudioRecording): AudioRecording {
        // Valid immutable fixture as can arrive in a backup with deduplicated media references.
        val copy = source.copy(id = newId(), title = "Shared media", revision = 0,
            parts = source.parts.map { it.copy(id = newId(), sourceKey = null) })
        File(repository.root, "recordings/${copy.id}_0.json").writeText(AudioJson.toJson(copy).toString())
        val library = repository.loadLibrary()
        File(repository.root, "library.json").writeText(DocumentJson.libraryToJson(library.copy(audioItems = library.audioItems + copy.toItem())).toString())
        return copy
    }
    private suspend fun image(): ImageAsset {
        val source = File(RuntimeEnvironment.getApplication().cacheDir, "${newId()}.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return repository.importImage(Uri.fromFile(source))
    }
    private fun wave(): File {
        val samples = ByteArray(32_000)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size)
        }.array()
        return File(RuntimeEnvironment.getApplication().cacheDir, "${newId()}.wav").apply {
            outputStream().use { it.write(header); it.write(samples) }
        }
    }
    private fun entries(bytes: ByteArray): Set<String> = buildSet {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; add(entry.name); zip.closeEntry() }
        }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Invalid permanent deletion or stale write accepted") } catch (_: Exception) { }
    }
}
