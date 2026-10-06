package app.pulso.music

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

enum class ThemeChoice(val id: String, val title: String, val detail: String) {
    LIGHT("light", "Claro", "Blancos y azules"),
    DARK("dark", "Oscuro", "Negros y rojos");

    companion object {
        // Includes migration from the retired "metal" value.
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DARK
    }
}

internal fun pulsoColors(choice: ThemeChoice, custom: ThemeColors? = null): ColorScheme {
    val light = choice == ThemeChoice.LIGHT
    val base = if (light) lightColorScheme() else darkColorScheme()
    val requested = custom ?: ThemeColors.defaults(choice)
    val ink = if (light) Color.Black else Color.White
    val paper = if (light) Color.White else Color.Black
    // Keep shared text readable even when the user chooses opposing surface colors.
    val background = readableColor(rgb(requested.background), listOf(ink), paper, 9f)
    val panel = readableColor(rgb(requested.cards), listOf(ink), paper, 9f)
    val surface = if (custom == null) rgb(if (light) "FFFFFF" else "101115") else lerp(background, panel, .4f)
    val surfaces = listOf(background, panel, surface)
    val text = readableColor(rgb(if (light) "10213B" else "F5F3F5"), surfaces, ink, 7f)
    val muted = readableColor(rgb(if (light) "50617A" else "B9BDC9"), surfaces, ink, 4.5f)
    val primary = readableColor(rgb(requested.buttons), surfaces, ink, 4.5f)
    val secondary = readableColor(rgb(requested.accent), surfaces, ink, 4.5f)
    val selected = lerp(panel, primary, .14f)
    val errorContainer = rgb(if (light) "FFE1E7" else "49202A")
    return base.copy(
        primary = primary, onPrimary = foreground(primary),
        primaryContainer = selected, onPrimaryContainer = foreground(selected),
        secondary = secondary, onSecondary = foreground(secondary),
        secondaryContainer = selected, onSecondaryContainer = foreground(selected),
        tertiary = secondary, onTertiary = foreground(secondary),
        tertiaryContainer = selected, onTertiaryContainer = foreground(selected),
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = panel, onSurfaceVariant = muted, surfaceTint = primary,
        surfaceContainerLowest = background, surfaceContainerLow = surface,
        surfaceContainer = panel, surfaceContainerHigh = panel, surfaceContainerHighest = panel,
        surfaceDim = panel, surfaceBright = surface,
        outline = muted, outlineVariant = lerp(panel, ink, .25f),
        inverseSurface = ink, inverseOnSurface = paper, inversePrimary = paper,
        error = readableColor(rgb(if (light) "B51D36" else "FFB2BD"), surfaces, ink, 4.5f),
        onError = foreground(readableColor(rgb(if (light) "B51D36" else "FFB2BD"), surfaces, ink, 4.5f)),
        errorContainer = errorContainer, onErrorContainer = foreground(errorContainer)
    )
}

internal data class ThemeSelection(
    val choice: ThemeChoice,
    val custom: ThemeColors? = null,
    val select: (ThemeChoice) -> Unit = {},
    val customize: (ThemeColors?) -> Unit = {}
)
internal val LocalPulsoTheme = staticCompositionLocalOf { ThemeSelection(ThemeChoice.DARK) }

@Suppress("DEPRECATION")
@Composable internal fun ThemeDialogBars() {
    val view = LocalView.current
    val color = MaterialTheme.colorScheme.background
    val light = foreground(color) == Color.Black
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.statusBarColor = color.toArgb()
            window.navigationBarColor = color.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
}

@Composable internal fun PulsoTheme(activity: ComponentActivity, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    val selection = remember(prefs, revision) {
        val choice = ThemeChoice.fromId(prefs.getString("theme", null))
        ThemeSelection(choice, ThemeColors.decode(prefs.getString("custom_${choice.id}", null)),
            select = { prefs.edit().putString("theme", it.id).apply() },
            customize = { colors -> prefs.edit().putString("custom_${choice.id}", colors?.encode()).apply() })
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        if (prefs.getString("theme", null) == "metal") prefs.edit().putString("theme", "dark").apply()
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val colors = pulsoColors(selection.choice, selection.custom)
    SideEffect {
        val dark = foreground(colors.background) == Color.White
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
        )
    }
    CompositionLocalProvider(LocalPulsoTheme provides selection) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

@Composable internal fun ThemePicker() {
    val selected = LocalPulsoTheme.current
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Apariencia", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        ThemeChoice.entries.forEach { choice ->
            val colors = pulsoColors(choice)
            val active = selected.choice == choice
            val shape = RoundedCornerShape(18.dp)
            Row(Modifier.fillMaxWidth().clip(shape).background(colors.surfaceVariant)
                .border(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                .selectable(active, role = Role.RadioButton, onClick = { selected.select(choice) }).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(colors.background),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    listOf(colors.primary, colors.secondary).forEach { color -> Box(Modifier.padding(2.dp).size(18.dp).clip(CircleShape).background(color)) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(choice.title, color = colors.onSurface, fontWeight = FontWeight.Bold)
                    Text(if (active && selected.custom != null) "Personalizado" else choice.detail, color = colors.onSurfaceVariant, fontSize = 12.sp)
                }
                RadioButton(active, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.onSurfaceVariant))
            }
        }
        OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Personalizar colores") }
    }
    if (editing) ThemeEditor(selected, onDismiss = { editing = false })
}

@Composable internal fun PulsoHeader(section: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("PULSO", color = colors.primary, fontSize = 32.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Black)
            Text(section, fontSize = 15.sp, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        PulsoMark(Modifier.size(52.dp).then(LocalPanel.modifier()).pulseLight(18.dp))
    }
}
