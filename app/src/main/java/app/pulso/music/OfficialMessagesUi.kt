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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun OfficialConversationRow(open: () -> Unit) {
    val state by OfficialMessages.state.collectAsStateWithLifecycle()
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { PulsoMark(Modifier.size(30.dp)) } }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PULSO Oficial", Modifier.weight(1f, false), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.Verified, "Canal oficial", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(state.messages.lastOrNull()?.title ?: "Novedades de PULSO", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.unread > 0) Badge { Text(if (state.unread > 99) "99+" else state.unread.toString()) }
        }
    }
}

@Composable internal fun OfficialConversation(close: () -> Unit) {
    val state by OfficialMessages.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { OfficialMessages.refresh(force = true) }
    LaunchedEffect(state.messages.map { it.id }, lifecycle) {
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) OfficialMessages.markRead(state.messages.map { it.id }.toSet())
    }
    LaunchedEffect(state.messages.lastOrNull()?.id) { if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                    PulsoMark(Modifier.size(32.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("PULSO Oficial", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Icon(Icons.Default.Verified, "Canal oficial", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text("Novedades y anuncios", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(enabled = !state.refreshing, onClick = { OfficialMessages.refresh(force = true) }) { Icon(Icons.Default.Refresh, "Actualizar anuncios") }
                }
                if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(state.messages, key = { it.id }) { message ->
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(message.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (message.image.isNotBlank()) {
                                        var imageFailed by remember(message.image) { mutableStateOf(false) }
                                        if (!imageFailed) AsyncImage(model = message.image, contentDescription = message.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(1.3f), onError = { imageFailed = true })
                                    }
                                    SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                                    Text(SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(message.at)), modifier = Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (state.messages.isEmpty()) item { Text("Los anuncios aparecerán aquí", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (state.error.isNotEmpty()) Text(state.error, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Solo anuncios de PULSO", Modifier.align(Alignment.CenterHorizontally).padding(12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
