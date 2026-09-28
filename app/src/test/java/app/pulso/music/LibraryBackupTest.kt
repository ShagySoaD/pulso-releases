package app.pulso.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class LibraryBackupTest {
    private val first = Track("AAAAAAAAAAA", "One", "Metallica", localUri = "content://media/audio/123", favorite = true)
    private val second = Track("BBBBBBBBBBB", "Two", "Band", localUri = "content://media/audio/124")
    private val state = LibraryState(listOf(first, second, Track("CCCCCCCCCCC", "Search result")), listOf(Playlist("Metal", listOf(first.id)), Playlist("Empty", emptyList())))
    private val preferences = PortablePreferences("metal", true, listOf(-2f, 0f, 4f))
    private fun valid() = BackupFiles.create(state, preferences, 1234)
    @Test fun customThemesSurviveBackupAndOldBackupsRemainCompatible() {
        val colors = mapOf("light" to "F6F9FF,EAF1FF,1359C9,245CBD", "dark" to "08090C,1C1D24,FF697D,FFB3BA")
        val custom = preferences.copy(theme = "light", customThemes = colors)
        assertEquals(custom, BackupFiles.parse(BackupFiles.create(state, custom, 1)).preferences)
        val legacy = BackupFiles.parse(editedPayload { it.getJSONObject("preferences").remove("customThemes") })
        assertEquals("dark", legacy.preferences.theme)
        assertTrue(legacy.preferences.customThemes.isEmpty())
        assertTrue(runCatching { BackupFiles.parse(editedPayload {
            it.getJSONObject("preferences").put("customThemes", JSONObject().put("dark", "not-a-color"))
        }) }.isFailure)
    }
    private fun editedPayload(edit: (JSONObject) -> Unit): String {
        val envelope = JSONObject(valid()); val payload = JSONObject(envelope.getString("payload"))
        edit(payload)
        val text = payload.toString()
        return envelope.put("payload", text).put("sha256", MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }).toString()
    }
    @Test fun backupContainsMetadataAndPreferencesWithoutAudioUrisOrSearchHistory() {
        val json = valid(); val backup = BackupFiles.parse(json)
        assertFalse(json.contains("content://"))
        assertEquals(2, backup.library.tracks.size)
        assertTrue(backup.library.tracks.all { it.localUri.isEmpty() })
        assertEquals(state.playlists, backup.library.playlists)
        assertEquals(preferences.copy(theme = "dark"), backup.preferences)
        assertEquals(listOf(first.id, second.id), backup.downloaded)
    }
    @Test fun restorePreservesExistingFilesFavoritesAndOrderAndIsIdempotent() {
        val current = LibraryState(listOf(first), listOf(Playlist("Metal", listOf(first.id))))
        val restored = BackupFiles.restoreLibrary(BackupFiles.parse(valid()))
        val merged = BackupFiles.merge(current, restored)
        assertEquals(first.localUri, merged.tracks.first { it.id == first.id }.localUri)
        assertTrue(merged.tracks.first { it.id == first.id }.favorite)
        assertEquals("", merged.tracks.first { it.id == second.id }.localUri)
        assertEquals(listOf(second.id), merged.playlists.first { it.name == "Descargas del respaldo" }.ids)
        assertEquals(merged, BackupFiles.merge(merged, restored))
    }
    @Test fun backupOnlyInstallRecoversOriginalPlaylistOrderAndFavoriteFlags() {
        val source = state.copy(playlists = listOf(Playlist("Ordered", listOf(second.id, first.id))))
        val restored = BackupFiles.parse(BackupFiles.create(source, preferences, 1))
        val merged = BackupFiles.merge(LibraryState(), restored.library)
        assertEquals(listOf(second.id, first.id), merged.playlists.single().ids)
        assertTrue(merged.tracks.first { it.id == first.id }.favorite)
    }
    @Test fun corruptedAndUnsupportedBackupsAreRejectedBeforeRestoring() {
        assertTrue(runCatching { BackupFiles.parse(JSONObject(valid()).put("sha256", "bad").toString()) }.isFailure)
        assertTrue(runCatching { BackupFiles.parse(JSONObject(valid()).put("version", 99).toString()) }.isFailure)
        assertTrue(runCatching { BackupFiles.parse(valid().take(40)) }.isFailure)
    }
    @Test fun missingTracksInAPlaylistAndBadSettingsAreRejected() {
        assertTrue(runCatching { BackupFiles.parse(editedPayload { it.getJSONArray("playlists").getJSONObject(0).getJSONArray("ids").put("ZZZZZZZZZZZ") }) }.isFailure)
        assertTrue(runCatching { BackupFiles.parse(editedPayload { it.getJSONObject("preferences").put("theme", "bad") }) }.isFailure)
    }
    @Test fun foreignFilePathsAndArbitraryImageHostsAreNeverRestored() {
        val restored = BackupFiles.parse(editedPayload {
            it.getJSONArray("tracks").getJSONObject(0).put("localUri", "content://someone/private").put("artwork", "https://example.com/tracking")
        })
        assertEquals("", restored.library.tracks.first().localUri)
        assertEquals("", restored.library.tracks.first().artwork)
    }
    @Test fun localAudioReferencesSurviveWithoutClaimingToBeDownloaded() {
        val local = Track("local--123", "Local song", "Me", localUri = "content://document/test")
        val backup = BackupFiles.parse(BackupFiles.create(LibraryState(listOf(local), listOf(Playlist("Local", listOf(local.id)))), preferences, 1))
        assertEquals(local.id, backup.library.tracks.single().id)
        assertEquals("", backup.library.tracks.single().localUri)
        assertTrue(backup.downloaded.isEmpty())
    }
    @Test fun importingSameNamedListsMergesRatherThanDeletes() {
        val current = LibraryState(listOf(first), listOf(Playlist("Mix", listOf(first.id))))
        val incoming = LibraryState(listOf(second.copy(localUri = "")), listOf(Playlist("Mix", listOf(second.id))))
        val merged = BackupFiles.merge(current, incoming)
        assertEquals(listOf(first.id, second.id), merged.playlists.single().ids)
        assertEquals(current.tracks.single(), merged.tracks.first())
    }
}
