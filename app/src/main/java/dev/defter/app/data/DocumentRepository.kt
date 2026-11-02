package dev.defter.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.defter.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ImageAsset(val assetId: String, val width: Int, val height: Int)
data class SyncMergeResult(val notebookId: String, val conflict: Boolean, val fingerprint: String)
data class RecoveryCopy(val notebookId: String, val page: PageDocument)

/**
 * Local prototype store. Each page is an independent atomic snapshot. Library metadata is
 * committed only after every newly referenced file is durable. Orphans are retained on
 * interrupted creation, so a failed import never damages previously saved documents.
 * A shared mutex orders reads, edits and backup snapshots across repository instances.
 */
class DocumentRepository(context: Context) {
    private val appContext = context.applicationContext
    internal val root = File(appContext.filesDir, "defter")
    private val pageDirectory = File(root, "pages")
    private val assetDirectory = File(root, "assets")
    private val recordingDirectory = File(root, "recordings")
    private val libraryFile = AtomicSnapshotFile(File(root, "library.json"))

    suspend fun loadLibrary(): LibraryData = locked { readLibrary() }

    suspend fun createAudioRecording(title: String, folderId: String? = null, notebookId: String? = null): AudioRecording = locked {
        val library = readLibrary()
        val recording = AudioRecording(title = title.trim(), folderId = folderId, notebookId = notebookId)
        validateAudioLinks(recording, library)
        publishAudio(library, recording)
    }

    suspend fun loadAudioRecording(id: String): AudioRecording = locked { readAudio(readLibrary(), id) }

    fun audioPartFile(part: AudioPart): File = audioAssetFile(part.assetId)

    /** The caller keeps the source until this returns. A UUID filename makes retries idempotent. */
    suspend fun commitAudioPart(id: String, source: File, durationMs: Long, mimeType: String = "audio/mp4"): AudioRecording = locked {
        val library = readLibrary()
        val old = readAudio(library, id)
        require(old.deletedAt == null) { "Silinmiş ses kaydına parça eklenemez." }
        if (old.parts.any { it.sourceKey == source.name }) return@locked old
        require(source.isFile && source.length() in 1..MAX_ENTRY_BYTES) { "Ses parçası boş veya 64 MB sınırını aşıyor." }
        val part = AudioPart(assetId = newId(), mimeType = mimeType, durationMs = durationMs,
            offsetMs = old.durationMs, bytes = source.length(), sourceKey = source.name)
        val next = nextAudio(old).copy(parts = old.parts + part)
        AudioJson.validate(next)
        validateAudioContainer(source, mimeType)
        atomicWrite(AtomicSnapshotFile(audioPartFile(part))) { output -> source.inputStream().use { copyBounded(it, output, MAX_ENTRY_BYTES) } }
        require(audioPartFile(part).length() == part.bytes) { "Ses kaynağı kayıt sırasında değişti." }
        publishAudio(library, next)
    }

    suspend fun setAudioState(id: String, state: AudioRecordingState): AudioRecording = locked {
        val library = readLibrary()
        val old = readAudio(library, id)
        publishAudio(library, nextAudio(old).copy(state = state))
    }

