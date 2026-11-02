package dev.defter.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.defter.app.DefterViewModel
import dev.defter.app.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun EraserSettingsDialog(config: DrawingConfig, onChange: (DrawingConfig) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Silgi ayarları") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DialogChoiceGroup("Silme biçimi") {
                DialogChoice("Çizginin tamamı", config.eraserMode == EraserMode.STROKE) { onChange(config.copy(eraserMode = EraserMode.STROKE)) }
                DialogChoice("Yalnız dokunulan bölüm", config.eraserMode == EraserMode.PARTIAL) { onChange(config.copy(eraserMode = EraserMode.PARTIAL)) }
            }
            DialogChoiceGroup("Silinecek içerik") {
                listOf(EraserFilter.ALL to "Tüm çizimler", EraserFilter.PEN to "Yalnız kalem", EraserFilter.HIGHLIGHTER to "Yalnız fosforlu", EraserFilter.SHAPES to "Yalnız şekiller").forEach { (filter, label) ->
                    DialogChoice(label, config.eraserFilter == filter) { onChange(config.copy(eraserFilter = filter)) }
                }
            }
            Text("Resim ve metinler silgiden etkilenmez. Bunları seçim aracıyla silebilirsin.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tamam") } })
}

@Composable
fun GeometrySettingsDialog(config: DrawingConfig, onChange: (DrawingConfig) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Geometri araçları") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Şekilleri hizala", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(checked = config.shapeSnap, onCheckedChange = { onChange(config.copy(shapeSnap = it)) }, modifier = Modifier.semantics { contentDescription = "Şekil hizalama" })
                }
            }
            Text("Açıkken çizgi ve oklar seçilen açılara oturur. Dikdörtgen aracı kare, elips aracı çember çizer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DialogChoiceGroup("Açı aralığı") {
                for (angle in listOf(15f, 30f, 45f, 90f)) {
                    DialogChoice("${angle.toInt()}°", config.snapAngleDegrees == angle) { onChange(config.copy(snapAngleDegrees = angle)) }
                }
            }
            Text("Kalemle serbest yazıyı değiştirmez.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tamam") } })
}

@Composable
private fun DialogChoiceGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
    }
}

