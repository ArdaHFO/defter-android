package dev.defter.app.ui

import androidx.compose.runtime.*

/** Dialogs defer supplemental messages without relying on window-focus heuristics. */
internal class NoticeGate {
    var count by mutableIntStateOf(0); private set
    fun hold() { count++ }
    fun release() { check(count > 0); count-- }
}

internal val LocalNoticeGate = staticCompositionLocalOf { NoticeGate() }

@Composable
internal fun NoticeBlocker(active: Boolean) {
    val gate = LocalNoticeGate.current
    DisposableEffect(gate, active) {
        if (active) gate.hold()
        onDispose { if (active) gate.release() }
    }
}
