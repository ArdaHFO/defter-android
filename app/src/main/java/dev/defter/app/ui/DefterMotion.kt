package dev.defter.app.ui

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

internal val LocalDefterAnimationsEnabled = staticCompositionLocalOf { true }

/** Compose applies nonzero system duration scales itself. Do not multiply them a second time. */
@Composable
fun <T> defterTween(durationMillis: Int = 160): TweenSpec<T> =
    tween(durationMillis = if (LocalDefterAnimationsEnabled.current) durationMillis else 0)

@Composable
internal fun rememberDefterAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.applicationContext.contentResolver
    var enabled by remember(resolver) { mutableStateOf(readAnimationsEnabled(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = readAnimationsEnabled(resolver) }
        }
        val registered = runCatching {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }.isSuccess
        // Close the small gap between the initial read and observer registration.
        enabled = readAnimationsEnabled(resolver)
        onDispose { if (registered) resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private fun readAnimationsEnabled(resolver: ContentResolver): Boolean = runCatching {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}.getOrDefault(true)
