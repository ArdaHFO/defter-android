package dev.defter.app.ui

import dev.defter.app.localization.tr
import dev.defter.app.localization.quantity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.toArgb
import dev.defter.app.DefterViewModel
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.model.DrawingConfig
import dev.defter.app.model.DrawingTool

@Composable
fun NotebookTabs(model: DefterViewModel, modifier: Modifier = Modifier, onChoose: () -> Unit) {
    BoxWithConstraints(modifier) {
    val titleWidth = (maxWidth - 57.dp).coerceIn(48.dp, 200.dp)
    ScrollHintRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.spacedBy(8.dp)) {
        model.openTabs.forEach { id ->
            model.library.notebooks.firstOrNull { it.id == id && it.deletedAt == null }?.let { book ->
                val requester = remember(id) { BringIntoViewRequester() }
                val selected = model.notebookId == id
                LaunchedEffect(selected, titleWidth) {
                    if (selected) { withFrameNanos { }; requester.bringIntoView() }
                }
                Box(Modifier.bringIntoViewRequester(requester)) {
                    DefterNotebookTab(book.title, selected, !model.busy,
                        onSelect = { model.openNotebook(id) }, onClose = { model.closeTab(id) }, maxTitleWidth = titleWidth)
                }
            }
        }
        FilledTonalIconButton(onClick = onChoose, enabled = !model.busy, modifier = Modifier.size(DefterDesign.MinTouchTarget), shape = DefterDesign.ControlShape) { Icon(Icons.Outlined.Add, tr("Sekme aç"), Modifier.size(20.dp)) }
    }
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
        val reference = model.referencePage != null
        val horizontal = maxWidth >= 840.dp
        val share = model.uiPreferences.referenceShare
        val divider = MaterialTheme.colorScheme.outlineVariant
        // The editable View always occupies child zero, including when reference opens.
        Layout(content = {
            Box(Modifier.fillMaxSize()) { editor() }
            if (reference) Box(Modifier.fillMaxSize().drawBehind {
                if (horizontal) drawLine(divider, androidx.compose.ui.geometry.Offset(size.width - 1, 0f), androidx.compose.ui.geometry.Offset(size.width - 1, size.height))
                else drawLine(divider, androidx.compose.ui.geometry.Offset(0f, size.height - 1), androidx.compose.ui.geometry.Offset(size.width, size.height - 1))
            }) { ReferencePane(model) }
        }, modifier = Modifier.fillMaxSize()) { children, constraints ->
            val w = constraints.maxWidth; val h = constraints.maxHeight
            val refWidth = if (reference && horizontal) (w * share).toInt().coerceIn(minOf(192.dp.roundToPx(), w / 3), maxOf(w / 3, w - 256.dp.roundToPx())) else 0
            val refHeight = if (reference && !horizontal) (h * share).toInt().coerceIn(minOf(128.dp.roundToPx(), h / 3), maxOf(h / 3, h - minOf(160.dp.roundToPx(), h / 2))) else 0
            val primary = children[0].measure(Constraints.fixed(w - refWidth, h - refHeight))
            val secondary = children.getOrNull(1)?.measure(Constraints.fixed(if (horizontal) refWidth else w, if (horizontal) h else refHeight))
            layout(w, h) { primary.placeRelative(refWidth, refHeight); secondary?.placeRelative(0, 0) }
        }
    }
}

/** Thumbnail tools share the editor on tablets and use a modal sheet on phones. */
@Composable
internal fun EditorPageWorkspace(model: DefterViewModel, pagesOpen: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier, editor: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 1000.dp
        val panelWidth = model.uiPreferences.pagesWidth.dp.coerceAtMost((maxWidth - 600.dp).coerceAtLeast(0.dp))
        Row(Modifier.fillMaxSize()) {
            if (wide) AnimatedSidePanel(pagesOpen, panelWidth, Modifier.testTag("pages-side-panel")) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { PageManagerDialog(model, embedded = true, onDismiss = onClose) }
                    VerticalDivider()
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { editor() }
        }
        if (!wide && pagesOpen) PageManagerDialog(model, onDismiss = onClose)
    }
}

