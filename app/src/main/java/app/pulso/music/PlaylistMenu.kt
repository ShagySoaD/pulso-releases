package app.pulso.music

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi

@UnstableApi
@Composable internal fun PlaylistMenu(playlist: Playlist, vm: MusicViewModel, renamed: (String) -> Unit, deleted: () -> Unit) {
    val context = LocalContext.current
    val library by vm.library.collectAsStateWithLifecycle()
    var expanded by remember(playlist.name) { mutableStateOf(false) }
    var rename by remember(playlist.name) { mutableStateOf(false) }
    var confirmDelete by remember(playlist.name) { mutableStateOf(false) }
    var name by remember(playlist.name) { mutableStateOf(playlist.name) }
    var working by remember(playlist.name) { mutableStateOf(false) }
    val error = PlaylistManagement.nameError(library.playlists, playlist.name, name)
    Box {
        IconButton(onClick = { expanded = true }, enabled = !working) { Icon(Icons.Default.MoreVert, "Opciones de ${playlist.name}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Renombrar") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { expanded = false; name = playlist.name; rename = true })
            DropdownMenuItem(text = { Text("Compartir playlist") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                expanded = false; working = true
                vm.sharePlaylist(playlist.name) { intent ->
                    working = false
                    if (intent != null) runCatching { context.startActivity(intent) }.onFailure { vm.message("No se pudo abrir el menú para compartir.", true) }
                }
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Eliminar playlist", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { expanded = false; confirmDelete = true })
        }
    }
    if (rename) AlertDialog(onDismissRequest = { if (!working) rename = false }, title = { Text("Renombrar playlist") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !working, singleLine = true, label = { Text("Nombre") }, isError = error != null,
            supportingText = { Text(error ?: "${name.trim().length}/80") }, modifier = Modifier.fillMaxWidth())
    }, confirmButton = { TextButton(enabled = !working && error == null && name.trim() != playlist.name, onClick = {
        working = true
        vm.renamePlaylist(playlist.name, name.trim()) { success -> working = false; if (success) { rename = false; renamed(name.trim()) } }
    }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = { rename = false }, enabled = !working) { Text("Cancelar") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { if (!working) confirmDelete = false }, title = { Text("Eliminar playlist") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Se eliminará «${playlist.name}» de tu biblioteca.")
            Text("Las canciones descargadas, favoritas y otras playlists se conservarán. Si está sonando, la cola seguirá reproduciéndose.")
        }
    }, confirmButton = { TextButton(enabled = !working, onClick = {
        working = true
        vm.deletePlaylist(playlist.name) { success -> working = false; if (success) { confirmDelete = false; deleted() } }
    }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !working) { Text("Cancelar") } })
}
