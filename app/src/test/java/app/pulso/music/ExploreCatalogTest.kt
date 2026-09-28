package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class ExploreCatalogTest {
    private fun fixture(name: String) = javaClass.getResource("/$name.json")!!.readText()
    @Test fun communitySearchHasRealTitlesAuthorsAndContinuation() {
        val page = ExploreCatalog.parse(fixture("community-search"), SearchCategory.PLAYLISTS)
        assertTrue(page.playlists.size >= 10)
        assertTrue(page.playlists.all { it.id.startsWith("VL") && it.title.isNotBlank() && it.artwork.isNotBlank() })
        assertTrue(page.playlists.any { it.author.isNotBlank() })
        assertNotNull(page.next)
        val more = ExploreCatalog.parse(fixture("community-search-more"), SearchCategory.PLAYLISTS)
        assertTrue(more.playlists.isNotEmpty())
        assertTrue(more.playlists.any { p -> page.playlists.none { it.id == p.id } })
    }
    @Test fun artistSearchOpensValidArtistIds() {
        val page = ExploreCatalog.parse(fixture("artist-search"), SearchCategory.ARTISTS)
        assertTrue(page.artists.any { it.name.equals("Metallica", true) })
        assertTrue(page.artists.all { ArtistRef.valid(it.id) })
        assertTrue(page.playlists.isEmpty())
    }
    @Test fun communityPlaylistPreservesSongsAcrossPagesIncludingVideoRecordings() {
        val page = ExploreCatalog.parsePlaylist(fixture("community-playlist"))
        val more = ExploreCatalog.parsePlaylist(fixture("community-playlist-more"))
        assertTrue(page.songs.size >= 20)
        assertNotNull(page.next)
        assertTrue(more.songs.isNotEmpty())
        assertTrue(more.songs.any { song -> page.songs.none { it.id == song.id } })
    }
    @Test fun endOfPlaylistDoesNotLoadUnrelatedRecommendations() {
        val page = ExploreCatalog.parsePlaylist(fixture("community-playlist-short"))
        assertEquals(16, page.songs.size)
        assertNull(page.next)
    }
    @Test fun missingTypeCanStillBeMusicAndPlaylistCanContainVideoVersions() {
        val raw = fixture("music-songs")
        assertEquals(MusicCatalog.parse(raw).size, MusicCatalog.parse(raw.replace("MUSIC_VIDEO_TYPE_ATV", "")).size)
        assertEquals(MusicCatalog.parse(raw).size, MusicCatalog.parsePage(raw.replace("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV"), true).tracks.size)
    }
    @Test fun explicitTracksAreNotHidden() {
        val root = org.json.JSONObject(fixture("music-songs"))
        var marked = 0
        fun mark(value: Any?) {
            when (value) {
                is org.json.JSONObject -> {
                    value.optJSONObject("musicResponsiveListItemRenderer")?.let { row ->
                        row.put("badges", org.json.JSONArray("[{\"musicInlineBadgeRenderer\":{\"icon\":{\"iconType\":\"MUSIC_EXPLICIT_BADGE\"}}}]")); marked++
                    }
                    value.keys().forEach { mark(value.opt(it)) }
                }
                is org.json.JSONArray -> (0 until value.length()).forEach { mark(value.opt(it)) }
            }
        }
        mark(root)
        assertTrue(marked > 0)
        assertEquals(MusicCatalog.parse(fixture("music-songs")), MusicCatalog.parse(root.toString()))
    }
    @Test fun serverFailureIsNotReportedAsEmptySearch() {
        try { ExploreCatalog.parse("{\"error\":{\"code\":503}}", SearchCategory.PLAYLISTS); fail() }
        catch (_: IllegalStateException) { }
    }
    @Test fun unrelatedNavigationDoesNotBecomeSearchResult() {
        val raw = fixture("community-search").replace("\"contents\"", "\"suggestions\"")
        assertTrue(ExploreCatalog.parse(raw, SearchCategory.PLAYLISTS).playlists.isEmpty())
    }
}
