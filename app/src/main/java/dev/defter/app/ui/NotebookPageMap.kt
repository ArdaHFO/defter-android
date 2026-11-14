package dev.defter.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.defter.app.DefterViewModel
import dev.defter.app.localization.tr
import dev.defter.app.model.NotebookViewport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Only visible map items load thumbnails. Viewport changes redraw this overlay alone. */
@Composable
internal fun NotebookPageMap(model: DefterViewModel, viewport: State<NotebookViewport?>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val library = model.library
    val bookId = model.notebookId
    val ids = remember(library, bookId) { library.notebooks.firstOrNull { it.id == bookId }?.pageIds.orEmpty() }
    val activeId = model.page?.id
    val activeIndex = remember(ids, activeId) { ids.indexOf(activeId).coerceAtLeast(0) }
    if (ids.isEmpty()) return
    val list = rememberLazyListState(initialFirstVisibleItemIndex = activeIndex.coerceIn(ids.indices))
    val position = viewport.value
    val center = position?.centerId ?: model.page?.id
    LaunchedEffect(center, ids) {
        val index = ids.indexOf(center)
        if (index >= 0 && !list.isScrollInProgress && list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
    }
    BoxWithConstraints(modifier.width(104.dp).heightIn(max = 336.dp).testTag("page-map")) {
    val seekAvailable = maxHeight >= 240.dp
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${activeIndex + 1}/${ids.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, tr("Haritayı kapat"), Modifier.size(18.dp)) }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                    val active = id == model.page?.id
                    val slice = position?.pages?.firstOrNull { it.id == id }
                    val revision = model.loadedPages[id]?.revision ?: 0L
                    val thumbnail by produceState<android.graphics.Bitmap?>(null, id, revision) {
                        // Keep the previous thumbnail during pen bursts and cancel superseded work.
                        delay(200)
                        try { value = model.pageThumbnail(id) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { value = null }
                    }
                    Surface(onClick = { model.selectPage(id) }, enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth().testTag("map-page-$id").semantics {
                            contentDescription = tr("Sayfa %1\$s", index + 1); selected = active
                        }, shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(2.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(96.dp).padding(4.dp), contentAlignment = Alignment.Center) {
                                val bitmap = thumbnail?.takeUnless { it.isRecycled }
                                if (bitmap != null) {
                                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                    if (slice != null) {
                                        val paper = model.loadedPages[id]?.backgroundColor ?: 0xFFFFFFFF.toInt()
                                        val accent = if (android.graphics.Color.luminance(paper) < .3f) androidx.compose.ui.graphics.Color(0xFFA9CAFF) else DefterDesign.Accent
                                        Canvas(Modifier.fillMaxSize()) {
                                            val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                                            val w = bitmap.width * scale; val h = bitmap.height * scale
                                            val left = (size.width - w) / 2 + (position?.left ?: 0f) * w
                                            val top = (size.height - h) / 2 + slice.from * h
                                            val box = Size(((position?.right ?: 1f) - (position?.left ?: 0f)) * w, ((slice.to - slice.from) * h).coerceAtLeast(2.dp.toPx()))
                                            drawRect(accent.copy(alpha = .15f), Offset(left, top), box)
                                            drawRect(accent, Offset(left, top), box, style = Stroke(2.dp.toPx()))
                                        }
                                    }
                                } else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
                            }
                            Text("${index + 1}", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (ids.size > 3 && seekAvailable) {
                var seek by remember(ids) { mutableFloatStateOf(activeIndex.toFloat()) }
                LaunchedEffect(activeIndex) { seek = activeIndex.toFloat() }
                Slider(value = seek.coerceIn(0f, ids.lastIndex.toFloat()), onValueChange = { seek = it },
                    onValueChangeFinished = { ids.getOrNull(seek.toInt())?.let(model::selectPage) },
                    valueRange = 0f..ids.lastIndex.toFloat(), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).semantics { contentDescription = tr("Haritada sayfaya git") })
            }
        }
    }
}
}
