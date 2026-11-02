package dev.defter.app.ui

import android.content.ActivityNotFoundException

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.defter.app.DefterViewModel
import dev.defter.app.audio.AudioViewModel
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.model.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Accent = DefterDesign.Accent
private val AccentSoft = DefterDesign.AccentSoft
private val Paper = DefterDesign.Canvas
private val Ink = DefterDesign.Ink
private val Muted = DefterDesign.Muted
private val Outline = DefterDesign.Outline
private val Workspace = DefterDesign.Workspace
private val Covers = listOf(0xFF7162C9.toInt(), 0xFF5488AA.toInt(), 0xFF5A978C.toInt(), 0xFFC28071.toInt(), 0xFFC1A16A.toInt(), 0xFF68758D.toInt())
private val InkColors = listOf(0xFF24344B.toInt(), 0xFF356859.toInt(), 0xFF397AC6.toInt(), 0xFFC34E54.toInt(), 0xFF9566B5.toInt(), 0xFFDF933C.toInt(), 0xFFF4CD4C.toInt(), 0xFFFFFFFF.toInt())

@Composable
fun DefterApp(model: DefterViewModel, audioModel: AudioViewModel? = null) {
    DefterTheme {
        var handwritingDialog by rememberSaveable { mutableStateOf(false) }
        val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
            it?.let(model::exportBackup)
        }
        val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(model::importBackup)
        }
        val importImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(model::importImage)
        }
        val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importPdf) }
        val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
            it?.let(model::exportPdf)
        }
        val date = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) }
        fun launchPicker(action: () -> Unit) {
            try { action() } catch (_: ActivityNotFoundException) { model.reportFilePickerUnavailable() }
        }
        val backUpAction = {
            if (audioModel?.recorder?.state?.value?.isBusy == true) audioModel.reportError("Yedek almadan önce ses kaydını durdur ve kaydedilmesini bekle.")
            else launchPicker { exportBackup.launch("Defter-yedek-$date.defter") }
        }
        val restoreAction = {
            if (audioModel?.recorder?.state?.value?.isBusy == true) audioModel.reportError("Yedek yüklemeden önce ses kaydını durdur.")
            else launchPicker { importBackup.launch(arrayOf("*/*")) }
        }
        val importPdfAction = { launchPicker { importPdf.launch(arrayOf("application/pdf")) } }
        // Consume back while an editor operation waits for saved data.
        BackHandler(model.notebookId != null && audioModel?.visible != true) { if (!model.busy) model.closeNotebook() }
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = Paper) {
            Box(Modifier.fillMaxSize()) {
                when {
                    model.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    audioModel?.visible == true -> AudioWorkspace(audioModel, model)
                    model.notebookId == null -> LibraryScreen(model, backUpAction, restoreAction, importPdfAction, { handwritingDialog = true }, audioModel)
                    else -> EditorScreen(model,
                        onImage = { launchPicker { importImage.launch(arrayOf("image/*")) } },
                        onPdf = { launchPicker { exportPdf.launch("${model.notebook?.title ?: "Defter"}.pdf") } },
                        onBackup = backUpAction, onImportPdf = importPdfAction, onHandwriting = { handwritingDialog = true }, audioModel = audioModel)
                }
                if (model.busy) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 12.dp, color = Color.White) {
                            Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                                Text(model.progress.ifBlank { "Hazırlanıyor…" })
                            }
                        }
                    }
                }
            }
        }
        if (handwritingDialog) HandwritingSettingsDialog(model) { handwritingDialog = false }
        if (model.recognitionCandidates.isNotEmpty()) RecognitionResultDialog(model)
        audioModel?.error?.let { message ->
            AlertDialog(onDismissRequest = audioModel::dismissError, title = { Text("Ses işlemi") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = audioModel::dismissError) { Text("Tamam") } })
        }
        audioModel?.notice?.let { message ->
            AlertDialog(onDismissRequest = audioModel::dismissNotice, title = { Text("Sesler") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = audioModel::dismissNotice) { Text("Tamam") } })
        }
        model.error?.let { error ->
            AlertDialog(onDismissRequest = model::dismissError, icon = { Icon(Icons.Outlined.Info, null) },
                title = { Text(if (model.unsaved) "Kaydetme tamamlanamadı" else "İşlem tamamlanamadı") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { model.dismissError(); if (model.unsaved) model.retrySave() }) { Text(if (model.unsaved) "Yeniden dene" else "Tamam") } },
                dismissButton = { if (model.unsaved) TextButton(onClick = { model.dismissError(); model.recoverUnsavedAsCopies() }) { Text("Kopya olarak kurtar") } })
        }
        model.notice?.let { notice ->
            AlertDialog(onDismissRequest = model::dismissNotice, title = { Text("Defter") }, text = { Text(notice) },
                confirmButton = { TextButton(onClick = model::dismissNotice) { Text("Tamam") } })
        }
    }
}

