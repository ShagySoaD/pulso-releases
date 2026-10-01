package app.pulso.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun MessagesScreen(onPlay: (Track) -> Unit) {
    val state by SocialEngine.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }; var editing by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    var invitation by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf<String?>(null) }; var profile by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val requested by SocialEngine.openRequested.collectAsStateWithLifecycle()
    val incomingInvite by SocialEngine.inviteRequested.collectAsStateWithLifecycle()
    LaunchedEffect(incomingInvite, state.ready) {
        if (incomingInvite != null && state.ready) { invitation = incomingInvite!!; adding = true; SocialEngine.inviteRequested.value = null }
    }
    LaunchedEffect(requested, state.ready) {
        if (requested != null && state.ready) { chat = requested.takeIf { key -> state.friends.any { it.key == key } }; SocialEngine.openRequested.value = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ConnectProfileCard(state, { editing = true }, { qr = true })
        if (state.error.isNotBlank()) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) { Text(state.error); if (state.ready) TextButton(onClick = SocialEngine::clearError) { Text("Cerrar") } }
        }
        if (state.requests.isNotEmpty()) Text("Solicitudes", style = MaterialTheme.typography.titleMedium)
        state.requests.forEach { key -> Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { ConnectAvatar(key.take(2).toInt(16) % 8, "", 42.dp); Spacer(Modifier.width(12.dp)); Text("Nueva solicitud", fontWeight = FontWeight.SemiBold) }
                Row { TextButton(onClick = { SocialEngine.accept(key) }, enabled = state.ready) { Text("Aceptar") }; TextButton(onClick = { SocialEngine.reject(key) }) { Text("Rechazar") } }
            }
        } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Conversaciones", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = { adding = true }, enabled = state.ready && state.address.isNotBlank()) { Icon(Icons.Default.PersonAddAlt1, "Añadir contacto") }
        }
        if (state.friends.isEmpty()) Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Default.Forum, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Aún no hay conversaciones", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { adding = true }, enabled = state.ready && state.address.isNotBlank()) { Text("Añadir con QR") }
            }
        }
        state.friends.sortedByDescending { it.messages.lastOrNull()?.at ?: 0L }.forEach { friend ->
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().clickable { chat = friend.key }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box { ConnectAvatar(friend.avatar, friend.photo, 56.dp); Box(Modifier.align(Alignment.BottomEnd).padding(2.dp)) { ConnectStatus(friend.online) } }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(friend.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (friend.online) "Conectado" else "Desconectado", style = MaterialTheme.typography.labelSmall, color = if (friend.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (friend.online) friend.now?.let { song ->
                            Text("♫ Escuchando ahora · ${song.title} — ${song.artist}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        friend.messages.lastOrNull()?.let { message ->
                            Text(if (message.mine) "Tú: ${message.text}" else message.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (friend.unread > 0) Badge { Text(if (friend.unread > 99) "99+" else friend.unread.toString()) }
                    IconButton(onClick = { profile = friend.key }) { Icon(Icons.Default.ChevronRight, "Ver perfil") }
                }
            }
        }
    }
    if (qr) ConnectQrDialog(state) { qr = false }
    if (adding) AddConnectContact(invitation) { adding = false; invitation = "" }
    if (editing) EditSocialProfile(state) { editing = false }
    state.friends.firstOrNull { it.key == chat }?.let { SocialChat(it, state.ready, profile == null, { chat = null }, { profile = it.key }) }
    state.friends.firstOrNull { it.key == profile }?.let { friend -> SocialProfile(friend, { onPlay(it); profile = null; chat = null }, { profile = null }, { removing = friend.key }) }
    if (removing != null) AlertDialog(onDismissRequest = { removing = null }, title = { Text("¿Eliminar contacto?") }, text = { Text("Se borrará también la conversación de este teléfono.") }, confirmButton = { TextButton(onClick = { SocialEngine.remove(removing!!); removing = null; profile = null; chat = null }) { Text("Eliminar") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } })
}

@Composable private fun SocialChat(friend: SocialFriend, enabled: Boolean, reading: Boolean, close: () -> Unit, profile: () -> Unit) {
    var text by remember(friend.key) { mutableStateOf("") }; val scroll = rememberLazyListState()
    DisposableEffect(friend.key, reading) { SocialEngine.viewing(if (reading) friend.key else null); onDispose { SocialEngine.viewing(null) } }
    LaunchedEffect(friend.messages.lastOrNull()?.id) { if (friend.messages.isNotEmpty()) scroll.animateScrollToItem(friend.messages.lastIndex) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 14.dp)) {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                Row(Modifier.weight(1f).clickable(onClick = profile), verticalAlignment = Alignment.CenterVertically) {
                    ConnectAvatar(friend.avatar, friend.photo, 42.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(friend.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (friend.online) "En línea" else "Desconectado", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = profile) { Icon(Icons.Default.MoreVert, "Ver perfil") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(friend.messages, key = { it.id }) { message -> Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start) {
                    Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (message.mine) 20.dp else 5.dp, bottomEnd = if (message.mine) 5.dp else 20.dp), color = if (message.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 320.dp)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            SelectionContainer { Text(message.text) }
                            Row(Modifier.align(Alignment.End).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.at)), style = MaterialTheme.typography.labelSmall)
                                if (message.mine) Icon(when (message.status) { "Entregado" -> Icons.Default.DoneAll; "Enviado" -> Icons.Default.Done; else -> Icons.Default.Schedule }, message.status, Modifier.size(14.dp))
                            }
                        }
                    }
                } }
            }
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { if (it.toByteArray().size <= 1372) text = it }, modifier = Modifier.weight(1f), placeholder = { Text("Mensaje") }, maxLines = 4, shape = RoundedCornerShape(24.dp))
                FilledIconButton(enabled = enabled && text.isNotBlank(), onClick = { SocialEngine.send(friend.key, text); text = "" }, modifier = Modifier.padding(bottom = 4.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar") }
            }
        } }
    }
}
