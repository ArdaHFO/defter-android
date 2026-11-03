package dev.defter.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.defter.app.localization.tr

/** Keep playback accessible when narrow panes or large text need a second control row. */
@Composable
internal fun DefterPlaybackHeader(
    time: String, status: String, speed: Float, playing: Boolean, enabled: Boolean,
    onPlayPause: () -> Unit, onSpeed: () -> Unit
) {
    val fill by animateColorAsState(
        if (playing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
        defterTween(), label = "playbackControlColor"
    )
    val content by animateColorAsState(
        if (playing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary,
        defterTween(), label = "playbackControlContent"
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(60.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = fill, contentColor = content)
                ) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        tr("Sesi oynat veya duraklat"), Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!compact) PlaybackSpeed(speed, onSpeed)
            }
            if (compact) Box(Modifier.fillMaxWidth().padding(start = 74.dp, top = 4.dp)) { PlaybackSpeed(speed, onSpeed) }
        }
    }
}

@Composable
private fun PlaybackSpeed(speed: Float, onSpeed: () -> Unit) {
    TextButton(onClick = onSpeed, modifier = Modifier.heightIn(min = DefterDesign.MinTouchTarget).widthIn(min = DefterDesign.MinTouchTarget), shape = DefterDesign.ControlShape) {
        Text("${speed}×", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}
