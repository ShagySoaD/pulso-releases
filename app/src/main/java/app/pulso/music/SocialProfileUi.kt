package app.pulso.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun ConnectAvatar(preset: Int, photo: String, size: Dp = 64.dp) {
    val bitmap = remember(photo) { SocialMedia.decodePhoto(photo)?.asImageBitmap() }
    if (bitmap != null) Image(bitmap, "Foto de perfil", Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    else Canvas(Modifier.size(size).clip(CircleShape).semantics { contentDescription = "Avatar ${preset + 1}" }) {
        val accents = listOf(0xff6979ef, 0xffa56bd4, 0xffcc795c, 0xff50a594, 0xff5b91c6, 0xffbd617b, 0xff989359, 0xff646c83)
        val tone = Color(accents[preset.coerceIn(0, 7)])
        val w = this.size.width
        drawRect(Brush.linearGradient(listOf(tone, tone.copy(red = tone.red * .45f, green = tone.green * .45f, blue = tone.blue * .45f))))
        drawCircle(Color.White.copy(alpha = .08f), w*.42f, Offset(w*.85f, w*.12f))
        drawOval(Color(0xff222539), Offset(w*.16f, w*.69f), Size(w*.68f, w*.55f))
        val skin = listOf(Color(0xffe8b99b), Color(0xffbd866a), Color(0xff875e4d))[preset % 3]
        drawCircle(Color(0xff282334), w*.26f, Offset(w*.5f,w*.40f))
        drawOval(skin, Offset(w*.29f,w*.28f), Size(w*.42f,w*.45f))
        drawArc(Color(0xff282334), 175f, 185f, true, Offset(w*.25f,w*.16f), Size(w*.5f,w*.37f))
        if (preset % 2 == 0) {
            drawArc(Color.White.copy(alpha = .8f), 185f, 170f, false, Offset(w*.19f,w*.21f), Size(w*.62f,w*.62f), style = Stroke(w*.045f))
            drawRoundRect(Color(0xff222539), Offset(w*.17f,w*.44f), Size(w*.10f,w*.2f), androidx.compose.ui.geometry.CornerRadius(w*.04f))
            drawRoundRect(Color(0xff222539), Offset(w*.73f,w*.44f), Size(w*.10f,w*.2f), androidx.compose.ui.geometry.CornerRadius(w*.04f))
        }
        drawCircle(Color(0xff332a33), w*.018f, Offset(w*.42f,w*.47f)); drawCircle(Color(0xff332a33), w*.018f, Offset(w*.58f,w*.47f))
        drawArc(Color(0xff804b4b), 10f, 160f, false, Offset(w*.43f,w*.52f), Size(w*.14f,w*.10f), style = Stroke(w*.015f))
    }
}

@Composable internal fun ConnectStatus(online: Boolean, label: String = if (online) "En línea" else "Desconectado") {
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) Color(0xff56cfaa) else MaterialTheme.colorScheme.outline).semantics { contentDescription = label })
}

@Composable internal fun ConnectProfileCard(state: SocialState, edit: () -> Unit, qr: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(colors.primary.copy(alpha = .18f), colors.surfaceVariant))).padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ConnectAvatar(state.avatar, state.photo, 48.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.name.ifBlank { "Mi perfil" }, Modifier.weight(1f, false), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ConnectStatus(state.status == "Conectado", state.status)
                }
                Text(state.bio.ifBlank { "PULSO Connect" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = qr, enabled = state.address.isNotEmpty()) { Icon(Icons.Default.QrCode2, "Mi QR", tint = colors.primary) }
            IconButton(onClick = edit, enabled = state.ready) { Icon(Icons.Default.Edit, "Editar mi perfil") }
        }
    }
}

