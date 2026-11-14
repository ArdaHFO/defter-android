package dev.defter.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Hints appear only where hidden content actually exists and never intercept input. */
@Composable
internal fun ScrollHintRow(modifier: Modifier = Modifier, arrangement: Arrangement.Horizontal = Arrangement.Start, content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.drawWithContent {
        drawContent()
        val band = minOf(14.dp.toPx(), size.width / 3)
        fun edge(start: Boolean) {
            val x = if (start) 0f else size.width - band
            drawRect(Brush.horizontalGradient(if (start) listOf(surface, Color.Transparent) else listOf(Color.Transparent, surface), x, x + band), Offset(x, 0f), androidx.compose.ui.geometry.Size(band, size.height))
            val middle = size.height / 2
            val near = if (start) 3.dp.toPx() else size.width - 3.dp.toPx()
            val inner = near + if (start) 3.dp.toPx() else -3.dp.toPx()
            drawPath(Path().apply { moveTo(inner, middle - 4.dp.toPx()); lineTo(near, middle); lineTo(inner, middle + 4.dp.toPx()) }, ink, style = Stroke(1.5.dp.toPx()))
        }
        if (scroll.canScrollBackward) edge(true)
        if (scroll.canScrollForward) edge(false)
    }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = arrangement, content = content)
    }
}

/** Native Material tooltips plus stylus hover. Accessible labels remain on the controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DefterTooltip(label: String, content: @Composable () -> Unit) {
    val state = rememberTooltipState()
    val scope = rememberCoroutineScope()
    var hover by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(label) { onDispose { hover?.cancel(); state.dismiss() } }
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = state, focusable = false,
        modifier = Modifier.pointerInput(label) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Enter && event.changes.any { it.type == PointerType.Stylus || it.type == PointerType.Eraser }) {
                        hover?.cancel(); hover = scope.launch { delay(450); state.show() }
                    } else if (event.type == PointerEventType.Exit || event.type == PointerEventType.Press) {
                        hover?.cancel(); state.dismiss()
                    }
                }
            }
        }) { content() }
}

/** Reserve the final width once. Animate only the panel layer, never the ink surface. */
@Composable
internal fun AnimatedSidePanel(visible: Boolean, width: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    val density = LocalDensity.current
    val distance = with(density) { 8.dp.roundToPx() }
    if (transition.currentState || transition.targetState) Box(modifier.width(width).fillMaxHeight()) {
        AnimatedVisibility(visibleState = transition,
            enter = fadeIn(defterTween(130)) + slideInHorizontally(defterTween(130)) { distance },
            exit = fadeOut(defterTween(100)) + slideOutHorizontally(defterTween(100)) { distance }) { content() }
    }
}
