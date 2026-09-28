package app.pulso.music

import org.json.JSONArray
import org.json.JSONObject

/** Playlist edits change membership metadata only, never tracks, favorites or audio files. */
object PlaylistManagement {
    fun nameError(playlists: List<Playlist>, old: String, proposed: String): String? {
        val name = proposed.trim()
        return when {
            name.isBlank() -> "Escribe un nombre."
            name.length > 80 -> "Usa hasta 80 caracteres."
            name.any { it.isISOControl() } -> "El nombre no puede contener saltos de línea ni caracteres de control."
            playlists.any { it.name != old && it.name.equals(name, ignoreCase = true) } -> "Ya existe una playlist con ese nombre."
            else -> null
        }
    }
    fun rename(state: LibraryState, old: String, proposed: String): LibraryState {
        require(state.playlists.any { it.name == old }) { "La playlist ya no existe." }
        val error = nameError(state.playlists, old, proposed)
        require(error == null) { error.orEmpty() }
        return state.copy(playlists = state.playlists.map { if (it.name == old) it.copy(name = proposed.trim()) else it })
    }
    fun delete(state: LibraryState, name: String) = state.copy(playlists = state.playlists.filterNot { it.name == name })
    /** Explicit global song deletion; empty playlists remain available. */
    fun forgetTrack(state: LibraryState, id: String) = state.copy(
        tracks = state.tracks.filterNot { it.id == id },
        playlists = state.playlists.map { it.copy(ids = it.ids.filterNot { songId -> songId == id }) }
    )
    fun share(state: LibraryState, name: String): String {
        val playlist = state.playlists.firstOrNull { it.name == name } ?: error("La playlist ya no existe.")
        val byId = state.tracks.associateBy { it.id }
        require(playlist.ids.all { it in byId }) { "Faltan datos de canciones en esta playlist." }
        val songs = playlist.ids.distinct().map { id ->
            val track = byId.getValue(id)
            // Portable metadata only: no device paths, favorite flags or private settings.
            JSONObject().put("title", track.title).put("artist", track.artist).put("seconds", track.seconds)
        }
        return JSONObject().put("format", "pulso-playlist").put("version", 1)
            .put("playlists", JSONArray().put(JSONObject().put("name", playlist.name).put("songs", JSONArray(songs)))).toString(2)
    }
    fun filename(name: String) = name.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(60).ifBlank { "Playlist" } + ".json"
}
