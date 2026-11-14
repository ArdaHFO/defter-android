package dev.defter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.defter.app.DefterViewModel
import dev.defter.app.localization.tr
import dev.defter.app.model.*
import java.util.Locale

/** Fixed geometry: save progress, tool selection and S Pen state never move the paper. */
@Composable
internal fun FixedEditorToolbar(
    model: DefterViewModel,
    effectiveTool: DrawingTool,
    onTool: (DrawingTool) -> Unit,
    onTouchMode: () -> Unit,
    onColor: () -> Unit,
    onWidth: () -> Unit,
    onEraser: () -> Unit,
    onGeometry: () -> Unit,
    onImage: () -> Unit,
    onText: () -> Unit,
    onAudio: (() -> Unit)?
) {
    var palette by remember { mutableStateOf(false) }
    var extras by remember { mutableStateOf(false) }
    NoticeBlocker(palette || extras)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().height(64.dp).testTag("editor-toolbar").padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DefterTooltip(tr("Geri al")) { IconButton(onClick = model::undo, enabled = model.canUndo && !model.busy, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Undo, tr("Geri al"), Modifier.size(22.dp))
            }
            }
            DefterTooltip(tr("Yinele")) { IconButton(onClick = model::redo, enabled = model.canRedo && !model.busy, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Redo, tr("Yinele"), Modifier.size(22.dp))
            }
            }
            VerticalDivider(Modifier.height(24.dp))
            ScrollHintRow(Modifier.weight(1f).testTag("drawing-tools")) {
                DrawingTool.entries.forEach { tool ->
                    ToolButton(toolIcon(tool), if (tool == DrawingTool.ERASER && model.config.eraserMode == EraserMode.PARTIAL)
                        tr("Kısmi silgi") else tool.label, effectiveTool == tool) { onTool(tool) }
                    if (tool == DrawingTool.ERASER || tool == DrawingTool.ELLIPSE) VerticalDivider(Modifier.padding(horizontal = 8.dp).height(24.dp))
                }
            }
            VerticalDivider(Modifier.height(24.dp))
            Box {
                DefterTooltip(tr("Renk ve kalınlık")) { IconButton(onClick = { palette = !palette }, enabled = !model.busy,
                    modifier = Modifier.size(48.dp).semantics { selected = palette }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Palette, tr("Renk ve kalınlık"), Modifier.size(23.dp))
                        Box(Modifier.align(Alignment.BottomEnd).size(9.dp).background(Color(model.config.color), CircleShape))
                    }
                }
                }
                DropdownMenu(expanded = palette, onDismissRequest = { palette = false }, modifier = Modifier.width(272.dp).testTag("ink-options")) {
                    InkOptionsContent(model, effectiveTool, { palette = false }, onColor, onWidth, onEraser, onGeometry)
                }
            }
            val touchLabel = if (model.config.touchDrawing) tr("Parmak ve kalemle yaz. Taşıma moduna geç.")
                else tr("Parmakla taşı, kalemle yaz. Çizim moduna geç.")
            DefterTooltip(if (model.config.touchDrawing) tr("Parmak: çiz") else tr("Parmak: taşı")) { FilledTonalIconToggleButton(checked = model.config.touchDrawing, onCheckedChange = { onTouchMode() },
                enabled = !model.busy, modifier = Modifier.size(48.dp).semantics { contentDescription = touchLabel }) {
                Icon(if (model.config.touchDrawing) Icons.Outlined.TouchApp else Icons.Outlined.PanTool, null, Modifier.size(22.dp))
            }
            }
            Box {
                DefterTooltip(tr("Ekle ve araçlar")) { IconButton(onClick = { extras = true }, enabled = !model.busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.AddCircleOutline, tr("Ekle ve araçlar"), Modifier.size(23.dp))
                }
                }
                DropdownMenu(expanded = extras, onDismissRequest = { extras = false }) {
                    DropdownMenuItem(text = { Text(tr("Resim ekle")) }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { extras = false; onImage() })
                    DropdownMenuItem(text = { Text(tr("Metin ekle")) }, leadingIcon = { Icon(Icons.Outlined.TextFields, null) }, onClick = { extras = false; onText() })
                    DropdownMenuItem(text = { Text(tr("Panodan yapıştır")) }, leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) }, onClick = { extras = false; model.paste() })
                    if (onAudio != null) DropdownMenuItem(text = { Text(tr("Defter sesleri")) }, leadingIcon = { Icon(Icons.Outlined.Mic, null) }, onClick = { extras = false; onAudio() })
                }
            }
        }
    }
}
