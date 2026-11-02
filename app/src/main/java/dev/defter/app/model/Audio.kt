package dev.defter.app.model

enum class AudioRecordingState { RECORDING, PAUSED, READY, INTERRUPTED }
enum class TranscriptionStatus { NONE, RUNNING, PAUSED, COMPLETE, FAILED }

/** Immutable encoded media. Offsets refer to the continuous recording timeline. */
data class AudioPart(
    val id: String = newId(),
    val assetId: String,
    val mimeType: String = "audio/mp4",
    val durationMs: Long,
    val offsetMs: Long,
    val bytes: Long,
    val sourceKey: String? = null
)

data class TranscriptSegment(
    val id: String = newId(),
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val language: String? = null,
    val speaker: String? = null,
    val edited: Boolean = false
)

data class AudioBookmark(
    val id: String = newId(),
    val positionMs: Long,
    val label: String = "",
    val pageId: String? = null
)

/** Small file-library index. The full transcript lives in recordings/{id}.json. */
data class AudioItem(
    val id: String,
    val title: String,
    val folderId: String? = null,
    val notebookId: String? = null,
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val state: AudioRecordingState = AudioRecordingState.RECORDING,
    val revision: Long = 0
)

data class AudioRecording(
    val id: String = newId(),
    val title: String,
    val folderId: String? = null,
    val notebookId: String? = null,
    val parts: List<AudioPart> = emptyList(),
    val transcript: List<TranscriptSegment> = emptyList(),
    val bookmarks: List<AudioBookmark> = emptyList(),
    val state: AudioRecordingState = AudioRecordingState.RECORDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    val revision: Long = 0,
    val transcriptionStatus: TranscriptionStatus = TranscriptionStatus.NONE,
    val transcriptionModel: String? = null,
    val transcriptionLanguage: String = "tr",
    val transcribedUntilMs: Long = 0,
    val transcriptionError: String? = null
) {
    val durationMs: Long get() = parts.sumOf { it.durationMs }
    fun toItem(): AudioItem = AudioItem(id, title, folderId, notebookId, durationMs, updatedAt, deletedAt, state, revision)
}