    suspend fun importAudio(uri: Uri, title: String, folderId: String? = null, notebookId: String? = null): AudioRecording = locked {
        val library = readLibrary()
        val initial = AudioRecording(title = title.trim(), folderId = folderId, notebookId = notebookId, state = AudioRecordingState.READY)
        validateAudioLinks(initial, library)
        val staging = stagingDirectory()
        try {
            val source = File(staging, "import.audio")
            appContext.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { copyBounded(input, it, AudioJson.MAX_AUDIO_BYTES) } }
                ?: throw IOException("Ses dosyası açılamadı.")
            currentCoroutineContext().ensureActive()
            val metadata = inspectAudio(source)
            validateAudioContainer(source, metadata.second)
            val part = AudioPart(assetId = newId(), durationMs = metadata.first, offsetMs = 0, bytes = source.length(), mimeType = metadata.second)
            val recording = initial.copy(parts = listOf(part))
            AudioJson.validate(recording)
            atomicWrite(AtomicSnapshotFile(audioPartFile(part))) { output -> source.inputStream().use { it.copyTo(output) } }
            currentCoroutineContext().ensureActive()
            publishAudio(library, recording)
        } finally { staging.deleteRecursively() }
    }

    suspend fun updateTranscript(id: String, segments: List<TranscriptSegment>, expectedRevision: Long? = null): AudioRecording = locked {
        val library = readLibrary()
        val old = readAudio(library, id)
        checkAudioRevision(old, expectedRevision)
        publishAudio(library, nextAudio(old).copy(transcript = segments))
    }

    suspend fun saveTranscription(
        id: String, segments: List<TranscriptSegment>, completedThroughMs: Long, model: String?, language: String,
        status: TranscriptionStatus, error: String? = null, expectedRevision: Long? = null
    ): AudioRecording = locked {
        val library = readLibrary()
        val old = readAudio(library, id)
        checkAudioRevision(old, expectedRevision)
        publishAudio(library, nextAudio(old).copy(transcript = segments, transcribedUntilMs = completedThroughMs,
            transcriptionModel = model, transcriptionLanguage = language, transcriptionStatus = status, transcriptionError = error))
    }

    suspend fun addAudioBookmark(id: String, positionMs: Long, label: String = "", pageId: String? = null): AudioRecording = locked {
        val library = readLibrary()
        val old = readAudio(library, id)
        publishAudio(library, nextAudio(old).copy(bookmarks = old.bookmarks + AudioBookmark(positionMs = positionMs, label = label, pageId = pageId)))
    }

    suspend fun removeAudioBookmark(id: String, bookmarkId: String): AudioRecording = locked {
        val library = readLibrary()
        val old = readAudio(library, id)
        require(old.bookmarks.any { it.id == bookmarkId }) { "Ses işareti bulunamadı." }
        publishAudio(library, nextAudio(old).copy(bookmarks = old.bookmarks.filterNot { it.id == bookmarkId }))
    }

    suspend fun renameAudio(id: String, title: String): LibraryData = mutateAudioIndex(id) { it.copy(title = title.trim()) }
    suspend fun moveAudio(id: String, folderId: String?): LibraryData = mutateAudioIndex(id) { it.copy(folderId = folderId) }
    suspend fun linkAudio(id: String, notebookId: String?): LibraryData = mutateAudioIndex(id) { it.copy(notebookId = notebookId) }
    suspend fun setAudioDeleted(id: String, deleted: Boolean): LibraryData = mutateAudioIndex(id) {
        require(!deleted || it.state !in setOf(AudioRecordingState.RECORDING, AudioRecordingState.PAUSED)) { "Önce ses kaydını durdurun." }
        it.copy(deletedAt = if (deleted) System.currentTimeMillis() else null)
    }

    private suspend fun mutateAudioIndex(id: String, transform: (AudioRecording) -> AudioRecording): LibraryData = locked {
        val library = readLibrary()
        publishAudio(library, transform(nextAudio(readAudio(library, id))))
        readLibrary()
    }

    private fun nextAudio(old: AudioRecording): AudioRecording = old.copy(revision = Math.addExact(old.revision, 1), updatedAt = System.currentTimeMillis())
    private fun checkAudioRevision(old: AudioRecording, expectedRevision: Long?) {
        require(expectedRevision == null || expectedRevision == old.revision) { "Ses kaydı başka bir işlem tarafından değiştirildi. Yenileyip tekrar deneyin." }
        require(old.deletedAt == null) { "Silinmiş ses kaydı düzenlenemez." }
    }

    private fun validateAudioLinks(recording: AudioRecording, library: LibraryData) {
        AudioJson.validate(recording)
        require(recording.folderId == null || library.folders.any { it.id == recording.folderId }) { "Ses kaydının klasörü bulunamadı." }
        require(recording.notebookId == null || library.notebooks.any { it.id == recording.notebookId }) { "Ses kaydının defteri bulunamadı." }
        val pages = library.notebooks.flatMap { it.pageIds + it.trashedPageIds }.toSet()
        require(recording.bookmarks.all { it.pageId == null || it.pageId in pages }) { "Ses işaretinin sayfası bulunamadı." }
    }

    /** Immutable metadata + one atomic index publication prevents cross-file partial updates. */
    private fun publishAudio(library: LibraryData, recording: AudioRecording): AudioRecording {
        validateAudioLinks(recording, library)
        validateAudioAssets(recording)
        writeAudio(recording)
        writeLibrary(library.copy(audioItems = library.audioItems.filterNot { it.id == recording.id } + recording.toItem()))
        return recording
    }

    private fun writeAudio(recording: AudioRecording) {
        val bytes = AudioJson.toJson(recording).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "Ses metni bu sürüm için çok büyük." }
        atomicWrite(AtomicSnapshotFile(recordingFile(recording.id, recording.revision))) { it.write(bytes) }
    }

    private fun readAudio(library: LibraryData, id: String): AudioRecording {
        val item = library.audioItems.firstOrNull { it.id == id } ?: throw IOException("Ses kaydı bulunamadı.")
        return AtomicSnapshotFile(recordingFile(id, item.revision)).openRead().use { AudioJson.fromJson(readJson(it, MAX_JSON_BYTES)) }.also {
            require(it.toItem() == item) { "Ses kaydı dizini uyuşmuyor." }
        }
    }

    private fun recordingFile(id: String, revision: Long): File = File(recordingDirectory, "${DocumentJson.validId(id)}_$revision.json")
    private fun audioAssetFile(id: String): File = File(assetDirectory, "${DocumentJson.validId(id)}.audio")
    private fun validateAudioAssets(recording: AudioRecording) = recording.parts.forEach {
        require(audioPartFile(it).isFile && audioPartFile(it).length() == it.bytes) { "Ses parçası eksik veya boyutu değişmiş." }
    }

    private fun inspectAudio(file: File): Pair<Long, String> {
        require(file.isFile && file.length() in 1..AudioJson.MAX_AUDIO_BYTES) { "Ses dosyası boş veya 256 MB sınırını aşıyor." }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { audioFormat = format; break }
            }
            val format = requireNotNull(audioFormat) { "Dosyada desteklenen ses izi bulunamadı." }
            // HAS_AUDIO and container MIME are optional on valid WAV/FLAC inputs. Track metadata
            // is the same source used by the decoder, while the file header identifies its container.
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
            val duration = if (durationUs != null && durationUs > 0) {
                require(durationUs <= AudioJson.MAX_DURATION_MS * 1000) { "Ses süresi desteklenmiyor." }
                (durationUs + 999) / 1000
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.path)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        ?: throw IOException("Ses süresi okunamadı.")
                } finally { runCatching { retriever.release() } }
            }
            require(duration in 1..AudioJson.MAX_DURATION_MS) { "Ses süresi desteklenmiyor." }
            val mime = detectAudioContainer(file)
            // Reuse the explicit codec allow-list rather than trusting a provider MIME label.
            AudioJson.validate(AudioRecording(title = "Kontrol", parts = listOf(AudioPart(assetId = newId(), mimeType = mime,
                durationMs = duration, offsetMs = 0, bytes = file.length()))))
            duration to mime
        } catch (error: Exception) { throw IOException("Ses dosyası açılamadı veya desteklenmiyor.", error) }
        finally { runCatching { extractor.release() } }
    }

    private fun detectAudioContainer(file: File): String {
        require(file.length() >= 8) { "Ses dosyası boş veya eksik." }
        val header = ByteArray(minOf(64L, file.length()).toInt())
        DataInputStream(file.inputStream()).use { it.readFully(header) }
        fun text(offset: Int, value: String): Boolean = header.size >= offset + value.length &&
            header.copyOfRange(offset, offset + value.length).toString(Charsets.ISO_8859_1) == value
        return when {
            text(4, "ftyp") -> "audio/mp4"
            text(0, "RIFF") && text(8, "WAVE") -> "audio/wav"
            text(0, "OggS") -> "audio/ogg"
            text(0, "fLaC") -> "audio/flac"
            text(0, "#!AMR") -> "audio/amr"
            text(0, "ID3") -> "audio/mpeg"
            (header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xf6) == 0xf0 -> "audio/aac"
            (header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xe0) == 0xe0 -> "audio/mpeg"
            header.take(4).map { it.toInt() and 0xff } == listOf(0x1a, 0x45, 0xdf, 0xa3) -> "audio/webm"
            else -> throw IOException("Desteklenmeyen ses kapsayıcısı.")
        }
    }

    private fun validateAudioContainer(file: File, mime: String) {
        require(file.length() in 8..AudioJson.MAX_AUDIO_BYTES) { "Ses dosyası boş veya çok büyük." }
        val header = ByteArray(minOf(64L, file.length()).toInt())
        DataInputStream(file.inputStream()).use { it.readFully(header) }
        fun text(offset: Int, value: String): Boolean = header.size >= offset + value.length &&
            header.copyOfRange(offset, offset + value.length).toString(Charsets.ISO_8859_1) == value
        fun syncWord(): Boolean = (header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xe0) == 0xe0
        val valid = when (mime) {
            "audio/mp4", "audio/3gpp" -> text(4, "ftyp")
            "audio/mpeg" -> text(0, "ID3") || syncWord()
            "audio/aac" -> syncWord()
            "audio/ogg", "audio/opus" -> text(0, "OggS")
            "audio/wav", "audio/x-wav" -> text(0, "RIFF") && text(8, "WAVE")
            "audio/flac" -> text(0, "fLaC")
            "audio/amr" -> text(0, "#!AMR")
            "audio/webm", "audio/x-matroska" -> header.take(4).map { it.toInt() and 0xff } == listOf(0x1a, 0x45, 0xdf, 0xa3)
            else -> false
        }
        require(valid) { "Ses dosyasının biçimi ve başlığı uyuşmuyor." }
    }

    suspend fun createFolder(name: String, parentId: String?): LibraryData = locked {
        val cleanName = name.trim().also(DocumentJson::validateName)
        val library = readLibrary()
        require(parentId == null || library.folders.any { it.id == parentId }) { "Üst klasör bulunamadı." }
        library.copy(folders = library.folders + Folder(name = cleanName, parentId = parentId)).also(::writeLibrary)
    }

    suspend fun renameFolder(id: String, name: String): LibraryData = locked {
        val cleanName = name.trim().also(DocumentJson::validateName)
        val library = readLibrary()
        require(library.folders.any { it.id == id }) { "Klasör bulunamadı." }
        library.copy(folders = library.folders.map { if (it.id == id) it.copy(name = cleanName) else it }).also(::writeLibrary)
    }

    suspend fun createNotebook(
        title: String, folderId: String?, template: PaperTemplate, coverColor: Int
    ): Notebook = locked {
        val cleanTitle = title.trim().also(DocumentJson::validateName)
        val library = readLibrary()
        require(folderId == null || library.folders.any { it.id == folderId }) { "Klasör bulunamadı." }
        val page = PageDocument(template = template)
        writePage(page)
        val notebook = Notebook(title = cleanTitle, folderId = folderId, coverColor = coverColor, pageIds = listOf(page.id))
        writeLibrary(library.copy(notebooks = library.notebooks + notebook))
        notebook
    }

    suspend fun renameNotebook(id: String, title: String): LibraryData = locked {
        val cleanTitle = title.trim().also(DocumentJson::validateName)
        val library = readLibrary()
        notebook(library, id)
        library.copy(notebooks = library.notebooks.map {
            if (it.id == id) it.copy(title = cleanTitle, updatedAt = System.currentTimeMillis()) else it
        }).also(::writeLibrary)
    }

    suspend fun moveNotebook(id: String, folderId: String?): LibraryData = locked {
        val library = readLibrary()
        notebook(library, id)
        require(folderId == null || library.folders.any { it.id == folderId }) { "Klasör bulunamadı." }
        library.copy(notebooks = library.notebooks.map {
            if (it.id == id) it.copy(folderId = folderId, updatedAt = System.currentTimeMillis()) else it
        }).also(::writeLibrary)
    }

    suspend fun setNotebookDeleted(id: String, deleted: Boolean): LibraryData = locked {
        val library = readLibrary()
        notebook(library, id)
        library.copy(notebooks = library.notebooks.map {
            if (it.id == id) it.copy(deletedAt = if (deleted) System.currentTimeMillis() else null,
                updatedAt = System.currentTimeMillis()) else it
        }).also(::writeLibrary)
    }

    suspend fun loadPage(id: String): PageDocument = locked { readPage(id) }

    suspend fun savePage(page: PageDocument, expectedRevision: Long? = null): Unit = locked {
        DocumentJson.validatePage(page)
        val library = readLibrary()
        val owner = library.notebooks.find { page.id in it.pageIds }
            ?: throw IOException("Sayfa bir deftere ait değil.")
        require(owner.deletedAt == null) { "Defter çöp kutusunda." }
        val previous = readPage(page.id)
        // An identical retry is safe even when the page write succeeded but metadata did not.
        if (page != previous) {
            require(expectedRevision == null || previous.revision == expectedRevision) { "Sayfa başka bir oturumda değişti. Çakışan not korunuyor; sayfayı yeniden aç." }
            require(page.revision > previous.revision) { "Eski veya çakışan sayfa sürümü kaydedilemez." }
        }
        validateAssetsExist(page)
        writePage(page)
        // If this secondary update fails the page is already intact and recoverable.
        writeLibrary(library.copy(notebooks = library.notebooks.map {
            if (it.id == owner.id) it.copy(updatedAt = System.currentTimeMillis()) else it
        }))
    }

    suspend fun addPage(notebookId: String, template: PaperTemplate, afterPageId: String? = null): PageDocument = locked {
        val library = readLibrary()
        val owner = notebook(library, notebookId)
        require(owner.deletedAt == null) { "Defter çöp kutusunda." }
        require(afterPageId == null || afterPageId in owner.pageIds) { "Eklenecek konum bulunamadı." }
        val page = PageDocument(template = template)
        writePage(page)
        val ids = owner.pageIds.toMutableList().apply {
            add(if (afterPageId == null) size else indexOf(afterPageId) + 1, page.id)
        }
        writeLibrary(library.copy(notebooks = library.notebooks.map {
            if (it.id == notebookId) it.copy(pageIds = ids, updatedAt = System.currentTimeMillis()) else it
        }))
        page
    }

    suspend fun duplicatePage(notebookId: String, pageId: String): PageDocument = locked {
        val library = readLibrary()
        val owner = notebook(library, notebookId)
        require(owner.deletedAt == null && pageId in owner.pageIds) { "Sayfa bulunamadı." }
        val source = readPage(pageId)
        val page = source.copy(id = newId(), revision = 0, recognitionRevision = if (source.recognitionRevision == source.revision) 0 else null,
            elements = source.elements.map { reidentifyElement(it) })
        writePage(page)
        val ids = owner.pageIds.toMutableList().apply { add(indexOf(pageId) + 1, page.id) }
        writeLibrary(library.copy(notebooks = library.notebooks.map {
            if (it.id == notebookId) it.copy(pageIds = ids, updatedAt = System.currentTimeMillis()) else it
        }))
        page
    }

    /** Preserve an unsaved draft without overwriting a conflicting, moved or trashed original. */
    suspend fun recoverPageCopy(source: PageDocument, ownerNotebookId: String?): RecoveryCopy = locked {
        DocumentJson.validatePage(source)
        validateAssetsExist(source)
        val library = readLibrary()
        val owner = library.notebooks.firstOrNull { it.id == ownerNotebookId }
        val copy = source.copy(id = newId(), revision = 0,
            recognitionRevision = if (source.recognitionRevision == source.revision) 0 else null,
            elements = source.elements.map { reidentifyElement(it) })
        writePage(copy)
        if (owner != null && owner.deletedAt == null) {
            val pages = owner.pageIds.toMutableList().apply {
                val originalIndex = indexOf(source.id)
                add(if (originalIndex < 0) size else originalIndex + 1, copy.id)
            }
            writeLibrary(library.copy(notebooks = library.notebooks.map {
                if (it.id == owner.id) it.copy(pageIds = pages, updatedAt = System.currentTimeMillis()) else it
            }))
            RecoveryCopy(owner.id, copy)
        } else {
            val recoveredBook = Notebook(title = owner?.title?.take(175)?.plus(" · kurtarılan") ?: "Kurtarılan notlar",
                folderId = owner?.folderId, coverColor = owner?.coverColor ?: 0xFF6254D9.toInt(), pageIds = listOf(copy.id))
            writeLibrary(library.copy(notebooks = library.notebooks + recoveredBook))
            RecoveryCopy(recoveredBook.id, copy)
        }
    }

    suspend fun reorderPages(notebookId: String, orderedIds: List<String>): LibraryData = locked {
        val library = readLibrary()
        val owner = notebook(library, notebookId)
        require(owner.deletedAt == null) { "Defter çöp kutusunda." }
        require(orderedIds.size == owner.pageIds.size && orderedIds.toSet() == owner.pageIds.toSet()) { "Sayfa sıralaması tüm sayfaları bir kez içermeli." }
        library.copy(notebooks = library.notebooks.map {
            if (it.id == notebookId) it.copy(pageIds = orderedIds.toList(), updatedAt = System.currentTimeMillis()) else it
        }).also(::writeLibrary)
    }

    suspend fun deletePage(notebookId: String, pageId: String): LibraryData = locked {
        val library = readLibrary()
        val owner = notebook(library, notebookId)
        require(owner.deletedAt == null && pageId in owner.pageIds) { "Sayfa bulunamadı." }
        require(owner.pageIds.size > 1) { "Son sayfa silinemez. Önce yeni bir sayfa ekle." }
        library.copy(notebooks = library.notebooks.map {
            if (it.id == notebookId) it.copy(pageIds = it.pageIds - pageId,
                trashedPageIds = it.trashedPageIds + pageId, updatedAt = System.currentTimeMillis()) else it
        }).also(::writeLibrary)
    }

    suspend fun restorePage(notebookId: String, pageId: String, afterPageId: String? = null): LibraryData = locked {
        val library = readLibrary()
        val owner = notebook(library, notebookId)
        require(owner.deletedAt == null && pageId in owner.trashedPageIds) { "Silinen sayfa bulunamadı." }
        require(afterPageId == null || afterPageId in owner.pageIds) { "Eklenecek konum bulunamadı." }
        val ids = owner.pageIds.toMutableList().apply { add(if (afterPageId == null) size else indexOf(afterPageId) + 1, pageId) }
        library.copy(notebooks = library.notebooks.map {
            if (it.id == notebookId) it.copy(pageIds = ids, trashedPageIds = it.trashedPageIds - pageId,
                updatedAt = System.currentTimeMillis()) else it
        }).also(::writeLibrary)
    }

    suspend fun transferPage(sourceNotebookId: String, pageId: String, destinationNotebookId: String,
        copy: Boolean, afterPageId: String? = null): PageDocument = locked {
        val library = readLibrary()
        val source = notebook(library, sourceNotebookId)
        val destination = notebook(library, destinationNotebookId)
        require(source.deletedAt == null && destination.deletedAt == null && pageId in source.pageIds) { "Sayfa veya hedef defter bulunamadı." }
        require(afterPageId == null || afterPageId in destination.pageIds) { "Eklenecek konum bulunamadı." }
        require(copy || source.id == destination.id || source.pageIds.size > 1) { "Son sayfa taşınamaz. Önce yeni bir sayfa ekle veya sayfayı kopyala." }
        require(copy || afterPageId != pageId) { "Sayfa kendi arkasına taşınamaz." }
        val original = readPage(pageId)
        val result = if (copy) original.copy(id = newId(), revision = 0, recognitionRevision = if (original.recognitionRevision == original.revision) 0 else null,
            elements = original.elements.map { reidentifyElement(it) }).also(::writePage) else original
        val destinationIds = destination.pageIds.toMutableList().apply {
            if (!copy && source.id == destination.id) remove(pageId)
            add(if (afterPageId == null) size else indexOf(afterPageId) + 1, result.id)
        }
        writeLibrary(library.copy(notebooks = library.notebooks.map {
            when {
                it.id == destination.id -> it.copy(pageIds = destinationIds, updatedAt = System.currentTimeMillis())
                !copy && it.id == source.id -> it.copy(pageIds = it.pageIds - pageId, updatedAt = System.currentTimeMillis())
                else -> it
            }
        }))
        result
    }

    suspend fun importPdf(uri: Uri, title: String, folderId: String?): Notebook = locked {
        val cleanTitle = title.trim().also(DocumentJson::validateName)
        val library = readLibrary()
        require(folderId == null || library.folders.any { it.id == folderId }) { "Klasör bulunamadı." }
        val staging = stagingDirectory()
        try {
            val source = File(staging, "source.pdf")
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("PDF açılamadı.")
            input.use { stream -> source.outputStream().use { copyBounded(stream, it, MAX_ENTRY_BYTES) } }
            val dimensions = validatePdfFile(source)
            currentCoroutineContext().ensureActive()
            val assetId = newId()
            atomicWrite(AtomicSnapshotFile(pdfAssetFile(assetId))) { output -> source.inputStream().use { it.copyTo(output) } }
            val pages = dimensions.mapIndexed { index, size ->
                currentCoroutineContext().ensureActive()
                PageDocument(template = PaperTemplate.BLANK, width = size.first * 2f, height = size.second * 2f,
                    backgroundColor = android.graphics.Color.WHITE,
                    pdfBackground = PdfBackground(assetId, index, size.first, size.second)).also(::writePage)
            }
            val result = Notebook(title = cleanTitle, folderId = folderId, pageIds = pages.map { it.id })
            // This is the sole visible commit. Existing notes are untouched on failure.
            currentCoroutineContext().ensureActive()
            writeLibrary(library.copy(notebooks = library.notebooks + result))
            result
        } finally { staging.deleteRecursively() }
    }

    fun pdfAssetFile(assetId: String): File = File(assetDirectory, "${DocumentJson.validId(assetId)}.pdf")

    suspend fun renderPdfBackground(background: PdfBackground, maxDimension: Int = 2048): Bitmap = locked {
        require(maxDimension in 64..4096) { "PDF önizleme boyutu geçersiz." }
        openPdf(pdfAssetFile(background.assetId)).use { renderer ->
            require(background.pageIndex in 0 until renderer.pageCount) { "PDF sayfası bulunamadı." }
            renderer.openPage(background.pageIndex).use { page ->
                val scale = maxDimension.toFloat() / maxOf(page.width, page.height)
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (error: Throwable) { bitmap.recycle(); throw error }
            }
        }
    }

    suspend fun importImage(uri: Uri): ImageAsset = locked {
        val staging = stagingDirectory()
        var decoded: Bitmap? = null
        var transformed: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val source = File(staging, "source")
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Görsel açılamadı.")
            input.use { stream -> source.outputStream().use { copyBounded(stream, it, MAX_ENTRY_BYTES) } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Desteklenmeyen görsel." }
            // Decode at a sampled size first; never materialize an unbounded source bitmap.
            var sample = 1
            while (bounds.outWidth / sample > MAX_IMAGE_EDGE || bounds.outHeight / sample > MAX_IMAGE_EDGE ||
                (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_IMAGE_PIXELS) sample *= 2
            decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: throw IOException("Görsel çözümlenemedi.")
            val original = decoded
            val orientation = try { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL) } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
            val matrix = orientationMatrix(orientation)
            transformed = if (matrix.isIdentity) original else Bitmap.createBitmap(original, 0, 0,
                original.width, original.height, matrix, true)
            val oriented = transformed
            val scale = max(max(oriented.width.toDouble() / MAX_IMAGE_EDGE, oriented.height.toDouble() / MAX_IMAGE_EDGE),
                sqrt(oriented.width.toDouble() * oriented.height / MAX_IMAGE_PIXELS)).coerceAtLeast(1.0)
            scaled = if (scale > 1.0) Bitmap.createScaledBitmap(oriented,
                (oriented.width / scale).roundToInt().coerceAtLeast(1),
                (oriented.height / scale).roundToInt().coerceAtLeast(1), true) else oriented
            val ready = scaled
            val assetId = newId()
            val output = AtomicSnapshotFile(assetFile(assetId))
            atomicWrite(output) { stream ->
                if (!ready.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Görsel kaydedilemedi.")
            }
            ImageAsset(assetId, ready.width, ready.height)
        } finally {
            listOfNotNull(scaled, transformed, decoded).distinct().forEach { it.recycle() }
            staging.deleteRecursively()
        }
    }

    fun assetFile(assetId: String): File = File(assetDirectory, "${DocumentJson.validId(assetId)}.png")

    suspend fun exportBackup(output: OutputStream): Unit = locked { writeArchive(readLibrary(), output) }

    suspend fun exportNotebookBackup(notebookId: String, output: OutputStream): String = locked {
        val library = readLibrary()
        val owner = notebook(library, notebookId)
        val snapshotFingerprint = fingerprint(library, owner)
        writeArchive(notebookLibrary(library, owner), output)
        snapshotFingerprint
    }

    suspend fun getNotebookFingerprint(notebookId: String): String? = locked {
        val library = readLibrary()
        library.notebooks.firstOrNull { it.id == notebookId }?.let { fingerprint(library, it) }
    }

    private fun writeArchive(library: LibraryData, output: OutputStream) {
        val files = linkedMapOf<String, File>()
        val staging = stagingDirectory()
        try {
            // Export verified atomic snapshots, not .new/.bak implementation files.
            val librarySnapshot = File(staging, "library.json")
            librarySnapshot.writeText(DocumentJson.libraryToJson(library).toString(), Charsets.UTF_8)
            files["library.json"] = librarySnapshot
            library.notebooks.forEach { owner -> (owner.pageIds + owner.trashedPageIds).forEach { id ->
                val page = readPage(id)
                validateAssetsExist(page)
                val snapshot = File(staging, "$id.json")
                snapshot.writeText(DocumentJson.pageToJson(page).toString(), Charsets.UTF_8)
                files["pages/$id.json"] = snapshot
                page.elements.filterIsInstance<ImageElement>().forEach {
                    files["assets/${it.assetId}.png"] = assetFile(it.assetId)
                }
                page.pdfBackground?.let { files["assets/${it.assetId}.pdf"] = pdfAssetFile(it.assetId) }
            } }
            val includedPages = library.notebooks.flatMap { it.pageIds + it.trashedPageIds }.toSet()
            library.audioItems.forEach { item ->
                val recording = readAudio(library, item.id)
                validateAudioAssets(recording)
                val snapshot = File(staging, "audio-${item.id}.json")
                // A single-notebook export cannot preserve a link into an excluded notebook.
                snapshot.writeText(AudioJson.toJson(recording.copy(bookmarks = recording.bookmarks.map {
                    if (it.pageId != null && it.pageId !in includedPages) it.copy(pageId = null) else it
                })).toString(), Charsets.UTF_8)
                files["recordings/${item.id}.json"] = snapshot
                recording.parts.forEach { files["assets/${it.assetId}.audio"] = audioPartFile(it) }
            }
            require(files.size + 1 <= MAX_ARCHIVE_ENTRIES) { "Yedekte çok fazla dosya var." }
            val entries = JSONObject()
            var total = 0L
            files.forEach { (name, file) ->
                require(file.length() <= if (name.endsWith(".audio")) AudioJson.MAX_AUDIO_BYTES else MAX_ENTRY_BYTES) { "Yedek için bir dosya çok büyük." }
                total += file.length()
                require(total <= MAX_ARCHIVE_BYTES) { "Bu alfa sürümünde yedek en fazla 512 MB olabilir." }
                entries.put(name, JSONObject().put("bytes", file.length()).put("sha256", sha256(file)))
            }
            val manifest = JSONObject().put("format", "defter-backup").put("version", 3)
                .put("createdAt", System.currentTimeMillis()).put("entries", entries).toString().toByteArray(Charsets.UTF_8)
            require(manifest.size <= MAX_JSON_BYTES && total + manifest.size <= MAX_ARCHIVE_BYTES) {
                "Bu alfa sürümünde yedek en fazla 512 MB olabilir."
            }
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
                files.forEach { (name, file) ->
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun importBackup(input: InputStream): LibraryData = locked { importArchive(input, false, null, null).first }

    suspend fun importSyncBackup(input: InputStream, expectedLocalFingerprint: String?, expectedNotebookId: String? = null): SyncMergeResult = locked {
        importArchive(input, true, expectedLocalFingerprint, expectedNotebookId).second!!
    }

    private fun importArchive(input: InputStream, sync: Boolean, expectedLocalFingerprint: String?, expectedNotebookId: String?): Pair<LibraryData, SyncMergeResult?> {
        val existing = readLibrary()
        val staging = stagingDirectory()
        return try {
            val extracted = extractArchive(input, staging)
            val manifestFile = extracted["manifest.json"] ?: throw IOException("Yedek bildirimi eksik.")
            val manifest = readJson(manifestFile, MAX_JSON_BYTES)
            require(manifest.getString("format") == "defter-backup" && manifest.getInt("version") in 1..3) {
                "Desteklenmeyen yedek biçimi."
            }
            val entries = manifest.getJSONObject("entries")
            val declared = entries.keys().asSequence().toSet()
            require(declared == extracted.keys - "manifest.json") { "Yedekteki dosya listesi eşleşmiyor." }
            declared.forEach { name ->
                val descriptor = entries.getJSONObject(name)
                val file = extracted.getValue(name)
                require(descriptor.getLong("bytes") == file.length() &&
                    descriptor.getString("sha256").matches(Regex("[a-f0-9]{64}")) &&
                    descriptor.getString("sha256") == sha256(file)) { "Yedek dosyası bozuk: $name" }
            }
            val library = DocumentJson.libraryFromJson(readJson(extracted["library.json"]
                ?: throw IOException("Kütüphane eksik."), MAX_JSON_BYTES))
            require(!sync || library.notebooks.size == 1) { "Eşitleme dosyası tek defter içermeli." }
            require(!sync || expectedNotebookId == null || library.notebooks.single().id == expectedNotebookId) { "Eşitleme defter kimliği eşleşmiyor." }
            val pageIds = linkedSetOf<String>()
            val assetIds = linkedSetOf<String>()
            val pdfIds = linkedSetOf<String>()
            val pages = linkedMapOf<String, PageDocument>()
            library.notebooks.forEach { owner -> (owner.pageIds + owner.trashedPageIds).forEach { id ->
                val file = extracted["pages/$id.json"] ?: throw IOException("Yedekte sayfa eksik.")
                val page = DocumentJson.pageFromJson(readJson(file, MAX_JSON_BYTES))
                require(page.id == id) { "Sayfa kimliği eşleşmiyor." }
                pageIds.add(id)
                pages[id] = page
                page.elements.filterIsInstance<ImageElement>().forEach { assetIds.add(it.assetId) }
                page.pdfBackground?.let { pdfIds.add(it.assetId) }
            } }
            assetIds.forEach { id ->
                validateImageFile(extracted["assets/$id.png"] ?: throw IOException("Yedekte görsel eksik."))
            }
            pdfIds.forEach { id ->
                val dimensions = validatePdfFile(extracted["assets/$id.pdf"] ?: throw IOException("Yedekte PDF eksik."))
                pages.values.mapNotNull { it.pdfBackground }.filter { it.assetId == id }.forEach { background ->
                    val size = dimensions.getOrNull(background.pageIndex) ?: throw IOException("PDF sayfası eksik.")
                    require(size.first == background.widthPoints && size.second == background.heightPoints) { "PDF sayfa ölçüleri eşleşmiyor." }
                }
            }
            val recordings = linkedMapOf<String, AudioRecording>()
            val audioIds = linkedSetOf<String>()
            library.audioItems.forEach { item ->
                val recording = AudioJson.fromJson(readJson(extracted["recordings/${item.id}.json"] ?: throw IOException("Yedekte ses belgesi eksik."), MAX_JSON_BYTES))
                require(recording.toItem() == item) { "Yedekte ses dizini uyuşmuyor." }
                validateAudioLinks(recording, library)
                recording.parts.forEach { part ->
                    val file = extracted["assets/${part.assetId}.audio"] ?: throw IOException("Yedekte ses parçası eksik.")
                    require(file.length() == part.bytes) { "Yedekte ses parçası boyutu uyuşmuyor." }
                    validateAudioContainer(file, part.mimeType)
                    audioIds.add(part.assetId)
                }
                recordings[item.id] = recording
            }
            require(!sync || library.audioItems.all { it.notebookId == library.notebooks.single().id }) { "Eşitleme dosyasında ilişkisiz ses var." }
            val referenced = setOf("library.json") + pageIds.map { "pages/$it.json" } + assetIds.map { "assets/$it.png" } + pdfIds.map { "assets/$it.pdf" } +
                recordings.keys.map { "recordings/$it.json" } + audioIds.map { "assets/$it.audio" }
            require(declared == referenced) { "Yedekte ilişkisiz dosyalar var." }
            // Validate the entire archive before committing any new visible library entry.
            val remoteBook = if (sync) library.notebooks.single() else null
            val localBook = remoteBook?.let { incoming -> existing.notebooks.firstOrNull { it.id == incoming.id } }
            val conflict = sync && if (localBook == null) expectedLocalFingerprint != null
                else fingerprint(existing, localBook) != expectedLocalFingerprint
            val replaceId = localBook?.id?.takeIf { !conflict }
            val (folderMap, importedFolders) = mapImportedFolders(library, existing, sync)
            val pageMap = pageIds.associateWith { newId() }
            val assetMap = assetIds.associateWith { newId() }
            val pdfMap = pdfIds.associateWith { newId() }
            val audioMap = audioIds.associateWith { newId() }
            val importedNotebooks = library.notebooks.map { it.copy(id = if (sync && !conflict) it.id else newId(),
                title = if (conflict) it.title.take(174) + " (eşitleme çakışması)" else it.title,
                deletedAt = if (conflict) null else it.deletedAt,
                folderId = it.folderId?.let(folderMap::getValue), pageIds = it.pageIds.map(pageMap::getValue),
                trashedPageIds = it.trashedPageIds.map(pageMap::getValue)) }
            val notebookMap = library.notebooks.zip(importedNotebooks).associate { it.first.id to it.second.id }
            val importedAudio = recordings.values.map { recording -> recording.copy(id = newId(), revision = 0,
                folderId = recording.folderId?.let(folderMap::getValue), notebookId = recording.notebookId?.let(notebookMap::getValue),
                state = if (recording.state == AudioRecordingState.RECORDING || recording.state == AudioRecordingState.PAUSED) AudioRecordingState.INTERRUPTED else recording.state,
                transcriptionStatus = if (recording.transcriptionStatus == TranscriptionStatus.RUNNING) TranscriptionStatus.PAUSED else recording.transcriptionStatus,
                parts = recording.parts.map { it.copy(id = newId(), assetId = audioMap.getValue(it.assetId), sourceKey = null) },
                transcript = recording.transcript.map { it.copy(id = newId()) },
                bookmarks = recording.bookmarks.map { it.copy(id = newId(), pageId = it.pageId?.let(pageMap::getValue)) }) }
            val reboundAudio = mutableListOf<AudioRecording>()
            val retainedPageIds = linkedSetOf<String>()
            val replacedPageIds = if (replaceId != null) (localBook!!.pageIds + localBook.trashedPageIds).toSet() else emptySet()
            val retainedAudioItems = existing.audioItems.filterNot { replaceId != null && it.notebookId == replaceId }.map { item ->
                if (replacedPageIds.isEmpty()) item else {
                    val recording = readAudio(existing, item.id)
                    val bookmarks = recording.bookmarks.map { bookmark ->
                        val oldPage = bookmark.pageId
                        if (oldPage == null || oldPage !in replacedPageIds) bookmark
                        else if (oldPage in pageMap) bookmark.copy(pageId = pageMap.getValue(oldPage))
                        else { retainedPageIds.add(oldPage); bookmark }
                    }
                    if (bookmarks == recording.bookmarks) item else nextAudio(recording).copy(bookmarks = bookmarks).also(reboundAudio::add).toItem()
                }
            }
            // Earlier safe imports may have reidentified every page. Do not guess page identity
            // from its position or silently detach a saved audio link when replacing that notebook.
            var recoveryBlank: PageDocument? = null
            val recoveredNotebook = if (retainedPageIds.isEmpty()) null else {
                val prior = checkNotNull(localBook)
                retainedPageIds.forEach { validateAssetsExist(readPage(it)) }
                val active = prior.pageIds.filter { it in retainedPageIds }.ifEmpty {
                    listOf(PageDocument().also { recoveryBlank = it }.id)
                }
                Notebook(title = "Ses işaretleri · ${prior.title.take(180)}", folderId = prior.folderId,
                    coverColor = prior.coverColor, pageIds = active, deletedAt = prior.deletedAt,
                    trashedPageIds = prior.trashedPageIds.filter { it in retainedPageIds })
            }
            val merged = existing.copy(folders = existing.folders + importedFolders,
                notebooks = existing.notebooks.filterNot { it.id == replaceId } + importedNotebooks + listOfNotNull(recoveredNotebook),
                audioItems = retainedAudioItems + importedAudio.map { it.toItem() })
            DocumentJson.validateLibrary(merged)
            reboundAudio.forEach { validateAudioLinks(it, merged) }
            assetMap.forEach { (oldId, newId) ->
                atomicWrite(AtomicSnapshotFile(assetFile(newId))) { out ->
                    extracted.getValue("assets/$oldId.png").inputStream().use { it.copyTo(out) }
                }
            }
            pdfMap.forEach { (oldId, newId) ->
                atomicWrite(AtomicSnapshotFile(pdfAssetFile(newId))) { out ->
                    extracted.getValue("assets/$oldId.pdf").inputStream().use { it.copyTo(out) }
                }
            }
            audioMap.forEach { (oldId, newId) ->
                atomicWrite(AtomicSnapshotFile(audioAssetFile(newId))) { out ->
                    extracted.getValue("assets/$oldId.audio").inputStream().use { it.copyTo(out) }
                }
            }
            importedAudio.forEach(::writeAudio)
            reboundAudio.forEach(::writeAudio)
            recoveryBlank?.let(::writePage)
            pageIds.forEach { oldId ->
                val page = pages.getValue(oldId)
                writePage(page.copy(id = pageMap.getValue(oldId), revision = 0,
                    recognitionRevision = if (page.recognitionRevision == page.revision) 0 else null,
                    pdfBackground = page.pdfBackground?.let { it.copy(assetId = pdfMap.getValue(it.assetId)) },
                    elements = page.elements.map { reidentifyElement(it, assetMap) }))
            }
            val syncResult = if (sync) importedNotebooks.single().let { SyncMergeResult(it.id, conflict, fingerprint(merged, it)) } else null
            writeLibrary(merged)
            merged to syncResult
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Yedek içe aktarılamadı. Mevcut defterler korunuyor.", error)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun readLibrary(): LibraryData {
        if (!libraryFile.baseFile.exists() && !File(root, "library.json.bak").exists()) {
            // A .new file with no prior committed library is an interrupted first save.
            return LibraryData()
        }
        return libraryFile.openRead().use { DocumentJson.libraryFromJson(readJson(it, MAX_JSON_BYTES)) }
    }

    private fun writeLibrary(library: LibraryData) {
        val bytes = DocumentJson.libraryToJson(library).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "Kütüphane dosyası çok büyük." }
        atomicWrite(libraryFile) { it.write(bytes) }
    }

    private fun readPage(id: String): PageDocument {
        val file = AtomicSnapshotFile(pageFile(id))
        return file.openRead().use { DocumentJson.pageFromJson(readJson(it, MAX_JSON_BYTES)) }.also {
            require(it.id == id) { "Sayfa kimliği eşleşmiyor." }
        }
    }

    private fun writePage(page: PageDocument) {
        val bytes = DocumentJson.pageToJson(page).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "Sayfa dosyası bu sürüm için çok büyük." }
        atomicWrite(AtomicSnapshotFile(pageFile(page.id))) { it.write(bytes) }
    }

    private fun pageFile(id: String) = File(pageDirectory, "${DocumentJson.validId(id)}.json")

    private fun validateAssetsExist(page: PageDocument) {
        page.elements.filterIsInstance<ImageElement>().forEach {
            require(assetFile(it.assetId).isFile) { "Sayfaya ait görsel bulunamadı." }
        }
        page.pdfBackground?.let { require(pdfAssetFile(it.assetId).isFile) { "Sayfaya ait PDF bulunamadı." } }
    }

    private fun notebookLibrary(library: LibraryData, owner: Notebook): LibraryData {
        val folders = library.folders.associateBy { it.id }
        val selected = linkedSetOf<String>()
        val audio = library.audioItems.filter { it.notebookId == owner.id }
        (listOf(owner.folderId) + audio.map { it.folderId }).forEach { folderId ->
            var parent = folderId
            while (parent != null) { selected.add(parent); parent = folders.getValue(parent).parentId }
        }
        return LibraryData(folders = library.folders.filter { it.id in selected }, notebooks = listOf(owner), audioItems = audio)
    }

    private fun fingerprint(library: LibraryData, owner: Notebook): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII)); digest.update(0.toByte())
            digest.update(bytes)
        }
        add(DocumentJson.libraryToJson(notebookLibrary(library, owner)).toString())
        val imageIds = sortedSetOf<String>()
        val pdfIds = sortedSetOf<String>()
        (owner.pageIds + owner.trashedPageIds).forEach { id ->
            val page = readPage(id)
            validateAssetsExist(page)
            add(DocumentJson.pageToJson(page).toString())
            imageIds.addAll(page.elements.filterIsInstance<ImageElement>().map { it.assetId })
            page.pdfBackground?.let { pdfIds.add(it.assetId) }
        }
        imageIds.forEach { add("image:$it:${sha256(assetFile(it))}") }
        pdfIds.forEach { add("pdf:$it:${sha256(pdfAssetFile(it))}") }
        library.audioItems.filter { it.notebookId == owner.id }.forEach { item ->
            val recording = readAudio(library, item.id)
            validateAudioAssets(recording)
            add(AudioJson.toJson(recording).toString())
            recording.parts.forEach { add("audio:${it.assetId}:${sha256(audioPartFile(it))}") }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun mapImportedFolders(incoming: LibraryData, existing: LibraryData, sync: Boolean): Pair<Map<String, String>, List<Folder>> {
        if (!sync) {
            val map = incoming.folders.associate { it.id to newId() }
            return map to incoming.folders.map { it.copy(id = map.getValue(it.id), parentId = it.parentId?.let(map::getValue)) }
        }
        // Match folder paths when syncing, so repeatedly downloading a notebook adds no duplicate hierarchy.
        val children = incoming.folders.groupBy { it.parentId }
        val queue = ArrayDeque(children[null].orEmpty())
        val map = mutableMapOf<String, String>()
        val added = mutableListOf<Folder>()
        val byPath = existing.folders.groupBy { it.parentId to it.name }.mapValues { it.value.first() }.toMutableMap()
        while (queue.isNotEmpty()) {
            val folder = queue.removeFirst()
            val parent = folder.parentId?.let(map::getValue)
            val path = parent to folder.name
            val matched = byPath[path] ?: folder.copy(id = newId(), parentId = parent).also { added.add(it); byPath[path] = it }
            map[folder.id] = matched.id
            queue.addAll(children[folder.id].orEmpty())
        }
        require(map.size == incoming.folders.size) { "Klasör ağacı çözümlenemedi." }
        return map to added
    }

    private fun openPdf(file: File): PdfRenderer {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
    }

    private fun validatePdfFile(file: File): List<Pair<Float, Float>> {
        require(file.length() in 5..MAX_ENTRY_BYTES) { "PDF boş veya çok büyük." }
        val header = ByteArray(minOf(1024, file.length().toInt()))
        file.inputStream().use { input -> require(input.read(header) == header.size) { "PDF okunamadı." } }
        require(header.toString(Charsets.ISO_8859_1).contains("%PDF-")) { "Dosya PDF biçiminde değil." }
        return try {
            openPdf(file).use { renderer ->
                require(renderer.pageCount in 1..2000) { "PDF 1–2000 sayfa içermeli." }
                List(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        require(page.width in 1..50_000 && page.height in 1..50_000) { "PDF sayfası desteklenen boyutu aşıyor." }
                        page.width.toFloat() to page.height.toFloat()
                    }
                }
            }
        } catch (error: Exception) { throw IOException("PDF açılamadı. Dosya bozuk veya parola korumalı olabilir.", error) }
    }

    private fun notebook(library: LibraryData, id: String): Notebook =
        library.notebooks.find { it.id == id } ?: throw IOException("Defter bulunamadı.")

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            ensureDirectory(root); ensureDirectory(pageDirectory); ensureDirectory(assetDirectory); ensureDirectory(recordingDirectory)
            block()
        }
    }

    private fun stagingDirectory(): File = File(root, "staging-${newId()}").also(::ensureDirectory)

    private fun extractArchive(input: InputStream, staging: File): Map<String, File> {
        val result = linkedMapOf<String, File>()
        var total = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && (name == "manifest.json" || name == "library.json" ||
                    name.matches(Regex("pages/[a-zA-Z0-9][a-zA-Z0-9_-]{0,100}\\.json")) ||
                    name.matches(Regex("recordings/[a-zA-Z0-9][a-zA-Z0-9_-]{0,100}\\.json")) ||
                    name.matches(Regex("assets/[a-zA-Z0-9][a-zA-Z0-9_-]{0,100}\\.(png|pdf|audio)")))) { "Güvenli olmayan yedek yolu." }
                require(name !in result && result.size < MAX_ARCHIVE_ENTRIES) { "Geçersiz yedek dosya listesi." }
                val target = File(staging, name)
                require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Geçersiz yedek yolu." }
                ensureDirectory(target.parentFile ?: throw IOException("Yedek yolu bulunamadı."))
                val remaining = (MAX_ARCHIVE_BYTES - total).coerceAtLeast(0)
                val limit = minOf(when { name.endsWith(".json") -> MAX_JSON_BYTES.toLong(); name.endsWith(".audio") -> AudioJson.MAX_AUDIO_BYTES; else -> MAX_ENTRY_BYTES }, remaining)
                total += target.outputStream().use { copyBounded(zip, it, limit) }
                result[name] = target
                zip.closeEntry()
            }
        }
        return result
    }

    private fun validateImageFile(file: File) {
        val signature = ByteArray(8)
        file.inputStream().use { require(it.read(signature) == 8) { "Görsel dosyası bozuk." } }
        require(signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) {
            "Görsel PNG biçiminde değil."
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth in 1..MAX_IMAGE_EDGE && bounds.outHeight in 1..MAX_IMAGE_EDGE &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS) { "Geçersiz yedek görseli." }
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        val check = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Yedekteki görsel çözümlenemedi.")
        check.recycle()
    }

    private fun reidentifyElement(element: PageElement, assets: Map<String, String>? = null): PageElement = when (element) {
        is StrokeElement -> element.copy(id = newId())
        is ShapeElement -> element.copy(id = newId())
        is ImageElement -> element.copy(id = newId(), assetId = assets?.getValue(element.assetId) ?: element.assetId)
        is TextElement -> element.copy(id = newId())
    }

    companion object {
        private val storeMutex = Mutex()
        internal const val MAX_JSON_BYTES = 32 * 1024 * 1024
        internal const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        internal const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 50_000
        private const val MAX_IMAGE_EDGE = 4096
        private const val MAX_IMAGE_PIXELS = 8_388_608L

        private fun ensureDirectory(directory: File) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Kayıt klasörü oluşturulamadı.")
        }

        private inline fun atomicWrite(file: AtomicSnapshotFile, block: (FileOutputStream) -> Unit) {
            var stream: FileOutputStream? = null
            try {
                stream = file.startWrite()
                block(stream)
                file.finishWrite(stream)
            } catch (error: Throwable) {
                try { file.failWrite(stream) } catch (cleanupError: Throwable) { error.addSuppressed(cleanupError) }
                throw error
            }
        }

        private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
            var count = 0L
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) return count
                count += read
                if (count > limit) throw IOException("Dosya izin verilen boyutu aşıyor.")
                output.write(buffer, 0, read)
            }
        }

        private fun readJson(file: File, limit: Int): JSONObject = file.inputStream().use { readJson(it, limit) }

        private fun readJson(input: InputStream, limit: Int): JSONObject {
            val bytes = ByteArrayOutputStream()
            copyBounded(input, bytes, limit.toLong())
            return StrictJson.objectFromText(bytes.toString(Charsets.UTF_8.name()))
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
    }
}
