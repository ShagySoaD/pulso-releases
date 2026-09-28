package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class SongRemovalTest {
    private val target = Track("one", "Same title", "Band", localUri = "content://media/external/audio/media/10", favorite = true)
    private val other = target.copy(id = "two", localUri = "content://media/external/audio/media/11")

    @Test fun globalRemovalClearsFavoriteDownloadReferenceAndEveryPlaylistButKeepsOtherSongs() {
        val original = LibraryState(listOf(target, other), listOf(Playlist("First", listOf("two", "one")), Playlist("Second", listOf("one", "two", "one"))))
        val removed = PlaylistManagement.forgetTrack(original, target.id)
        assertEquals(listOf(other), removed.tracks)
        assertEquals(listOf(Playlist("First", listOf("two")), Playlist("Second", listOf("two"))), removed.playlists)
        assertEquals(original.tracks[1], removed.tracks.single())
    }

    @Test fun removingLastSongKeepsAnEmptyPlaylistAndRepeatedRemovalIsHarmless() {
        val original = LibraryState(listOf(target), listOf(Playlist("Keep this list", listOf(target.id))))
        val removed = PlaylistManagement.forgetTrack(original, target.id)
        assertTrue(removed.tracks.isEmpty())
        assertEquals(listOf(Playlist("Keep this list", emptyList())), removed.playlists)
        assertEquals(removed, PlaylistManagement.forgetTrack(removed, target.id))
    }

    @Test fun audioMustBeDeletedBeforeTheLibraryEntryIncludingForImportedAudio() {
        listOf(target, target.copy(id = "local-imported")).forEach { track ->
            val calls = mutableListOf<String>()
            SongRemoval.remove(track, { assertEquals(track.localUri, it.localUri); calls += "delete" }, { assertEquals(track.id, it); calls += "forget" })
            assertEquals(listOf("delete", "forget"), calls)
        }
    }

    @Test fun deniedFileDeletionPreservesTheLibraryAndPropagatesTheError() {
        var forgotten = false
        val error = runCatching { SongRemoval.remove(target, { throw SecurityException("Read only") }, { forgotten = true }) }.exceptionOrNull()
        assertTrue(error is SecurityException)
        assertFalse(forgotten)
    }

    @Test fun songWithoutAudioOnlyRemovesItsLibraryEntry() {
        var removed = ""
        SongRemoval.remove(target.copy(localUri = ""), { fail("There is no audio to delete") }, { removed = it })
        assertEquals(target.id, removed)
    }
}