@Composable
private fun LibraryScreen(model: DefterViewModel, onBackup: () -> Unit, onRestore: () -> Unit, onImportPdf: () -> Unit, onHandwriting: () -> Unit, audioModel: AudioViewModel? = null) {
    var create by rememberSaveable { mutableStateOf(false) }
    var folderDialog by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Notebook?>(null) }
    var trashTarget by remember { mutableStateOf<Notebook?>(null) }
    var moving by remember { mutableStateOf<Notebook?>(null) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query, model.library) { delay(250); model.searchPages(query) }
    val folder = model.library.folders.firstOrNull { it.id == model.folderId }
    val books = model.library.notebooks.filter {
        (if (showTrash) it.deletedAt != null else it.deletedAt == null && (query.isNotBlank() || it.folderId == model.folderId)) &&
            (it.title.contains(query, ignoreCase = true) || model.searchResults.any { result -> result.notebookId == it.id })
    }.sortedByDescending { it.updatedAt }
    val folders = if (!showTrash && query.isBlank()) model.library.folders.filter { it.parentId == model.folderId } else emptyList()
    BackHandler(model.folderId != null && !showTrash) { model.navigateFolder(folder?.parentId) }
    BackHandler(showTrash) { showTrash = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Column(Modifier.width(240.dp).fillMaxHeight().background(Color.White).padding(20.dp)) {
                    Brand()
                    Spacer(Modifier.height(32.dp))
                    Text("ÇALIŞMA ALANIN", Modifier.padding(start = 14.dp, bottom = 12.dp), fontSize = 10.sp, letterSpacing = 1.5.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                    NavigationRow(Icons.Outlined.AutoStories, "Kütüphane", !showTrash) { showTrash = false; model.navigateFolder(null); query = "" }
                    if (audioModel != null) NavigationRow(Icons.Outlined.GraphicEq, "Sesler", false) { audioModel.open(folderId = model.folderId) }
                    NavigationRow(Icons.Outlined.DeleteOutline, "Çöp kutusu", showTrash) { showTrash = true; query = "" }
                    Spacer(Modifier.weight(1f))
                    NavigationRow(Icons.Outlined.PictureAsPdf, "PDF içe aktar", false, onImportPdf)
                    NavigationRow(Icons.Outlined.FindInPage, "El yazısı ve arama", false, onHandwriting)
                    NavigationRow(Icons.Outlined.FileUpload, "Yedek al", false, onBackup)
                    NavigationRow(Icons.Outlined.FileDownload, "Yedekten yükle", false, onRestore)
                    Spacer(Modifier.height(18.dp))
                    Surface(color = Paper, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Outline.copy(alpha = .65f))) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.OfflinePin, null, tint = Color(0xFF367A6D), modifier = Modifier.size(20.dp))
                            Text("Notların cihazında.\nİnternetsiz çalışır.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (wide) 36.dp else 20.dp)) {
                Spacer(Modifier.height(if (wide) 28.dp else 20.dp))
                if (!wide) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Brand(Modifier.weight(1f))
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Kütüphane menüsü") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(if (showTrash) "Kütüphaneye dön" else "Çöp kutusu") }, onClick = { showTrash = !showTrash; menu = false })
                                DropdownMenuItem(text = { Text("Tam yedek al") }, onClick = { menu = false; onBackup() })
                                DropdownMenuItem(text = { Text("Yedekten yükle") }, onClick = { menu = false; onRestore() })
                                DropdownMenuItem(text = { Text("PDF içe aktar") }, onClick = { menu = false; onImportPdf() })
                                DropdownMenuItem(text = { Text("El yazısı ve arama") }, onClick = { menu = false; onHandwriting() })
                                if (audioModel != null) DropdownMenuItem(text = { Text("Sesler") }, onClick = { menu = false; audioModel.open(folderId = model.folderId) })
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (folder != null && !showTrash) IconButton(onClick = { model.navigateFolder(folder.parentId) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Üst klasör") }
                    Column(Modifier.weight(1f)) {
                        if (wide) Text(if (showTrash) "KÜTÜPHANEYİ DÜZENLE" else "FİKİRLERİNE YER AÇ", fontSize = 10.sp, letterSpacing = 1.6.sp, color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 9.dp))
                        Text(if (showTrash) "Çöp kutusu" else folder?.name ?: "Kütüphanen", fontSize = if (wide) 36.sp else 30.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(7.dp))
                        Text(if (showTrash) "Silinen defterlerini buradan geri getirebilirsin." else "${books.size} defter · ${folders.size} klasör", color = Muted, fontSize = 14.sp)
                    }
                }
                if (audioModel != null && !showTrash) {
                    Surface(Modifier.fillMaxWidth().padding(top = 18.dp).clickable { audioModel.open(folderId = model.folderId) }, color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Outline)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).background(AccentSoft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.GraphicEq, null, tint = Accent, modifier = Modifier.size(24.dp)) }
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text("Sesler", fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text("Bağımsız kayıtlar ve ses dosyaları", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 3.dp)) }
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Ses arşivini aç", tint = Accent, modifier = Modifier.size(20.dp))
                        }
                    }
                    AudioRecorderStrip(audioModel, null, audioModel::showActiveRecording)
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f), placeholder = { Text("Defter veya içerik ara", maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Muted) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Aramayı temizle") } },
                        singleLine = true, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White, unfocusedBorderColor = Outline))
                    if (!showTrash) {
                        IconButton(onClick = { folderDialog = true }, modifier = Modifier.size(54.dp).background(Color.White, RoundedCornerShape(18.dp)).border(1.dp, Outline, RoundedCornerShape(18.dp))) { Icon(Icons.Outlined.CreateNewFolder, "Yeni klasör") }
                        if (wide) Button(onClick = { create = true }, modifier = Modifier.height(54.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Yeni defter", fontWeight = FontWeight.SemiBold) }
                        else FilledIconButton(onClick = { create = true }, modifier = Modifier.size(54.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Outlined.Add, "Yeni defter") }
                    }
                }
                if (model.pendingSharedImage != null || model.pendingSharedText != null) {
                    Surface(Modifier.fillMaxWidth().padding(top = 16.dp), color = AccentSoft, shape = RoundedCornerShape(12.dp)) {
                        Text("Paylaşılan içeriği eklemek için bir defter aç veya oluştur.", Modifier.padding(14.dp), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (query.isNotBlank()) PageSearchResults(model)
                if (books.isEmpty() && folders.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(bottom = 40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(106.dp).clip(RoundedCornerShape(30.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                            if (showTrash || query.isNotBlank()) Icon(if (showTrash) Icons.Outlined.DeleteOutline else Icons.Outlined.Search, null, Modifier.size(44.dp), tint = Accent)
                            else DefterBrandMark(Modifier.size(80.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(if (showTrash) "Çöp kutusu boş" else if (query.isNotBlank()) "Defter bulunamadı" else "Yeni bir sayfa aç.", fontWeight = FontWeight.SemiBold, fontSize = 25.sp, letterSpacing = (-.5).sp)
                        Spacer(Modifier.height(10.dp))
                        Text(if (showTrash) "Defterlerin kütüphanende güvende." else if (query.isNotBlank()) "Başka bir adla aramayı dene." else "Derslerin, çizimlerin, aklına gelenler.", color = Muted, fontSize = 14.sp)
                        if (!showTrash && query.isBlank()) {
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { create = true }, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("İlk defterini oluştur") }
                        }
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(if (wide) 200.dp else 145.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (folders.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { LibrarySectionHeading("KLASÖRLER", folders.size) }
                            items(folders, key = { "folder-${it.id}" }) { item ->
                                Surface(Modifier.fillMaxWidth().clickable { model.navigateFolder(item.id) }, color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Outline)) {
                                    Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(40.dp).background(Color(0xFFF8F1E3), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Folder, null, tint = Color(0xFF9A7639), modifier = Modifier.size(23.dp)) }
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text("${model.library.notebooks.count { it.folderId == item.id && it.deletedAt == null }} defter", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                                        }
                                        IconButton(onClick = { renameFolder = item }) { Icon(Icons.Outlined.MoreHoriz, "Klasörü yeniden adlandır", Modifier.size(20.dp)) }
                                    }
                                }
                            }
                        }
                        if (books.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { LibrarySectionHeading(if (showTrash) "SİLİNEN DEFTERLER" else "DEFTERLER", books.size) }
                        items(books, key = { it.id }) { book -> NotebookCard(book, showTrash,
                            onOpen = { if (!showTrash) model.openNotebook(book.id) },
                            onRename = { rename = book }, onDelete = { trashTarget = book },
                            onRestore = { model.trashNotebook(book.id, false) }, onMove = { moving = book }) }
                    }
                }
            }
        }
    }
    if (create) CreateNotebookDialog(onDismiss = { create = false }) { name, template, color -> create = false; model.createNotebook(name, template, color) }
    if (folderDialog) NameDialog("Yeni klasör", "Klasör adı", "", onDismiss = { folderDialog = false }) { folderDialog = false; model.createFolder(it) }
    rename?.let { book -> NameDialog("Defteri yeniden adlandır", "Defter adı", book.title, onDismiss = { rename = null }) { rename = null; model.renameNotebook(book.id, it) } }
    renameFolder?.let { folderToRename -> NameDialog("Klasörü yeniden adlandır", "Klasör adı", folderToRename.name, onDismiss = { renameFolder = null }) { renameFolder = null; model.renameFolder(folderToRename.id, it) } }
    moving?.let { book ->
        AlertDialog(onDismissRequest = { moving = null }, title = { Text("Defteri taşı") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = { moving = null; model.moveNotebook(book.id, null) }) { Icon(Icons.Outlined.Home, null); Spacer(Modifier.width(8.dp)); Text("Kütüphane") }
                model.library.folders.forEach { target ->
                    val parent = model.library.folders.firstOrNull { it.id == target.parentId }
                    TextButton(onClick = { moving = null; model.moveNotebook(book.id, target.id) }) {
                        Icon(Icons.Outlined.Folder, null); Spacer(Modifier.width(8.dp)); Text(if (parent == null) target.name else "${parent.name} / ${target.name}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { moving = null }) { Text("Vazgeç") } })
    }
    trashTarget?.let { book ->
        AlertDialog(onDismissRequest = { trashTarget = null }, title = { Text("Çöp kutusuna taşı?") }, text = { Text("“${book.title}” defterini daha sonra geri getirebilirsin.") },
            confirmButton = { TextButton(onClick = { trashTarget = null; model.trashNotebook(book.id, true) }) { Text("Çöp kutusuna taşı") } },
            dismissButton = { TextButton(onClick = { trashTarget = null }) { Text("Vazgeç") } })
    }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DefterBrandMark(Modifier.size(44.dp))
        Column {
            Text("defter", fontSize = 27.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("DÜŞÜN. YAZ. HATIRLA.", fontSize = 8.sp, letterSpacing = 1.1.sp, color = Muted, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun LibrarySectionHeading(title: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 10.sp, letterSpacing = 1.5.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        Box(Modifier.background(Outline.copy(alpha = .65f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
            Text(count.toString(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Muted)
        }
        HorizontalDivider(Modifier.weight(1f), color = Outline.copy(alpha = .8f))
    }
}

@Composable
private fun NavigationRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) AccentSoft else Color.Transparent, tween(180), label = "navigation-fill")
    val tint by animateColorAsState(if (selected) Accent else Muted, tween(180), label = "navigation-ink")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(15.dp)).background(fill).clickable(onClick = onClick).semantics { this.selected = selected }.padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(21.dp), tint = tint)
        Text(label, color = tint, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Box(Modifier.size(5.dp).background(Accent, CircleShape))
    }
}

@Composable
private fun NotebookCard(book: Notebook, trash: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onRestore: () -> Unit, onMove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, tween(150), label = "cover-press")
    val stroke by animateColorAsState(if (pressed) Accent.copy(alpha = .4f) else Outline, tween(150), label = "cover-outline")
    val updated = remember(book.updatedAt) { SimpleDateFormat("d MMM", Locale.forLanguageTag("tr")).format(Date(book.updatedAt)) }
    Surface(modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, shape = RoundedCornerShape(23.dp), color = Color.White, border = BorderStroke(1.dp, stroke), shadowElevation = 1.dp) {
        Column(Modifier.padding(9.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.06f).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(book.coverColor), androidx.compose.ui.graphics.lerp(Color(book.coverColor), Ink, .18f)))).clickable(interactionSource = interaction, indication = ripple(), enabled = !trash, onClick = onOpen)) {
            Box(Modifier.width(9.dp).fillMaxHeight().background(Color.Black.copy(alpha = .10f)))
            Box(Modifier.padding(start = 14.dp).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = .22f)))
            Box(Modifier.align(Alignment.TopEnd).padding(end = 18.dp).width(10.dp).height(29.dp).background(Color.White.copy(alpha = .18f), RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)))
            Column(Modifier.fillMaxSize().padding(start = 25.dp, end = 20.dp, top = 21.dp, bottom = 18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("defter", fontSize = 12.sp, letterSpacing = (-.3).sp, color = Color.White.copy(alpha = .8f), fontWeight = FontWeight.SemiBold)
                }
                Text(book.title, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-.4).sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(38.dp).height(2.dp).background(Color.White.copy(alpha = .65f), CircleShape))
                    Box(Modifier.width(24.dp).height(2.dp).background(Color.White.copy(alpha = .27f), CircleShape))
                }
            }
        }
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(book.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text("${book.pageIds.size} sayfa · $updated", fontSize = 11.sp, color = Muted)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "${book.title} seçenekleri", Modifier.size(20.dp)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (trash) DropdownMenuItem(text = { Text("Geri getir") }, onClick = { menu = false; onRestore() })
                    else {
                        DropdownMenuItem(text = { Text("Yeniden adlandır") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Klasöre taşı") }, onClick = { menu = false; onMove() })
                        DropdownMenuItem(text = { Text("Çöp kutusuna taşı") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun EditorScreen(model: DefterViewModel, onImage: () -> Unit, onPdf: () -> Unit, onBackup: () -> Unit, onImportPdf: () -> Unit, onHandwriting: () -> Unit, audioModel: AudioViewModel? = null) {
    var canvas by remember { mutableStateOf<NotebookCanvas?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var eraserDialog by rememberSaveable { mutableStateOf(false) }
    var geometryDialog by rememberSaveable { mutableStateOf(false) }
    var editTextId by rememberSaveable { mutableStateOf<String?>(null) }
    var cropImageId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionColorTargets by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var selectionColorDialog by rememberSaveable { mutableStateOf(false) }
    var chooseTab by rememberSaveable { mutableStateOf(false) }
    var chooseReference by rememberSaveable { mutableStateOf(false) }
    var temporaryEraser by remember { mutableStateOf(false) }
    var toolWidths by rememberSaveable { mutableStateOf(DrawingTool.entries.map { if (it == DrawingTool.HIGHLIGHTER) 24f else 3.5f }) }
    var pageDialog by rememberSaveable { mutableStateOf(false) }
    var templateDialog by rememberSaveable { mutableStateOf(false) }
    var colorDialog by rememberSaveable { mutableStateOf(false) }
    var widthDialog by rememberSaveable { mutableStateOf(false) }
    var textDialog by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val current = model.page
    LaunchedEffect(model.config.tool, model.config.width) {
        toolWidths = toolWidths.toMutableList().also { it[model.config.tool.ordinal] = model.config.width }
    }
    fun selectTool(tool: DrawingTool) {
        if (tool == model.config.tool) return
        val widths = toolWidths.toMutableList().also { it[model.config.tool.ordinal] = model.config.width }
        toolWidths = widths
        model.config = model.config.copy(tool = tool, width = widths[tool.ordinal])
    }
    val effectiveTool = if (temporaryEraser) DrawingTool.ERASER else model.config.tool
    Column(Modifier.fillMaxSize().background(Workspace)) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(Color.White)) {
            val separateHistoryRow = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
            Column {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = model::closeNotebook) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Kütüphaneye dön", tint = Ink) }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(model.notebook?.title ?: "Defter", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = (-.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            val saving = model.pendingSaves > 0
                            Icon(if (model.unsaved) Icons.Outlined.ErrorOutline else if (saving) Icons.Outlined.Sync else Icons.Outlined.CheckCircle, null, Modifier.size(12.dp), tint = if (model.unsaved) Color(0xFFB64444) else Muted)
                            Text(if (model.unsaved) "Kaydedilemedi · yeniden dene" else if (saving) "Kaydediliyor…" else "Cihazına kaydedildi", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (model.unsaved) Color(0xFFB64444) else Muted, modifier = Modifier.clickable(enabled = model.unsaved) { model.retrySave() })
                        }
                    }
                    if (!separateHistoryRow) HistoryControls(model)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Defter menüsü") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Resim ekle") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { menu = false; onImage() })
                            DropdownMenuItem(text = { Text("PDF içe aktar") }, onClick = { menu = false; onImportPdf() })
                            DropdownMenuItem(text = { Text("Panodan yapıştır") }, leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) }, onClick = { menu = false; model.paste() })
                            DropdownMenuItem(text = { Text("Metin ekle") }, leadingIcon = { Icon(Icons.Outlined.TextFields, null) }, onClick = { menu = false; textDialog = true })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Sayfa şablonu") }, onClick = { menu = false; templateDialog = true })
                            DropdownMenuItem(text = { Text("Sayfayı çoğalt") }, onClick = { menu = false; model.duplicatePage() })
                            DropdownMenuItem(text = { Text("Silgi seçenekleri") }, onClick = { menu = false; eraserDialog = true })
                            DropdownMenuItem(text = { Text("Matematik araçları") }, onClick = { menu = false; geometryDialog = true })
                            DropdownMenuItem(text = { Text("El yazısını metne çevir") }, onClick = { menu = false; model.recognizeHandwriting(selectedIds) })
                            DropdownMenuItem(text = { Text("El yazısı ve arama") }, onClick = { menu = false; onHandwriting() })
                            if (audioModel != null) DropdownMenuItem(text = { Text("Ses kaydı ve transkript") }, leadingIcon = { Icon(Icons.Outlined.Mic, null) }, onClick = { menu = false; audioModel.open(model.notebookId, model.notebook?.folderId) })
                            DropdownMenuItem(text = { Text("Yan yana aç") }, onClick = { menu = false; chooseReference = true })
                            DropdownMenuItem(text = { Text("PDF olarak kaydet") }, onClick = { menu = false; onPdf() })
                            DropdownMenuItem(text = { Text("Tam yedek al") }, onClick = { menu = false; onBackup() })
                        }
                    }
                }
                if (separateHistoryRow) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) { HistoryControls(model) }
                }
            }
        }
        HorizontalDivider(color = Outline)
        NotebookTabs(model) { chooseTab = true }
        if (audioModel != null) AudioRecorderStrip(audioModel, model.page?.id?.takeIf { audioModel.library.audioItems.any { item -> item.id == audioModel.recorder.state.value.recordingId && item.notebookId == model.notebookId } }, audioModel::showActiveRecording)
        Surface(Modifier.widthIn(max = 860.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 12.dp, vertical = 10.dp), color = Color.White, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Outline.copy(alpha = .85f)), shadowElevation = 3.dp) {
            Column {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                DrawingTool.entries.forEach { tool ->
                    ToolButton(toolIcon(tool), if (tool == DrawingTool.ERASER && model.config.eraserMode == EraserMode.PARTIAL) "Kısmi silgi" else tool.label, effectiveTool == tool) { selectTool(tool) }
                    if (tool == DrawingTool.ERASER || tool == DrawingTool.ELLIPSE || tool == DrawingTool.HAND) {
                        Box(Modifier.padding(horizontal = 7.dp).width(1.dp).height(24.dp).background(Outline))
                    }
                }
                ToolButton(Icons.Outlined.Image, "Resim ekle", false, onImage)
                if (audioModel != null) ToolButton(Icons.Outlined.Mic, "Defter sesleri", false) { audioModel.open(model.notebookId, model.notebook?.folderId) }
                ToolButton(Icons.Outlined.TextFields, "Metin ekle", false) { textDialog = true }
                ToolButton(Icons.Outlined.Tune, "Silgi seçenekleri", configIsEraserCustom(model.config)) { eraserDialog = true }
                ToolButton(Icons.Outlined.SquareFoot, "Matematik araçları", model.config.shapeSnap) { geometryDialog = true }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Outline)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                InkColors.forEach { color -> ColorDot(color, model.config.color == color, 24) { model.config = model.config.copy(color = color) } }
                IconButton(onClick = { colorDialog = true }) { Icon(Icons.Outlined.Palette, "Özel renk", Modifier.size(21.dp), tint = Muted) }
                OutlinedButton(onClick = { widthDialog = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(48.dp).semantics { contentDescription = "Kalınlık ayarı" }, border = BorderStroke(1.dp, Outline), shape = RoundedCornerShape(14.dp)) {
                    Box(Modifier.size(model.config.width.coerceIn(3f, 12f).dp).background(Ink, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(String.format(Locale.ROOT, "%.1f", model.config.width), fontSize = 12.sp, color = Ink)
                }
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = model.config.touchDrawing, onClick = { model.config = model.config.copy(touchDrawing = !model.config.touchDrawing) }, label = { Text("Parmakla yaz", fontSize = 12.sp) }, modifier = Modifier.height(48.dp), leadingIcon = { Icon(Icons.Outlined.TouchApp, null, Modifier.size(17.dp)) })
            }
            }
        }
        if (model.pendingSharedImage != null || model.pendingSharedText != null) {
            TextButton(onClick = model::insertSharedContent, modifier = Modifier.fillMaxWidth().background(AccentSoft)) { Icon(Icons.Outlined.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Paylaşılan içeriği bu sayfaya ekle") }
        }
        WorkspacePanes(model, Modifier.weight(1f).fillMaxWidth()) {
        Box(Modifier.fillMaxSize().background(Workspace)) {
            if (current != null) {
                AndroidView(factory = { context -> NotebookCanvas(context).also { view ->
                    canvas = view
                    view.onDocumentChanged = model::changePage
                    view.onEditingError = model::reportEditingError
                    view.onZoomChanged = { zoom = it }
                    view.onSelectionChanged = { selected = it }
                    view.onSelectionIdsChanged = { selectedIds = it }
                    view.onTemporaryEraserChanged = { temporaryEraser = it }
                    temporaryEraser = view.isTemporaryEraserActive
                } }, update = { view ->
                    view.document = current
                    view.config = model.config
                    view.assets = model.assets
                    view.isEnabled = !model.busy
                }, modifier = Modifier.fillMaxSize())
                if (temporaryEraser) {
                    // A visual-only Row lets pen input reach the page behind this status badge.
                    Row(Modifier.align(Alignment.TopCenter).padding(12.dp).shadow(4.dp, RoundedCornerShape(16.dp)).background(Ink, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(toolIcon(DrawingTool.ERASER), null, tint = Color.White, modifier = Modifier.size(17.dp))
                        Text("Yan düğme · Silgi", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (selectedIds.isNotEmpty() || selected != null) {
                    Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), shape = RoundedCornerShape(18.dp), shadowElevation = 8.dp, color = Color.White) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { canvas?.scaleSelection(.9f) }) { Icon(Icons.Outlined.Remove, "Seçileni küçült") }
                            Text("${selectedIds.size.coerceAtLeast(1)} nesne", fontSize = 12.sp, color = Muted)
                            IconButton(onClick = { canvas?.scaleSelection(1.1f) }) { Icon(Icons.Outlined.Add, "Seçileni büyüt") }
                            VerticalDivider(Modifier.height(24.dp))
                            IconButton(onClick = { canvas?.copySelection() }) { Icon(Icons.Outlined.ContentCopy, "Seçileni kopyala") }
                            IconButton(onClick = { selectionColorTargets = selectedIds.toList(); selectionColorDialog = true }) { Icon(Icons.Outlined.Palette, "Seçileni renklendir") }
                            if (canvas?.selectedElements?.any { it is ImageElement } == true) IconButton(onClick = { canvas?.rotateSelection(90f) }) { Icon(Icons.AutoMirrored.Outlined.RotateRight, "Seçili görseli döndür") }
                            (canvas?.selectedElements?.singleOrNull() as? ImageElement)?.let { image -> IconButton(onClick = { cropImageId = image.id }) { Icon(Icons.Outlined.Crop, "Seçili görseli kırp") } }
                            (canvas?.selectedElements?.singleOrNull() as? TextElement)?.let { text -> IconButton(onClick = { editTextId = text.id }) { Icon(Icons.Outlined.EditNote, "Seçili metni düzenle") } }
                            IconButton(onClick = { canvas?.deleteSelection() }) { Icon(Icons.Outlined.DeleteOutline, "Seçileni sil", tint = Color(0xFFB64444)) }
                        }
                    }
                }
            }
        }
        }
        HorizontalDivider(color = Outline)
        Row(Modifier.fillMaxWidth().height(60.dp).background(Color.White).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { canvas?.resetViewport() }) { Text("${(zoom * 100).toInt()}%", fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { model.notebook?.pageIds?.getOrNull(model.pageIndex - 1)?.let(model::selectPage) }, enabled = model.pageIndex > 0) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Önceki sayfa", Modifier.size(19.dp)) }
            TextButton(onClick = { pageDialog = true }, modifier = Modifier.background(AccentSoft, RoundedCornerShape(14.dp)).semantics { contentDescription = "Sayfaları göster" }) { Icon(Icons.Outlined.GridView, null, Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text("${model.pageIndex + 1} / ${model.notebook?.pageIds?.size ?: 0}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            IconButton(onClick = { model.notebook?.pageIds?.getOrNull(model.pageIndex + 1)?.let(model::selectPage) }, enabled = model.pageIndex < (model.notebook?.pageIds?.lastIndex ?: 0)) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Sonraki sayfa", Modifier.size(19.dp)) }
            Spacer(Modifier.weight(1f))
            FilledIconButton(onClick = { model.addPage(current?.template ?: PaperTemplate.DOTS) }, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.AutoMirrored.Outlined.NoteAdd, "Yeni sayfa", Modifier.size(21.dp)) }
        }
    }
    if (colorDialog) ColorDialog(model.config.color, onDismiss = { colorDialog = false }) { colorDialog = false; model.config = model.config.copy(color = it) }
    if (widthDialog) {
        var width by rememberSaveable { mutableFloatStateOf(model.config.width) }
        AlertDialog(onDismissRequest = { widthDialog = false }, title = { Text("Kalınlık") },
            text = { Column { Text(String.format(Locale.ROOT, "%.1f", width)); Slider(width, { width = it }, valueRange = .8f..40f) } },
            confirmButton = { TextButton(onClick = { model.config = model.config.copy(width = width); widthDialog = false }) { Text("Uygula") } })
    }
    if (textDialog) NameDialog("Metin ekle", "Notun", "", onDismiss = { textDialog = false }, multiline = true) { textDialog = false; model.addText(it) }
    if (templateDialog) {
        AlertDialog(onDismissRequest = { templateDialog = false }, title = { Text("Sayfa şablonu") },
            text = { Column { PaperTemplate.entries.forEach { template ->
                Row(Modifier.fillMaxWidth().clickable { model.setTemplate(template); templateDialog = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = current?.template == template, onClick = { model.setTemplate(template); templateDialog = false })
                    Text(template.label)
                }
            }
                Text("Kâğıt rengi", Modifier.padding(top = 12.dp, bottom = 8.dp), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(0xFFFFFEF9.toInt(), 0xFFFFFFFF.toInt(), 0xFFF0E6D0.toInt(), 0xFF28383D.toInt()).forEach { color ->
                        ColorDot(color, current?.backgroundColor == color, 30) { model.setPaperColor(color) }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { templateDialog = false }) { Text("Kapat") } })
    }
    if (pageDialog) PageManagerDialog(model) { pageDialog = false }
    if (eraserDialog) EraserSettingsDialog(model.config, { model.config = it }, { eraserDialog = false })
    if (geometryDialog) GeometrySettingsDialog(model.config, { model.config = it }, { geometryDialog = false })
    if (chooseTab) NotebookPickerDialog(model, "Sekmede aç", { model.openNotebook(it); chooseTab = false }, { chooseTab = false })
    if (chooseReference) NotebookPickerDialog(model, "Yan yana aç", { model.openReference(it); chooseReference = false }, { chooseReference = false })
    if (selectionColorDialog) ColorDialog(model.config.color, { selectionColorDialog = false }) { canvas?.selectElements(selectionColorTargets.toSet()); canvas?.recolorSelection(it); selectionColorDialog = false }
    current?.elements?.filterIsInstance<TextElement>()?.firstOrNull { it.id == editTextId }?.let { text -> EditTextDialog(text, { value, size, color -> canvas?.selectElements(setOf(text.id)); canvas?.updateSelectedText(value, size, color); editTextId = null }, { editTextId = null }) }
    current?.elements?.filterIsInstance<ImageElement>()?.firstOrNull { it.id == cropImageId }?.let { image -> ImageCropDialog(image, { left, top, right, bottom -> canvas?.selectElements(setOf(image.id)); canvas?.cropSelectedImage(left, top, right, bottom); cropImageId = null }, { cropImageId = null }) }
}

private fun configIsEraserCustom(config: DrawingConfig) = config.eraserMode == EraserMode.PARTIAL || config.eraserFilter != EraserFilter.ALL

@Composable
private fun HistoryControls(model: DefterViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HistoryButton(Icons.AutoMirrored.Outlined.Undo, "Geri al", model.canUndo && !model.busy, model::undo)
        HistoryButton(Icons.AutoMirrored.Outlined.Redo, "Yinele", model.canRedo && !model.busy, model::redo)
    }
}

