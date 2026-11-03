package dev.defter.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.defter.app.localization.AppLanguage
import dev.defter.app.localization.tr

@Composable
fun LanguageSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppLanguage.tag(context)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Uygulama dili")) }, text = {
        Column {
            listOf("en" to "English", "tr" to "Türkçe").forEach { (tag, name) ->
                Row(Modifier.fillMaxWidth().clickable { selected = tag }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == tag, onClick = { selected = tag })
                    Text(name)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        val changed = AppLanguage.tag(context) != selected
        AppLanguage.set(context, selected)
        onDismiss()
        if (changed) context.findActivity()?.recreate()
    }) { Text(tr("Uygula")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Vazgeç")) } })
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
