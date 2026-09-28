package app.pulso.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** Opaque RGB values only; the same compact format is used in preferences and backups. */
internal data class ThemeColors(val background: String, val cards: String, val buttons: String, val accent: String) {
    fun encode() = listOf(background, cards, buttons, accent).joinToString(",")
    companion object {
        fun decode(raw: String?): ThemeColors? {
            val parts = raw?.split(',') ?: return null
            if (parts.size != 4 || parts.any { !it.matches(Regex("[0-9a-fA-F]{6}")) }) return null
            return ThemeColors(parts[0].uppercase(), parts[1].uppercase(), parts[2].uppercase(), parts[3].uppercase())
        }
        fun defaults(choice: ThemeChoice) = if (choice == ThemeChoice.LIGHT)
            ThemeColors("F6F9FF", "EAF1FF", "1359C9", "245CBD")
        else ThemeColors("08090C", "1C1D24", "FF697D", "FFB3BA")
    }
}

internal fun rgb(hex: String) = Color(0xFF000000L or hex.toLong(16))
internal fun Color.hex() = "%06X".format(toArgb() and 0xFFFFFF)
internal fun colorContrast(a: Color, b: Color): Float =
    (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
internal fun foreground(background: Color) =
    if (colorContrast(Color.Black, background) >= colorContrast(Color.White, background)) Color.Black else Color.White

/** Shared foregrounds are used throughout the app, so surfaces stay within their base theme. */
internal fun readableColor(requested: Color, against: List<Color>, toward: Color, minimum: Float): Color {
    for (step in 0..100) {
        val candidate = lerp(requested, toward, step / 100f)
        if (against.all { colorContrast(candidate, it) >= minimum }) return candidate
    }
    return toward
}
