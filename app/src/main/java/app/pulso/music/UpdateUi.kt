package app.pulso.music

import androidx.compose.foundation.layout.*


import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun AppUpdateSettings() {
    val context = LocalContext.current
    val state by AppUpdates.state.collectAsStateWithLifecycle()
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Actualizaciones", style = MaterialTheme.typography.titleLarge)
            Text("Versión ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.info?.let { info ->
                Text("PULSO ${info.name} disponible", style = MaterialTheme.typography.titleMedium)
                if (info.notes.isNotBlank()) Text(info.notes, style = MaterialTheme.typography.bodySmall)
                info.compatible(android.os.Build.SUPPORTED_ABIS.toList(), android.os.Build.VERSION.SDK_INT)?.let {
                    Text("Descarga: ${it.size / 1048576} MB", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(state.message.ifBlank { "Se comprueba al abrir PULSO y periódicamente en segundo plano." }, style = MaterialTheme.typography.bodySmall)
            if (state.downloading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (state.downloading) Text("${state.progress} %", style = MaterialTheme.typography.bodySmall)
            state.info?.let { info ->
                Button(onClick = {
                    if (state.ready != null) AppUpdates.install(context) else AppUpdates.download(context, info)
                }, enabled = !state.downloading && !state.checking, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.ready != null) "Instalar actualización" else "Descargar actualización")
                }
            }
            OutlinedButton(onClick = { AppUpdates.checkNow(context) }, enabled = !state.checking && !state.downloading && AppUpdates.configured,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.checking) "Buscando…" else "Buscar actualizaciones") }
        }
    }
}
