package app.pulso.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    private fun fixture() = javaClass.getResource("/music-radio.json")!!.readText()
    private fun song(id: Int, artist: String = "Artist", favorite: Boolean = false, local: String = "") =
        Track("AAAAAAAAA%02d".format(id), "Song $id", artist, favorite = favorite, localUri = local)
    @Test fun parsesRealMusicRadioWithArtistsAndDuration() {
        val songs = DiscoveryCatalog.parse(fixture())
        assertEquals(10, songs.size)
        assertEquals("Master of Puppets", songs[0].title)
        assertEquals("Megadeth", songs[2].artist)
        assertEquals(332L, songs[1].seconds)
        assertTrue(songs[1].artwork.startsWith("https://"))
    }
    @Test fun rejectsIdentifiedVideosButKeepsAudioWithMissingType() {
        val root = JSONObject(fixture())
        val rows = root.getJSONObject("contents").getJSONObject("playlistPanelRenderer").getJSONArray("contents")
        fun row(i: Int) = rows.getJSONObject(i).getJSONObject("playlistPanelVideoRenderer")
        row(0).getJSONObject("navigationEndpoint").getJSONObject("watchEndpoint")
            .getJSONObject("watchEndpointMusicSupportedConfigs").getJSONObject("watchEndpointMusicConfig").put("musicVideoType", "MUSIC_VIDEO_TYPE_OMV")
        row(1).getJSONObject("navigationEndpoint").getJSONObject("watchEndpoint").remove("watchEndpointMusicSupportedConfigs")
        row(2).getJSONObject("title").getJSONArray("runs").getJSONObject(0).put("text", "Song Official HD Video")
        val songs = DiscoveryCatalog.parse(root.toString())
        assertEquals(8, songs.size)
        assertTrue(songs.any { it.id == row(1).getJSONObject("navigationEndpoint").getJSONObject("watchEndpoint").getString("videoId") })
    }
    @Test fun ignoresActionMenusAndDuplicateRows() {
        val root = JSONObject(fixture())
        val rows = root.getJSONObject("contents").getJSONObject("playlistPanelRenderer").getJSONArray("contents")
        val duplicate = JSONObject(rows.getJSONObject(0).toString())
        rows.put(duplicate)
        val hidden = JSONObject(duplicate.toString())
        hidden.getJSONObject("playlistPanelVideoRenderer").getJSONObject("navigationEndpoint").getJSONObject("watchEndpoint").put("videoId", "xxxxxxxxxxx")
        root.getJSONObject("contents").put("menu", hidden)
        assertEquals(10, DiscoveryCatalog.parse(root.toString()).size)
        assertFalse(DiscoveryCatalog.parse(root.toString()).any { it.id == "xxxxxxxxxxx" })
    }
    @Test fun favoritesAndDownloadsInfluenceTasteButSearchHistoryDoesNot() {
        val input = listOf(song(0), song(1, favorite = true), song(2, local = "content://audio/2"), song(3).copy(id = "local-123", favorite = true))
        assertEquals(listOf(song(1).id, song(2).id), DiscoveryPolicy.tastes(input).map { it.id })
        assertEquals(DiscoveryPolicy.key(input, null), DiscoveryPolicy.key(input.reversed() + song(4), null))
        assertTrue(DiscoveryPolicy.seeds(listOf(song(0)), 0).isEmpty())
    }
    @Test fun seedsRotateAcrossArtistsAndNeverRepeatAnArtist() {
        val input = listOf(song(0, "Metallica", true), song(1, "Metallica - Topic", true), song(2, "Megadeth", true), song(3, "Dio", true), song(4, "Slayer", true)).map { it.copy(localUri = "content://audio/${it.id}") }
        val first = DiscoveryPolicy.seeds(input, 0)
        assertEquals(4, first.size)
        assertEquals(listOf("Metallica", "Megadeth", "Dio", "Slayer"), first.map { it.artist })
        assertNotEquals(first, DiscoveryPolicy.seeds(input, 3))
    }
    @Test fun prioritizesOtherArtistsExcludesKnownAndLimitsRepetition() {
        val seed = song(0, "Metallica", true)
        val candidates = (0..10).map { song(it, "Metallica") } + (11..15).map { song(it, "Megadeth") } + song(16, "Dio") + song(16, "Dio")
        val result = DiscoveryPolicy.select(seed, candidates, setOf(song(11).id))
        assertEquals("Megadeth", result.first().artist)
        assertEquals("Dio", result[1].artist)
        assertFalse(result.any { it.id == seed.id || it.id == song(11).id })
        assertEquals(result.size, result.distinctBy { it.id }.size)
        assertTrue(result.groupBy { it.artist }.all { it.value.size <= 8 })
        assertTrue(result.count { it.artist == "Metallica" } > 4)
    }
    @Test fun cachePreservesMixesAndExpiresOrRejectsDifferentTaste() {
        val mix = DiscoveryMix(song(0), listOf(song(1), song(2)))
        val cache = DiscoverySnapshot("taste:a", 10_000, listOf(mix))
        assertEquals(cache, DiscoverySnapshot.parse(cache.json().toString()))
        assertTrue(cache.fresh("taste:a", 10_001))
        assertFalse(cache.fresh("taste:b", 10_001))
        assertFalse(cache.fresh("taste:a", 0))
        assertFalse(cache.fresh("taste:a", 43_210_000))
    }
}
