package dev.defter.app.audio

import dev.defter.app.localization.tr
import dev.defter.app.localization.quantity

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class AudioViewModel @JvmOverloads constructor(application: Application, private val saved: SavedStateHandle = SavedStateHandle()) : AndroidViewModel(application) {
    private val repository = DocumentRepository(application)
    val recorder = AudioRecorderController(application)
    val player = AudioPlaybackController(application)
    val transcription = AudioTranscriptionController(application)
    val models = AudioRuntime.models(application)
    var visible by mutableStateOf(saved["audioVisible"] ?: false); private set
    var scopeNotebookId by mutableStateOf<String?>(saved["audioBook"]); private set
    var scopeFolderId by mutableStateOf<String?>(saved["audioFolder"]); private set
    var selected by mutableStateOf<AudioRecording?>(null); private set
    var library by mutableStateOf(LibraryData()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var query by mutableStateOf(""); private set
    var matches by mutableStateOf<Set<String>>(emptySet()); private set
    var searching by mutableStateOf(false); private set
    private var selectionGeneration = 0L
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var modelJob: Job? = null

    init {
        action {
            recorder.recoverInterrupted()
            library = repository.loadLibrary()
            // Service work can be killed by Android; completed chunks remain resumable.
            if (!transcription.state.value.running) {
                library.audioItems.forEach { item ->
                    val document = repository.loadAudioRecording(item.id)
                    if (document.transcriptionStatus == TranscriptionStatus.RUNNING) repository.saveTranscription(document.id,
                        document.transcript, document.transcribedUntilMs, document.transcriptionModel ?: TranscriptionRunner.MODEL_ID,
                        document.transcriptionLanguage, TranscriptionStatus.PAUSED, null, document.revision)
                }
            }
            saved.get<String>("audioSelected")?.let { id ->
                if (library.audioItems.any { it.id == id }) selected = repository.loadAudioRecording(id)
            }
            refreshData()
        }
        viewModelScope.launch { models.refresh() }
        viewModelScope.launch {
            recorder.state.map { Triple(it.recordingId, it.phase, it.error) }.distinctUntilChanged().collect {
                delay(120)
                runCatching { refreshData() }.onFailure { failure -> if (failure !is CancellationException) error = failure.message }
            }
        }
        viewModelScope.launch {
            transcription.state.collect { state ->
                delay(100)
                state.error?.let { error = it }
                runCatching { refreshData() }.onFailure { failure -> if (failure !is CancellationException) error = failure.message }
            }
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message?.take(350) ?: tr("Ses işlemi tamamlanamadı.") }
            finally { busy = false }
        }
    }

    private suspend fun refreshData() {
        val loaded = repository.loadLibrary()
        library = loaded
        val id = selected?.id ?: saved.get<String>("audioSelected")
        if (id != null && loaded.audioItems.any { it.id == id }) {
            val generation = selectionGeneration
            val document = repository.loadAudioRecording(id)
            if (generation == selectionGeneration) selected = document
        } else if (id != null) clearSelection()
        if (query.isNotBlank()) search(query)
    }

    fun open(notebookId: String? = null, folderId: String? = null) {
        scopeNotebookId = notebookId; scopeFolderId = folderId
        saved["audioBook"] = notebookId; saved["audioFolder"] = folderId
        clearSelection(); query = ""; matches = emptySet()
        visible = true; saved["audioVisible"] = true
        action { refreshData() }
    }
    fun close() { visible = false; saved["audioVisible"] = false; player.pause() }
    fun showActiveRecording() {
        val id = recorder.state.value.recordingId ?: return
        visible = true; saved["audioVisible"] = true
        action {
            val document = repository.loadAudioRecording(id)
            scopeNotebookId = document.notebookId; scopeFolderId = document.folderId
            saved["audioBook"] = document.notebookId; saved["audioFolder"] = document.folderId
            selectionGeneration++; selected = document; saved["audioSelected"] = id
            refreshData()
        }
    }
    fun back() { if (selected != null) clearSelection() else close() }
    fun clearSelection() { selectionGeneration++; selected = null; saved["audioSelected"] = null; player.pause() }
    fun select(id: String) {
        val generation = ++selectionGeneration
        action {
            val document = repository.loadAudioRecording(id)
            if (selectionGeneration == generation) { selected = document; saved["audioSelected"] = id; player.load(document, document.parts.map(repository::audioPartFile)) }
        }
    }
    fun dismissError() { error = null; recorder.clearError() }
    fun dismissNotice() { notice = null }
    fun reportError(message: String) { error = message.take(350) }

    /** UI requests microphone permission before calling this visible-user action. */
    fun startRecording(title: String) = action {
        check(!recorder.state.value.isBusy) { tr("Önce açık kaydı durdur.") }
        player.pause()
        transcription.pause()
        val document = repository.createAudioRecording(title.trim().ifBlank { tr("Yeni ses kaydı") }, scopeFolderId, scopeNotebookId)
        selected = document; saved["audioSelected"] = document.id
        if (!recorder.start(document.id)) repository.setAudioState(document.id, AudioRecordingState.INTERRUPTED)
        refreshData()
    }
    fun resumeRecording() { player.pause(); transcription.pause(); recorder.resume() }
    fun prepareImport() { saved["audioImportBook"] = scopeNotebookId; saved["audioImportFolder"] = scopeFolderId }
    fun importAudio(uri: Uri) = action {
        val title = withContext(Dispatchers.IO) {
            runCatching { getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null
            } }.getOrNull()?.take(180)?.ifBlank { null } ?: tr("İçe aktarılan ses")
        }
        val document = repository.importAudio(uri, title, saved["audioImportFolder"], saved["audioImportBook"])
        selected = document; saved["audioSelected"] = document.id
        saved.remove<String>("audioImportBook"); saved.remove<String>("audioImportFolder")
        player.load(document, document.parts.map(repository::audioPartFile)); refreshData()
    }
    fun rename(id: String, title: String) = action { repository.renameAudio(id, title); refreshData() }
    fun move(id: String, folderId: String?) = action { requireIdle(id); repository.moveAudio(id, folderId); refreshData() }
    fun link(id: String, notebookId: String?) = action { requireIdle(id); repository.linkAudio(id, notebookId); refreshData() }
    fun delete(id: String, deleted: Boolean) = action {
        requireIdle(id)
        player.pause(); repository.setAudioDeleted(id, deleted); clearSelection(); refreshData()
    }
    fun permanentlyDelete(ids: Set<String>) {
        val deleting = ids.toSet()
        if (deleting.isEmpty()) return
        action {
            deleting.forEach(::requireIdle)
            library = repository.permanentlyDeleteAudio(deleting)
            if (selected?.id in deleting) clearSelection()
            if (player.state.value.recordingId in deleting) player.pause()
            refreshData()
            notice = quantity("%1\$d ses kaydı kalıcı olarak silindi.", deleting.size)
        }
    }
    private fun requireIdle(id: String) {
        check(!(recorder.state.value.recordingId == id && recorder.state.value.isBusy)) { tr("Önce ses kaydını durdur.") }
        check(!(transcription.state.value.recordingId == id && transcription.state.value.running)) { tr("Önce metne çevirmeyi duraklat.") }
    }
    fun addBookmark(pageId: String? = null, label: String = tr("İşaret")) = action {
        val capture = recorder.state.value
        val id = if (capture.isBusy) capture.recordingId else selected?.id
        check(id != null) { tr("Önce bir ses kaydı aç.") }
        val position = if (capture.isBusy) capture.elapsedMs else player.state.value.positionMs
        repository.addAudioBookmark(id, position, label, pageId)
        refreshData(); notice = tr("%1\$s konumuna işaret eklendi.", TranscriptTools.clock(position))
    }
    fun playAt(position: Long) {
        val document = selected ?: return
        if (recorder.state.value.isBusy) { error = tr("Dinlemek için önce açık kaydı durdur."); return }
        if (player.state.value.recordingId != document.id) player.load(document, document.parts.map(repository::audioPartFile))
        player.seekTo(position); player.play()
    }
    fun togglePlayback() {
        val document = selected ?: return
        if (recorder.state.value.isBusy) { error = tr("Dinlemek için önce açık kaydı durdur."); return }
        if (player.state.value.recordingId != document.id || player.state.value.durationMs != document.durationMs) player.load(document, document.parts.map(repository::audioPartFile))
        player.togglePlayback()
    }
    fun transcribe(language: String, restart: Boolean = false) {
        val document = selected ?: return
        if (recorder.state.value.isBusy) { error = tr("Metne çevirmeden önce ses kaydını durdur."); return }
        if (models.state.value.status != WhisperModelStatus.READY) { error = tr("Önce yerel konuşma modelini indir."); return }
        player.pause()
        if (!transcription.start(document.id, language, restart)) error = tr("Başka bir ses zaten işleniyor veya işlem başlatılamadı.")
    }
    fun editSegment(segmentId: String, text: String) = action {
        val document = selected ?: return@action
        requireIdle(document.id)
        require(text.isNotBlank()) { tr("Transkript metni boş olamaz.") }
        selected = repository.updateTranscript(document.id, document.transcript.map {
            if (it.id == segmentId) it.copy(text = text.trim(), edited = true) else it
        }, document.revision)
        refreshData()
    }
    fun search(value: String) {
        query = value
        val generation = ++searchGeneration
        searchJob?.cancel()
        if (value.isBlank()) { matches = emptySet(); searching = false; return }
        searchJob = viewModelScope.launch {
            searching = true
            try {
                delay(220)
                val results = mutableSetOf<String>()
                for (item in library.audioItems) {
                    ensureActive()
                    if (item.title.contains(value, ignoreCase = true)) results += item.id
                    else if (repository.loadAudioRecording(item.id).transcript.any { it.text.contains(value, ignoreCase = true) }) results += item.id
                }
                if (generation == searchGeneration) matches = results
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { if (generation == searchGeneration) searching = false }
        }
    }
    fun downloadModel() {
        if (modelJob?.isActive == true) return
        modelJob = viewModelScope.launch {
            try { models.download() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: tr("Konuşma modeli indirilemedi.") }
        }
    }
    fun cancelDownload() { models.cancelDownload(); modelJob?.cancel() }
    fun prepareExport(format: String) { saved["audioExportId"] = selected?.id; saved["audioExportFormat"] = format }
    fun exportTranscript(uri: Uri) = action {
        val id = saved.get<String>("audioExportId") ?: error(tr("Ses kaydı seçilmedi."))
        val format = saved.get<String>("audioExportFormat") ?: "txt"
        val document = repository.loadAudioRecording(id)
        val text = if (format == "srt") TranscriptTools.srt(document.transcript) else "${document.title}\n\n${TranscriptTools.plain(document.transcript)}"
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                ?: throw IllegalStateException(tr("Çıktı dosyası açılamadı."))
        }
        notice = tr("Transkript kaydedildi.")
        saved.remove<String>("audioExportId"); saved.remove<String>("audioExportFormat")
    }

    override fun onCleared() { player.release(); recorder.release(); modelJob?.cancel(); super.onCleared() }
}
