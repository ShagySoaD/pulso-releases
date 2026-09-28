package app.pulso.music

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

class SpotifyPlaylistTest {
    private val id = "37i9dQZF1DXcBWIGoYBM5M"
    private fun fixture() = javaClass.getResource("/spotify-public-playlist.html")!!.readText()
    private fun page(rows: JSONArray, uri: String = "spotify:playlist:$id"): String {
        val entity = JSONObject().put("uri", uri).put("name", "Mi lista").put("trackList", rows)
        val json = JSONObject().put("props", JSONObject().put("pageProps", JSONObject().put("state", JSONObject().put("data", JSONObject().put("entity", entity)))))
        return "<script type='application/json' id='__NEXT_DATA__'>$json</script>"
    }
    private fun song(title: String = "One") = JSONObject().put("uri", "spotify:track:3h5T5JypYU7huFiVYhv1dr")
        .put("title", title).put("subtitle", "Band,\u00a0Guest").put("duration", 210500)

    @Test fun acceptsPlaylistLinksWithLocaleAndSharingParameters() {
        listOf("https://open.spotify.com/playlist/$id?si=abc", "https://open.spotify.com/intl-es/playlist/$id/", "spotify:playlist:$id").forEach {
            assertEquals(id, SpotifyPlaylist.playlistId(it))
        }
    }
    @Test fun rejectsForeignHostsCredentialsPortsAndOtherContent() {
        listOf("http://open.spotify.com/playlist/$id", "https://open.spotify.com.evil.test/playlist/$id", "https://user@open.spotify.com/playlist/$id",
            "https://open.spotify.com:444/playlist/$id", "https://open.spotify.com/album/$id", "https://open.spotify.com/playlist/../x",
            "https://open.spotify.com/playlist/$id/more", "spotify:playlist:bad", "https://example.com/").forEach {
            assertTrue(it, runCatching { SpotifyPlaylist.playlistId(it) }.isFailure)
        }
    }
    @Test fun parsesRealPublicResponseAndAlwaysDisclosesUnverifiedCompleteness() {
        val preview = SpotifyPlaylist.parse(fixture(), id)
        assertEquals(50, preview.entries)
        assertEquals(50, preview.songs.size)
        assertEquals("BbY WOW", preview.songs.first().title)
        assertEquals(225L, preview.songs.first().seconds)
        assertTrue(preview.songs.first().artist.contains("KAROL G"))
        assertTrue(preview.warning.contains("no podemos confirmar", ignoreCase = true))
        assertFalse(preview.file().songs.isEmpty())
    }
    @Test fun preservesUnicodeOrderAndNormalizesArtistSpacing() {
        val preview = SpotifyPlaylist.parse(page(JSONArray().put(song("Canción 🎵")).put(song("Después"))), id)
        assertEquals(listOf("Canción 🎵", "Después"), preview.songs.map { it.title })
        assertEquals("Band, Guest", preview.songs.first().artist)
        assertEquals(210L, preview.songs.first().seconds)
    }
    @Test fun skipsEpisodesAndMissingMetadataWithCount() {
        val rows = JSONArray().put(song()).put(song().put("uri", "spotify:episode:3h5T5JypYU7huFiVYhv1dr")).put(song().put("subtitle", ""))
        val preview = SpotifyPlaylist.parse(page(rows), id)
        assertEquals(3, preview.entries)
        assertEquals(2, preview.skipped)
        assertEquals(1, preview.songs.size)
    }
    @Test fun refusesEmptyPrivateChangedAndMismatchedPages() {
        listOf("<html>Login</html>", "<script id='__NEXT_DATA__'>{}</script>", page(JSONArray()), page(JSONArray().put(song()), "spotify:playlist:AAAAAAAAAAAAAAAAAAAAAA"))
            .forEach { assertTrue(runCatching { SpotifyPlaylist.parse(it, id) }.isFailure) }
    }
    @Test fun hundredEntriesAreNeverLabeledAsComplete() {
        val rows = JSONArray(); repeat(100) { rows.put(song("Song $it")) }
        val preview = SpotifyPlaylist.parse(page(rows), id)
        assertEquals(100, preview.songs.size)
        assertTrue(preview.warning.contains("solo una parte"))
    }
    @Test fun oversizedListFailsWithoutSilentTruncation() {
        val rows = JSONArray(); repeat(5001) { rows.put(song()) }
        assertTrue(runCatching { SpotifyPlaylist.parse(page(rows), id) }.isFailure)
    }
    @Test fun sourceNoticeSurvivesPauseAndDraftRestore() {
        val preview = SpotifyPlaylist.parse(page(JSONArray().put(song())), id)
        val draft = ImportDraft(preview.file(), sourceUrl = preview.url, sourceNotice = preview.warning)
        assertEquals(draft, ImportDraft.parse(draft.json()))
    }
    @Test fun earlierDraftsStillLoad() {
        val draft = ImportDraft(PlaylistFile(listOf(ImportedPlaylist("Old", listOf(ImportedSong("One", "Band"))))))
        val raw = JSONObject(draft.json()).apply { remove("sourceUrl"); remove("sourceNotice") }.toString()
        assertEquals(draft, ImportDraft.parse(raw))
    }
    @Test fun spotifyDraftUsesExistingMatchingAndLibraryMergeWithoutReplacingLists() {
        val preview = SpotifyPlaylist.parse(page(JSONArray().put(song("One"))), id)
        val song = preview.songs.single()
        val track = Track("AAAAAAAAAAA", "One", "Band, Guest", seconds = 210)
        val match = SongMatching.resolve(song, listOf(track))
        assertEquals(track, match.chosen)
        val draft = ImportDraft(preview.file(), mapOf(song.key to match), preview.url, preview.warning)
        val existing = LibraryState(listOf(Track("BBBBBBBBBBB", "Old")), listOf(Playlist("Mi lista", listOf("BBBBBBBBBBB"))))
        val result = BackupFiles.merge(existing, draft.library())
        assertEquals(listOf("BBBBBBBBBBB", "AAAAAAAAAAA"), result.playlists.single().ids)
        assertEquals(result, BackupFiles.merge(result, draft.library()))
    }
    @Test fun fullIndexRejectsTruncationAndCountMismatches() {
        val complete = JSONObject().put("length", 1).put("contents", JSONObject().put("pos", 0).put("truncated", false).put("items", JSONArray().put(song())))
        assertEquals(1, SpotifyPlaylist.parseIndex(complete.toString()).total)
        assertTrue(runCatching { SpotifyPlaylist.parseIndex(complete.put("length", 2).toString()) }.isFailure)
        complete.put("length", 1).getJSONObject("contents").put("truncated", true)
        assertTrue(runCatching { SpotifyPlaylist.parseIndex(complete.toString()) }.isFailure)
    }
    private fun anonymousPage(): String {
        val text = page(JSONArray().put(song()))
        return text.replace("\"state\":{", "\"state\":{\"settings\":{\"session\":{\"isAnonymous\":true,\"accessToken\":\"public-test-session\"}},")
    }
    @Test fun fullImportUsesIndexOrderAndFetchesMissingTrackMetadata() = runBlocking {
        val missing = "spotify:track:AAAAAAAAAAAAAAAAAAAAAA"
        val calls = AtomicInteger(0)
        val result = SpotifyPlaylist.fetchId(id, { url, token -> when {
            url.contains("/embed/playlist/") -> anonymousPage()
            url.contains("spclient") -> {
                assertEquals("public-test-session", token)
                """{"length":2,"contents":{"pos":0,"truncated":false,"items":[{"uri":"$missing"},{"uri":"spotify:track:3h5T5JypYU7huFiVYhv1dr"}]}}"""
            }
            else -> {
                assertNull(token); calls.incrementAndGet()
                page(JSONArray()).replace("\"trackList\":[]", "\"title\":\"Missing\",\"duration\":200000,\"artists\":[{\"name\":\"Other artist\"}]").replace("spotify:playlist:$id", missing)
            }
        } })
        assertTrue(result.completeIndex)
        assertEquals(listOf("Missing", "One"), result.songs.map { it.title })
        assertEquals(1, calls.get())
        assertEquals(0, result.skipped)
        assertTrue(result.warning.contains("todas"))
    }
    @Test fun unavailableIndexFallsBackWithoutClaimingCompleteness() = runBlocking {
        val result = SpotifyPlaylist.fetchId(id, { url, _ -> if (url.contains("/embed/playlist/")) anonymousPage() else error("HTTP 403") })
        assertFalse(result.completeIndex)
        assertEquals(1, result.songs.size)
    }
    @Test fun missingMetadataIsExplicitlyReported() = runBlocking {
        val result = SpotifyPlaylist.fetchId(id, { url, _ -> when {
            url.contains("/embed/playlist/") -> anonymousPage()
            url.contains("spclient") -> """{"length":2,"contents":{"pos":0,"truncated":false,"items":[{"uri":"spotify:track:AAAAAAAAAAAAAAAAAAAAAA"},{"uri":"spotify:track:3h5T5JypYU7huFiVYhv1dr"}]}}"""
            else -> error("unavailable")
        } })
        assertEquals(1, result.skipped)
        assertTrue(result.warning.contains("no se pudieron"))
    }
}
