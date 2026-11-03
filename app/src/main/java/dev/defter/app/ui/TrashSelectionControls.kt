package dev.defter.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.defter.app.localization.tr
import dev.defter.app.localization.quantity

@Composable
fun TrashSelectionBar(total: Int, selected: Int, busy: Boolean, onSelectAll: () -> Unit, onClear: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("%1\$d seçildi", selected), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = if (selected == total && total > 0) onClear else onSelectAll, enabled = !busy && total > 0,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (selected == total && total > 0) tr("Seçimi temizle") else tr("Tümünü seç"))
        }
        FilledTonalButton(onClick = onDelete, enabled = !busy && selected > 0,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
            Icon(Icons.Outlined.DeleteForever, null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("Seçilenleri sil"))
        }
    }
}

@Composable
fun PermanentDeletionDialog(count: Int, audio: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(tr("Kalıcı olarak silinsin mi?")) },
        text = { Text(if (audio) quantity("%1\$d ses kaydı ve transkripti kalıcı olarak silinecek. Bu işlem geri alınamaz.", count)
            else quantity("%1\$d defter ve sayfaları kalıcı olarak silinecek. Bağlı ses kayıtları korunur, silinen sayfalara bağlantıları kaldırılır. Bu işlem geri alınamaz.", count)) },
        confirmButton = { TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(tr("Kalıcı sil")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) } })
}
