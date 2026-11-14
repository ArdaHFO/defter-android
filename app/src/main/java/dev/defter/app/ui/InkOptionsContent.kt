package dev.defter.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.defter.app.DefterViewModel
import dev.defter.app.localization.tr
import dev.defter.app.model.*
import java.util.Locale

@Composable
internal fun InkOptionsContent(model: DefterViewModel, tool: DrawingTool, close: () -> Unit, onColor: () -> Unit,
    onWidth: () -> Unit, onEraser: () -> Unit, onGeometry: () -> Unit) {
    val erasing = tool == DrawingTool.ERASER
    val label = if (erasing && model.config.eraserMode == EraserMode.PARTIAL) tr("Kısmi silgi") else tool.label
    Text(label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
    if (erasing) {
        Text(tr("Silme biçimi"), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((mode, text) in listOf(EraserMode.STROKE to tr("Çizginin tamamı"), EraserMode.PARTIAL to tr("Yalnız dokunulan bölüm"))) {
            ChoiceMenuItem(text, model.config.eraserMode == mode) { model.config = model.config.copy(eraserMode = mode) }
        }
        Text(tr("Silinecek içerik"), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((filter, text) in listOf(EraserFilter.ALL to tr("Tüm çizimler"), EraserFilter.PEN to tr("Yalnız kalem"), EraserFilter.HIGHLIGHTER to tr("Yalnız fosforlu"), EraserFilter.SHAPES to tr("Yalnız şekiller"))) {
            ChoiceMenuItem(text, model.config.eraserFilter == filter) { model.config = model.config.copy(eraserFilter = filter) }
        }
    } else {
        val color = Color(model.config.color)
        val width = model.config.width.coerceIn(1f, 28f)
        Canvas(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(model.page?.backgroundColor ?: 0xFFFFFEF9.toInt())).semantics { contentDescription = tr("Çizgi önizlemesi") }) {
            drawLine(if (tool == DrawingTool.HIGHLIGHTER) color.copy(alpha = .38f) else color,
                Offset(16.dp.toPx(), size.height / 2), Offset(size.width - 16.dp.toPx(), size.height / 2), width.dp.toPx(), StrokeCap.Round)
        }
        InkColors.chunked(4).forEach { colors ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                colors.forEach { value -> ColorDot(value, model.config.color == value, 24) { model.config = model.config.copy(color = value) } }
            }
        }
        DropdownMenuItem(text = { Text(tr("Özel renk")) }, leadingIcon = { Icon(Icons.Outlined.Colorize, null) }, onClick = { close(); onColor() })
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(if (erasing) "Silgi boyutu" else "Kalınlık"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(String.format(Locale.ROOT, "%.1f", model.config.width), style = MaterialTheme.typography.labelMedium)
        }
        Slider(model.config.width.coerceIn(.8f, 40f), { model.config = model.config.copy(width = it) }, valueRange = .8f..40f,
            modifier = Modifier.semantics { contentDescription = tr(if (erasing) "Silgi boyutu" else "Çizgi kalınlığı") })
    }
    DropdownMenuItem(text = { Text(tr("Kalınlık ayarı")) }, leadingIcon = { Icon(Icons.Outlined.LineWeight, null) }, onClick = { close(); onWidth() })
    HorizontalDivider()
    DropdownMenuItem(text = { Text(tr("Silgi seçenekleri")) }, leadingIcon = { Icon(Icons.Outlined.AutoFixNormal, null) }, onClick = { close(); onEraser() })
    DropdownMenuItem(text = { Text(tr("Matematik araçları")) }, leadingIcon = { Icon(Icons.Outlined.SquareFoot, null) }, onClick = { close(); onGeometry() })
}

@Composable
private fun ChoiceMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, modifier = Modifier.semantics { this.selected = selected },
        trailingIcon = { Icon(Icons.Outlined.Check, null, tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent) })
}
