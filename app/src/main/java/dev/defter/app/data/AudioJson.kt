package dev.defter.app.data

import dev.defter.app.localization.tr

import dev.defter.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal object AudioJson {
    const val MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_AUDIO_BYTES = 256L * 1024 * 1024
    private val languages = Regex("[A-Za-z0-9-]{1,80}")
    private val mimeTypes = setOf("audio/mp4", "audio/mpeg", "audio/aac", "audio/ogg", "audio/opus", "audio/wav", "audio/x-wav", "audio/flac", "audio/3gpp", "audio/amr", "audio/webm", "audio/x-matroska")

    fun itemToJson(item: AudioItem): JSONObject = JSONObject().put("id", item.id).put("title", item.title)
        .put("folderId", item.folderId ?: JSONObject.NULL).put("notebookId", item.notebookId ?: JSONObject.NULL)
        .put("durationMs", item.durationMs).put("updatedAt", item.updatedAt).put("deletedAt", item.deletedAt ?: JSONObject.NULL)
        .put("state", item.state.name).put("revision", item.revision)

    fun itemFromJson(json: JSONObject): AudioItem = AudioItem(json.getString("id"), json.getString("title"),
        json.nullableString("folderId"), json.nullableString("notebookId"), json.getLong("durationMs"),
        json.getLong("updatedAt"), json.nullableLong("deletedAt"), AudioRecordingState.valueOf(json.getString("state")), json.getLong("revision"))

    fun toJson(recording: AudioRecording): JSONObject {
        validate(recording)
        return itemToJson(recording.toItem()).put("version", 3).put("createdAt", recording.createdAt)
            .put("transcriptionStatus", recording.transcriptionStatus.name)
            .put("transcriptionModel", recording.transcriptionModel ?: JSONObject.NULL)
            .put("transcriptionLanguage", recording.transcriptionLanguage).put("transcribedUntilMs", recording.transcribedUntilMs)
            .put("transcriptionError", recording.transcriptionError ?: JSONObject.NULL)
            .put("parts", JSONArray().apply { recording.parts.forEach { p -> put(JSONObject().put("id", p.id)
                .put("assetId", p.assetId).put("mimeType", p.mimeType).put("durationMs", p.durationMs).put("offsetMs", p.offsetMs)
                .put("bytes", p.bytes).put("sourceKey", p.sourceKey ?: JSONObject.NULL)) } })
            .put("transcript", JSONArray().apply { recording.transcript.forEach { s -> put(JSONObject().put("id", s.id)
                .put("startMs", s.startMs).put("endMs", s.endMs).put("text", s.text).put("language", s.language ?: JSONObject.NULL)
                .put("speaker", s.speaker ?: JSONObject.NULL).put("edited", s.edited)) } })
            .put("bookmarks", JSONArray().apply { recording.bookmarks.forEach { b -> put(JSONObject().put("id", b.id)
                .put("positionMs", b.positionMs).put("label", b.label).put("pageId", b.pageId ?: JSONObject.NULL)) } })
    }

    fun fromJson(json: JSONObject): AudioRecording = try {
        require(json.getInt("version") == 3) { tr("Desteklenmeyen ses belgesi sürümü.") }
        val item = itemFromJson(json)
        AudioRecording(item.id, item.title, item.folderId, item.notebookId,
            json.getJSONArray("parts").objects().map { p -> AudioPart(p.getString("id"), p.getString("assetId"), p.getString("mimeType"),
                p.getLong("durationMs"), p.getLong("offsetMs"), p.getLong("bytes"), p.nullableString("sourceKey")) },
            json.getJSONArray("transcript").objects().map { s -> TranscriptSegment(s.getString("id"), s.getLong("startMs"), s.getLong("endMs"),
                s.getString("text"), s.nullableString("language"), s.nullableString("speaker"), s.getBoolean("edited")) },
            json.getJSONArray("bookmarks").objects().map { b -> AudioBookmark(b.getString("id"), b.getLong("positionMs"), b.getString("label"), b.nullableString("pageId")) },
            item.state, json.getLong("createdAt"), item.updatedAt, item.deletedAt, item.revision,
            TranscriptionStatus.valueOf(json.getString("transcriptionStatus")), json.nullableString("transcriptionModel"),
            json.getString("transcriptionLanguage"), json.getLong("transcribedUntilMs"), json.nullableString("transcriptionError"))
            .also { validate(it); require(it.toItem() == item) { tr("Ses dizini ve belge uyuşmuyor.") } }
    } catch (error: Exception) { throw IOException(tr("Ses belgesi okunamadı. Mevcut kayıt korunuyor."), error) }

    fun validateItem(item: AudioItem) {
        DocumentJson.validId(item.id); DocumentJson.validateName(item.title)
        item.folderId?.let(DocumentJson::validId); item.notebookId?.let(DocumentJson::validId)
        require(item.durationMs in 0..MAX_DURATION_MS && item.revision >= 0 && item.updatedAt >= 0 && (item.deletedAt == null || item.deletedAt >= 0)) { tr("Geçersiz ses dizini.") }
    }

    fun validate(recording: AudioRecording) {
        validateItem(recording.toItem())
        require(recording.createdAt >= 0 && recording.parts.size <= 10_000 && recording.transcript.size <= 100_000 && recording.bookmarks.size <= 100_000) { tr("Ses belgesi çok büyük.") }
        require(recording.transcriptionLanguage.matches(languages) && (recording.transcriptionModel?.length ?: 0) <= 200 && (recording.transcriptionError?.length ?: 0) <= 2000) { tr("Geçersiz yazıya çevirme bilgisi.") }
        var offset = 0L
        val ids = mutableSetOf<String>()
        val sourceKeys = mutableSetOf<String>()
        recording.parts.forEach { p ->
            DocumentJson.validId(p.id); DocumentJson.validId(p.assetId)
            require(ids.add(p.id) && p.mimeType in mimeTypes && p.bytes in 1..MAX_AUDIO_BYTES && p.durationMs in 1..MAX_DURATION_MS && p.offsetMs == offset) { tr("Geçersiz ses parçası.") }
            p.sourceKey?.let { require(it.length in 1..200 && '/' !in it && '\\' !in it && sourceKeys.add(it)) { tr("Geçersiz ses kaynak kimliği.") } }
            offset = Math.addExact(offset, p.durationMs)
            require(offset <= MAX_DURATION_MS) { tr("Ses kaydı en fazla 7 gün olabilir.") }
        }
        require(recording.transcribedUntilMs in 0..offset) { tr("Geçersiz yazıya çevirme konumu.") }
        ids.clear()
        recording.transcript.forEach { s ->
            DocumentJson.validId(s.id)
            require(ids.add(s.id) && s.startMs in 0..offset && s.endMs in s.startMs..offset && s.text.length <= 100_000 &&
                (s.language == null || s.language.matches(languages)) && (s.speaker?.length ?: 0) <= 200) { tr("Geçersiz zamanlı metin.") }
        }
        ids.clear()
        recording.bookmarks.forEach { b ->
            DocumentJson.validId(b.id); b.pageId?.let(DocumentJson::validId)
            // A live bookmark can lead the last durable part. Recording interruption preserves it.
            require(ids.add(b.id) && b.positionMs in 0..MAX_DURATION_MS && b.label.length <= 1000) { tr("Geçersiz ses işareti.") }
        }
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
}
