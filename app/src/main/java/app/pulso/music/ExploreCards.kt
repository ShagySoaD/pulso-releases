package app.pulso.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable fun CommunityPlaylistCard(playlist: CommunityPlaylist, open: () -> Unit) {
    Surface(onClick = open, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(playlist.artwork, null, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(playlist.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(playlist.author.ifBlank { "Playlist de la comunidad" }, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable fun RadarCard(mix: DiscoveryMix, open: () -> Unit, play: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var entered by remember(mix.id) { mutableStateOf(false) }
    LaunchedEffect(mix.id) { entered = true }
    val reveal by animateFloatAsState(if (entered) 1f else 0f, tween(650), label = "radar-entry")
    Surface(onClick = open, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer, colors.surfaceVariant, colors.surface)))
            .padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("RADAR PULSO", Modifier.weight(1f), fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = colors.onPrimaryContainer)
                Text("DESCUBRIR", fontSize = 10.sp, color = colors.onPrimaryContainer)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    repeat(6) { index ->
                        drawLine(colors.primary.copy(alpha = .09f),
                            androidx.compose.ui.geometry.Offset(size.width * index / 5f, 0f),
                            androidx.compose.ui.geometry.Offset(size.width * (index - 2) / 5f, size.height), strokeWidth = 2.dp.toPx())
                    }
                }
                val covers = mix.tracks.filter { it.artwork.isNotBlank() }.distinctBy { it.artwork }.take(3)
                val coverSize = minOf(130.dp, maxWidth * .42f)
                covers.forEachIndexed { index, song ->
                    Artwork(song, Modifier.align(Alignment.Center)
                        .size(coverSize).graphicsLayer { translationX = ((index - 1) * 65).dp.toPx() * reveal; rotationZ = (index - 1) * 11f * reveal; alpha = reveal; shadowElevation = 10.dp.toPx() })
                }
            }
            Text(mix.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${mix.tracks.size} canciones · ${mix.tracks.map { it.artist }.distinct().size} artistas", Modifier.weight(1f), fontSize = 12.sp)
                FilledIconButton(onClick = play) { Icon(Icons.Default.PlayArrow, "Escuchar Radar PULSO") }
            }
        }
    }
}
