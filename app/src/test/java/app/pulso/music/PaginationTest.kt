package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class PaginationTest {
    private fun fixture() = javaClass.getResource("/music-continuation.json")!!.readText()
    @Test fun readsContinuationResultsAndNextToken() {
        val page = MusicCatalog.parsePage(fixture())
        assertEquals(3, page.tracks.size)
        assertEquals("next-page", page.next)
    }
    @Test fun filteredPageKeepsContinuationForFurtherSongs() {
        val page = MusicCatalog.parsePage(fixture().replace("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV"))
        assertTrue(page.tracks.isEmpty())
        assertEquals("next-page", page.next)
    }
    @Test fun readsModernContinuationWithoutPickingMenuTokens() {
        val page = MusicCatalog.parsePage("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-modern"}}}}]}}]}""")
        assertEquals("next-modern", page.next)
    }
    @Test fun endOfResultsHasNoToken() {
        assertNull(MusicCatalog.parsePage("""{"continuationContents":{"musicShelfContinuation":{"contents":[]}}}""").next)
    }
    @Test fun excludesPromotionalVideoTitlesEvenIfMarkedAsAudio() {
        val fixture = javaClass.getResource("/music-songs.json")!!.readText()
        val changed = fixture.replace("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", "Get Lucky Official HD Video")
        assertFalse(MusicCatalog.parse(changed).any { it.id == "4D7u5KF7SP8" })
    }
}
