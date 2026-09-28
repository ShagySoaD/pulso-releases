package app.pulso.music

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

data class ImportedSong(val title: String, val artist: String, val album: String = "", val seconds: Long = 0) {
    val key get() = listOf(title, artist, album, seconds.toString()).joinToString("\u0000")
    fun json() = JSONObject().put("title", title).put("artist", artist).put("album", album).put("seconds", seconds)
}
data class ImportedPlaylist(val name: String, val songs: List<ImportedSong>)
data class PlaylistFile(val playlists: List<ImportedPlaylist>, val skipped: Int = 0) {
    val songs get() = playlists.flatMap { it.songs }.distinctBy { it.key }
}

object PlaylistFiles {
    const val MAX_SONGS = 5000
    private fun value(j: JSONObject, vararg keys: String) = keys.firstNotNullOfOrNull { key ->
        (j.opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() }
    }.orEmpty()
    fun parse(raw: String, filename: String): PlaylistFile {
        val text = raw.removePrefix("\uFEFF").trim()
        require(text.isNotBlank()) { "El archivo está vacío." }
        val result = if (text.startsWith("{") || text.startsWith("[")) json(text, filename.substringBeforeLast('.')) else csv(text, filename.substringBeforeLast('.'))
        return combine(listOf(result))
    }
    fun combine(files: List<PlaylistFile>): PlaylistFile {
        val lists = files.flatMap { it.playlists }.groupBy { it.name }.map { (name, parts) ->
            ImportedPlaylist(name, parts.flatMap { it.songs }.distinctBy { it.key })
        }
        require(lists.isNotEmpty()) { "No se encontraron playlists compatibles." }
        require(lists.size <= 200 && lists.sumOf { it.songs.size } <= MAX_SONGS) { "Importa hasta 200 playlists y 5000 canciones por vez." }
        return PlaylistFile(lists, files.sumOf { it.skipped })
    }
    private fun name(text: String) = text.trim().ifBlank { "Playlist importada" }.take(80)
    private fun json(raw: String, fallback: String): PlaylistFile {
        val root = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
        require(root !is JSONObject || root.optString("format") != "pulso-backup") { "Usa Ajustes → Copia de seguridad → Restaurar para este archivo." }
        val lists = when (root) {
            is JSONObject -> root.optJSONArray("playlists") ?: if (root.has("tracks") || root.has("songs")) JSONArray().put(root) else error("Este JSON no contiene playlists. Selecciona el archivo de playlists de la exportación.")
            is JSONArray -> if (root.optJSONObject(0)?.has("tracks") == true || root.optJSONObject(0)?.has("songs") == true) root else JSONArray().put(JSONObject().put("name", fallback).put("tracks", root))
            else -> error("JSON no compatible.")
        }
        require(lists.length() <= 200) { "El archivo contiene demasiadas playlists." }
        var skipped = 0
        var count = 0
        val parsed = (0 until lists.length()).map { index ->
            val list = lists.getJSONObject(index)
            val entries = list.optJSONArray("tracks") ?: list.optJSONArray("songs") ?: JSONArray()
            count += entries.length()
            require(count <= MAX_SONGS) { "Importa hasta 5000 canciones por vez." }
            val songs = (0 until entries.length()).mapNotNull { i ->
                val item = entries.optJSONObject(i)
                val row = item?.optJSONObject("track") ?: item
                if (row == null) { skipped++; return@mapNotNull null }
                val title = value(row, "trackName", "title", "name")
                val artist = value(row, "artistName", "artist").ifBlank {
                    row.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { k ->
                        a.optJSONObject(k)?.optString("name") ?: (a.opt(k) as? String)
                    }.joinToString(", ") }.orEmpty()
                }
                if (title.isBlank() || artist.isBlank()) { skipped++; return@mapNotNull null }
                require(title.length <= 1000 && artist.length <= 500) { "El archivo contiene textos demasiado largos." }
                ImportedSong(title, artist, value(row, "albumName", "album").ifBlank { row.optJSONObject("album")?.optString("name").orEmpty() }.take(500),
                    (if (row.has("duration_ms")) row.optLong("duration_ms") / 1000 else row.optLong("seconds")).coerceIn(0, 86400))
            }
            ImportedPlaylist(name(value(list, "name", "title").ifBlank { fallback }), songs)
        }
        require(parsed.any { it.songs.isNotEmpty() } || count == 0) { "No hay canciones con título y artista. Los podcasts no se importan." }
        return PlaylistFile(parsed, skipped)
    }
    private fun csv(raw: String, fallback: String): PlaylistFile {
        val firstLine = raw.lineSequence().first()
        val delimiter = listOf(',', ';', '\t').maxBy { separator ->
            var quoted = false
            firstLine.count { c -> if (c == '"') quoted = !quoted; c == separator && !quoted }
        }
        val rows = csvRows(raw, delimiter)
        require(rows.isNotEmpty()) { "CSV vacío." }
        val headers = rows.first().map { normalize(it).replace(" ", "") }
        fun column(vararg aliases: String) = aliases.firstNotNullOfOrNull { alias -> headers.indexOf(alias).takeIf { it >= 0 } } ?: -1
        val title = column("trackname", "tracktitle", "title", "name", "song", "songname", "titulo", "cancion", "nombre")
        val artist = column("artistnames", "artistname", "artists", "artist", "artista", "artistas")
        val album = column("albumname", "album", "disco")
        val playlist = column("playlistname", "playlist", "listadereproduccion", "lista")
        val duration = column("durationms", "durationseconds", "duration", "seconds", "duracion")
        require(title >= 0 && artist >= 0) { "El CSV necesita columnas de título y artista (por ejemplo, Track Name y Artist Name)." }
        val grouped = linkedMapOf<String, MutableList<ImportedSong>>()
        var skipped = 0
        rows.drop(1).forEach { row ->
            fun field(i: Int) = row.getOrNull(i)?.trim().orEmpty()
            if (row.all { it.isBlank() }) return@forEach
            val t = field(title); val a = field(artist)
            if (t.isBlank() || a.isBlank()) { skipped++; return@forEach }
            require(t.length <= 1000 && a.length <= 500) { "El CSV contiene textos demasiado largos." }
            val d = field(duration)
            val seconds = if (d.contains(':') && d.matches(Regex("\\d{1,3}:\\d{2}(:\\d{2})?"))) d.split(':').fold(0L) { acc, part -> acc * 60 + part.toLong() }
                else (d.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { if (headers.getOrNull(duration) == "durationms") it / 1000 else it }?.toLong() ?: 0)
            grouped.getOrPut(name(field(playlist).ifBlank { fallback })) { mutableListOf() }.add(ImportedSong(t, a, field(album).take(500), seconds.coerceIn(0, 86400)))
        }
        require(grouped.isNotEmpty()) { "No hay canciones con título y artista en el CSV." }
        return PlaylistFile(grouped.map { ImportedPlaylist(it.key, it.value) }, skipped)
    }
    internal fun csvRows(raw: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var fields = mutableListOf<String>(); val cell = StringBuilder()
        var quote = false; var closed = false; var i = 0
        fun nextCell() { fields.add(cell.toString()); cell.setLength(0); closed = false }
        fun nextRow() { nextCell(); rows.add(fields); fields = mutableListOf(); require(rows.size <= MAX_SONGS + 2) { "Importa hasta 5000 canciones por vez." } }
        while (i < raw.length) {
            val c = raw[i++]
            if (quote) {
                if (c == '"') { if (raw.getOrNull(i) == '"') { cell.append('"'); i++ } else { quote = false; closed = true } }
                else cell.append(c)
            } else when (c) {
                '"' -> { require(cell.isEmpty() && !closed) { "Comillas inválidas en el CSV." }; quote = true }
                delimiter -> nextCell()
                '\r', '\n' -> { if (c == '\r' && raw.getOrNull(i) == '\n') i++; nextRow() }
                else -> { require(!closed || c.isWhitespace()) { "Separador inválido en el CSV." }; if (!closed) cell.append(c) }
            }
            require(cell.length <= 10000 && fields.size <= 150) { "El CSV contiene una fila demasiado grande." }
        }
        require(!quote) { "El CSV tiene una celda sin cerrar." }
        if (cell.isNotEmpty() || fields.isNotEmpty() || closed) nextRow()
        return rows
    }
    fun normalize(text: String) = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
}

