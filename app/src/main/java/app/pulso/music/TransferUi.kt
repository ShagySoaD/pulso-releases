package app.pulso.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun BackupControls(vm: TransferViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportBackup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openBackup) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Copia de seguridad", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Playlists, favoritas y ajustes. Sin archivos de audio.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Button(onClick = { export.launch("Pulso-respaldo-${java.time.LocalDate.now()}.json") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(8.dp)); Text("Crear respaldo")
            }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = !state.busy && !state.running, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Restaurar respaldo")
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.message.isNotBlank()) Text(state.message, fontSize = 13.sp)
        }
    }
}

@Composable internal fun TransferDialog(vm: TransferViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.openPlaylists(it) }
    var confirmSave by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var includePreferences by rememberSaveable { mutableStateOf(true) }
    var reviewLimit by rememberSaveable { mutableIntStateOf(30) }
    var spotifyLink by rememberSaveable { mutableStateOf("") }
    var reviewAll by rememberSaveable { mutableStateOf(false) }
    if (state.screen.isBlank()) return
    val draft = state.draft
    Dialog(onDismissRequest = vm::hide, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ThemeDialogBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::hide) { Icon(Icons.Default.ArrowBack, "Volver") }
                    Text(if (state.screen == "restore") "Restaurar respaldo" else "Importar playlists", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                LazyColumn(Modifier.widthIn(max = 800.dp).fillMaxWidth().weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.message.isNotBlank()) item { Text(state.message, fontSize = 14.sp) }
                    if (state.screen == "restore") {
                        state.backup?.let { backup ->
                            item {
                                Text("${backup.library.playlists.size} playlists · ${backup.library.tracks.count { it.favorite }} favoritas", fontWeight = FontWeight.Bold)
                                if (backup.created > 0) Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.created)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                Spacer(Modifier.height(10.dp))
                                Text("Se combinarán con tu biblioteca. Las canciones y playlists existentes se conservarán, sin duplicar identificadores.")
                                Text("Los audios tendrás que descargarlos de nuevo. Las descargas que no estaban en una playlist ni en favoritas aparecerán en «Descargas del respaldo».", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                                if (backup.library.tracks.any { it.id.startsWith("local-") }) Text("Los archivos locales se recuperan como referencias. Vuelve a importar sus audios para escucharlos.", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(includePreferences, { includePreferences = it }, enabled = !state.busy)
                                    Text("Restaurar tema y ecualizador", modifier = Modifier.weight(1f))
                                }
                                Button(onClick = { vm.restore(includePreferences) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Combinar y restaurar") }
                            }
                            items(backup.library.playlists, key = { it.name }) { list ->
                                Text("${list.name} · ${list.ids.size} canciones", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else if (draft == null) {
                        item {
                            Text("Desde un enlace de Spotify", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            OutlinedTextField(value = spotifyLink, onValueChange = { spotifyLink = it }, singleLine = true,
                                enabled = !state.busy, label = { Text("Enlace de playlist pública") }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                            Button(onClick = { vm.consultSpotify(spotifyLink) }, enabled = !state.busy && spotifyLink.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Consultar playlist") }
                            Text("Se consultan títulos y artistas, sin iniciar sesión. Después podrás buscar y revisar las versiones disponibles en PULSO.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                        }
                        state.spotify?.let { preview -> item {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(preview.name, fontWeight = FontWeight.Bold)
                                    Text(preview.warning, fontSize = 13.sp)
                                    val uniqueCount = preview.file().songs.size
                                    if (uniqueCount < preview.songs.size) Text("${preview.songs.size - uniqueCount} entradas repetidas se agruparán: $uniqueCount canciones únicas para buscar.", fontSize = 12.sp)
                                    if (preview.skipped > 0) Text("${preview.skipped} entradas sin metadatos utilizables o que no son canciones.", fontSize = 12.sp)
                                    preview.songs.take(5).forEach { Text("${it.title} · ${it.artist}", maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) }
                                    Button(onClick = vm::importSpotify, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Continuar con estas $uniqueCount canciones") }
                                }
                            }
                        } }
                        item {
                            HorizontalDivider(Modifier.padding(vertical = 12.dp))
                            Text("Desde un archivo", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("CSV con título y artista, o JSON de playlists de Spotify y formatos compatibles.")
                            Text("Si recibiste un ZIP, extrae primero los archivos JSON de playlists. Puedes seleccionar varios archivos.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                            Button(onClick = { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream", "application/vnd.ms-excel")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Elegir archivos")
                            }
                            Text("La búsqueda de canciones necesita Internet. No se conecta ninguna cuenta ni se descarga audio durante la importación.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        }
                    } else {
                        item {
                            Text("${draft.file.playlists.size} playlists · ${draft.file.songs.size} canciones", fontWeight = FontWeight.Bold)
                            Text(draft.file.playlists.take(5).joinToString(" · ") { it.name }, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (draft.sourceNotice.isNotBlank()) Text(draft.sourceNotice, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                            if (draft.file.skipped > 0) Text("${draft.file.skipped} filas omitidas: faltaba título o artista, o eran podcasts.", fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("${draft.found} encontradas · ${draft.file.songs.count { draft.results[it.key] != null }} revisadas", fontSize = 14.sp)
                            if (state.running) {
                                LinearProgressIndicator(progress = { draft.results.size.toFloat() / draft.file.songs.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                                Text(state.current, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                OutlinedButton(onClick = vm::pause, modifier = Modifier.fillMaxWidth()) { Text("Pausar") }
                            } else {
                                if (draft.results.size < draft.file.songs.size) Button(onClick = { vm.search() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (draft.results.isEmpty()) "Buscar canciones" else "Continuar búsqueda") }
                                if (draft.results.values.any { it.chosen == null && it.suggestions.isEmpty() }) OutlinedButton(onClick = { vm.search(true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Reintentar pendientes") }
                                Button(onClick = { if (draft.found < draft.file.songs.size) confirmSave = true else vm.saveImport() }, enabled = !state.busy && (draft.found > 0 || draft.file.songs.isEmpty()), modifier = Modifier.fillMaxWidth()) { Text("Guardar playlists") }
                                TextButton(onClick = { confirmDiscard = true }, enabled = !state.busy) { Text("Descartar borrador") }
                            }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(reviewAll, { reviewAll = it; reviewLimit = 30 })
                                Text("Revisar también las coincidencias seleccionadas", modifier = Modifier.weight(1f), fontSize = 13.sp)
                            }
                        }
                        val pending = draft.file.songs.filter { (reviewAll || draft.results[it.key]?.chosen == null) && draft.results.containsKey(it.key) }
                        if (pending.isNotEmpty()) item { Text("${if (reviewAll) "Coincidencias" else "Por revisar"} · ${pending.size}", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                        items(pending.take(reviewLimit), key = { it.key }) { song ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(song.title, fontWeight = FontWeight.Bold)
                                    Text(song.artist, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val options = draft.results[song.key]?.suggestions.orEmpty()
                                    val chosen = draft.results[song.key]?.chosen
                                    if (chosen != null) {
                                        Text("Seleccionada: ${chosen.title} · ${chosen.artist}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                        TextButton(onClick = { vm.clearChoice(song) }, enabled = !state.running && !state.busy) { Text("Dejar pendiente") }
                                    }
                                    if (options.isEmpty()) Text(if (draft.results[song.key]?.error == true) "No se pudo consultar. Reintenta con conexión." else "Sin coincidencia segura.", fontSize = 12.sp)
                                    options.forEach { track ->
                                        OutlinedButton(onClick = { vm.choose(song, track) }, enabled = !state.running && !state.busy, modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.weight(1f)) {
                                                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text("${track.artist}${if (track.seconds > 0) " · ${track.seconds / 60}:${(track.seconds % 60).toString().padStart(2, '0')}" else ""}", fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                            Icon(Icons.Default.Check, "Elegir esta versión")
                                        }
                                    }
                                }
                            }
                        }
                        if (pending.size > reviewLimit) item { OutlinedButton(onClick = { reviewLimit += 30 }, modifier = Modifier.fillMaxWidth()) { Text("Ver más pendientes") } }
                    }
                }
            }
        }
    }
    if (confirmSave) AlertDialog(onDismissRequest = { confirmSave = false }, title = { Text("Guardar canciones encontradas") },
        text = { Text("Se guardarán las ${draft?.found ?: 0} canciones encontradas. Las pendientes quedarán en el borrador para revisarlas después. Las playlists con el mismo nombre se combinarán.") },
        confirmButton = { TextButton(onClick = { confirmSave = false; vm.saveImport() }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Revisar") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Descartar importación") },
        text = { Text("Se eliminará este borrador. Las playlists que ya guardaste en Biblioteca se conservarán.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discard() }) { Text("Descartar") } }, dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancelar") } })
}
