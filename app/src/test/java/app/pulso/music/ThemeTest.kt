package app.pulso.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance(); val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }
    @Test fun savedChoicesRestoreAndUnknownValuesFallback() {
        ThemeChoice.entries.forEach { assertEquals(it, ThemeChoice.fromId(it.id)) }
        assertEquals(ThemeChoice.DARK, ThemeChoice.fromId(null))
        assertEquals(ThemeChoice.DARK, ThemeChoice.fromId("invalid"))
        assertEquals(ThemeChoice.DARK, ThemeChoice.fromId("metal"))
        assertEquals(listOf("light", "dark"), ThemeChoice.entries.map { it.id })
    }

    @Test fun customColorsRoundTripAndMalformedValuesAreRejected() {
        val colors = ThemeColors("F6F9FF", "EAF1FF", "1359C9", "245CBD")
        assertEquals(colors, ThemeColors.decode(colors.encode().lowercase()))
        listOf(null, "", "FFFFFF", "FFFFFF,000000,FF0000,GGGGGG", "00FFFFFF,000000,FF0000,00FF00").forEach {
            assertNull(ThemeColors.decode(it))
        }
    }

    @Test fun extremeAndMixedColorsRemainReadableOnBothBases() {
        val random = kotlin.random.Random(78)
        val values = listOf(
            ThemeColors("FFFFFF", "000000", "FFFFFF", "000000"),
            ThemeColors("000000", "FFFFFF", "000000", "FFFFFF"),
            ThemeColors("777777", "888888", "777777", "888888")
        ) + List(100) {
            fun hex() = "%06X".format(random.nextInt(0x1000000))
            ThemeColors(hex(), hex(), hex(), hex())
        }
        ThemeChoice.entries.forEach { base ->
            values.forEach { requested ->
                val c = pulsoColors(base, requested)
                listOf(c.background, c.surface, c.surfaceVariant).forEach { bg ->
                    listOf(c.onSurface, c.onSurfaceVariant, c.primary, c.secondary, c.error).forEach { ink ->
                        assertTrue("$base $requested: text contrast", contrast(ink, bg) >= 4.5f)
                    }
                }
                assertTrue(contrast(c.primary, c.onPrimary) >= 4.5f)
                assertTrue(contrast(c.secondary, c.onSecondary) >= 4.5f)
                assertTrue(contrast(c.secondaryContainer, c.onSecondaryContainer) >= 4.5f)
            }
        }
    }
    @Test fun allThemesKeepReadableTextAndControls() {
        ThemeChoice.entries.forEach { theme ->
            val c = pulsoColors(theme)
            listOf(c.background, c.surface, c.surfaceVariant).forEach { surface ->
                assertTrue("$theme body text", contrast(c.onSurface, surface) >= 4.5f)
                assertTrue("$theme secondary text", contrast(c.onSurfaceVariant, surface) >= 4.5f)
                assertTrue("$theme accent text", contrast(c.primary, surface) >= 4.5f)
            }
            assertTrue("$theme button", contrast(c.onPrimary, c.primary) >= 4.5f)
            assertTrue("$theme navigation", contrast(c.onSecondaryContainer, c.secondaryContainer) >= 4.5f)
            assertTrue("$theme error", contrast(c.onErrorContainer, c.errorContainer) >= 4.5f)
        }
    }
}
