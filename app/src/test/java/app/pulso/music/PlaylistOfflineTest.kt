package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class PlaylistOfflineTest {
    private val remote = Track("remote", "Pending")
    private val saved = Track("saved", "Saved", localUri = "content://media/external/audio/media/1", favorite = true)
    private val imported = Track("local-1", "Imported", localUri = "content://documents/1")
    @Test fun downloadSkipsSavedImportedAndDuplicateSongs() {
        assertEquals(listOf(remote), PlaylistOffline.toDownload(listOf(saved, imported, remote, remote)))
    }
    @Test fun downloadSkipsAlreadyQueuedSongs() {
        assertTrue(PlaylistOffline.toDownload(listOf(remote), setOf(remote.id)).isEmpty())
    }
    @Test fun deletionOnlyTargetsDownloadedCopiesOnce() {
        assertEquals(listOf(saved), PlaylistOffline.toDelete(listOf(remote, imported, saved, saved)))
        assertTrue(saved.favorite)
    }
    @Test fun emptyPlaylistHasNoWork() {
        assertTrue(PlaylistOffline.toDownload(emptyList()).isEmpty())
        assertTrue(PlaylistOffline.toDelete(emptyList()).isEmpty())
    }
}