@Composable
private fun HistoryButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (enabled) AccentSoft else Paper, tween(180), label = "history-fill")
    val tint by animateColorAsState(if (enabled) Accent else Muted.copy(alpha = .65f), tween(180), label = "history-ink")
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 54.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = fill, contentColor = tint,
            disabledContainerColor = fill, disabledContentColor = tint
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private fun toolIcon(tool: DrawingTool): ImageVector = when (tool) {
    DrawingTool.PEN -> Icons.Outlined.Edit
    DrawingTool.HIGHLIGHTER -> Icons.Outlined.BorderColor
    DrawingTool.ERASER -> Icons.Outlined.AutoFixNormal
    DrawingTool.LINE -> Icons.Outlined.HorizontalRule
    DrawingTool.ARROW -> Icons.AutoMirrored.Outlined.ArrowForward
    DrawingTool.RECTANGLE -> Icons.Outlined.CropSquare
    DrawingTool.ELLIPSE -> Icons.Outlined.RadioButtonUnchecked
    DrawingTool.SELECT -> Icons.Outlined.NearMe
    DrawingTool.LASSO -> Icons.Outlined.Gesture
    DrawingTool.HAND -> Icons.Outlined.PanTool
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(if (selected) Accent else Color.Transparent, tween(180), label = "tool-fill")
    val tint by animateColorAsState(if (selected) Color.White else Muted, tween(180), label = "tool-ink")
    val scale by animateFloatAsState(if (pressed) .9f else 1f, tween(130), label = "tool-press")
    IconButton(onClick = onClick, interactionSource = interaction, modifier = Modifier.size(48.dp).background(fill, RoundedCornerShape(15.dp)).semantics { this.selected = selected }) {
        Icon(icon, label, Modifier.size(21.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = tint)
    }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, size: Int, onClick: () -> Unit) {
    val ring by animateColorAsState(if (selected) Accent else Color.Transparent, tween(180), label = "ink-color-ring")
    val label = when (color) {
        0xFF24344B.toInt() -> "Lacivert"
        0xFF356859.toInt() -> "Yeşil"
        0xFF397AC6.toInt() -> "Mavi"
        0xFFC34E54.toInt() -> "Kırmızı"
        0xFF9566B5.toInt() -> "Mor"
        0xFFDF933C.toInt() -> "Turuncu"
        0xFFF4CD4C.toInt() -> "Sarı"
        0xFFFFFFFF.toInt() -> "Beyaz"
        0xFFFFFEF9.toInt() -> "Kırık beyaz"
        0xFFF0E6D0.toInt() -> "Krem"
        0xFF28383D.toInt() -> "Koyu"
        0xFF7162C9.toInt() -> "Lavanta"
        0xFF5488AA.toInt() -> "Deniz mavisi"
        0xFF5A978C.toInt() -> "Adaçayı"
        0xFFC28071.toInt() -> "Kiremit"
        0xFFC1A16A.toInt() -> "Kum"
        0xFF68758D.toInt() -> "Arduvaz"
        else -> String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
    }
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.RadioButton, onClick = onClick).semantics {
        contentDescription = "$label renk"
        this.selected = selected
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.size((size + 8).dp).border(2.dp, ring, CircleShape).padding(4.dp).background(Color(color), CircleShape).border(1.dp, Color.Black.copy(alpha = .12f), CircleShape))
    }
}

