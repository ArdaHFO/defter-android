package dev.defter.app.data

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AudioStorageTest {
    private lateinit var repository: DocumentRepository
    @Before fun setup() {
        repository = DocumentRepository(RuntimeEnvironment.getApplication())
        repository.root.deleteRecursively()
    }

    @Test fun versionOneAndTwoMigrateWithoutInventingAudioAndV3RequiresItsIndex() {
        for (version in 1..2) {
            val decoded = DocumentJson.libraryFromJson(JSONObject().put("version", version).put("folders", org.json.JSONArray()).put("notebooks", org.json.JSONArray()))
            assertEquals(3, decoded.version)
            assertTrue(decoded.audioItems.isEmpty())
        }
        try {
            DocumentJson.libraryFromJson(JSONObject("{\"version\":3,\"folders\":[],\"notebooks\":[]}"))
            fail("Missing V3 audio index accepted")
        } catch (_: java.io.IOException) { }
    }

    @Test fun independentAndNotebookBoundRecordingsPersistOutsideLibraryTranscript() = runBlocking {
        val folder = repository.createFolder("Ders", null).folders.single()
        val book = book(folder.id)
        val independent = repository.createAudioRecording("Bağımsız", folder.id)
        val linked = repository.createAudioRecording("Notlu", notebookId = book.id)
        assertNull(repository.loadAudioRecording(independent.id).notebookId)
        assertEquals(book.id, repository.loadAudioRecording(linked.id).notebookId)
        assertEquals(2, repository.loadLibrary().audioItems.size)
        assertFalse(File(repository.root, "library.json").readText().contains("transcript"))
    }

    @Test fun partCommitIsIdempotentAcrossRepositoryInstancesAndDoesNotDeleteSource() = runBlocking {
        val recording = repository.createAudioRecording("Ders")
        val source = wave()
        val first = repository.commitAudioPart(recording.id, source, 1000, "audio/wav")
        val another = DocumentRepository(RuntimeEnvironment.getApplication())
        val retry = another.commitAudioPart(recording.id, source, 1000, "audio/wav")
        assertEquals(first, retry)
        assertEquals(1, retry.parts.size)
        assertTrue(source.isFile)
        assertArrayEquals(source.readBytes(), repository.audioPartFile(first.parts.single()).readBytes())
        val second = repository.commitAudioPart(recording.id, wave(), 2000, "audio/wav")
        assertEquals(1000L, second.parts.last().offsetMs)
        assertEquals(3000L, second.durationMs)
    }

    @Test fun transcriptProgressBookmarksAndManualEditRoundTripWithCompareAndSwap() = runBlocking {
        val book = book()
        val initial = audio(book.id)
        val recognized = repository.saveTranscription(initial.id, listOf(TranscriptSegment(startMs = 0, endMs = 1000, text = "Ders başladı")),
            1000, "tiny", "tr", TranscriptionStatus.COMPLETE, expectedRevision = initial.revision)
        val marked = repository.addAudioBookmark(initial.id, 250, "Tanım", book.pageIds.single())
        rejected { repository.saveTranscription(initial.id, emptyList(), 0, "tiny", "tr", TranscriptionStatus.RUNNING, expectedRevision = recognized.revision) }
        val edited = repository.updateTranscript(initial.id, marked.transcript.map { it.copy(text = "Ders başladı.", edited = true) }, marked.revision)
        assertEquals(1000L, edited.transcribedUntilMs)
        assertEquals(TranscriptionStatus.COMPLETE, edited.transcriptionStatus)
        assertTrue(edited.transcript.single().edited)
        assertEquals(edited, repository.loadAudioRecording(initial.id))
        assertEquals(edited, AudioJson.fromJson(AudioJson.toJson(edited)))
    }

    @Test fun invalidTimingAndMissingLinksLeavePriorRecordingUnchanged() = runBlocking {
        val initial = audio()
        rejected { repository.updateTranscript(initial.id, listOf(TranscriptSegment(startMs = 0, endMs = 1001, text = "overrun"))) }
        rejected { repository.addAudioBookmark(initial.id, -1) }
        rejected { repository.addAudioBookmark(initial.id, 0, pageId = "missing") }
        rejected { repository.linkAudio(initial.id, "missing") }
        rejected { repository.moveAudio(initial.id, "missing") }
        rejected { repository.saveTranscription(initial.id, emptyList(), 1001, "tiny", "tr", TranscriptionStatus.COMPLETE) }
        assertEquals(initial, repository.loadAudioRecording(initial.id))
    }

    @Test fun invalidContainerAndDurationNeverPublishOrModifyPriorNotebook() = runBlocking {
        book()
        val recording = repository.createAudioRecording("Ses")
        val original = repository.loadLibrary()
        val invalid = File(RuntimeEnvironment.getApplication().cacheDir, "${newId()}.wav").apply { writeText("this is not audio media") }
        rejected { repository.commitAudioPart(recording.id, invalid, 1000, "audio/wav") }
        rejected { repository.commitAudioPart(recording.id, wave(), -1, "audio/wav") }
        assertEquals(original, repository.loadLibrary())
        assertEquals(recording, repository.loadAudioRecording(recording.id))
    }

    @Test fun failedLibraryPublicationPreservesFullPriorTranscriptAndAllowsRetry() = runBlocking {
        val initial = audio()
        val obstruction = File(repository.root, "library.json.new").apply { mkdir(); resolve("block").writeText("keep") }
        val segment = TranscriptSegment(startMs = 0, endMs = 500, text = "Kalıcı metin")
        rejected { repository.updateTranscript(initial.id, listOf(segment), initial.revision) }
        assertEquals(initial, repository.loadAudioRecording(initial.id))
        obstruction.deleteRecursively()
        val retried = repository.updateTranscript(initial.id, listOf(segment), initial.revision)
        assertEquals(segment, retried.transcript.single())
        assertEquals(initial.revision + 1, retried.revision)
    }

    @Test fun failedPartPublicationCanRetryWithoutDuplicateOrLostSource() = runBlocking {
        val initial = repository.createAudioRecording("Ses")
        val source = wave()
        val obstruction = File(repository.root, "library.json.new").apply { mkdir(); resolve("block").writeText("keep") }
        rejected { repository.commitAudioPart(initial.id, source, 1000, "audio/wav") }
        assertTrue(source.isFile)
        assertTrue(repository.loadAudioRecording(initial.id).parts.isEmpty())
        obstruction.deleteRecursively()
        repository.commitAudioPart(initial.id, source, 1000, "audio/wav")
        assertEquals(1, repository.commitAudioPart(initial.id, source, 1000, "audio/wav").parts.size)
    }

    @Test fun moveRenameLinkTrashAndRestoreKeepMediaAndTranscript() = runBlocking {
        val initial = audio()
        val folder = repository.createFolder("Arşiv", null).folders.single()
        val book = book()
        repository.moveAudio(initial.id, folder.id)
        repository.renameAudio(initial.id, "Yeni ad")
        repository.linkAudio(initial.id, book.id)
        repository.setAudioDeleted(initial.id, true)
        assertNotNull(repository.loadAudioRecording(initial.id).deletedAt)
        repository.setAudioDeleted(initial.id, false)
        val restored = repository.loadAudioRecording(initial.id)
        assertNull(restored.deletedAt)
        assertEquals(folder.id, restored.folderId)
        assertEquals(book.id, restored.notebookId)
        assertEquals("Yeni ad", restored.title)
        assertEquals(initial.parts, restored.parts)
        assertTrue(repository.audioPartFile(restored.parts.single()).isFile)
    }

    @Test fun fullBackupIncludesIndependentTrashedAndLinkedAudioAndRebindsEveryIdentity() = runBlocking {
        val book = book()
        val linked = audio(book.id)
        repository.addAudioBookmark(linked.id, 300, "Önemli", book.pageIds.single())
        repository.updateTranscript(linked.id, listOf(TranscriptSegment(startMs = 0, endMs = 1000, text = "Yedek metni", edited = true)))
        val independent = audio()
        repository.setAudioDeleted(independent.id, true)
        val bytes = ByteArrayOutputStream().also { repository.exportBackup(it) }.toByteArray()
        val files = entries(bytes)
        assertTrue(files.contains("recordings/${linked.id}.json"))
        assertTrue(files.contains("assets/${linked.parts.single().assetId}.audio"))
        val merged = repository.importBackup(ByteArrayInputStream(bytes))
        val importedBook = merged.notebooks.last()
        val imported = repository.loadAudioRecording(merged.audioItems.last { it.notebookId == importedBook.id }.id)
        assertNotEquals(linked.id, imported.id)
        assertNotEquals(linked.parts.single().assetId, imported.parts.single().assetId)
        assertEquals(importedBook.pageIds.single(), imported.bookmarks.single().pageId)
        assertEquals("Yedek metni", imported.transcript.single().text)
        assertTrue(imported.transcript.single().edited)
        assertArrayEquals(repository.audioPartFile(linked.parts.single()).readBytes(), repository.audioPartFile(imported.parts.single()).readBytes())
        assertEquals(2, merged.audioItems.count { it.deletedAt != null })
        assertEquals(4, merged.audioItems.size)
    }

    @Test fun notebookBackupIncludesOnlyItsAudioAndClearsExternalPageLink() = runBlocking {
        val first = book()
        val second = book()
        val linked = audio(first.id)
        repository.addAudioBookmark(linked.id, 300, "Dış sayfa", second.pageIds.single())
        val independent = audio()
        val bytes = ByteArrayOutputStream().also { repository.exportNotebookBackup(first.id, it) }.toByteArray()
        assertFalse(entries(bytes).contains("recordings/${independent.id}.json"))
        val merged = repository.importBackup(ByteArrayInputStream(bytes))
        val imported = repository.loadAudioRecording(merged.audioItems.last().id)
        assertNull(imported.bookmarks.single().pageId)
        assertEquals(merged.notebooks.last().id, imported.notebookId)
    }

    @Test fun backupOfInterruptedWorkRestoresSafeStatesAndMissingAssetFailsWithoutLibraryChange() = runBlocking {
        val initial = audio()
        repository.setAudioState(initial.id, AudioRecordingState.RECORDING)
        val current = repository.loadAudioRecording(initial.id)
        repository.saveTranscription(initial.id, emptyList(), 0, "tiny", "tr", TranscriptionStatus.RUNNING, expectedRevision = current.revision)
        val bytes = ByteArrayOutputStream().also { repository.exportBackup(it) }.toByteArray()
        val merged = repository.importBackup(ByteArrayInputStream(bytes))
        val restored = repository.loadAudioRecording(merged.audioItems.last().id)
        assertEquals(AudioRecordingState.INTERRUPTED, restored.state)
        assertEquals(TranscriptionStatus.PAUSED, restored.transcriptionStatus)
        val baseline = repository.loadLibrary()
        repository.audioPartFile(initial.parts.single()).delete()
        rejected { repository.exportBackup(ByteArrayOutputStream()) }
        assertEquals(baseline, repository.loadLibrary())
    }

    @Test fun tamperedAudioArchiveIsRejectedBeforePublishingAnyImportedEntry() = runBlocking {
        book()
        val recording = audio()
        val baseline = repository.loadLibrary()
        val original = ByteArrayOutputStream().also { repository.exportBackup(it) }.toByteArray()
        val tampered = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zipOut ->
                ZipInputStream(ByteArrayInputStream(original)).use { zipIn ->
                    while (true) {
                        val entry = zipIn.nextEntry ?: break
                        val bytes = zipIn.readBytes()
                        if (entry.name.endsWith(".audio")) bytes[bytes.lastIndex] = 1
                        zipOut.putNextEntry(ZipEntry(entry.name)); zipOut.write(bytes); zipOut.closeEntry()
                        zipIn.closeEntry()
                    }
                }
            }
        }.toByteArray()
        rejected { repository.importBackup(ByteArrayInputStream(tampered)) }
        assertEquals(baseline, repository.loadLibrary())
        assertEquals(recording, repository.loadAudioRecording(recording.id))
    }

    @Test fun liveBookmarksSurviveInterruptionAndActiveRecordingCannotBeTrashed() = runBlocking {
        val recording = repository.createAudioRecording("Canlı ders")
        repository.addAudioBookmark(recording.id, 25_000, "Henüz tamamlanmamış parça")
        rejected { repository.setAudioDeleted(recording.id, true) }
        val interrupted = repository.setAudioState(recording.id, AudioRecordingState.INTERRUPTED)
        assertEquals(0L, interrupted.durationMs)
        assertEquals(25_000L, interrupted.bookmarks.single().positionMs)
        repository.setAudioDeleted(recording.id, true)
        repository.setAudioDeleted(recording.id, false)
        assertEquals(interrupted.bookmarks, repository.loadAudioRecording(recording.id).bookmarks)
    }

    private suspend fun audio(notebookId: String? = null): AudioRecording {
        val initial = repository.createAudioRecording("Ses ${newId()}", notebookId = notebookId)
        repository.commitAudioPart(initial.id, wave(), 1000, "audio/wav")
        return repository.setAudioState(initial.id, AudioRecordingState.READY)
    }
    private suspend fun book(folderId: String? = null) = repository.createNotebook("Defter", folderId, PaperTemplate.DOTS, 0)
    private fun wave(): File {
        val samples = ByteArray(32_000)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size)
        }.array()
        return File(RuntimeEnvironment.getApplication().cacheDir, "${newId()}.wav").apply { outputStream().use { it.write(header); it.write(samples) } }
    }
    private fun entries(bytes: ByteArray): Set<String> = buildSet {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val next = zip.nextEntry ?: break; add(next.name); zip.closeEntry() } }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Invalid mutation accepted") } catch (expected: Exception) { }
    }
}