@Composable
private fun DialogChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val choiceColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(180), label = "Setting selection"
    )
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(choiceColor)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun ImageCropDialog(image: ImageElement, onApply: (Float, Float, Float, Float) -> Unit, onDismiss: () -> Unit) {
    var left by rememberSaveable(image.id) { mutableFloatStateOf(image.cropLeft) }
    var top by rememberSaveable(image.id) { mutableFloatStateOf(image.cropTop) }
    var right by rememberSaveable(image.id) { mutableFloatStateOf(image.cropRight) }
    var bottom by rememberSaveable(image.id) { mutableFloatStateOf(image.cropBottom) }
    val valid = right - left >= .009999f && bottom - top >= .009999f
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Görseli kırp") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Özgün görselde kalacak bölgeyi seç. Kırpma geri alınabilir, asıl görsel korunur.")
            CropSlider("Sol", left) { left = it.coerceAtMost(right - .01f).coerceAtLeast(0f) }
            CropSlider("Üst", top) { top = it.coerceAtMost(bottom - .01f).coerceAtLeast(0f) }
            CropSlider("Sağ", right) { right = it.coerceAtLeast(left + .01f).coerceAtMost(1f) }
            CropSlider("Alt", bottom) { bottom = it.coerceAtLeast(top + .01f).coerceAtMost(1f) }
            OutlinedButton(onClick = { left = 0f; top = 0f; right = 1f; bottom = 1f }) { Text("Tam görsele dön") }
        }
    }, confirmButton = { TextButton(onClick = { onApply(left, top, right, bottom) }, enabled = valid) { Text("Uygula") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
private fun CropSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Text("$label · %${(value * 100).toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(value = value, onValueChange = onChange, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = "$label kırpma sınırı" })
}

@Composable
fun EditTextDialog(element: TextElement, onApply: (String, Float, Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(element.id) { mutableStateOf(element.text) }
    var size by rememberSaveable(element.id) { mutableFloatStateOf(element.size) }
    var hex by rememberSaveable(element.id) { mutableStateOf(String.format(Locale.ROOT, "%06X", element.color and 0xFFFFFF)) }
    val validColor = hex.matches(Regex("[0-9a-fA-F]{6}"))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Metni düzenle") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it.take(10_000) }, label = { Text("Metin") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            Text("Yazı boyutu · ${size.toInt()}")
            Slider(value = size.coerceIn(8f, 200f), onValueChange = { size = it }, valueRange = 8f..200f, modifier = Modifier.semantics { contentDescription = "Metin boyutu" })
            OutlinedTextField(value = hex, onValueChange = { value -> hex = value.removePrefix("#").filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6) }, label = { Text("Renk kodu") }, prefix = { Text("#") }, singleLine = true, isError = !validColor, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        TextButton(onClick = { onApply(text, size, (element.color and 0xFF000000.toInt()) or hex.toInt(16)) }, enabled = text.isNotBlank() && validColor) { Text("Kaydet") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

@Composable
fun PageManagerDialog(model: DefterViewModel, onDismiss: () -> Unit) {
    val book = model.notebook ?: return
    val persistedOrder = book.pageIds
    var order by remember(book.id) { mutableStateOf(persistedOrder) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var transferId by rememberSaveable { mutableStateOf<String?>(null) }
    var transferCopy by rememberSaveable { mutableStateOf(false) }
    var showDeleted by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(book.id, persistedOrder) {
        // A repository update while dragging invalidates the old ordering transaction.
        dragging = null
        order = persistedOrder
    }
    fun cancelDrag() { dragging = null; order = persistedOrder; scrollJob?.cancel() }
    AlertDialog(
        onDismissRequest = { cancelDrag(); onDismiss() },
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Sayfalar · ${book.pageIds.size}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sıralamak için sayfaya basılı tutup sürükle. Oklarla da taşıyabilirsin.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !showDeleted, onClick = { cancelDrag(); showDeleted = false }, label = { Text("Sayfalar") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = showDeleted, onClick = { cancelDrag(); showDeleted = true }, label = { Text("Silinenler (${book.trashedPageIds.size})") })
                }
                val displayed = if (showDeleted) book.trashedPageIds else order
                if (displayed.isEmpty()) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Silinen sayfa yok.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp), state = grid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 430.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(displayed, key = { _, id -> id }) { index, id ->
                        var menu by remember(id) { mutableStateOf(false) }
                        val current = model.page?.id == id
                        val emphasized = current || dragging == id
                        val cardOutline by animateColorAsState(
                            targetValue = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            animationSpec = tween(180), label = "Page selection outline"
                        )
                        val cardColor by animateColorAsState(
                            targetValue = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            animationSpec = tween(180), label = "Page selection surface"
                        )
                        val dragModifier = if (showDeleted) Modifier else Modifier.pointerInput(id, book.id, model.busy) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { local ->
                                    if (!model.busy) {
                                        grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let { item ->
                                            dragging = id
                                            dragPosition = Offset(item.offset.x.toFloat(), item.offset.y.toFloat()) + local
                                        }
                                    }
                                },
                                onDrag = { change, amount ->
                                    val active = dragging
                                    if (active != null && !model.busy) {
                                        change.consume()
                                        dragPosition += amount
                                        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                            dragPosition.x >= item.offset.x && dragPosition.x <= item.offset.x + item.size.width &&
                                                dragPosition.y >= item.offset.y && dragPosition.y <= item.offset.y + item.size.height
                                        }?.key as? String
                                        if (target != null && target != active && target in order) {
                                            val next = order.toMutableList()
                                            val oldIndex = next.indexOf(active)
                                            val newIndex = next.indexOf(target)
                                            if (oldIndex >= 0 && newIndex >= 0) { next.removeAt(oldIndex); next.add(newIndex, active); order = next }
                                        }
                                        val info = grid.layoutInfo
                                        val scroll = when {
                                            dragPosition.y < info.viewportStartOffset + 60f -> -24f
                                            dragPosition.y > info.viewportEndOffset - 60f -> 24f
                                            else -> 0f
                                        }
                                        if (scroll != 0f) { scrollJob?.cancel(); scrollJob = scope.launch { grid.scrollBy(scroll) } }
                                    }
                                },
                                onDragCancel = { cancelDrag() },
                                onDragEnd = {
                                    val changed = dragging != null && order != persistedOrder
                                    dragging = null
                                    scrollJob?.cancel()
                                    if (changed && !model.busy && order.toSet() == model.notebook?.pageIds?.toSet()) model.reorderPages(order)
                                }
                            )
                        }
                        Surface(
                            modifier = Modifier.testTag("page-card-$id").then(dragModifier),
                            border = BorderStroke(if (emphasized) 2.dp else 1.dp, cardOutline),
                            shape = RoundedCornerShape(16.dp), color = cardColor
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Box(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable(enabled = !showDeleted && !model.busy) { model.selectPage(id); onDismiss() }) {
                                    PageThumbnail(model, id, book.updatedAt)
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (showDeleted) "Silinen sayfa" else "Sayfa ${index + 1}", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                    if (showDeleted) {
                                        IconButton(onClick = { model.restorePage(id) }, enabled = !model.busy) { Icon(Icons.Outlined.Restore, "Sayfayı geri getir") }
                                    } else Box {
                                        IconButton(onClick = { menu = true }, enabled = !model.busy) { Icon(Icons.Outlined.MoreVert, "Sayfa ${index + 1} işlemleri") }
                                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                            DropdownMenuItem(text = { Text("Başka deftere kopyala") }, onClick = { menu = false; transferId = id; transferCopy = true })
                                            DropdownMenuItem(text = { Text("Başka deftere taşı") }, enabled = order.size > 1, onClick = { menu = false; transferId = id; transferCopy = false })
                                            DropdownMenuItem(text = { Text("Sayfayı sil") }, enabled = order.size > 1, onClick = { menu = false; deleteId = id })
                                        }
                                    }
                                }
                                if (!showDeleted) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = .65f)), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    IconButton(onClick = { model.reorderPage(id, -1) }, enabled = index > 0 && !model.busy && dragging == null) { Icon(Icons.Outlined.ArrowUpward, "Sayfa ${index + 1} önceye taşı") }
                                    IconButton(onClick = { model.reorderPage(id, 1) }, enabled = index < order.lastIndex && !model.busy && dragging == null) { Icon(Icons.Outlined.ArrowDownward, "Sayfa ${index + 1} sonraya taşı") }
                                }
                            }
                        }
                    }
                }
                if (book.pageIds.size == 1) Text("Defterde en az bir sayfa kalır.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { model.addPage(model.page?.template ?: PaperTemplate.DOTS); onDismiss() }, enabled = !model.busy) { Text("Sayfa ekle") } },
        dismissButton = { TextButton(onClick = { cancelDrag(); onDismiss() }) { Text("Kapat") } }
    )
    deleteId?.let { pageId ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Sayfayı sil?") }, text = { Text("Sayfa Silinenler bölümüne taşınır. Daha sonra geri getirebilirsin.") },
            confirmButton = { TextButton(onClick = { model.deletePage(pageId); deleteId = null }, enabled = !model.busy && book.pageIds.size > 1) { Text("Sil") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Vazgeç") } })
    }
    transferId?.let { pageId ->
        val destinations = model.library.notebooks.filter { it.id != book.id && it.deletedAt == null }
        AlertDialog(onDismissRequest = { transferId = null }, title = { Text(if (transferCopy) "Sayfayı kopyala" else "Sayfayı taşı") }, text = {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (destinations.isEmpty()) Text("Önce başka bir defter oluştur.")
                destinations.forEach { target ->
                    OutlinedButton(onClick = { model.transferPage(pageId, target.id, transferCopy); transferId = null }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(target.title) }
                }
            }
        }, confirmButton = { TextButton(onClick = { transferId = null }) { Text("Vazgeç") } })
    }
}

@Composable
private fun PageThumbnail(model: DefterViewModel, id: String, updatedAt: Long) {
    val result by produceState<Pair<Bitmap?, Boolean>>(null to false, id, updatedAt) {
        value = null to false
        try { value = model.pageThumbnail(id) to false }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { value = null to true }
    }
    // Bitmaps leave composition with their painter and are reclaimed by Android together.
    // Calling recycle() while a previous frame still references a painter is unsafe.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bitmap = result.first
        if (bitmap != null && !bitmap.isRecycled) Image(bitmap.asImageBitmap(), contentDescription = "Sayfa önizlemesi", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else if (result.second) Text("Önizleme açılamadı", style = MaterialTheme.typography.bodySmall)
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}
