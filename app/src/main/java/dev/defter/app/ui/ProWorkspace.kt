package dev.defter.app.ui

import dev.defter.app.localization.tr
import dev.defter.app.localization.quantity

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.defter.app.DefterViewModel
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.model.DrawingConfig
import dev.defter.app.model.DrawingTool

@Composable
fun NotebookTabs(model: DefterViewModel, onChoose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        model.openTabs.forEach { id ->
            model.library.notebooks.firstOrNull { it.id == id && it.deletedAt == null }?.let { book ->
                val isSelected = model.notebookId == id
                val tabColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    animationSpec = tween(180), label = "Notebook tab selection"
                )
                val tabOutline by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = .22f) else MaterialTheme.colorScheme.outlineVariant,
                    animationSpec = tween(180), label = "Notebook tab outline"
                )
                Surface(color = tabColor, border = BorderStroke(1.dp, tabOutline),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { model.openNotebook(id) }, enabled = !model.busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.widthIn(max = 200.dp).semantics { contentDescription = tr("Sekme %1\$s", book.title); selected = isSelected }) {
                            Icon(Icons.Outlined.AutoStories, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier.clearAndSetSemantics {})
                        }
                        IconButton(onClick = { model.closeTab(id) }, enabled = !model.busy) { Icon(Icons.Outlined.Close, tr("%1\$s sekmesini kapat", book.title), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        FilledTonalIconButton(onClick = onChoose, enabled = !model.busy, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Outlined.Add, tr("Sekme aç"), Modifier.size(20.dp)) }
    }
}

@Composable
fun NotebookPickerDialog(model: DefterViewModel, title: String, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(model.library.notebooks.filter { it.deletedAt == null }, key = { it.id }) { book ->
                OutlinedButton(onClick = { onChoose(book.id) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    Icon(Icons.Outlined.AutoStories, null, Modifier.size(21.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(book.title, maxLines = 2, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(quantity("%1\$s sayfa", book.pageIds.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Kapat")) } })
}

@Composable
fun WorkspacePanes(model: DefterViewModel, modifier: Modifier = Modifier, editor: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        if (model.referencePage == null) editor()
        else if (maxWidth < 600.dp) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { ReferencePane(model) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth()) { editor() }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { ReferencePane(model) }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight()) { editor() }
            }
        }
    }
}

@Composable
private fun ReferencePane(model: DefterViewModel) {
    val doc = model.referencePage ?: return
    val book = model.library.notebooks.firstOrNull { it.id == model.referenceNotebookId }
    val index = book?.pageIds?.indexOf(doc.id) ?: -1
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Outlined.AutoStories, null, Modifier.padding(9.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(book?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("Referans · %1\$s/%2\$s", index + 1, book?.pageIds?.size ?: 0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = model::swapPanes, enabled = !model.busy) { Icon(Icons.Outlined.SwapHoriz, tr("Referansı düzenlemeye geç")) }
            IconButton(onClick = model::closeReference, enabled = !model.busy) { Icon(Icons.Outlined.Close, tr("Referansı kapat")) }
        }
        AndroidView(factory = { NotebookCanvas(it) }, update = { view ->
            view.document = doc
            view.assets = model.referenceAssets
            view.config = DrawingConfig(tool = DrawingTool.HAND, touchDrawing = false)
            view.isReadOnly = true
            view.isEnabled = !model.busy
        }, modifier = Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { model.stepReference(-1) }, enabled = !model.busy && index > 0) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Referansta önceki sayfa")) }
            Icon(Icons.Outlined.PanTool, null, Modifier.align(Alignment.CenterVertically).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tr("Kaydır ve yakınlaştır"), Modifier.align(Alignment.CenterVertically).padding(horizontal = 7.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { model.stepReference(1) }, enabled = !model.busy && index >= 0 && index < (book?.pageIds?.lastIndex ?: 0)) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, tr("Referansta sonraki sayfa")) }
        }
    }
}

@Composable
fun PageSearchResults(model: DefterViewModel) {
    if (model.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (model.searchResults.isNotEmpty()) {
        Text(quantity("SAYFA İÇERİĞİ · %1\$s sonuç", model.searchResults.size), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(model.searchResults, key = { it.pageId }) { result ->
                OutlinedButton(onClick = { model.openPage(result.notebookId, result.pageId) }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentPadding = PaddingValues(14.dp)) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr("%1\$s · %2\$s. sayfa", result.title, result.pageNumber), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(result.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun HandwritingSettingsDialog(model: DefterViewModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!model.busy) onDismiss() }, title = { Text(tr("El yazısı ve arama")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Dil modeli bir kez internetten indirilir. Yazı tanıma cihaz üzerinde çalışır. Fotoğraf ve PDF içindeki basılı metin bu el yazısı modelinin kapsamı dışındadır."))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = model.recognitionLanguage == "tr-TR", onClick = { model.recognitionLanguage = "tr-TR" }, enabled = !model.busy, label = { Text(tr("Türkçe")) })
                FilterChip(selected = model.recognitionLanguage == "en-US", onClick = { model.recognitionLanguage = "en-US" }, enabled = !model.busy, label = { Text(tr("İngilizce")) })
            }
            OutlinedButton(onClick = model::downloadHandwritingModel, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Dil modelini indir")) }
            Button(onClick = model::indexHandwriting, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(tr("El yazılarını aramaya hazırla")) }
            Text(tr("Yeni yazı eklediğinde dizini yeniden güncelleyebilirsin. Dönüştürmek için editör menüsündeki El yazısını metne çevir seçeneğini kullan."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.progress.isNotBlank()) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(model.progress) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !model.busy) { Text(tr("Kapat")) } })
}

@Composable
fun RecognitionResultDialog(model: DefterViewModel) {
    val candidates = model.recognitionCandidates
    var value by rememberSaveable(candidates) { mutableStateOf(candidates.firstOrNull().orEmpty()) }
    var replace by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = model::dismissRecognition, title = { Text(tr("El yazısı sonucu")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Sonucu kontrol edip düzeltebilirsin."))
            OutlinedTextField(value, { value = it.take(10_000) }, label = { Text(tr("Tanınan metin")) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
            if (candidates.size > 1) Row(Modifier.horizontalScroll(rememberScrollState())) {
                candidates.take(5).forEachIndexed { index, text -> SuggestionChip(onClick = { value = text }, label = { Text(tr("Öneri %1\$s", index + 1)) }, modifier = Modifier.padding(end = 6.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(replace, { replace = it }); Text(tr("Seçilen el yazısını metinle değiştir")) }
            Text(if (replace) tr("Özgün mürekkep kaldırılır. İşlemi Geri al ile geri çevirebilirsin.") else tr("Özgün el yazısı korunur, metin ayrıca eklenir."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { model.useRecognizedText(value, replace) }, enabled = value.isNotBlank() && !model.busy) { Text(tr("Uygula")) } },
        dismissButton = { TextButton(onClick = model::dismissRecognition) { Text(tr("İptal")) } })
}
