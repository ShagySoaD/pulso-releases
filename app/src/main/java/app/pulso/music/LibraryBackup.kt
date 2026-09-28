package app.pulso.music

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

data class PortablePreferences(val theme: String = "dark", val equalizer: Boolean = false, val bands: List<Float> = emptyList(), val customThemes: Map<String, String> = emptyMap())
data class LibraryBackup(val library: LibraryState, val preferences: PortablePreferences, val downloaded: List<String> = emptyList(), val created: Long = 0)

object BackupFiles {
    const val MAX_BYTES = 16 * 1048576
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun create(library: LibraryState, preferences: PortablePreferences, now: Long): String {
        val wanted = library.playlists.flatMap { it.ids }.toSet() + library.tracks.filter { it.favorite || it.localUri.isNotBlank() }.map { it.id }
        val songs = library.tracks.filter { it.id in wanted }.map { it.copy(localUri = "") }
        val payload = JSONObject().put("created", now)
            .put("tracks", JSONArray(songs.map { it.json().apply { remove("localUri") } }))
            .put("playlists", JSONArray(library.playlists.map { JSONObject().put("name", it.name).put("ids", JSONArray(it.ids)) }))
            .put("downloaded", JSONArray(library.tracks.filter { it.localUri.isNotBlank() && !it.id.startsWith("local-") }.map { it.id }))
            .put("preferences", JSONObject().put("theme", preferences.theme).put("equalizer", preferences.equalizer).put("bands", JSONArray(preferences.bands)).put("customThemes", JSONObject(preferences.customThemes)))
            .toString()
        // Hash the exact payload text, independent of JSON property ordering or numeric serialization.
        val encoded = JSONObject().put("format", "pulso-backup").put("version", 1).put("sha256", sha(payload)).put("payload", payload).toString(2)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "El respaldo supera el límite de 16 MB." }
        return encoded
    }
    private fun artwork(raw: String): String {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return ""
        val host = uri.host?.lowercase().orEmpty()
        return raw.takeIf { uri.scheme == "https" && uri.userInfo == null && listOf("googleusercontent.com", "ggpht.com", "ytimg.com", "youtube.com").any { host == it || host.endsWith(".$it") } }.orEmpty()
    }
    fun parse(raw: String): LibraryBackup {
        val envelope = JSONObject(raw.removePrefix("\uFEFF"))
        require(envelope.optString("format") == "pulso-backup") { "Selecciona un respaldo creado por PULSO." }
        require(envelope.optInt("version") == 1) { "Este respaldo requiere otra versión de PULSO." }
        val payload = envelope.getString("payload")
        require(sha(payload) == envelope.getString("sha256")) { "El respaldo está incompleto o dañado. No se cambió tu biblioteca." }
        val root = JSONObject(payload)
        val tracks = root.getJSONArray("tracks"); val lists = root.getJSONArray("playlists")
        require(tracks.length() <= 50000 && lists.length() <= 5000) { "El respaldo es demasiado grande." }
        val songs = (0 until tracks.length()).map { index ->
            val track = Track.from(tracks.getJSONObject(index))
            require(track.id.matches(Regex("[A-Za-z0-9_-]{11}|local-[A-Za-z0-9_-]{1,100}")) && track.title.isNotBlank() && track.title.length <= 1000 && track.artist.length <= 500 && track.seconds in 0..86400) { "El respaldo contiene una canción inválida." }
            require(track.artists.size <= 50) { "Demasiados artistas en una canción." }
            track.copy(localUri = "", artwork = artwork(track.artwork), artists = track.artists.filter { ArtistRef.valid(it.id) && it.name.length <= 500 }.map { it.copy(image = artwork(it.image)) })
        }
        require(songs.distinctBy { it.id }.size == songs.size) { "El respaldo contiene identificadores repetidos." }
        val ids = songs.map { it.id }.toSet()
        var references = 0
        val playlists = (0 until lists.length()).map { index ->
            val list = lists.getJSONObject(index); val name = list.getString("name"); val entries = list.getJSONArray("ids")
            references += entries.length()
            require(name.isNotBlank() && name.length <= 80 && references <= 200000) { "Playlist inválida en el respaldo." }
            val saved = (0 until entries.length()).map { entries.getString(it) }
            require(saved.all { it in ids }) { "El respaldo contiene playlists incompletas." }
            Playlist(name, saved.distinct())
        }
        require(playlists.map { it.name }.distinct().size == playlists.size) { "El respaldo contiene playlists repetidas." }
        val preferences = root.getJSONObject("preferences")
        val theme = preferences.getString("theme")
        require(theme in setOf("light", "dark", "metal")) { "Tema inválido en el respaldo." }
        val custom = preferences.optJSONObject("customThemes") ?: JSONObject()
        require(!preferences.has("customThemes") || preferences.opt("customThemes") is JSONObject) { "Colores inválidos en el respaldo." }
        val customThemes = custom.keys().asSequence().associateWith { key ->
            require(key in setOf("light", "dark")) { "Tema personalizado inválido." }
            val colors = ThemeColors.decode(custom.getString(key))
            require(colors != null) { "Colores inválidos en el respaldo." }
            colors.encode()
        }
        val bands = preferences.getJSONArray("bands")
        require(bands.length() <= 32) { "Ecualizador inválido en el respaldo." }
        val levels = (0 until bands.length()).map { bands.getDouble(it).toFloat() }
        require(levels.all { it.isFinite() && it in -24f..24f }) { "Ecualizador inválido en el respaldo." }
        val downloads = root.optJSONArray("downloaded") ?: JSONArray()
        val downloaded = (0 until downloads.length()).map { downloads.getString(it) }
        require(downloaded.all { it in ids && !it.startsWith("local-") }) { "Descargas inválidas en el respaldo." }
        return LibraryBackup(LibraryState(songs, playlists), PortablePreferences(ThemeChoice.fromId(theme).id, preferences.getBoolean("equalizer"), levels, customThemes), downloaded.distinct(), root.optLong("created"))
    }
    fun merge(current: LibraryState, incoming: LibraryState): LibraryState {
        val songs = current.tracks.associateBy { it.id }.toMutableMap()
        incoming.tracks.forEach { track ->
            val old = songs[track.id]
            songs[track.id] = if (old == null) track.copy(localUri = "") else old.copy(favorite = old.favorite || track.favorite,
                artwork = old.artwork.ifBlank { track.artwork }, artists = old.artists.ifEmpty { track.artists })
        }
        val lists = current.playlists.associateBy { it.name }.toMutableMap()
        incoming.playlists.forEach { list -> lists[list.name] = list.copy(ids = (lists[list.name]?.ids.orEmpty() + list.ids).distinct()) }
        return LibraryState(songs.values.toList(), lists.values.toList())
    }
    fun restoreLibrary(backup: LibraryBackup): LibraryState {
        val covered = backup.library.playlists.flatMap { it.ids }.toSet() + backup.library.tracks.filter { it.favorite }.map { it.id }
        val orphans = backup.downloaded.filter { it !in covered }
        if (orphans.isEmpty()) return backup.library
        var name = "Descargas del respaldo"
        var suffix = 2
        while (backup.library.playlists.any { it.name == name }) name = "Descargas del respaldo (${suffix++})"
        return backup.library.copy(playlists = backup.library.playlists + Playlist(name, orphans))
    }
}
