package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class EditorialTest {
    private val seed = Track("E0ozmU9cJDg", "Master of Puppets", "Metallica", localUri = "content://audio/1")
    @Test fun aSmallPoolDoesNotBecomeSeveralIdenticalPlaylists() {
        val songs = (0 until 25).map { Track("song$it", "Song $it", "Artist $it") }
        val shelves = DiscoveryPolicy.shelves(listOf(DiscoveryMix(seed, songs, "Metal")), listOf(seed))
        assertEquals(1, shelves.size)
        val ids = shelves.map { mix -> mix.tracks.map(Track::id).toSet() }
        assertEquals(ids.size, ids.distinct().size)
        val partial = DiscoverySnapshot("downloads", 0, shelves)
        assertEquals(shelves, DiscoverySnapshot.parse(partial.json().toString()).mixes)
        assertFalse(partial.fresh("downloads", System.currentTimeMillis()))
    }
    @Test fun readsRealRadioContinuationWithoutVideoFallback() {
        val raw = javaClass.getResource("/music-radio-more.json")!!.readText()
        val page = DiscoveryCatalog.parsePage(raw)
        assertTrue(page.tracks.isNotEmpty())
        assertEquals(page.tracks.size, page.tracks.distinctBy { it.id }.size)
        assertTrue(DiscoveryCatalog.parse(raw.replace("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV")).isEmpty())
    }
    @Test fun largerCollectionsRemainDiverseAndHaveDifferentStableIds() {
        val songs = (0 until 120).map { Track("song$it", "Song $it", "Artist ${it % 35}") }
        val radios = listOf(DiscoveryMix(seed, songs.take(60), "Metal"), DiscoveryMix(seed.copy(id = "CHIWNDAwTqQ", artist = "Megadeth"), songs.drop(50).take(60), "Metal"))
        val shelves = DiscoveryPolicy.shelves(radios, listOf(seed))
        val collection = shelves.first()
        assertEquals("Distorsión sin fronteras", collection.title)
        assertTrue(collection.tracks.size > 40)
        assertTrue(collection.tracks.size <= 100)
        assertTrue(collection.tracks.groupBy { it.artist }.all { it.value.size <= 6 })
        assertEquals(shelves.size, shelves.distinctBy { it.id }.size)
    }
    @Test fun favoriteOnlyLibraryCanGeneratePersonalSeeds() {
        assertEquals(1, DiscoveryPolicy.seeds(listOf(seed.copy(localUri = "", favorite = true)), 0).size)
        assertEquals(listOf(seed), DiscoveryPolicy.seeds(listOf(seed, seed.copy(id = "CHIWNDAwTqQ", localUri = "")), 0))
    }
    @Test fun cachePreservesEditorialKindsAndSourceTracks() {
        val mix = DiscoveryMix(seed, listOf(seed.copy(id = "CHIWNDAwTqQ")), "Metal", kind = "collection")
        val snapshot = DiscoverySnapshot("downloads", 20, listOf(mix))
        assertEquals(snapshot, DiscoverySnapshot.parse(snapshot.json().toString()))
        assertNotEquals(mix.id, mix.copy(kind = "radio").id)
    }
}
