package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class YouTubeInputTest {
    @Test fun acceptsMusicPlaylistsAndSearches() {
        assertEquals("ytsearch20:Daft Punk", YouTubeInput.target(" Daft Punk "))
        val playlist = "https://music.youtube.com/playlist?list=PLexample"
        assertEquals(playlist, YouTubeInput.target(playlist))
        assertEquals("https://youtu.be/jNQXAC9IVRw", YouTubeInput.target("https://youtu.be/jNQXAC9IVRw"))
    }
    @Test fun rejectsOptionsAndImpersonatedHosts() {
        listOf("--exec=anything", "", "http://youtube.com/watch?v=x", "https://youtube.com.evil.test/", "https://youtube.com@evil.test/").forEach { input ->
            assertTrue("Must reject $input", runCatching { YouTubeInput.target(input) }.isFailure)
        }
    }
}
