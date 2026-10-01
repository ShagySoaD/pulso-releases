package app.pulso.music

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun ConnectQrDialog(state: SocialState, close: () -> Unit) {
    val context = LocalContext.current; val clipboard = LocalClipboardManager.current
    val qr by produceState<android.graphics.Bitmap?>(null, state.address) { value = withContext(Dispatchers.Default) { runCatching { SocialMedia.qr(state.address) }.getOrNull() } }
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Mi QR", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); IconButton(onClick = close) { Icon(Icons.Default.Close, "Cerrar") } }
                ConnectAvatar(state.avatar, state.photo, 64.dp)
                Text(state.name.ifBlank { "PULSO Connect" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                qr?.let { Image(it.asImageBitmap(), "Mi código QR de PULSO Connect", Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).background(Color.White, RoundedCornerShape(16.dp)).padding(8.dp)) } ?: CircularProgressIndicator()
                Text("Escanea para añadirme", style = MaterialTheme.typography.bodyMedium)
                Button(enabled = qr != null, modifier = Modifier.fillMaxWidth(), onClick = { runCatching { SocialMedia.shareQr(context, qr!!) }.onFailure { error = "No se pudo compartir el QR." } }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Compartir QR") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(SocialMedia.invitation(state.address))); android.widget.Toast.makeText(context, "Invitación copiada", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Copiar invitación") }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable internal fun AddConnectContact(invitation: String = "", close: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var code by remember(invitation) { mutableStateOf(invitation) }; var error by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    fun selected(text: String) { runCatching { SocialProtocol.address(text) }.onSuccess { code = it; error = "" }.onFailure { error = "Este QR no es una invitación de PULSO Connect." } }
    val camera = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::selected) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) scope.launch {
        busy = true; error = ""
        runCatching { withContext(Dispatchers.IO) { SocialMedia.readQr(context, uri) } }.onSuccess { selected(it) }.onFailure { error = "No se encontró un QR de Connect en esta imagen." }
        busy = false
    } }
    AlertDialog(onDismissRequest = close, title = { Text("Añadir contacto") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY), onClick = {
                camera.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Escanea un QR de PULSO Connect").setBeepEnabled(false).setOrientationLocked(false))
            }) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Escanear QR") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { gallery.launch("image/*") }) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text("Abrir imagen de QR") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (manual) OutlinedTextField(code, { code = it.take(200); error = "" }, label = { Text("Invitación o código") }, maxLines = 3)
            else TextButton(onClick = { manual = true }) { Text("Pegar invitación") }
            if (code.isNotEmpty() && !manual) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("Invitación lista") }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = code.isNotBlank() && !busy, onClick = { runCatching { SocialProtocol.address(code) }.onSuccess { SocialEngine.add(it); close() }.onFailure { error = "Revisa la invitación y vuelve a intentarlo." } }) { Text("Añadir") } }, dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
}
