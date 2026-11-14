package dev.defter.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import dev.defter.app.audio.AudioViewModel
import dev.defter.app.localization.tr

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun FocusSystemBars(enabled: Boolean) {
    val activity = LocalContext.current.activity() ?: return
    DisposableEffect(activity, enabled) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val oldBehavior = controller.systemBarsBehavior
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (enabled) { controller.show(WindowInsetsCompat.Type.systemBars()); controller.systemBarsBehavior = oldBehavior }
        }
    }
}

@Composable
internal fun FocusRecordingButton(audio: AudioViewModel) {
    val state by audio.recorder.state.collectAsState()
    if (state.isBusy) IconButton(onClick = audio::showActiveRecording) {
        Icon(Icons.Outlined.Mic, tr("Ses kaydı ve transkript"), tint = MaterialTheme.colorScheme.error)
    }
}
