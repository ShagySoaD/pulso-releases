package app.pulso.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun AppUpdateHost() {
    val context = LocalContext.current
    val state by AppUpdates.state.collectAsStateWithLifecycle()
    val requested by AppUpdates.openRequested.collectAsStateWithLifecycle()
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.info?.code) {
        state.info?.let {
            if (AppUpdates.shouldPrompt(context, it.code)) {
                AppUpdates.prompted(context, it.code)
                visible = true
            }
        }
    }
    LaunchedEffect(requested) {
        if (requested) {
            visible = true
            AppUpdates.openRequested.value = false
            if (state.info == null) AppUpdates.checkNow(context)
        }
    }
    if (visible) AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text(state.info?.let { "PULSO ${it.name} disponible" } ?: "Actualizaciones") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.info?.let { info ->
                    if (info.notes.isNotBlank()) Text(info.notes)
                    val asset = info.compatible(android.os.Build.SUPPORTED_ABIS.toList(), android.os.Build.VERSION.SDK_INT)
                    asset?.let { Text("Descarga: ${it.size / 1048576} MB", style = MaterialTheme.typography.bodySmall) }
                }
                if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.downloading) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${state.progress} %")
                }
                if (state.message.isNotBlank()) Text(state.message)
            }
        },
        confirmButton = {
            state.info?.let { info ->
                Button(enabled = !state.downloading && !state.checking, onClick = {
                    if (state.ready != null) AppUpdates.install(context) else AppUpdates.download(context, info)
                }) { Text(if (state.ready != null) "Instalar" else "Descargar") }
            }
        },
        dismissButton = { TextButton(onClick = { visible = false }) { Text(if (state.downloading) "Continuar en segundo plano" else "Más tarde") } }
    )
}

@Composable internal fun AppUpdateSettings() {
    val context = LocalContext.current
    val state by AppUpdates.state.collectAsStateWithLifecycle()
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Actualizaciones", style = MaterialTheme.typography.titleLarge)
            Text("Versión ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.message.ifBlank { "Consulta automática cada 12 horas y al abrir cuando corresponda." }, style = MaterialTheme.typography.bodySmall)
            if (state.downloading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (state.info != null) Button(onClick = { AppUpdates.openRequested.value = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.ready != null) "Instalar actualización" else "Ver actualización")
            }
            OutlinedButton(onClick = { AppUpdates.checkNow(context) }, enabled = !state.checking && !state.downloading && AppUpdates.configured,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.checking) "Buscando…" else "Buscar actualizaciones") }
        }
    }
}