@Composable internal fun EditSocialProfile(state: SocialState, close: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val library = Library.state.value
    var name by remember { mutableStateOf(state.name) }; var bio by remember { mutableStateOf(state.bio) }
    var favorites by remember { mutableStateOf(state.shareFavorites) }; var now by remember { mutableStateOf(state.shareNow) }
    var playlists by remember { mutableStateOf(state.sharePlaylists) }
    var avatar by remember { mutableIntStateOf(state.avatar) }; var photo by remember { mutableStateOf(state.photo) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) scope.launch {
        busy = true; error = ""
        runCatching { withContext(Dispatchers.IO) { SocialMedia.photo(context, uri) } }.onSuccess { photo = it }.onFailure { error = "No se pudo abrir esta imagen." }
        busy = false
    } }
    AlertDialog(onDismissRequest = close, title = { Text("Mi perfil") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ConnectAvatar(avatar, photo, 56.dp)
                TextButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Icon(Icons.Default.AddAPhoto, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "Preparando foto…" else "Elegir foto") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items((0..7).toList()) { option ->
                Box(Modifier.clip(CircleShape).border(if (photo.isEmpty() && avatar == option) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape).clickable { avatar = option; photo = "" }.padding(4.dp)) { ConnectAvatar(option, "", 36.dp) }
            } }
            OutlinedTextField(name, { name = it.take(40) }, modifier = Modifier.fillMaxWidth(), label = { Text("Nombre") }, singleLine = true)
            OutlinedTextField(bio, { bio = it.take(120) }, modifier = Modifier.fillMaxWidth(), label = { Text("Sobre mí") }, maxLines = 2)
            Text("Compartir con mis contactos", style = MaterialTheme.typography.labelLarge)
            ShareMusicSetting("Favoritas", library.tracks.filter { it.favorite }, favorites) { favorites = it }
            val playlistIds = library.playlists.flatMap { it.ids }.toSet()
            ShareMusicSetting("Playlists", library.tracks.filter { it.id in playlistIds }, playlists) { playlists = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Escuchando ahora", Modifier.weight(1f)); Switch(now, { now = it }) }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank() && !busy, onClick = { SocialEngine.profile(name, bio, favorites, now, avatar, photo, playlists); close() }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
}

@Composable private fun ShareMusicSetting(title: String, songs: List<Track>, checked: Boolean, change: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlaylistArtwork(songs, Modifier.size(38.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked, change, modifier = Modifier.semantics { contentDescription = "Compartir $title" })
        }
    }
}

@Composable internal fun SocialProfile(friend: SocialFriend, onPlay: (Track) -> Unit, onClose: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var section by remember(friend.key) { mutableStateOf<String?>(null) }
    var playlistId by remember(friend.key) { mutableStateOf<String?>(null) }
    val selected = friend.playlists.firstOrNull { it.id == playlistId }
    LaunchedEffect(friend.sharesFavorites, friend.sharesPlaylists, friend.online) {
        if (!friend.online || (section == "favorites" && !friend.sharesFavorites) || (section == "playlists" && !friend.sharesPlaylists)) { section = null; playlistId = null }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Surface(shape = RoundedCornerShape(22.dp)) {
                            Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(colors.primary.copy(alpha = .22f), colors.surfaceVariant))).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                ConnectAvatar(friend.avatar, friend.photo, 56.dp)
                                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(friend.name, Modifier.weight(1f, false), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        ConnectStatus(friend.online)
                                    }
                                    Text(if (friend.online) "Conectado" else "Desconectado", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    if (friend.bio.isNotBlank()) Text(friend.bio, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar perfil") }
                            }
                        }
                    }
                    if (section == null) {
                        if (friend.online) friend.now?.let { song -> item {
                            Text("Escuchando ahora", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                            Spacer(Modifier.height(6.dp)); SharedSong(song, onPlay)
                        } }
                        item {
                            CollectionCard("Favoritas", if (friend.sharesFavorites) "${friend.favorites.size} canciones" else if (friend.online) "No compartidas" else "Sin conexión", friend.favorites, friend.online && friend.sharesFavorites) { section = "favorites" }
                        }
                        item {
                            CollectionCard("Playlists", if (friend.sharesPlaylists) "${friend.playlists.size} listas" else if (friend.online) "No compartidas" else "Sin conexión", friend.playlists.flatMap { it.tracks }, friend.online && friend.sharesPlaylists) { section = "playlists" }
                        }
                        item { TextButton(onClick = onRemove) { Text("Eliminar contacto", color = colors.error) } }
                    } else {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { if (playlistId != null) playlistId = null else section = null }) { Icon(Icons.Default.ChevronLeft, "Volver") }
                                Text(if (section == "favorites") "Favoritas" else selected?.name ?: "Playlists", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (section == "playlists" && playlistId == null) {
                            if (friend.playlists.isEmpty()) item { Text("Aún no hay playlists compartidas", color = colors.onSurfaceVariant) }
                            items(friend.playlists, key = { it.id }) { list -> CollectionCard(list.name, "${list.tracks.size} canciones", list.tracks, true) { playlistId = list.id } }
                        } else {
                            val songs = if (section == "favorites") friend.favorites else selected?.tracks.orEmpty()
                            if (songs.isEmpty()) item { Text("Aún no hay canciones compartidas", color = colors.onSurfaceVariant) }
                            items(songs, key = { it.id }) { SharedSong(it, onPlay) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CollectionCard(title: String, subtitle: String, songs: List<Track>, enabled: Boolean, open: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = open).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistArtwork(songs, Modifier.size(56.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (enabled) Icons.Default.ChevronRight else Icons.Default.Lock, if (enabled) "Abrir $title" else null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun SharedSong(track: Track, play: (Track) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().clickable { play(track) }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(40.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.PlayArrow, "Reproducir", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
