package app.pulso.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

internal val themePalettes = listOf(
    "Original" to ("1359C9" to "245CBD"),
    "Océano" to ("168BE0" to "24C7CF"),
    "Bosque" to ("369A75" to "AED581"),
    "Violeta" to ("AA78ED" to "ED8FC4"),
    "Ámbar" to ("EAA23A" to "EF775E"),
    "Rosa" to ("E85A88" to "B884DB")
)

@Composable internal fun ThemeEditor(selection: ThemeSelection, onDismiss: () -> Unit) {
    val defaults = ThemeColors.defaults(selection.choice)
    var encoded by rememberSaveable(selection.choice.id) { mutableStateOf((selection.custom ?: defaults).encode()) }
    var reset by rememberSaveable(selection.choice.id) { mutableStateOf(false) }
    var role by rememberSaveable { mutableIntStateOf(0) }
    var validInput by remember { mutableStateOf(true) }
    var editorRevision by rememberSaveable { mutableIntStateOf(0) }
    val draft = ThemeColors.decode(encoded) ?: defaults
    val values = listOf(draft.background, draft.cards, draft.buttons, draft.accent)
    val preview = pulsoColors(selection.choice, if (reset) null else draft)
    fun change(value: String) {
        encoded = values.toMutableList().apply { this[role] = value }.joinToString(",")
        reset = false
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ThemeDialogBars()
        Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp)
            .widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(.95f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Personalizar · ${selection.choice.title}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cancelar cambios") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Vista previa", style = MaterialTheme.typography.labelLarge)
                    MaterialTheme(colorScheme = preview) {
                        Surface(color = preview.background, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, preview.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PulsoMark(Modifier.size(36.dp))
                                    Text("PULSO", color = preview.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                                }
                                Surface(color = preview.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(Icons.Default.MusicNote, null, tint = preview.secondary, modifier = Modifier.size(32.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Tu playlist", color = preview.onSurface, fontWeight = FontWeight.Bold)
                                            Text("Artista · Álbum", color = preview.onSurfaceVariant)
                                        }
                                        Icon(Icons.Default.Favorite, null, tint = preview.secondary)
                                    }
                                }
                                Surface(color = preview.primary, contentColor = preview.onPrimary, shape = CircleShape) {
                                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PlayArrow, null)
                                        Text("Reproducir")
                                    }
                                }
                            }
                        }
                    }
                    Text("Paletas", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        themePalettes.forEachIndexed { index, (name, pair) ->
                            AssistChip(onClick = {
                                val colors = if (index == 0) defaults else defaults.copy(buttons = pair.first, accent = pair.second)
                                encoded = colors.encode()
                                reset = index == 0
                                editorRevision++
                            }, label = { Text(name) }, leadingIcon = {
                                Box(Modifier.size(14.dp).background(rgb(if (index == 0) defaults.buttons else pair.first), CircleShape))
                            })
                        }
                    }
                    Text("Colores", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Fondo", "Tarjetas", "Botones", "Acentos").forEachIndexed { index, label ->
                            FilterChip(selected = role == index, onClick = { role = index }, label = { Text(label) })
                        }
                    }
                    Text("El brillo se ajusta automáticamente para mantener el contraste del tema.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    key(editorRevision, role) { ColorControls(values[role], role, { validInput = it }, ::change) }
                    TextButton(onClick = { encoded = defaults.encode(); reset = true; editorRevision++ }) { Text("Restablecer este tema") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = validInput, onClick = {
                        selection.customize(if (reset || draft == defaults) null else draft)
                        onDismiss()
                    }) { Text("Aplicar") }
                }
            }
        }
    }
}

@Composable private fun ColorControls(value: String, role: Int, validity: (Boolean) -> Unit, onChange: (String) -> Unit) {
    var input by rememberSaveable(value, role) { mutableStateOf(value) }
    val valid = input.matches(Regex("[0-9a-fA-F]{6}"))
    LaunchedEffect(valid) { validity(valid) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).background(rgb(value), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        OutlinedTextField(value = input, onValueChange = { raw ->
            input = raw.removePrefix("#").take(6)
            if (input.matches(Regex("[0-9a-fA-F]{6}"))) onChange(input.uppercase())
        }, label = { Text("Color HEX") }, prefix = { Text("#") }, singleLine = true, isError = !valid,
            supportingText = { if (!valid) Text("Escribe 6 caracteres: 0–9 y A–F") }, modifier = Modifier.weight(1f))
    }
    val swatches = listOf("FFFFFF", "DDEBFF", "101115", "283445", "E74C64", "E9A33B", "53B78C", "348BDD", "A17AE8", "E87AAC", "24B7C6", "8D96A5")
    Column(Modifier.selectableGroup()) {
        swatches.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { hex ->
                    Box(Modifier.size(48.dp).selectable(value.equals(hex, true), role = Role.RadioButton, onClick = { onChange(hex) })
                        .semantics { contentDescription = "Color #$hex" }.padding(6.dp)
                        .background(rgb(hex), CircleShape).border(if (value.equals(hex, true)) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface, CircleShape))
                }
            }
        }
    }
    val channels = value.chunked(2).map { it.toInt(16) }
    listOf("Rojo", "Verde", "Azul").forEachIndexed { index, name ->
        Text("$name · ${channels[index]}", style = MaterialTheme.typography.labelMedium)
        Slider(value = channels[index].toFloat(), onValueChange = { next ->
            onChange(channels.toMutableList().apply { this[index] = next.roundToInt() }.joinToString("") { "%02X".format(it) })
        }, valueRange = 0f..255f, modifier = Modifier.semantics { contentDescription = name })
    }
}
