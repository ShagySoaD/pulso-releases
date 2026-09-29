package app.pulso.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun HomeReveal(identity: String, content: @Composable () -> Unit) {
    var visible by rememberSaveable(identity) { mutableStateOf(false) }
    LaunchedEffect(identity) { visible = true }
    AnimatedVisibility(visible, enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 10 }) { content() }
}

@Composable internal fun EditorialMixCard(mix: DiscoveryMix, play: () -> Unit, save: () -> Unit, open: (() -> Unit)?, modifier: Modifier = Modifier, featured: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val theme = LocalPulsoTheme.current.choice
    val accent = colors.primary
    val shape = RoundedCornerShape(24.dp)
    val backdrop = Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(colors.surfaceVariant, accent, if (theme == ThemeChoice.LIGHT) .09f else .16f), colors.surfaceVariant, colors.surface))
    Column(modifier.clip(shape).pulseLight(24.dp).background(backdrop)
        .then(if (open != null) Modifier.clickable(onClickLabel = "Abrir ${mix.title}", onClick = open) else Modifier)
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (featured) 145.dp else 118.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                repeat(3) { ring -> drawCircle(accent.copy(alpha = .06f), radius = size.width * (.32f + ring * .12f), center = androidx.compose.ui.geometry.Offset(size.width * .16f, size.height * .4f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
            }
            val covers = mix.tracks.filter { it.artwork.isNotBlank() }.distinctBy { CoverArt.highQuality(it.artwork) }.take(3)
            val coverSize = minOf(if (featured) 124.dp else 100.dp, maxWidth * .44f)
            covers.reversed().forEachIndexed { index, song ->
                val layer = covers.size - index - 1
                Artwork(song, Modifier.align(Alignment.CenterEnd).padding(end = (layer * 30).dp)
                    .size(coverSize).graphicsLayer { rotationZ = (layer - 1) * 9f; shadowElevation = 6.dp.toPx() })
            }
            Text(mix.badge, color = accent, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(6.dp)).background(colors.surface.copy(alpha = .92f)).padding(horizontal = 6.dp, vertical = 3.dp))
        }
        Text(mix.genre.ifBlank { "PARA TI" }.uppercase(), color = accent, letterSpacing = 2.sp, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Reserve the same line slots, allowing Android font scaling without fixed-height clipping.
        Text(mix.title, color = colors.onSurface, fontSize = if (featured) 28.sp else 22.sp, lineHeight = if (featured) 32.sp else 27.sp, fontWeight = FontWeight.Bold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(mix.preview, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${mix.tracks.size} canciones", fontSize = 12.sp, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (mix.kind == "radio") "Desde ${mix.seed.artist}" else "Según tu biblioteca", fontSize = 10.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = save) { Icon(Icons.Default.PlaylistAdd, "Guardar ${mix.title} en playlist", tint = colors.onSurface) }
            FilledIconButton(onClick = play, colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = colors.onPrimary)) { Icon(Icons.Default.PlayArrow, "Reproducir ${mix.title}") }
        }
    }
}
