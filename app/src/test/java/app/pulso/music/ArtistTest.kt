package app.pulso.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtistTest {
    private val ref = ArtistRef("UCGexNm_Kw4rdQjLxmpb2EKw", "Metallica")
    private fun fixture() = javaClass.getResource("/artist-metallica.json")!!.readText()
    @Test fun readsActualArtistPhotoPopularSongsAndRelatedArtists() {
        val profile = ArtistCatalog.parse(fixture(), ref)
        assertEquals("Metallica", profile.artist.name)
        assertTrue(profile.artist.image.startsWith("https://"))
        assertEquals("Metal", profile.genre)
        assertEquals(5, profile.songs.size)
        assertEquals("Nothing Else Matters", profile.songs.first().title)
        assertTrue(profile.counts.values.any { it.contains("reproducciones") })
        assertTrue(profile.related.isNotEmpty())
        assertTrue(profile.related.all { ArtistRef.valid(it.id) && it.id != ref.id })
        assertTrue(profile.songsBrowse.startsWith("VL"))
    }
    @Test fun popularSongsKeepCanonicalArtistIdentity() {
        val profile = ArtistCatalog.parse(fixture(), ref)
        assertTrue(profile.songs.all { it.artists.first().id == ref.id })
        assertEquals(ref.id, Track.from(profile.songs.first().json()).artists.first().id)
    }
    @Test fun artistCacheRoundTripPreservesEndpointsAndRelatedArtists() {
        val profile = ArtistCatalog.parse(fixture(), ref)
        assertEquals(profile, ArtistProfile.from(profile.json()))
    }
    @Test fun doesNotTurnAlbumsOrVideosIntoArtistsAndSongs() {
        val raw = fixture().replace("MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_ALBUM").replace("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV")
        val profile = ArtistCatalog.parse(raw, ref)
        assertTrue(profile.related.isEmpty())
        assertTrue(profile.songs.isEmpty())
    }
    @Test fun moreSongsUsesRealAudioCatalogPage() {
        val page = MusicCatalog.parsePage(javaClass.getResource("/artist-songs.json")!!.readText())
        assertTrue(page.tracks.size >= 20)
        assertEquals("Nothing Else Matters", page.tracks.first().title)
        assertTrue(page.tracks.all { it.artists.any { a -> a.id == ref.id } })
    }
    @Test fun genreFamiliesAreConservativeAndOnlyReadOpeningDescription() {
        assertEquals("Metal", GenreLabels.infer("Es una banda de thrash metal y hard rock."))
        assertEquals("Pop", GenreLabels.infer("A pop singer and songwriter."))
        assertEquals("", GenreLabels.infer("Artista sin descripción musical disponible."))
        assertEquals("", GenreLabels.infer("x".repeat(330) + " metal band"))
    }
    @Test fun sameGenreCombinesMixesWithoutDuplicatesAndKeepsArtists() {
        val a = Track("AAAAAAAAAAA", "A", "First")
        val b = Track("BBBBBBBBBBB", "B", "Second")
        val c = Track("CCCCCCCCCCC", "C", "Third")
        val result = GenreLabels.groups(listOf(DiscoveryMix(a, listOf(b, c), "Metal", listOf(ref)), DiscoveryMix(b, listOf(c, a), "Metal", listOf(ref)), DiscoveryMix(c, listOf(a), "Pop")))
        assertEquals(2, result.size)
        assertEquals(listOf(b, c, a), result[0].tracks)
        assertEquals(listOf(ref), result[0].artists)
        assertEquals("Distorsión sin fronteras", result[0].copy(kind = "collection").title)
        val sameArtist = (0..5).map { Track("track$it", "Song $it", "Band") }
        val combined = GenreLabels.groups(listOf(DiscoveryMix(a, sameArtist.take(3), "Metal"), DiscoveryMix(b, sameArtist.drop(3), "Metal")))
        assertEquals(6, combined.single().tracks.size)
    }
    @Test fun oldLibrariesRemainReadableWithoutArtistIds() {
        val old = Track.from(JSONObject("""{"id":"AAAAAAAAAAA","title":"Song","favorite":true,"localUri":"content://audio/1"}"""))
        assertTrue(old.artists.isEmpty())
        assertTrue(old.favorite)
        assertEquals("content://audio/1", old.localUri)
        assertFalse(ArtistRef.valid("../outside"))
    }
}
