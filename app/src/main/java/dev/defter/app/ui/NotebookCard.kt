package dev.defter.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.defter.app.DefterViewModel
import dev.defter.app.localization.AppLanguage
import dev.defter.app.localization.quantity
import dev.defter.app.localization.tr
import dev.defter.app.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun NotebookCard(model: DefterViewModel, book: Notebook, trash: Boolean, onOpen: () -> Unit, onRename: () -> Unit,
    onDelete: () -> Unit, onRestore: () -> Unit, onMove: () -> Unit, selectedForDeletion: Boolean? = null, onSelectionChange: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    NoticeBlocker(menu)
    val compact = model.uiPreferences.libraryLayout == LibraryLayout.LIST
    val language = AppLanguage.selectedTag
    val updated = remember(book.updatedAt, language) { SimpleDateFormat("d MMM", Locale.forLanguageTag(language)).format(Date(book.updatedAt)) }
    val preview by produceState<Bitmap?>(null, book.id, book.pageIds.firstOrNull(), book.updatedAt) {
        delay(120)
        try { value = book.pageIds.firstOrNull()?.let { model.pageThumbnail(it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { value = null }
    }
    val outline by animateColorAsState(if (selectedForDeletion == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, defterTween(), label = "Notebook card selection")
    Surface(Modifier.fillMaxWidth().testTag("notebook-card-${book.id}"), shape = DefterDesign.CardShape,
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, outline), shadowElevation = 1.dp) {
        Box {
            val body = Modifier.fillMaxWidth().clickable(enabled = !model.busy && (!trash || selectedForDeletion != null),
                onClick = if (trash) onSelectionChange else onOpen)
            val thumbnail: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                    val bitmap = preview?.takeUnless { it.isRecycled }
                    if (bitmap != null) Image(bitmap.asImageBitmap(), tr("Defter önizlemesi"), Modifier.fillMaxSize().padding(8.dp).testTag("notebook-preview-${book.id}"), contentScale = ContentScale.Fit)
                    else Icon(Icons.Outlined.AutoStories, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight().background(Color(book.coverColor)))
                }
            }
            val metadata: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(quantity("%1\$s sayfa · %2\$s", book.pageIds.size, updated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (compact) Row(body.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                thumbnail(Modifier.width(76.dp).height(100.dp))
                metadata(Modifier.weight(1f).padding(end = 40.dp))
            } else Column(body.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                thumbnail(Modifier.fillMaxWidth().aspectRatio(1.1f))
                metadata(Modifier.fillMaxWidth())
            }
            if (selectedForDeletion != null) Checkbox(selectedForDeletion, { onSelectionChange() },
                Modifier.align(Alignment.TopStart).semantics { contentDescription = tr("Silmek için seç: %1\$s", book.title) })
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }, enabled = !model.busy,
                    modifier = Modifier.padding(4.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))) {
                    Icon(Icons.Outlined.MoreHoriz, tr("%1\$s seçenekleri", book.title), Modifier.size(21.dp))
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (trash) DropdownMenuItem(text = { Text(tr("Geri getir")) }, onClick = { menu = false; onRestore() })
                    else {
                        DropdownMenuItem(text = { Text(tr("Yeniden adlandır")) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(tr("Klasöre taşı")) }, onClick = { menu = false; onMove() })
                        DropdownMenuItem(text = { Text(tr("Çöp kutusuna taşı")) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}
