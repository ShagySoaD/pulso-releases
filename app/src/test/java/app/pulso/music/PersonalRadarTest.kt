package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class PersonalRadarTest {
    private fun song(i: Int, artist: String = "Artist $i") = Track("song%07d".format(i), "Song $i", artist)
    @Test fun playlistsWithoutDownloadsOrFavoritesCreateTasteAndSeeds() {
        val tracks = listOf(song(1), song(2), song(3))
        val lists = listOf(Playlist("Rock", listOf(song(2).id)))
        assertEquals(listOf(song(2)), DiscoveryPolicy.tastes(tracks, lists))
        assertEquals(listOf(song(2)), DiscoveryPolicy.seeds(tracks, 0, lists))
        assertTrue(DiscoveryPolicy.tastes(tracks).isEmpty())
    }
    @Test fun playlistEditsAndFavoritesInvalidateRecommendationsButPlaybackMetadataDoesNot() {
        val tracks = listOf(song(1), song(2))
        val lists = listOf(Playlist("Rock", listOf(song(1).id)))
        val key = DiscoveryPolicy.key(tracks, null, lists)
        assertNotEquals(key, DiscoveryPolicy.key(tracks, null, listOf(Playlist("Rock", listOf(song(2).id)))))
        assertNotEquals(key, DiscoveryPolicy.key(tracks, null))
        assertNotEquals(key, DiscoveryPolicy.key(tracks + song(3).copy(favorite = true), null, lists))
        assertEquals(key, DiscoveryPolicy.key(tracks + song(4), null, lists))
    }
    @Test fun manyPlaylistsDoNotExcludeFavoritesAndDownloadsFromSeeds() {
        val tracks = (0..19).map { song(it) } + song(21).copy(favorite = true) + song(22).copy(localUri = "content://audio/22")
        val lists = (0..19).map { Playlist("List $it", listOf(song(it).id)) }
        val seeds = DiscoveryPolicy.seeds(tracks, 0, lists)
        assertTrue(seeds.any { it.id == song(21).id })
        assertTrue(seeds.any { it.id == song(22).id })
        assertTrue(seeds.any { it.id == song(0).id })
        assertTrue(seeds.size <= 10)
        assertNotEquals(seeds, DiscoveryPolicy.seeds(tracks, 7, lists))
    }
    @Test fun radarCombinesRadiosWithUpToTwoHundredUniqueNewSongs() {
        val radios = (0..3).map { seed -> DiscoveryMix(song(1000 + seed), (0..99).map { song(seed * 100 + it, "Band ${it % 30}") }) }
        val radar = DiscoveryPolicy.radar(radios + radios.first(), setOf(song(0).id))!!
        assertEquals("radar", radar.kind)
        assertEquals(200, radar.tracks.size)
        assertEquals(200, radar.tracks.distinctBy { it.id }.size)
        assertFalse(radar.tracks.any { it.id == song(0).id })
        assertTrue(radios.all { mix -> radar.tracks.any { it in mix.tracks } })
        assertTrue(radar.tracks.groupBy { it.artist }.all { it.value.size <= 10 })
    }
    @Test fun radarDoesNotFillWithAlreadySavedSongs() {
        assertNull(DiscoveryPolicy.radar(listOf(DiscoveryMix(song(0), listOf(song(1)))), setOf(song(1).id)))
    }
}
