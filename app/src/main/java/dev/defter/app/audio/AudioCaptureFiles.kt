package dev.defter.app.audio

import dev.defter.app.localization.tr

import android.content.Context
import android.media.MediaMetadataRetriever
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.AudioRecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class AudioRecoveryResult(val recoveredParts: Int, val interruptedRecordings: Int, val unreadableFiles: Int)

/** Pending files are never removed until their immutable asset and metadata are committed. */
internal object AudioCaptureFiles {
    private val recoveryMutex = Mutex()
    suspend fun <T> withRecoveryLock(block: suspend () -> T): T = recoveryMutex.withLock { block() }
    fun directory(context: Context, recordingId: String): File {
        require(recordingId.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { tr("Geçersiz ses kaydı kimliği.") }
        return File(context.filesDir, "defter/audio-pending/$recordingId").also {
            check(it.isDirectory || it.mkdirs()) { tr("Ses kayıt klasörü oluşturulamadı.") }
        }
    }

    fun durationMs(file: File): Long? {
        if (!file.isFile || file.length() == 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 }
        } catch (_: Exception) { null }
        finally { runCatching { retriever.release() } }
    }

    suspend fun recover(context: Context): AudioRecoveryResult = recoveryMutex.withLock {
        withContext(Dispatchers.IO) {
            val repository = DocumentRepository(context)
            val root = File(context.filesDir, "defter/audio-pending")
            var recovered = 0
            var interrupted = 0
            var unreadable = 0
            val reconciled = mutableSetOf<String>()
            root.listFiles()?.filter { it.isDirectory }?.forEach { directory ->
                val active = AudioRecorderSession.state.value
                if (active.isBusy && active.recordingId == directory.name) return@forEach
                val candidates = directory.listFiles()?.filter { it.isFile && it.extension == "m4a" }
                    ?.sortedBy { it.lastModified() }.orEmpty()
                if (candidates.isEmpty()) return@forEach
                try {
                    val recording = repository.loadAudioRecording(directory.name)
                    var foundUncommitted = false
                    for (file in candidates) {
                        val duration = durationMs(file)
                        if (duration == null) { unreadable++; foundUncommitted = true; continue }
                        val alreadyCommitted = recording.parts.any { it.sourceKey == file.name }
                        repository.commitAudioPart(recording.id, file, duration)
                        if (!alreadyCommitted) { recovered++; foundUncommitted = true }
                        // Safe only after commit returns. Failure to delete is harmless: sourceKey is idempotent.
                        file.delete()
                    }
                    if (foundUncommitted || recording.state in setOf(AudioRecordingState.RECORDING, AudioRecordingState.PAUSED)) {
                        repository.setAudioState(recording.id, AudioRecordingState.INTERRUPTED)
                        reconciled += recording.id
                        interrupted++
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep all not-yet-committed files. A later recovery can retry a storage failure.
                    unreadable++
                }
            }
            // Process death can happen before the first file or after a paused part was committed.
            // The process-local reservation is checked for every item so a concurrent Start is excluded.
            repository.loadLibrary().audioItems.filter {
                it.id !in reconciled && it.state in setOf(AudioRecordingState.RECORDING, AudioRecordingState.PAUSED)
            }.forEach { item ->
                val active = AudioRecorderSession.state.value
                if (!(active.isBusy && active.recordingId == item.id)) {
                    repository.setAudioState(item.id, AudioRecordingState.INTERRUPTED)
                    interrupted++
                }
            }
            AudioRecoveryResult(recovered, interrupted, unreadable)
        }
    }
}
