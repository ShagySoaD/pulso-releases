package app.pulso.music

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.random.Random

/** One light at a time, shared by visible surfaces. No per-card animation loops. */
internal class PulseLights {
    val targets = linkedMapOf<Any, () -> Boolean>()
    var active by mutableStateOf<Any?>(null)
    val progress = Animatable(0f)
}

private val LocalPulseLights = staticCompositionLocalOf<PulseLights?> { null }

@Composable internal fun PulseLightHost(content: @Composable () -> Unit) {
    val lights = remember { PulseLights() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lights, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (true) {
                    delay(Random.nextLong(16_000, 33_001))
                    if (!ValueAnimator.areAnimatorsEnabled()) continue
                    val candidates = lights.targets.filterValues { it() }.keys.toList()
                    val target = candidates.randomOrNull() ?: continue
                    lights.progress.snapTo(0f)
                    lights.active = target
                    lights.progress.animateTo(1f, tween(2200, easing = LinearEasing))
                    lights.active = null
                }
            } finally {
                lights.active = null
            }
        }
    }
    CompositionLocalProvider(LocalPulseLights provides lights, content = content)
}

/** Draws a short neon trail along the inside edge without changing layout or input. */
internal fun Modifier.pulseLight(radius: Dp = 24.dp, enabled: Boolean = true): Modifier = composed {
    val lights = LocalPulseLights.current
    if (lights == null || !enabled) return@composed this
    val token = remember { Any() }
    val view = LocalView.current
    val color = MaterialTheme.colorScheme.primary
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(lights, token, view) {
        lights.targets[token] = { visible && view.hasWindowFocus() && view.isShown }
        onDispose { lights.targets.remove(token) }
    }
    this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        // Lazy containers can compose neighbours outside the viewport.
        visible = coordinates.isAttached && bounds.width > coordinates.size.width * .6f &&
            bounds.height > coordinates.size.height * .6f
    }.drawWithContent {
        drawContent()
        if (lights.active != token || !visible || !view.hasWindowFocus() || !ValueAnimator.areAnimatorsEnabled()) return@drawWithContent
        val p = lights.progress.value
        val opacity = minOf(p * 7f, (1f - p) * 7f, 1f).coerceAtLeast(0f)
        val inset = 4.dp.toPx()
        if (size.minDimension <= inset * 2) return@drawWithContent
        val path = Path().apply {
            addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset,
                CornerRadius((radius.toPx() - inset).coerceAtLeast(0f))))
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val head = p * measure.length
        // Layered strokes create a soft glow; no bitmap blur or continuous redraw at rest.
        repeat(8) { index ->
            val end = head - index * measure.length * .014f
            val start = end - measure.length * .018f
            if (end > 0f) {
                val segment = Path()
                measure.getSegment(start.coerceAtLeast(0f), end, segment)
                val alpha = opacity * (1f - index / 8f)
                drawPath(segment, color.copy(alpha = alpha * .10f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                drawPath(segment, color.copy(alpha = alpha * .24f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                drawPath(segment, color.copy(alpha = alpha * .85f), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
