package dev.defter.app.audio

import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fake inference, real durable repository: these do not claim native speech accuracy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TranscriptionRunnerTest {
    private lateinit var repository: DocumentRepository
    @Before fun setup() {
        repository = DocumentRepository(RuntimeEnvironment.getApplication())
        repository.root.deleteRecursively()
    }

    @Test fun everyChunkIncludingSilenceHasADurableCheckpointBeforeTheNextChunk() = runBlocking {
        val initial = recording(1000)
        val progress = mutableListOf<Long>()
        val engine = fake { _, start, language, emit ->
            assertEquals(0L, start); assertEquals("tr", language)
            emit(WhisperChunk(0, 400, listOf(WhisperSegment(0, 300, " İlk bölüm "))))
            val first = repository.loadAudioRecording(initial.id)
            assertEquals(400L, first.transcribedUntilMs)
            assertEquals("İlk bölüm", first.transcript.single().text)
            emit(WhisperChunk(400, 800, emptyList()))
            val silent = repository.loadAudioRecording(initial.id)
            assertEquals(800L, silent.transcribedUntilMs)
            assertEquals(first.transcript, silent.transcript)
            emit(WhisperChunk(800, 1000, listOf(WhisperSegment(800, 1000, "Son bölüm"))))
        }
        TranscriptionRunner(repository, engine).run(initial.id, "tr") { done, total -> assertEquals(1000L, total); progress += done }
        val done = repository.loadAudioRecording(initial.id)
        assertEquals(TranscriptionStatus.COMPLETE, done.transcriptionStatus)
        assertEquals(1000L, done.transcribedUntilMs)
        assertEquals(listOf("İlk bölüm", "Son bölüm"), done.transcript.map { it.text })
        assertTrue(progress.containsAll(listOf(0L, 400L, 800L, 1000L)))
    }

    @Test fun resumeSkipsCompletedPartsAndUsesGlobalTimestampsForLaterParts() = runBlocking {
        val initial = recording(1000, 2000)
        val existing = TranscriptSegment(startMs = 0, endMs = 900, text = "Korunacak", edited = true)
        repository.saveTranscription(initial.id, listOf(existing), 1500, TranscriptionRunner.MODEL_ID, "tr", TranscriptionStatus.PAUSED)
        var calls = 0
        val engine = fake { file, start, _, emit ->
            calls++
            assertEquals(repository.audioPartFile(initial.parts.last()), file)
            assertEquals(500L, start)
            emit(WhisperChunk(500, 2000, listOf(WhisperSegment(500, 1900, "İkinci parça"))))
        }
        TranscriptionRunner(repository, engine).run(initial.id, "tr")
        val done = repository.loadAudioRecording(initial.id)
        assertEquals(1, calls)
        assertEquals(existing, done.transcript.first())
        assertEquals(1500L, done.transcript.last().startMs)
        assertEquals(2900L, done.transcript.last().endMs)
        assertEquals(3000L, done.transcribedUntilMs)
    }

    @Test(timeout = 15_000) fun cancellationPreservesCompletedChunkAndPausesDurably() = runBlocking {
        val initial = recording(1000)
        val checkpointSaved = CompletableDeferred<Unit>()
        val engine = fake { _, _, _, emit ->
            emit(WhisperChunk(0, 400, listOf(WhisperSegment(0, 300, "Kaydedildi"))))
            checkpointSaved.complete(Unit)
            awaitCancellation()
        }
        val job = launch { TranscriptionRunner(repository, engine).run(initial.id, "tr") }
        checkpointSaved.await()
        job.cancelAndJoin()
        val paused = repository.loadAudioRecording(initial.id)
        assertEquals(TranscriptionStatus.PAUSED, paused.transcriptionStatus)
        assertEquals(400L, paused.transcribedUntilMs)
        assertEquals("Kaydedildi", paused.transcript.single().text)
        assertNull(paused.transcriptionError)
    }

    @Test fun engineFailureKeepsPartialTextAndCheckpointForResume() = runBlocking {
        val initial = recording(1000)
        val engine = fake { _, _, _, emit ->
            emit(WhisperChunk(0, 250, listOf(WhisperSegment(0, 200, "Kısmi"))))
            throw IOException("Örnek çözümleyici hatası")
        }
        rejected { TranscriptionRunner(repository, engine).run(initial.id, "tr") }
        val failed = repository.loadAudioRecording(initial.id)
        assertEquals(TranscriptionStatus.FAILED, failed.transcriptionStatus)
        assertEquals(250L, failed.transcribedUntilMs)
        assertEquals("Kısmi", failed.transcript.single().text)
        assertTrue(failed.transcriptionError.orEmpty().contains("çözümleyici"))
    }

    @Test fun concurrentManualEditIsPreservedWhenNextChunkDetectsConflict() = runBlocking {
        val initial = recording(1000)
        val engine = fake { _, _, _, emit ->
            emit(WhisperChunk(0, 400, listOf(WhisperSegment(0, 300, "Otomatik"))))
            val latest = repository.loadAudioRecording(initial.id)
            repository.updateTranscript(initial.id, latest.transcript.map { it.copy(text = "Kullanıcının düzeltmesi", edited = true) }, latest.revision)
            emit(WhisperChunk(400, 1000, listOf(WhisperSegment(400, 900, "Eski işin devamı"))))
        }
        rejected { TranscriptionRunner(repository, engine).run(initial.id, "tr") }
        val preserved = repository.loadAudioRecording(initial.id)
        assertEquals(1, preserved.transcript.size)
        assertEquals("Kullanıcının düzeltmesi", preserved.transcript.single().text)
        assertTrue(preserved.transcript.single().edited)
        assertEquals(400L, preserved.transcribedUntilMs)
    }

    @Test fun deletedEntryCannotStartAndDeletionDuringInferenceDoesNotResurrectIt() = runBlocking {
        val initial = recording(1000)
        repository.setAudioDeleted(initial.id, true)
        val deleted = repository.loadAudioRecording(initial.id)
        rejected { TranscriptionRunner(repository, fake { _, _, _, _ -> fail("Engine called for trash") }).run(initial.id, "tr") }
        assertEquals(deleted, repository.loadAudioRecording(initial.id))
        repository.setAudioDeleted(initial.id, false)
        val engine = fake { _, _, _, emit ->
            emit(WhisperChunk(0, 300, listOf(WhisperSegment(0, 200, "Kayıt"))))
            repository.setAudioDeleted(initial.id, true)
            emit(WhisperChunk(300, 1000, listOf(WhisperSegment(300, 900, "Eklenmemeli"))))
        }
        rejected { TranscriptionRunner(repository, engine).run(initial.id, "tr") }
        val after = repository.loadAudioRecording(initial.id)
        assertNotNull(after.deletedAt)
        assertEquals(300L, after.transcribedUntilMs)
        assertEquals(listOf("Kayıt"), after.transcript.map { it.text })
    }

    @Test fun changingLanguageRequiresExplicitRestartAndResumeNeverDropsEditedPrefix() = runBlocking {
        val initial = recording(1000)
        val segment = TranscriptSegment(startMs = 0, endMs = 300, text = "Türkçe düzeltme", edited = true)
        repository.saveTranscription(initial.id, listOf(segment), 400, TranscriptionRunner.MODEL_ID, "tr", TranscriptionStatus.PAUSED)
        val before = repository.loadAudioRecording(initial.id)
        rejected { TranscriptionRunner(repository, fake { _, _, _, _ -> fail("Unexpected inference") }).run(initial.id, "en") }
        assertEquals(before, repository.loadAudioRecording(initial.id))
        val engine = fake { _, start, language, emit ->
            assertEquals(0L, start); assertEquals("en", language)
            emit(WhisperChunk(0, 1000, listOf(WhisperSegment(0, 900, "English result"))))
        }
        TranscriptionRunner(repository, engine).run(initial.id, "en", restart = true)
        val restarted = repository.loadAudioRecording(initial.id)
        assertEquals("en", restarted.transcriptionLanguage)
        assertEquals(listOf("English result"), restarted.transcript.map { it.text })
    }

    @Test fun concurrentSilentCheckpointChangeCannotBeOverwrittenAtPartCompletion() = runBlocking {
        val initial = recording(1000)
        val engine = fake { _, _, _, emit ->
            emit(WhisperChunk(0, 400, emptyList()))
            val current = repository.loadAudioRecording(initial.id)
            repository.saveTranscription(initial.id, emptyList(), 600, TranscriptionRunner.MODEL_ID, "tr", TranscriptionStatus.PAUSED,
                expectedRevision = current.revision)
        }
        rejected { TranscriptionRunner(repository, engine).run(initial.id, "tr") }
        val preserved = repository.loadAudioRecording(initial.id)
        assertEquals(600L, preserved.transcribedUntilMs)
        assertEquals(TranscriptionStatus.PAUSED, preserved.transcriptionStatus)
    }

    @Test fun bookmarkAddedDuringInferenceIsPreservedWithoutBlockingTranscript() = runBlocking {
        val initial = recording(1000)
        val engine = fake { _, _, _, emit ->
            repository.addAudioBookmark(initial.id, 100, "İşaret")
            emit(WhisperChunk(0, 1000, listOf(WhisperSegment(0, 900, "Metin"))))
        }
        TranscriptionRunner(repository, engine).run(initial.id, "tr")
        val done = repository.loadAudioRecording(initial.id)
        assertEquals("İşaret", done.bookmarks.single().label)
        assertEquals("Metin", done.transcript.single().text)
        assertEquals(TranscriptionStatus.COMPLETE, done.transcriptionStatus)
    }

    @Test fun activeRecordingAndUnsupportedLanguageFailBeforeAnyMutation() = runBlocking {
        val initial = recording(1000)
        repository.setAudioState(initial.id, AudioRecordingState.PAUSED)
        val before = repository.loadAudioRecording(initial.id)
        val runner = TranscriptionRunner(repository, fake { _, _, _, _ -> fail("Unexpected inference") })
        rejected { runner.run(initial.id, "tr") }
        rejected { runner.run(initial.id, "unsupported") }
        assertEquals(before, repository.loadAudioRecording(initial.id))
    }

    private suspend fun recording(vararg durations: Long): AudioRecording {
        val initial = repository.createAudioRecording("Deneme")
        durations.forEach { repository.commitAudioPart(initial.id, wave(), it, "audio/wav") }
        return repository.setAudioState(initial.id, AudioRecordingState.READY)
    }

    private fun wave(): File {
        val samples = ByteArray(32_000)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size)
        }.array()
        return File(RuntimeEnvironment.getApplication().cacheDir, "${newId()}.wav").apply { outputStream().use { it.write(header); it.write(samples) } }
    }

    private fun fake(block: suspend (File, Long, String, suspend (WhisperChunk) -> Unit) -> Unit): LocalAudioTranscriber = object : LocalAudioTranscriber {
        override suspend fun transcribe(audioFile: File, startMs: Long, language: String, onChunk: suspend (WhisperChunk) -> Unit) = block(audioFile, startMs, language, onChunk)
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Unsafe transcription accepted") } catch (_: Exception) { }
    }
}
