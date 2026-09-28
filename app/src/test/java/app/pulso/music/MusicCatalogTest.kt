package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class MusicCatalogTest {
    private fun fixture() = javaClass.getResource("/music-songs.json")!!.readText()
    @Test fun parsesActualSongMetadata() {
        val tracks = MusicCatalog.parse(fixture())
        assertEquals(3, tracks.size)
        assertEquals("4D7u5KF7SP8", tracks.first().id)
        assertEquals("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", tracks.first().title)
        assertEquals("Daft Punk, Pharrell Williams, Nile Rodgers", tracks.first().artist)
        assertEquals(370L, tracks.first().seconds)
    }
    @Test fun excludesVideosAndUnknownTypes() {
        assertTrue(MusicCatalog.parse(fixture().replace("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV")).isEmpty())
        assertTrue(MusicCatalog.parse(fixture().replace("MUSIC_VIDEO_TYPE_ATV", "UNKNOWN")).isEmpty())
    }
    @Test fun ignoresSuggestionsOutsideResults() {
        assertTrue(MusicCatalog.parse(fixture().replace("\"contents\"", "\"suggestions\"")).isEmpty())
    }
    @Test fun handlesEmptySearch() { assertTrue(MusicCatalog.parse("{\"contents\":{}}").isEmpty()) }
    @Test fun cleansPromotionalLabelsWithoutErasingVersions() {
        assertEquals("Artist - Song", MusicTitles.clean("Artist - Song (Official Music Video) [HD]"))
        assertEquals("Song (Live at Wembley)", MusicTitles.clean("Song (Live at Wembley)"))
        assertEquals("Song (Remix)", MusicTitles.clean("Song (Remix) [Official Audio]"))
        assertEquals("Artist", MusicTitles.artist("Artist - Topic"))
    }
}
