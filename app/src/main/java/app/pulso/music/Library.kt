package app.pulso.music

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Track(val id: String, val title: String, val artist: String = "YouTube", val artwork: String = "", val seconds: Long = 0, val localUri: String = "", val favorite: Boolean = false, val artists: List<ArtistRef> = emptyList()) {
    fun json() = JSONObject().put("id", id).put("title", title).put("artist", artist).put("artwork", artwork).put("seconds", seconds).put("localUri", localUri).put("favorite", favorite).put("artists", JSONArray(artists.map(ArtistRef::json)))
    companion object {
        fun from(j: JSONObject): Track {
            val artists = j.optJSONArray("artists")
            return Track(j.getString("id"), j.optString("title"), j.optString("artist", "YouTube"), j.optString("artwork"), j.optLong("seconds"), j.optString("localUri"), j.optBoolean("favorite"),
                (0 until (artists?.length() ?: 0)).map { ArtistRef.from(artists!!.getJSONObject(it)) })
        }
    }
}
data class Playlist(val name: String, val ids: List<String>)
data class LibraryState(val tracks: List<Track> = emptyList(), val playlists: List<Playlist> = emptyList())

object Library {
    private lateinit var file: AtomicFile
    private val mutable = MutableStateFlow(LibraryState())
    val state = mutable.asStateFlow()
    fun init(context: Context) {
        file = AtomicFile(File(context.filesDir, "library.json"))
        if (file.baseFile.exists()) runCatching {
            val root = JSONObject(file.readFully().toString(Charsets.UTF_8))
            val songs = root.optJSONArray("tracks") ?: JSONArray()
            val lists = root.optJSONArray("playlists") ?: JSONArray()
            mutable.value = LibraryState((0 until songs.length()).map { Track.from(songs.getJSONObject(it)) },
                (0 until lists.length()).map { i -> val p = lists.getJSONObject(i); val ids = p.getJSONArray("ids"); Playlist(p.getString("name"), (0 until ids.length()).map { ids.getString(it) }) })
        }.onFailure { file.baseFile.copyTo(File(context.filesDir, "library-recovery-${System.currentTimeMillis()}.json")) }
    }
    @Synchronized fun save(track: Track) {
        val old = mutable.value.tracks.find { it.id == track.id }
        val merged = if (old == null) track else track.copy(localUri = track.localUri.ifBlank { old.localUri }, favorite = old.favorite, artwork = CoverArt.prefer(old, track), artists = track.artists.ifEmpty { old.artists })
        commit(mutable.value.copy(tracks = mutable.value.tracks.filterNot { it.id == track.id } + merged))
    }
    @Synchronized fun saveAll(tracks: List<Track>) {
        val saved = mutable.value.tracks.associateBy { it.id }.toMutableMap()
        tracks.forEach { track ->
            val old = saved[track.id]
            saved[track.id] = if (old == null) track else track.copy(localUri = track.localUri.ifBlank { old.localUri }, favorite = old.favorite, artwork = CoverArt.prefer(old, track), artists = track.artists.ifEmpty { old.artists })
        }
        commit(mutable.value.copy(tracks = saved.values.toList()))
    }
    @Synchronized fun favorite(track: Track) {
        val old = mutable.value.tracks.find { it.id == track.id } ?: track
        commit(mutable.value.copy(tracks = mutable.value.tracks.filterNot { it.id == track.id } + old.copy(favorite = !old.favorite)))
    }
    @Synchronized fun playlist(name: String, tracks: List<Track>) {
        require(name.isNotBlank())
        saveAll(tracks)
        val old = mutable.value.playlists.find { it.name == name }
        val next = Playlist(name.take(80), ((old?.ids ?: emptyList()) + tracks.map { it.id }).distinct())
        commit(mutable.value.copy(playlists = mutable.value.playlists.filterNot { it.name == next.name } + next))
    }
    fun track(id: String) = state.value.tracks.find { it.id == id }
    @Synchronized fun renamePlaylist(old: String, name: String) {
        commit(PlaylistManagement.rename(mutable.value, old, name))
    }
    @Synchronized fun deletePlaylist(name: String) {
        commit(PlaylistManagement.delete(mutable.value, name))
    }
    @Synchronized fun merge(incoming: LibraryState) {
        commit(BackupFiles.merge(mutable.value, incoming))
    }
    @Synchronized fun updateArtists(id: String, artists: List<ArtistRef>) {
        if (artists.isEmpty() || mutable.value.tracks.none { it.id == id }) return
        commit(mutable.value.copy(tracks = mutable.value.tracks.map { if (it.id == id) it.copy(artists = artists) else it }))
    }
    @Synchronized fun updateArtwork(id: String, expected: String, artwork: String) {
        val old = mutable.value.tracks.find { it.id == id } ?: return
        if (old.artwork != expected || artwork.isBlank() || artwork == expected) return
        commit(mutable.value.copy(tracks = mutable.value.tracks.map { if (it.id == id) it.copy(artwork = artwork) else it }))
    }
    @Synchronized fun clearDownload(id: String, expectedUri: String) {
        commit(mutable.value.copy(tracks = mutable.value.tracks.map {
            if (it.id == id && it.localUri == expectedUri) it.copy(localUri = "") else it
        }))
    }
    @Synchronized fun forget(id: String) {
        commit(PlaylistManagement.forgetTrack(mutable.value, id))
    }
    private fun commit(next: LibraryState) {
        val root = JSONObject().put("tracks", JSONArray(next.tracks.map { it.json() }))
            .put("playlists", JSONArray(next.playlists.map { JSONObject().put("name", it.name).put("ids", JSONArray(it.ids)) }))
        val stream = file.startWrite()
        try { stream.write(root.toString().toByteArray()); file.finishWrite(stream); mutable.value = next }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

object YouTubeInput {
    fun target(input: String): String {
        val query = input.trim()
        require(query.isNotEmpty() && query.length <= 1000 && !query.startsWith("-")) { "Escribe una canción o un enlace de YouTube." }
        if (!query.contains("://")) return "ytsearch20:$query"
        val uri = java.net.URI(query)
        require(uri.scheme.equals("https", true) && uri.host?.lowercase() in setOf("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com", "youtu.be")) { "Usa un enlace HTTPS de YouTube o YouTube Music." }
        return query
    }
}
