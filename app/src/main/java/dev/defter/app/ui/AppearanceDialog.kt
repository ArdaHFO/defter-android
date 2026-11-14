package dev.defter.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.defter.app.DefterViewModel
import dev.defter.app.localization.tr
import dev.defter.app.model.*

@Composable
internal fun AppearanceDialog(model: DefterViewModel, onDismiss: () -> Unit) {
    val current = model.uiPreferences
    var theme by rememberSaveable { mutableStateOf(current.theme) }
    var layout by rememberSaveable { mutableStateOf(current.libraryLayout) }
    var pages by rememberSaveable { mutableFloatStateOf(current.pagesWidth) }
    var audio by rememberSaveable { mutableFloatStateOf(current.audioWidth) }
    var reference by rememberSaveable { mutableFloatStateOf(current.referenceShare) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Görünüm")) }, text = {
        Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .58f).dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Tema"), style = MaterialTheme.typography.titleSmall)
            for ((mode, label) in listOf(ThemeMode.SYSTEM to tr("Sistem teması"), ThemeMode.LIGHT to tr("Açık tema"), ThemeMode.DARK to tr("Koyu tema"))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(theme == mode, role = Role.RadioButton, onClick = { theme = mode }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(theme == mode, null); Text(label, Modifier.padding(start = 8.dp))
                }
            }
            Text(tr("Arayüz teması kâğıt ve mürekkep renklerini değiştirmez."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text(tr("Kütüphane düzeni"), style = MaterialTheme.typography.titleSmall)
            for ((value, label) in listOf(LibraryLayout.GRID to tr("Izgara"), LibraryLayout.LIST to tr("Liste"))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(layout == value, role = Role.RadioButton, onClick = { layout = value }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(layout == value, null); Text(label, Modifier.padding(start = 8.dp))
                }
            }
            HorizontalDivider()
            Text(tr("Panel boyutları"), style = MaterialTheme.typography.titleSmall)
            PanelWidthSlider(tr("Sayfa paneli"), pages, 240f..420f) { pages = it }
            PanelWidthSlider(tr("Ses paneli"), audio, 320f..560f) { audio = it }
            Text(tr("Referans alanı · %1\$s%%", (reference * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
            Slider(reference, { reference = it }, valueRange = .3f.. .7f, modifier = Modifier.semantics { contentDescription = tr("Referans alanı") })
            Text(tr("Paneller kullanılabilir alana uyarlanır. Boyutlar Uygula ile değişir."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { theme = ThemeMode.SYSTEM; layout = LibraryLayout.GRID; pages = 304f; audio = 380f; reference = .5f }) { Text(tr("Görünümü sıfırla")) }
        }
    }, confirmButton = {
        TextButton(onClick = { model.updateUiPreferences(UiPreferences(theme, layout, pages, audio, reference)); onDismiss() }) { Text(tr("Uygula")) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) } })
}

@Composable
private fun PanelWidthSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value, onChange, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("Dar"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tr("Geniş"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
