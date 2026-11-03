package dev.defter.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.defter.app.localization.tr

/** Selection and closing have separate, nonoverlapping touch targets and accessible names. */
@Composable
internal fun DefterNotebookTab(title: String, isSelected: Boolean, enabled: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val fill by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        defterTween(), label = "Notebook tab selection"
    )
    Surface(
        color = fill, shape = DefterDesign.ControlShape,
        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = .32f) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onSelect, enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface),
                contentPadding = PaddingValues(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                modifier = Modifier.widthIn(min = DefterDesign.MinTouchTarget, max = 200.dp).heightIn(min = DefterDesign.MinTouchTarget)
                    .semantics { contentDescription = tr("Sekme %1\$s", title); this.selected = isSelected }
            ) {
                Icon(if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.AutoStories, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.clearAndSetSemantics {})
            }
            VerticalDivider(Modifier.height(18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            IconButton(onClick = onClose, enabled = enabled, modifier = Modifier.size(DefterDesign.MinTouchTarget)) {
                Icon(Icons.Outlined.Close, tr("%1\$s sekmesini kapat", title), Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