@Composable
private fun ReferencePane(model: DefterViewModel) {
    val doc = model.referencePage ?: return
    val workspaceColor = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
    val book = model.library.notebooks.firstOrNull { it.id == model.referenceNotebookId }
    val index = book?.pageIds?.indexOf(doc.id) ?: -1
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 240.dp
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 8.dp, top = if (compact) 0.dp else 6.dp, bottom = if (compact) 0.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!compact) {
                    Icon(Icons.Outlined.AutoStories, null, Modifier.padding(8.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(book?.title.orEmpty(), style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!compact) Text(tr("Referans · %1\$s/%2\$s", index + 1, book?.pageIds?.size ?: 0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (compact) ReferenceStepControls(model, index, book?.pageIds?.size ?: 0)
                IconButton(onClick = model::swapPanes, enabled = !model.busy, modifier = Modifier.size(DefterDesign.MinTouchTarget)) { Icon(Icons.Outlined.SwapHoriz, tr("Referansı düzenlemeye geç"), tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = model::closeReference, enabled = !model.busy, modifier = Modifier.size(DefterDesign.MinTouchTarget)) { Icon(Icons.Outlined.Close, tr("Referansı kapat")) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AndroidView(factory = { NotebookCanvas(it) }, update = { view ->
                view.document = doc; view.assets = model.referenceAssets
                view.config = DrawingConfig(tool = DrawingTool.HAND, touchDrawing = false)
                view.isReadOnly = true; view.isEnabled = !model.busy
                if ((view.background as? android.graphics.drawable.ColorDrawable)?.color != workspaceColor) view.setBackgroundColor(workspaceColor)
            }, modifier = Modifier.weight(1f).fillMaxWidth())
            if (!compact) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.stepReference(-1) }, enabled = !model.busy && index > 0, modifier = Modifier.size(DefterDesign.MinTouchTarget)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Referansta önceki sayfa")) }
                    Text(tr("Kaydır ve yakınlaştır"), Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { model.stepReference(1) }, enabled = !model.busy && index >= 0 && index < (book?.pageIds?.lastIndex ?: 0), modifier = Modifier.size(DefterDesign.MinTouchTarget)) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, tr("Referansta sonraki sayfa")) }
                }
            }
        }
    }
}

@Composable
private fun ReferenceStepControls(model: DefterViewModel, index: Int, count: Int) {
    IconButton(onClick = { model.stepReference(-1) }, enabled = !model.busy && index > 0, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Referansta önceki sayfa"), Modifier.size(18.dp)) }
    IconButton(onClick = { model.stepReference(1) }, enabled = !model.busy && index >= 0 && index < count - 1, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, tr("Referansta sonraki sayfa"), Modifier.size(18.dp)) }
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
            model.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (model.progress.isNotBlank()) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(model.progress) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !model.busy) { Text(tr("Kapat")) } })
}

@Composable
fun RecognitionResultDialog(model: DefterViewModel) {
    NoticeBlocker(true)
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
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(replace, { replace = it }, modifier = Modifier.semantics { contentDescription = tr("Seçilen el yazısını metinle değiştir") }); Text(tr("Seçilen el yazısını metinle değiştir")) }
            Text(if (replace) tr("Özgün mürekkep kaldırılır. İşlemi Geri al ile geri çevirebilirsin.") else tr("Özgün el yazısı korunur, metin ayrıca eklenir."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { model.useRecognizedText(value, replace) }, enabled = value.isNotBlank() && !model.busy) { Text(tr("Uygula")) } },
        dismissButton = { TextButton(onClick = model::dismissRecognition) { Text(tr("İptal")) } })
}