data class SongMatch(val chosen: Track? = null, val suggestions: List<Track> = emptyList(), val error: Boolean = false)
object SongMatching {
    private fun title(text: String) = PlaylistFiles.normalize(text.replace(Regex("(?i)\\b(?:official audio|audio oficial|lyrics|lyric video)\\b"), ""))
    private fun overlap(a: String, b: String): Double {
        val x = a.split(' ').filter { it.isNotBlank() }.toSet(); val y = b.split(' ').filter { it.isNotBlank() }.toSet()
        return (x intersect y).size.toDouble() / (x + y).size.coerceAtLeast(1)
    }
    private fun versions(text: String) = Regex("\\b(live|en vivo|remix|acoustic|acustic[ao]|cover|karaoke|instrumental|sped|slowed|remaster(?:ed)?)\\b")
        .findAll(PlaylistFiles.normalize(text)).map { it.value.replace("remastered", "remaster") }.toSet()
    fun resolve(source: ImportedSong, candidates: List<Track>): SongMatch {
        val wanted = title(source.title)
        val artist = PlaylistFiles.normalize(MusicTitles.artist(source.artist))
        val ranked = candidates.distinctBy { it.id }.filter { it.id.matches(Regex("[A-Za-z0-9_-]{11}")) }.map { track ->
            val t = title(track.title); val a = PlaylistFiles.normalize(MusicTitles.artist(track.artist))
            val score = .7 * overlap(wanted, t) + .3 * overlap(artist, a)
            val duration = source.seconds == 0L || track.seconds == 0L || kotlin.math.abs(source.seconds - track.seconds) <= maxOf(8L, source.seconds / 25)
            val exact = wanted == t && artist == a && duration && versions(source.title) == versions(track.title)
            Triple(track, score, exact)
        }.sortedByDescending { it.second }
        // Auto-accept only a very clear title + artist match; the user reviews versions and partial artist matches.
        val best = ranked.firstOrNull()
        return SongMatch(chosen = best?.takeIf { it.third }?.first, suggestions = ranked.filter { it.second >= .4 }.take(3).map { it.first })
    }
}
