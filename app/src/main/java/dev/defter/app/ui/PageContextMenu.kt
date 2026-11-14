package dev.defter.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.defter.app.localization.tr

internal class PointMenuPosition(private val point: IntOffset, private val margin: Int,
    private val safeLeft: Int = 0, private val safeTop: Int = 0, private val safeRight: Int = 0, private val safeBottom: Int = 0) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val preferredX = if (layoutDirection == LayoutDirection.Rtl) point.x - popupContentSize.width else point.x
        val x = preferredX.coerceIn(safeLeft + margin, maxOf(safeLeft + margin, windowSize.width - popupContentSize.width - safeRight - margin))
        val y = (if (point.y + popupContentSize.height + margin <= windowSize.height - safeBottom) point.y + margin else point.y - popupContentSize.height - margin)
            .coerceIn(safeTop + margin, maxOf(safeTop + margin, windowSize.height - popupContentSize.height - safeBottom - margin))
        return IntOffset(x, y)
    }
}

@Composable
internal fun PageContextMenu(point: IntOffset, hasSelection: Boolean, hasContent: Boolean, onCopy: () -> Unit, onCut: () -> Unit,
    onPaste: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val margin = with(density) { 8.dp.roundToPx() }
    val safe = WindowInsets.safeDrawing
    val left = safe.getLeft(density, direction); val right = safe.getRight(density, direction)
    val top = safe.getTop(density); val bottom = safe.getBottom(density)
    val window = LocalWindowInfo.current.containerSize
    val maxWidth = with(density) { (window.width - left - right - margin * 2).toDp().coerceIn(48.dp, 288.dp) }
    val maxHeight = with(density) { (window.height - top - bottom - margin * 2).toDp().coerceAtLeast(48.dp) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, defterTween(100), label = "Context menu entry")
    Popup(popupPositionProvider = PointMenuPosition(point, margin, left, top, right, bottom), onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        Surface(modifier = Modifier.testTag("page-context-menu").graphicsLayer { this.alpha = alpha }, shape = DefterDesign.CardShape, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Sayfa işlemleri"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, tr("Kapat")) }
                }
                DropdownMenuItem(text = { Text(tr("Kopyala")) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, enabled = hasSelection, onClick = onCopy)
                DropdownMenuItem(text = { Text(tr("Kes")) }, leadingIcon = { Icon(Icons.Outlined.ContentCut, null) }, enabled = hasSelection, onClick = onCut)
                DropdownMenuItem(text = { Text(tr("Yapıştır")) }, leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) }, onClick = onPaste)
                DropdownMenuItem(text = { Text(tr("Tümünü seç")) }, leadingIcon = { Icon(Icons.Outlined.SelectAll, null) }, enabled = hasContent, onClick = onSelectAll)
                HorizontalDivider()
                DropdownMenuItem(text = { Text(tr("Seçimi sil"), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, enabled = hasSelection, onClick = onDelete)
            }
        }
    }
}
