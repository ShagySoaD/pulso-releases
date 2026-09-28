package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class DiscoveryVarietyTest {
    private fun seed(i: Int) = Track("AAAAAAAAA%02d".format(i), "Download $i", "Band $i", localUri = "content://music/$i")
    @Test fun tenDistinctArtistsCombineDownloadsAndFavorites() {
        val downloads = (0..9).map(::seed)
        val selected = DiscoveryPolicy.seeds(downloads + seed(11).copy(localUri = "", favorite = true), 0)
        assertEquals(10, selected.size)
        assertEquals(10, selected.map { it.artist }.distinct().size)
        assertTrue(selected.any { it.favorite })
        assertTrue(selected.filterNot { it.favorite }.all { it in downloads })
        assertNotEquals(selected, DiscoveryPolicy.seeds(downloads, 3))
    }
    @Test fun repeatedDownloadsOfOneArtistLeaveRoomForRelatedArtists() {
        val downloads = (0..9).map { seed(it).copy(artist = "Metallica") }
        assertEquals(1, DiscoveryPolicy.seeds(downloads, 0).size)
    }
    @Test fun playlistsHaveDistinctCriteriaAndUseOnlyAnchoredSongs() {
        val songs = (0 until 120).map { Track("song$it", "Song $it", "Artist ${it % 40}", seconds = if (it % 2 == 0) 360 else 200) }
        val radios = listOf(DiscoveryMix(seed(0), songs.take(60), "Metal"), DiscoveryMix(seed(1), songs.drop(30).take(60), "Metal"), DiscoveryMix(seed(2), songs.drop(60), "Metal"))
        val shelves = DiscoveryPolicy.shelves(radios, (0..2).map(::seed))
        assertTrue(shelves.size >= 5)
        assertTrue(shelves.any { it.kind == "longplay" })
        assertTrue(shelves.any { it.kind == "shortplay" })
        assertTrue(shelves.filter { it.kind == "longplay" }.flatMap { it.tracks }.all { it.seconds >= 300 })
        assertTrue(shelves.filter { it.kind == "shortplay" }.flatMap { it.tracks }.all { it.seconds in 1..240 })
        assertTrue(shelves.flatMap { it.tracks }.all { it in songs })
        assertEquals(shelves.size, shelves.map { it.id }.distinct().size)
        shelves.forEachIndexed { i, first -> shelves.drop(i + 1).forEach { second ->
            val a = first.tracks.map(Track::id).toSet(); val b = second.tracks.map(Track::id).toSet()
            assertTrue((a intersect b).size.toDouble() / (a + b).size < .82)
        } }
    }
    @Test fun missingDurationsNeverProduceTimeBasedPlaylists() {
        val songs = (0 until 25).map { Track("song$it", "Song $it", "Artist $it") }
        val shelves = DiscoveryPolicy.shelves(listOf(DiscoveryMix(seed(0), songs)), listOf(seed(0)))
        assertFalse(shelves.any { it.kind in setOf("shortplay", "longplay") })
    }
    @Test fun routeNamesAreVariedAndSurviveCaching() {
        val mixes = (0 until 6).map { DiscoveryMix(seed(it), listOf(seed(10)), edition = it) }
        assertEquals(6, mixes.map { it.badge }.distinct().size)
        assertEquals(1, mixes.count { it.title.startsWith("Órbita") })
        val cached = DiscoverySnapshot("test", 1000, mixes)
        assertEquals(cached, DiscoverySnapshot.parse(cached.json().toString()))
    }
    @Test fun olderCacheRemainsAvailableOfflineButNeedsRefresh() {
        val cache = DiscoverySnapshot("test", System.currentTimeMillis(), listOf(DiscoveryMix(seed(0), listOf(seed(1)))))
        val older = cache.json().put("version", 3)
        val restored = DiscoverySnapshot.parse(older.toString())
        assertEquals(cache.mixes, restored.mixes)
        assertFalse(restored.fresh("test", System.currentTimeMillis()))
    }
}
