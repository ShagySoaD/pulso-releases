package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class PlaylistManagementTest {
    private val songs = listOf(Track("AAAAAAAAAAA", "One", "Metallica", seconds = 446, localUri = "content://audio/1", favorite = true), Track("BBBBBBBBBBB", "Canción, \"dos\" 🎵", "Banda", seconds = 200))
    private val state = LibraryState(songs, listOf(Playlist("Metal", listOf(songs[1].id, songs[0].id)), Playlist("Rock", listOf(songs[0].id))))
    @Test fun deletionKeepsDownloadsFavoritesAndOtherPlaylists() {
        val result = PlaylistManagement.delete(state, "Metal")
        assertEquals(songs, result.tracks)
        assertEquals(listOf(state.playlists[1]), result.playlists)
        assertEquals(result, PlaylistManagement.delete(result, "Metal"))
    }
    @Test fun renamePreservesListPositionOrderAndTrackMetadata() {
        val result = PlaylistManagement.rename(state, "Metal", "  Metal favorito  ")
        assertEquals(listOf("Metal favorito", "Rock"), result.playlists.map { it.name })
        assertEquals(state.playlists.first().ids, result.playlists.first().ids)
        assertEquals(state.tracks, result.tracks)
    }
    @Test fun renameNeverMergesOrOverwritesOtherLists() {
        listOf("Rock", "rock", " ROCK ").forEach { name -> assertTrue(runCatching { PlaylistManagement.rename(state, "Metal", name) }.isFailure) }
        assertEquals(2, state.playlists.size)
    }
    @Test fun rejectsBlankLongControlAndMissingNames() {
        listOf(" ", "x".repeat(81), "Metal\nlista").forEach { name -> assertTrue(runCatching { PlaylistManagement.rename(state, "Metal", name) }.isFailure) }
        assertTrue(runCatching { PlaylistManagement.rename(state, "Missing", "New") }.isFailure)
        assertEquals(state, PlaylistManagement.rename(state, "Metal", "Metal"))
    }
    @Test fun sharedFileCanBeImportedWithSameNameOrderAndUnicode() {
        val file = PlaylistFiles.parse(PlaylistManagement.share(state, "Metal"), "Metal.json")
        assertEquals("Metal", file.playlists.single().name)
        assertEquals(listOf(songs[1].title, songs[0].title), file.songs.map { it.title })
        assertEquals(listOf(200L, 446L), file.songs.map { it.seconds })
    }
    @Test fun sharingExcludesPrivateDataPathsAndOtherLists() {
        val raw = PlaylistManagement.share(state, "Metal")
        assertFalse(raw.contains("content://"))
        assertFalse(raw.contains("favorite"))
        assertFalse(raw.contains("Rock"))
        assertFalse(raw.contains("localUri"))
    }
    @Test fun emptyPlaylistsRoundTripAndBrokenReferencesAreReported() {
        val empty = LibraryState(playlists = listOf(Playlist("Empty", emptyList())))
        assertEquals(0, PlaylistFiles.parse(PlaylistManagement.share(empty, "Empty"), "Empty.json").songs.size)
        assertTrue(runCatching { PlaylistManagement.share(state, "Missing") }.isFailure)
        assertTrue(runCatching { PlaylistManagement.share(state.copy(tracks = emptyList()), "Metal") }.isFailure)
    }
    @Test fun filenamesCannotEscapeTheSharedCacheDirectory() {
        val name = PlaylistManagement.filename("../../Music\\secret:track\n🎵")
        assertFalse(name.contains('/')); assertFalse(name.contains('\\')); assertFalse(name.contains(':'))
        assertFalse(name.contains("..")); assertFalse(name.contains('\n'))
        assertTrue(name.endsWith(".json"))
    }
}
