package dev.defter.app.audio

import dev.defter.app.localization.tr

import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Durable checkpoints are committed after each decoded chunk, including silence. */
class TranscriptionRunner(private val repository: DocumentRepository, private val engine: LocalAudioTranscriber) {
    suspend fun run(id: String, language: String, restart: Boolean = false, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        require(language in setOf("tr", "en", "auto")) { tr("Desteklenmeyen konuşma dili.") }
        var recording = repository.loadAudioRecording(id)
        require(recording.deletedAt == null && recording.parts.isNotEmpty()) { tr("Çevrilecek ses kaydı bulunamadı.") }
        require(recording.state == AudioRecordingState.READY || recording.state == AudioRecordingState.INTERRUPTED) { tr("Önce ses kaydını durdur.") }
        require(restart || (recording.transcribedUntilMs == 0L && recording.transcript.isEmpty()) || recording.transcriptionLanguage == language) { tr("Dili değiştirmek için transkripti yeniden oluştur.") }
        var segments = if (restart) emptyList() else recording.transcript
        var checkpoint = if (restart) 0L else recording.transcribedUntilMs
        val total = recording.durationMs
        var started = false
        try {
            recording = repository.saveTranscription(id, segments, checkpoint, MODEL_ID, language, TranscriptionStatus.RUNNING, null, recording.revision)
            started = true
            onProgress(checkpoint, total)
            for (part in recording.parts) {
                currentCoroutineContext().ensureActive()
                if (checkpoint >= part.offsetMs + part.durationMs) continue
                val localStart = (checkpoint - part.offsetMs).coerceAtLeast(0)
                engine.transcribe(repository.audioPartFile(part), localStart, language) { chunk ->
                    currentCoroutineContext().ensureActive()
                    val end = (part.offsetMs + chunk.endMs).coerceAtMost(part.offsetMs + part.durationMs)
                    require(end >= checkpoint) { tr("Ses zaman çizelgesi geçersiz.") }
                    val additions = chunk.segments.mapNotNull { segment ->
                        val startMs = (part.offsetMs + segment.startMs).coerceIn(checkpoint, total)
                        val endMs = (part.offsetMs + segment.endMs).coerceIn(startMs, end.coerceAtLeast(startMs))
                        segment.text.trim().takeIf { it.isNotEmpty() && endMs > startMs }?.let {
                            TranscriptSegment(startMs = startMs, endMs = endMs, text = it, language = language)
                        }
                    }
                    val fresh = repository.loadAudioRecording(id)
                    require(fresh.deletedAt == null) { tr("Ses kaydı çöp kutusunda.") }
                    require(fresh.transcript == segments && fresh.transcribedUntilMs == checkpoint) { tr("Transkript başka bir işlemde değişti. Kaydedilmiş metin korundu.") }
                    require(fresh.transcriptionStatus == TranscriptionStatus.RUNNING && fresh.transcriptionLanguage == language && fresh.transcriptionModel == MODEL_ID) { tr("Transkript işlemi başka bir oturumda değişti.") }
                    segments = segments + additions
                    recording = repository.saveTranscription(id, segments, end, MODEL_ID, language, TranscriptionStatus.RUNNING, null, fresh.revision)
                    checkpoint = end
                    onProgress(checkpoint, total)
                }
                // Container duration can differ by a few codec padding milliseconds.
                val fresh = repository.loadAudioRecording(id)
                require(fresh.transcript == segments && fresh.transcribedUntilMs == checkpoint && fresh.transcriptionStatus == TranscriptionStatus.RUNNING && fresh.transcriptionLanguage == language) { tr("Transkript başka bir işlemde değişti.") }
                val partEnd = part.offsetMs + part.durationMs
                recording = repository.saveTranscription(id, segments, partEnd, MODEL_ID, language, TranscriptionStatus.RUNNING, null, fresh.revision)
                checkpoint = partEnd
            }
            val fresh = repository.loadAudioRecording(id)
            require(fresh.transcript == segments && fresh.transcribedUntilMs == checkpoint && fresh.deletedAt == null && fresh.transcriptionStatus == TranscriptionStatus.RUNNING && fresh.transcriptionLanguage == language) { tr("Transkript başka bir işlemde değişti.") }
            repository.saveTranscription(id, segments, total, MODEL_ID, language, TranscriptionStatus.COMPLETE, null, fresh.revision)
            onProgress(total, total)
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                // Never overwrite a concurrent manual edit while reporting cancellation/failure.
                runCatching {
                    val fresh = repository.loadAudioRecording(id)
                    if (started && fresh.deletedAt == null && fresh.transcriptionStatus == TranscriptionStatus.RUNNING && fresh.transcriptionModel == MODEL_ID && fresh.transcriptionLanguage == language) {
                        repository.saveTranscription(id, fresh.transcript, fresh.transcribedUntilMs, fresh.transcriptionModel ?: MODEL_ID,
                            fresh.transcriptionLanguage, if (failure is CancellationException) TranscriptionStatus.PAUSED else TranscriptionStatus.FAILED,
                            if (failure is CancellationException) null else failure.message?.take(350), fresh.revision)
                    }
                }
            }
            throw failure
        }
    }

    companion object { const val MODEL_ID = "whisper-base-multilingual-5359861" }
}
