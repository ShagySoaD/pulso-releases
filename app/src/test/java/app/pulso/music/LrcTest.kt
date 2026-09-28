package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class LrcTest {
    @Test fun repeatedTimestampsAndPrecision() {
        val lines = Lrc.parse("[ar:Example]\n[00:01.2][00:03.250]Estribillo\n[00:02.05]Otra línea")
        assertEquals(listOf(1200L, 2050L, 3250L), lines.map { it.millis })
        assertEquals(-1, Lrc.active(lines, 500))
        assertEquals(1, Lrc.active(lines, 2050))
        assertEquals("Estribillo", lines.last().text)
    }
    @Test fun plainLyricsHaveNoFakeTimestamps() { assertTrue(Lrc.parse("Una canción\nsin tiempos").isEmpty()) }
}
