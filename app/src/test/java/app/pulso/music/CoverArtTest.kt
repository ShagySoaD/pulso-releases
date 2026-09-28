package app.pulso.music

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CoverArtTest {
    @Test fun staleQueueCannotReplaceRecoveredAlbumWithVideoThumbnail() {
        val existing = Track("E0ozmU9cJDg", "Song", artwork = "https://lh3.googleusercontent.com/album=w544-h544")
        val incoming = existing.copy(artwork = "https://i.ytimg.com/vi/E0ozmU9cJDg/hqdefault.jpg")
        assertEquals(existing.artwork, CoverArt.prefer(existing, incoming))
        assertEquals(existing.artwork, CoverArt.prefer(incoming, existing))
        assertEquals(existing.artwork, CoverArt.prefer(existing, existing.copy(artwork = "")))
    }
    @Test fun upgradesSameAssetWithoutChangingIdentityOrCropOptions() {
        assertEquals("https://yt3.googleusercontent.com/band=w600-h250-p", CoverArt.highQuality("https://yt3.googleusercontent.com/band=w1440-h600-p", 600))
        assertEquals("https://lh3.googleusercontent.com/album-id=w1200-h1200-l90-rj", CoverArt.highQuality("https://lh3.googleusercontent.com/album-id=w60-h60-l90-rj"))
        assertEquals("https://yt3.ggpht.com/album-id=s600-c-k", CoverArt.highQuality("https://yt3.ggpht.com/album-id=s120-c-k", 600))
    }
    @Test fun doesNotRewriteUnknownHostsOrVideoThumbnails() {
        listOf("https://example.com/album=w60-h60", "https://googleusercontent.com.evil.test/a=w60-h60", "https://i.ytimg.com/vi/123/hqdefault.jpg", "content://media/2", "").forEach { assertEquals(it, CoverArt.highQuality(it)) }
    }
    @Test fun picksLargestImageEvenWhenArrayIsNotSorted() {
        val images = JSONArray("""[{"url":"https://a/large","width":600,"height":600},{"url":"https://a/small","width":60,"height":60},{"url":"http://a/unsafe","width":2000,"height":2000}]""")
        assertEquals("https://a/large", CoverArt.best(images))
        assertEquals("", CoverArt.best(null))
    }
    @Test fun onlyLegacyRemoteTracksNeedExactCatalogLookup() {
        val track = Track("E0ozmU9cJDg", "Song", artwork = "https://i.ytimg.com/vi/E0ozmU9cJDg/hqdefault.jpg")
        assertTrue(CoverArt.needsCatalogCover(track))
        assertFalse(CoverArt.needsCatalogCover(track.copy(id = "local-123")))
        assertFalse(CoverArt.needsCatalogCover(track.copy(artwork = "https://lh3.googleusercontent.com/album=w60-h60")))
    }
}
