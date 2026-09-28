package app.pulso.music

import org.junit.Assert.*
import org.junit.Test

class PlaylistFilesTest {
    @Test fun explicitSongColumnsTakePriorityOverGenericNames() {
        val song = PlaylistFiles.parse("Name,Track Name,Artist Name\nPlaylist label,One,Metallica", "test.csv").songs.single()
        assertEquals("One", song.title)
    }
    @Test fun matchesRealCatalogAudioAndLeavesAlternativeArtistsForReview() {
        val candidates = MusicCatalog.parse(javaClass.getResource("/music-songs.json")!!.readText())
        val track = candidates.first()
        val source = ImportedSong(track.title, track.artist.replace(", ", ";"), seconds = track.seconds)
        assertEquals(track.id, SongMatching.resolve(source, candidates).chosen?.id)
        assertNull(SongMatching.resolve(source.copy(artist = "Another band"), candidates).chosen)
    }
    @Test fun spotifyExportsKeepNamesOrderAndIgnoreEpisodes() {
        val raw = """{"playlists":[{"name":"Metal","items":[],"tracks":[{"track":{"trackName":"One","artistName":"Metallica","albumName":"Justice"}},{"episode":{"episodeName":"Podcast"}},{"track":{"trackName":"Painkiller","artistName":"Judas Priest"}}]},{"name":"Vacía","tracks":[]}]}"""
        val file = PlaylistFiles.parse(raw, "Playlist1.json")
        assertEquals(listOf("Metal", "Vacía"), file.playlists.map { it.name })
        assertEquals(listOf("One", "Painkiller"), file.playlists[0].songs.map { it.title })
        assertEquals("Justice", file.songs.first().album)
        assertEquals(1, file.skipped)
    }
    @Test fun combinesSpotifyPartsAndRemovesRepeatedRows() {
        val first = PlaylistFiles.parse("""{"playlists":[{"name":"Mix","tracks":[{"track":{"trackName":"One","artistName":"Metallica"}}]}]}""", "one.json")
        assertEquals(1, PlaylistFiles.combine(listOf(first, first)).songs.size)
    }
    @Test fun csvSupportsQuotedCommasEscapedQuotesNewlinesAndMilliseconds() {
        val raw = "Track Name,Artist Name(s),Album Name,Duration (ms),Playlist Name\r\n\"Song, \\\"quoted\\\"\",Band,Album,210000,Mix".replace("\\\"", "\"\"")
        val song = PlaylistFiles.parse(raw, "test.csv").songs.single()
        assertEquals("Song, \"quoted\"", song.title)
        assertEquals(210L, song.seconds)
        assertEquals(listOf(listOf("a", "b"), listOf("line\none", "two")), PlaylistFiles.csvRows("a,b\n\"line\none\",two", ','))
    }
    @Test fun semicolonSpanishHeadersAndUtf8Bom() {
        val file = PlaylistFiles.parse("\uFEFFTítulo;Artista;Duración;Lista\nCanción;Banda;4:05;Rock", "music.csv")
        assertEquals("Rock", file.playlists.single().name)
        assertEquals(245L, file.songs.single().seconds)
    }
    @Test fun customJsonSupportsArtistArraysAndDuration() {
        val song = PlaylistFiles.parse("""[{"name":"One","artists":[{"name":"Metallica"}],"album":{"name":"Justice"},"duration_ms":440000}]""", "Metal.json").songs.single()
        assertEquals("Metallica", song.artist)
        assertEquals("Justice", song.album)
        assertEquals(440L, song.seconds)
    }
    @Test fun rejectsWrongFilesAndMalformedCsv() {
        listOf("{\"format\":\"pulso-backup\"}", "{\"profile\":{}}", "x,y\na,b", "title,artist\n\"unfinished,Band").forEach { raw ->
            assertTrue(runCatching { PlaylistFiles.parse(raw, "file.csv") }.isFailure)
        }
    }
    @Test fun enforcesBatchLimitsWithoutSilentTruncation() {
        val raw = "title,artist\n" + (0..PlaylistFiles.MAX_SONGS).joinToString("\n") { "Song $it,Artist" }
        assertTrue(runCatching { PlaylistFiles.parse(raw, "large.csv") }.isFailure)
    }
    @Test fun exactMatchesAcceptAccentAndPunctuationDifferences() {
        val source = ImportedSong("Canción!", "Artista", seconds = 200)
        val track = Track("AAAAAAAAAAA", "Cancion", "Artista", seconds = 202)
        assertEquals(track, SongMatching.resolve(source, listOf(track)).chosen)
    }
    @Test fun wrongArtistsLiveVersionsAndDurationDifferencesNeedReview() {
        val source = ImportedSong("One", "Metallica", seconds = 440)
        listOf(Track("AAAAAAAAAAA", "One", "U2", seconds = 440), Track("BBBBBBBBBBB", "One (Live)", "Metallica", seconds = 440), Track("CCCCCCCCCCC", "One", "Metallica", seconds = 270)).forEach { track ->
            assertNull(SongMatching.resolve(source, listOf(track)).chosen)
        }
    }
    @Test fun importDraftRoundTripsManualChoicesAndKeepsPlaylistOrder() {
        val first = ImportedSong("First", "Band"); val second = ImportedSong("Second", "Band")
        val track1 = Track("AAAAAAAAAAA", "First", "Band"); val track2 = Track("BBBBBBBBBBB", "Second", "Band")
        val draft = ImportDraft(PlaylistFile(listOf(ImportedPlaylist("Mix", listOf(first, second)))), mapOf(second.key to SongMatch(track2), first.key to SongMatch(track1)))
        assertEquals(draft, ImportDraft.parse(draft.json()))
        assertEquals(listOf(track1.id, track2.id), draft.library().playlists.single().ids)
    }
}
