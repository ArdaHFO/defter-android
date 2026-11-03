package dev.defter.app.ui

import dev.defter.app.localization.tr

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.defter.app.DefterViewModel
import dev.defter.app.audio.*
import dev.defter.app.model.*

@Composable
fun AudioWorkspace(audio: AudioViewModel, notes: DefterViewModel) {
    val context = LocalContext.current
    val capture by audio.recorder.state.collectAsState()
    val work by audio.transcription.state.collectAsState()
    val model by audio.models.state.collectAsState()
    var create by rememberSaveable { mutableStateOf(false) }
    var pendingTitle by rememberSaveable { mutableStateOf(tr("Yeni ses kaydı")) }
    var modelDialog by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var trashSelection by rememberSaveable(audio.scopeNotebookId, audio.scopeFolderId) { mutableStateOf(listOf<String>()) }
    var pendingPurge by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var allFolders by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var moveId by rememberSaveable { mutableStateOf<String?>(null) }
    var linkId by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        audio.startRecording(pendingTitle)
    }
    fun startWithNotification() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else audio.startRecording(pendingTitle)
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startWithNotification() else audio.reportError(tr("Kayıt başlatmak için mikrofon izni gerekli. Ses dosyalarını içe aktarmaya devam edebilirsin."))
    }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(audio::importAudio) }
    val exportText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(audio::exportTranscript) }
    val exportSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { it?.let(audio::exportTranscript) }
    fun picker(action: () -> Unit) { try { action() } catch (_: ActivityNotFoundException) { audio.reportError(tr("Bu cihazda dosya seçicisi bulunamadı.")) } }
    val selected = audio.selected
    val owner = notes.library.notebooks.firstOrNull { it.id == audio.scopeNotebookId }
    val folder = notes.library.folders.firstOrNull { it.id == audio.scopeFolderId }
    BackHandler { audio.back() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(Modifier.widthIn(max = 1080.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = audio::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Seslerden geri dön")) }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(selected?.title ?: if (owner != null) tr("Defter sesleri") else tr("Sesler"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(owner?.title ?: folder?.name ?: tr("Dosyalar · bağımsız ses arşivi"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalIconButton(onClick = { modelDialog = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.SettingsVoice, tr("Konuşma modeli")) }
                }
            }
        }
        AudioRecorderStrip(audio, notes.page?.id?.takeIf { notes.notebookId == audio.scopeNotebookId }, audio::showActiveRecording)
        if (work.running) {
            Surface(Modifier.widthIn(max = 1080.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(tr("Metne çevriliyor · %1\$s / %2\$s", TranscriptTools.clock(work.completedMs), TranscriptTools.clock(work.totalMs)), Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = audio.transcription::pause, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Duraklat")) }
                }
            }
        }
        if (selected == null) {
            val records = audio.library.audioItems.filter {
                (if (showTrash) it.deletedAt != null else it.deletedAt == null) &&
                    (if (audio.scopeNotebookId != null) it.notebookId == audio.scopeNotebookId else allFolders || (it.notebookId == null && it.folderId == audio.scopeFolderId)) &&
                    (audio.query.isBlank() || it.id in audio.matches)
            }.sortedByDescending { it.updatedAt }
            val selectableIds = records.filterNot { item ->
                (capture.isBusy && capture.recordingId == item.id) || (work.running && work.recordingId == item.id)
            }.map { it.id }.toSet()
            LaunchedEffect(showTrash, selectableIds) {
                trashSelection = if (showTrash) trashSelection.filter { it in selectableIds } else emptyList()
            }
            fun toggleTrash(id: String) {
                if (!audio.busy && id in selectableIds)
                    trashSelection = if (id in trashSelection) trashSelection - id else trashSelection + id
            }
            LazyColumn(Modifier.widthIn(max = 1080.dp).fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { create = true }, enabled = !audio.busy && !capture.isBusy, modifier = Modifier.heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) { Icon(Icons.Outlined.Mic, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Kayıt başlat")) }
                        OutlinedButton(onClick = { audio.prepareImport(); picker { importAudio.launch(arrayOf("audio/*")) } }, enabled = !audio.busy, modifier = Modifier.heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) { Icon(Icons.Outlined.AudioFile, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Ses içe aktar")) }
                    }
                }
                item {
                    OutlinedTextField(audio.query, audio::search, Modifier.fillMaxWidth(), placeholder = { Text(tr("Ses veya transkript ara")) }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (audio.searching) CircularProgressIndicator(Modifier.size(20.dp)) }, shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioFilterChip(!showTrash, { showTrash = false }, tr("Kayıtlar"))
                        AudioFilterChip(showTrash, { showTrash = true }, tr("Silinenler"))
                        if (owner == null) AudioFilterChip(allFolders, { allFolders = !allFolders }, tr("Tüm sesler"))
                    }
                }
                if (showTrash) item {
                    TrashSelectionBar(total = selectableIds.size, selected = trashSelection.size, busy = audio.busy,
                        onSelectAll = { trashSelection = selectableIds.toList() }, onClear = { trashSelection = emptyList() },
                        onDelete = { pendingPurge = trashSelection.filter { it in selectableIds }.takeIf { it.isNotEmpty() } })
                }
                if (records.isEmpty()) item {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Outlined.GraphicEq, null, Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(22.dp))
                            Text(if (showTrash) tr("Silinen ses yok") else tr("Henüz ses kaydı yok"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                            Text(tr("Mikrofonla kaydet veya cihazından bir ses dosyası ekle."), Modifier.widthIn(max = 340.dp).padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(records, key = { it.id }) { item ->
                    var menu by remember { mutableStateOf(false) }
                    Surface(onClick = { if (showTrash) toggleTrash(item.id) else audio.select(item.id) }, enabled = !audio.busy && (!showTrash || item.id in selectableIds), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, if (showTrash && item.id in trashSelection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (showTrash) Checkbox(checked = item.id in trashSelection, onCheckedChange = { toggleTrash(item.id) },
                                enabled = !audio.busy && item.id in selectableIds,
                                modifier = Modifier.semantics { contentDescription = tr("Ses kaydını seç: %1\$s", item.title) })
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Outlined.GraphicEq, null, Modifier.padding(13.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${TranscriptTools.clock(item.durationMs)} · ${audio.library.notebooks.firstOrNull { it.id == item.notebookId }?.title ?: tr("Bağımsız ses")}", Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = !audio.busy) { Icon(Icons.Outlined.MoreVert, tr("Ses seçenekleri %1\$s", item.title)) }
                                DropdownMenu(menu, { menu = false }) {
                                    if (showTrash) DropdownMenuItem(text = { Text(tr("Sesi geri getir")) }, onClick = { menu = false; audio.delete(item.id, false) })
                                    else {
                                        DropdownMenuItem(text = { Text(tr("Ses adını değiştir")) }, onClick = { menu = false; renameId = item.id })
                                        DropdownMenuItem(text = { Text(tr("Ses klasörünü değiştir")) }, onClick = { menu = false; moveId = item.id })
                                        DropdownMenuItem(text = { Text(tr("Deftere bağla / ayır")) }, onClick = { menu = false; linkId = item.id })
                                        DropdownMenuItem(text = { Text(tr("Sesi sil")) }, onClick = { menu = false; deleteId = item.id })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else AudioDetail(audio, notes, selected,
            onModel = { modelDialog = true }, onRename = { renameId = selected.id }, onDelete = { deleteId = selected.id }, onLink = { linkId = selected.id },
            onExport = { format -> audio.prepareExport(format); picker { if (format == "srt") exportSrt.launch("${selected.title.take(80)}.srt") else exportText.launch("${selected.title.take(80)}.txt") } })
    }
    if (create) AudioNameDialog(tr("Yeni ses kaydı"), tr("Yeni ses kaydı"), { create = false }) { title ->
        create = false; pendingTitle = title
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startWithNotification()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    renameId?.let { id -> AudioNameDialog(tr("Ses adını değiştir"), audio.library.audioItems.firstOrNull { it.id == id }?.title.orEmpty(), { renameId = null }) { audio.rename(id, it); renameId = null } }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(tr("Ses çöp kutusuna taşınsın mı?")) }, text = { Text(tr("Kaydı ve transkripti Silinenler bölümünden geri getirebilirsin.")) },
        confirmButton = { TextButton(onClick = { audio.delete(id, true); deleteId = null }) { Text(tr("Sil")) } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text(tr("Vazgeç")) } }) }
    pendingPurge?.let { ids -> PermanentDeletionDialog(ids.size, audio = true,
        onConfirm = { pendingPurge = null; audio.permanentlyDelete(ids.toSet()) }, onDismiss = { pendingPurge = null }) }
    moveId?.let { id -> AudioDestinationDialog(tr("Ses klasörü"), listOf(null to tr("Ana klasör")) + audio.library.folders.map { it.id to it.name }, { moveId = null }) { audio.move(id, it); moveId = null } }
    linkId?.let { id -> AudioDestinationDialog(tr("Deftere bağla"), listOf(null to tr("Bağımsız ses olarak tut")) + audio.library.notebooks.filter { it.deletedAt == null }.map { it.id to it.title }, { linkId = null }) { audio.link(id, it); linkId = null } }
    if (modelDialog) AlertDialog(onDismissRequest = { modelDialog = false }, icon = { Icon(Icons.Outlined.SettingsVoice, null) }, title = { Text(tr("Yerel konuşma modeli")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Türkçe ve İngilizce konuşmayı cihazında metne çevirir. Ses kayıtların gönderilmez. Model yaklaşık 148 MB indirilir."))
            Text(when (model.status) { WhisperModelStatus.READY -> tr("Model hazır · çevrimdışı kullanabilirsin"); WhisperModelStatus.DOWNLOADING -> tr("İndiriliyor · %1\$s / %2\$s MB", model.downloadedBytes / 1_000_000, model.totalBytes / 1_000_000); WhisperModelStatus.CHECKING -> tr("Model doğrulanıyor…"); WhisperModelStatus.ERROR -> model.message ?: tr("Model indirilemedi"); else -> tr("Model henüz indirilmedi") })
            if (model.status == WhisperModelStatus.DOWNLOADING) LinearProgressIndicator(progress = { if (model.totalBytes > 0) model.downloadedBytes.toFloat() / model.totalBytes else 0f }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { if (model.status == WhisperModelStatus.DOWNLOADING) TextButton(onClick = audio::cancelDownload) { Text(tr("İndirmeyi iptal et")) }
        else if (model.status != WhisperModelStatus.READY && model.status != WhisperModelStatus.CHECKING) TextButton(onClick = audio::downloadModel) { Text(tr("Modeli indir")) }
        else TextButton(onClick = { modelDialog = false }) { Text(tr("Tamam")) } }, dismissButton = { TextButton(onClick = { modelDialog = false }) { Text(tr("Kapat")) } })
}

@Composable
fun AudioRecorderStrip(audio: AudioViewModel, pageId: String?, onOpen: () -> Unit) {
    val capture by audio.recorder.state.collectAsState()
    if (!capture.isBusy && capture.error == null) return
    val stripColor by animateColorAsState(
        if (capture.phase == AudioRecorderPhase.PAUSED) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = tween(200), label = "recordingStateColor"
    )
    Surface(modifier = Modifier.fillMaxWidth(), color = stripColor) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 1080.dp).fillMaxWidth().animateContentSize(tween(200))) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                        Icon(Icons.Outlined.Mic, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${if (capture.phase == AudioRecorderPhase.PAUSED) tr("Duraklatıldı") else if (capture.phase == AudioRecorderPhase.FINALIZING) tr("Kaydediliyor") else tr("Ses kaydı")} · ${TranscriptTools.clock(capture.elapsedMs)}", fontWeight = FontWeight.SemiBold)
                    }
                    if (capture.phase == AudioRecorderPhase.RECORDING) FilledTonalIconButton(onClick = { audio.recorder.pause() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Pause, tr("Ses kaydını duraklat")) }
                    if (capture.phase == AudioRecorderPhase.PAUSED) FilledTonalIconButton(onClick = audio::resumeRecording, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.FiberManualRecord, tr("Ses kaydına devam et")) }
                    if (capture.isBusy) IconButton(onClick = { audio.recorder.stop() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Stop, tr("Ses kaydını bitir")) }
                    if (capture.phase in setOf(AudioRecorderPhase.RECORDING, AudioRecorderPhase.PAUSED)) IconButton(onClick = { audio.addBookmark(pageId) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.BookmarkAdd, tr("Ses zamanına işaret koy")) }
                }
                if (capture.error != null) Text(capture.error.orEmpty(), Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AudioDetail(audio: AudioViewModel, notes: DefterViewModel, recording: AudioRecording, onModel: () -> Unit,
    onRename: () -> Unit, onDelete: () -> Unit, onLink: () -> Unit, onExport: (String) -> Unit) {
    val playback by audio.player.state.collectAsState()
    val work by audio.transcription.state.collectAsState()
    val capture by audio.recorder.state.collectAsState()
    val model by audio.models.state.collectAsState()
    var language by rememberSaveable(recording.id) { mutableStateOf(recording.transcriptionLanguage) }
    var editId by rememberSaveable(recording.id) { mutableStateOf<String?>(null) }
    var editText by rememberSaveable(recording.id) { mutableStateOf("") }
    var confirmRestart by rememberSaveable(recording.id) { mutableStateOf(false) }
    val processing = work.running && work.recordingId == recording.id
    val position = if (playback.recordingId == recording.id) playback.positionMs else 0L
    val active = recording.deletedAt == null
    LazyColumn(Modifier.widthIn(max = 960.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val playing = playback.isPlaying && playback.recordingId == recording.id
                        val playColor by animateColorAsState(if (playing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary, tween(180), label = "playbackControlColor")
                        val playContent by animateColorAsState(if (playing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary, tween(180), label = "playbackControlContent")
                        FilledIconButton(onClick = audio::togglePlayback, enabled = recording.parts.isNotEmpty() && !capture.isBusy, modifier = Modifier.size(60.dp), shape = RoundedCornerShape(20.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = playColor, contentColor = playContent)) {
                            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, tr("Sesi oynat veya duraklat"), Modifier.size(30.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("${TranscriptTools.clock(position)} / ${TranscriptTools.clock(recording.durationMs)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (recording.state == AudioRecordingState.INTERRUPTED) tr("Kesintiden kurtarılan kayıt") else tr("Cihazında saklanıyor"), Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { audio.player.setSpeed(if (playback.speed >= 2f) .75f else playback.speed + .25f) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("${playback.speed}×", fontWeight = FontWeight.SemiBold) }
                    }
                    Slider(value = position.toFloat().coerceIn(0f, recording.durationMs.coerceAtLeast(1).toFloat()), onValueChange = { audio.player.seekTo(it.toLong()) }, valueRange = 0f..recording.durationMs.coerceAtLeast(1).toFloat(), enabled = recording.durationMs > 0 && !capture.isBusy, modifier = Modifier.padding(top = 8.dp))
                    if (playback.error != null && playback.recordingId == recording.id) Text(playback.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (active) {
                            TextButton(onClick = onRename, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Adını değiştir")) }
                            TextButton(onClick = onLink, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Defter bağlantısı")) }
                            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(tr("Sil")) }
                        } else TextButton(onClick = { audio.delete(recording.id, false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Sesi geri getir")) }
                    }
                }
            }
        }
        if (active) item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("tr" to tr("Türkçe"), "en" to tr("İngilizce"), "auto" to tr("Otomatik")).forEach { (tag, title) -> AudioFilterChip(language == tag, { language = tag }, title, enabled = !processing) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (model.status != WhisperModelStatus.READY) onModel()
                            else if (recording.transcriptionStatus == TranscriptionStatus.COMPLETE || (recording.transcribedUntilMs > 0 && language != recording.transcriptionLanguage)) confirmRestart = true
                            else audio.transcribe(language)
                        }, enabled = !audio.busy && !work.running && !capture.isBusy && recording.parts.isNotEmpty(), modifier = Modifier.heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.Transcribe, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (recording.transcriptionStatus == TranscriptionStatus.COMPLETE) tr("Yeniden metne çevir") else if (recording.transcribedUntilMs > 0) tr("Çevirmeye devam et") else tr("Metne çevir"))
                        }
                        OutlinedButton(onClick = { audio.addBookmark(notes.page?.id?.takeIf { notes.notebookId == recording.notebookId }) }, enabled = recording.parts.isNotEmpty() && !audio.busy && (!capture.isBusy || capture.recordingId == recording.id), modifier = Modifier.heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Text(tr("İşaret koy")) }
                    }
                    recording.transcriptionError?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (recording.transcriptionStatus == TranscriptionStatus.COMPLETE && recording.transcript.isEmpty()) Text(tr("Bu kayıtta tanınan konuşma bulunamadı."), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (recording.bookmarks.isNotEmpty()) {
            item { Text(tr("Zaman işaretleri"), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(recording.bookmarks, key = { "bookmark-${it.id}" }) { mark ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        TextButton(onClick = { audio.playAt(mark.positionMs) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("${TranscriptTools.clock(mark.positionMs)} · ${mark.label}", textAlign = TextAlign.Start)
                        }
                        val pageOwner = mark.pageId?.let { pageId -> audio.library.notebooks.firstOrNull { pageId in it.pageIds || pageId in it.trashedPageIds } }
                        if (mark.pageId != null && pageOwner != null && pageOwner.deletedAt == null && mark.pageId in pageOwner.pageIds) {
                            TextButton(onClick = { notes.openPage(pageOwner.id, mark.pageId); audio.close() }, modifier = Modifier.padding(start = 28.dp).heightIn(min = 48.dp)) { Text(tr("Sayfaya git")) }
                        } else if (mark.pageId != null) Text(tr("Bağlı sayfa kullanılamıyor"), Modifier.padding(start = 40.dp, end = 12.dp, bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Transkript"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (recording.transcript.isNotEmpty()) {
                    TextButton(onClick = { onExport("txt") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("TXT")) }
                    TextButton(onClick = { onExport("srt") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("SRT")) }
                }
            }
            Text(tr("Metne dokunarak o anı dinle. Tanıma hatalarını düzenleyebilirsin."), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(recording.transcript, key = { it.id }) { segment ->
            val current = playback.isPlaying && playback.recordingId == recording.id && position in segment.startMs..segment.endMs
            val segmentColor by animateColorAsState(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, tween(220), label = "transcriptSegmentColor")
            val segmentBorder by animateColorAsState(if (current) MaterialTheme.colorScheme.primary.copy(alpha = .35f) else MaterialTheme.colorScheme.outlineVariant, tween(220), label = "transcriptSegmentBorder")
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = segmentColor, border = BorderStroke(1.dp, segmentBorder)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { audio.playAt(segment.startMs) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) { Text(TranscriptTools.clock(segment.startMs), fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)) }
                        Spacer(Modifier.weight(1f))
                        if (active && !processing) IconButton(onClick = { editId = segment.id; editText = segment.text }) { Icon(Icons.Outlined.Edit, tr("Transkript bölümünü düzenle")) }
                        if (notes.page != null && active) IconButton(onClick = { notes.addText(segment.text); audio.close() }) { Icon(Icons.Outlined.PostAdd, tr("Transkripti açık sayfaya ekle")) }
                    }
                    SelectionContainer { Text(segment.text, Modifier.padding(top = 2.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp) }
                    if (segment.edited) Text(tr("Düzenlendi"), Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirmRestart) AlertDialog(onDismissRequest = { confirmRestart = false }, title = { Text(tr("Transkript yeniden oluşturulsun mu?")) }, text = { Text(tr("Mevcut transkript ve yaptığın metin düzeltmeleri yeni sonuçla değiştirilecek. Ses kaydı korunur.")) },
        confirmButton = { TextButton(onClick = { audio.transcribe(language, true); confirmRestart = false }) { Text(tr("Yeniden oluştur")) } }, dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text(tr("Vazgeç")) } })
    if (editId != null) AlertDialog(onDismissRequest = { editId = null }, title = { Text(tr("Transkripti düzenle")) }, text = { OutlinedTextField(editText, { editText = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 300.dp), label = { Text(tr("Konuşma metni")) }) },
        confirmButton = { TextButton(onClick = { audio.editSegment(editId!!, editText); editId = null }, enabled = editText.isNotBlank() && editText.length <= 10000) { Text(tr("Kaydet")) } }, dismissButton = { TextButton(onClick = { editId = null }) { Text(tr("Vazgeç")) } })
}

@Composable
private fun AudioFilterChip(selected: Boolean, onClick: () -> Unit, title: String, enabled: Boolean = true) {
    val container by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, tween(180), label = "audioFilterContainer")
    val content by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, tween(180), label = "audioFilterContent")
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .18f) else MaterialTheme.colorScheme.outlineVariant, tween(180), label = "audioFilterBorder")
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), label = { Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) }, shape = RoundedCornerShape(14.dp),
        colors = FilterChipDefaults.filterChipColors(containerColor = container, selectedContainerColor = container, labelColor = content, selectedLabelColor = content),
        border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = selected, borderColor = border, selectedBorderColor = border))
}

@Composable
private fun AudioNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true, label = { Text(tr("Kayıt adı")) }) },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }, enabled = value.isNotBlank() && value.trim().length <= 180) { Text(tr("Tamam")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) } })
}

@Composable
private fun AudioDestinationDialog(title: String, entries: List<Pair<String?, String>>, onDismiss: () -> Unit, onChoose: (String?) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { LazyColumn(Modifier.heightIn(max = 360.dp)) { items(entries) { (id, name) -> TextButton(onClick = { onChoose(id) }, Modifier.fillMaxWidth()) { Text(name) } } } }, confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) } })
}
