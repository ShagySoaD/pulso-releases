package app.pulso.music

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicitly opt-in: ./gradlew testReleaseUnitTest -PliveSpotify=true --tests '*SpotifyLiveTest'. */
class SpotifyLiveTest {
    @Test(timeout = 360000) fun readsPublicPlaylistAndChecksCatalogMatches() = runBlocking {
        assumeTrue(System.getProperty("pulso.liveSpotify") == "true")
        val preview = SpotifyPlaylist.fetch("https://open.spotify.com/playlist/3Knqbc1yVX5kPEJoPkM9Xc") { done, total ->
            if (done % 40 == 0 || done == total) println("Spotify metadata: $done/$total")
        }
        val folder = File("build/live-spotify").apply { mkdirs() }
        File(folder, "playlist.json").writeText(JSONObject().put("playlists", JSONArray().put(JSONObject().put("name", preview.name)
            .put("songs", JSONArray(preview.songs.map(ImportedSong::json))))).toString(2))
        val results = linkedMapOf<String, SongMatch>()
        val started = System.nanoTime()
        var failures = 0
        val canonical = PlaylistFiles.combine(listOf(preview.file()))
        val samples = canonical.songs.take(5) + canonical.songs.takeLast(5)
        for (song in samples) {
            if (System.nanoTime() - started > 180_000_000_000L || failures >= 3) break
            val match = try {
                SongMatching.resolve(song, MusicCatalog.search("${song.title} ${song.artist}")).also { failures = 0 }
            } catch (_: Exception) { failures++; SongMatch(error = true) }
            results[song.key] = match
            if (results.size % 25 == 0) println("Catalog checked: ${results.size}/${preview.file().songs.size}")
            Thread.sleep(300)
        }
        val draft = ImportDraft(canonical, results, preview.url, preview.warning)
        assertEquals(draft, ImportDraft.parse(draft.json()))
        File(folder, "draft.json").writeText(draft.json())
        val report = JSONObject().put("name", preview.name).put("entries", preview.entries).put("read", preview.songs.size)
            .put("completeIndex", preview.completeIndex).put("skipped", preview.skipped).put("notice", preview.warning)
            .put("unique", preview.file().songs.size).put("queried", results.size).put("automatic", results.values.count { it.chosen != null })
            .put("review", results.values.count { it.chosen == null && it.suggestions.isNotEmpty() })
            .put("unmatched", results.values.count { it.chosen == null && it.suggestions.isEmpty() && !it.error })
            .put("errors", results.values.count { it.error })
        File(folder, "summary.json").writeText(report.toString(2))
        println(report.toString())
        // Write diagnostics before strict live assertions: the provider can omit entries transiently.
        assertTrue(preview.completeIndex)
        assertTrue(preview.entries > 100)
        assertEquals(0, preview.skipped)
        assertEquals(preview.entries, preview.songs.size)
        assertTrue("No successful catalog queries", results.values.any { !it.error })
    }
}