@Composable
private fun NameDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, multiline: Boolean = false, onSave: (String) -> Unit) {
    var name by rememberSaveable(title, initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it.take(if (multiline) 10_000 else 120) }, label = { Text(label) }, singleLine = !multiline, minLines = if (multiline) 3 else 1, modifier = Modifier.fillMaxWidth())
    }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
private fun CreateNotebookDialog(onDismiss: () -> Unit, onCreate: (String, PaperTemplate, Int) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf(Covers.first()) }
    var template by rememberSaveable { mutableStateOf(PaperTemplate.DOTS) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Yeni defter", fontFamily = FontFamily.SansSerif, fontSize = 28.sp) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(title, { title = it.take(120) }, label = { Text("Defter adı") }, placeholder = { Text("Örneğin, Matematik") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Kapak rengi", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) { Covers.forEach { c -> ColorDot(c, color == c, 28) { color = c } } }
            Text("İlk sayfanın şablonu", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Column { PaperTemplate.entries.forEach { paper ->
                Row(Modifier.fillMaxWidth().clickable { template = paper }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = template == paper, onClick = { template = paper })
                    Text(paper.label, fontSize = 14.sp)
                }
            } }
        }
    }, confirmButton = { Button(onClick = { onCreate(title.trim(), template, color) }, enabled = title.isNotBlank()) { Text("Defteri oluştur") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
private fun ColorDialog(initial: Int, onDismiss: () -> Unit, onColor: (Int) -> Unit) {
    var hex by rememberSaveable(initial) { mutableStateOf(String.format(Locale.ROOT, "%06X", initial and 0xFFFFFF)) }
    val valid = hex.matches(Regex("[0-9a-fA-F]{6}"))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Özel renk") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(14.dp)).background(if (valid) Color((0xFF000000L or hex.toLong(16)).toInt()) else Color.LightGray))
            OutlinedTextField(hex, { value -> hex = value.removePrefix("#").filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6) }, label = { Text("HEX renk kodu") }, prefix = { Text("#") }, singleLine = true, isError = !valid, supportingText = { Text("Örneğin 356859 · 6 karakter") })
        }
    }, confirmButton = { TextButton(onClick = { if (valid) onColor((0xFF000000L or hex.toLong(16)).toInt()) }, enabled = valid) { Text("Seç") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}
