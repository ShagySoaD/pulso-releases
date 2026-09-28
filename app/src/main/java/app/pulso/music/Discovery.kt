package app.pulso.music

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

data class DiscoveryMix(val seed: Track, val tracks: List<Track>, val genre: String = "", val artists: List<ArtistRef> = emptyList(), val kind: String = "radio", val edition: Int = 0) {
    val id get() = "$kind:${seed.id}:$genre"
    val title get() = when (kind) {
        "radar" -> "Descubrimientos para ti"
        "artists" -> "Nuevas conexiones"
        "crossroads" -> "Cruce de caminos"
        "longplay" -> if (genre == "Metal" || genre == "Rock") "Riffs sin prisa" else "Sin mirar el reloj"
        "shortplay" -> "Pequeñas grandes canciones"
        "collection" -> mapOf("Metal" to "Distorsión sin fronteras", "Rock" to "Rutas de rock", "Pop" to "Pop en expansión", "Electrónica" to "Frecuencia electrónica", "Hip hop" to "Rimas y conexiones", "Salsa" to "Clave y sabor", "Reguetón" to "Pulso urbano", "Jazz" to "Rutas de jazz", "R&B" to "Soul en órbita", "Country" to "Raíces y caminos", "Clásica" to "Ecos clásicos")[genre] ?: "Conexiones de ${seed.artist}"
        else -> listOf("Órbita de", "A partir de", "La ruta de", "En sintonía con", "El universo de", "Conexiones de")[Math.floorMod(edition, 6)] + " ${seed.artist}"
    }
    val badge get() = when (kind) {
        "radar" -> "RADAR PULSO"
        "radio" -> listOf("ÓRBITA", "DESCUBRE", "RUTA", "SINTONÍA", "UNIVERSO", "CONEXIONES")[Math.floorMod(edition, 6)]
        "artists" -> "OTRAS VOCES"
        "crossroads" -> "EN COMÚN"
        "longplay" -> "5 MIN O MÁS"
        "shortplay" -> "HASTA 4 MIN"
        else -> "SELECCIÓN"
    }
    val preview get() = tracks.map { it.artist }.distinct().take(3).joinToString(" · ")
}
data class DiscoveryState(
    val mixes: List<DiscoveryMix> = emptyList(), val loading: Boolean = false,
    val message: String = "", val genre: String? = null, val updated: Long = 0
)
data class DiscoverySnapshot(val key: String, val time: Long, val mixes: List<DiscoveryMix>) {
    fun fresh(expected: String, now: Long) = key == expected && now >= time && now - time < 12 * 60 * 60 * 1000L
    fun json() = JSONObject().put("version", 5).put("key", key).put("time", time)
        .put("mixes", JSONArray(mixes.map { JSONObject().put("seed", it.seed.json()).put("tracks", JSONArray(it.tracks.map(Track::json))).put("genre", it.genre).put("kind", it.kind).put("edition", it.edition).put("artists", JSONArray(it.artists.map(ArtistRef::json))) }))
    companion object {
        fun parse(raw: String): DiscoverySnapshot {
            val root = JSONObject(raw)
            require(root.getInt("version") in 3..5)
            val mixes = root.getJSONArray("mixes")
            // Display old cached shelves offline, but regenerate them with the new policy online.
            return DiscoverySnapshot(root.getString("key"), if (root.getInt("version") == 5) root.getLong("time") else 0, (0 until mixes.length()).map { i ->
                val mix = mixes.getJSONObject(i); val songs = mix.getJSONArray("tracks")
                val artists = mix.optJSONArray("artists")
                DiscoveryMix(Track.from(mix.getJSONObject("seed")), (0 until songs.length()).map { Track.from(songs.getJSONObject(it)) }, mix.optString("genre"),
                    (0 until (artists?.length() ?: 0)).map { ArtistRef.from(artists!!.getJSONObject(it)) }, mix.optString("kind", "radio"), mix.optInt("edition", i))
            })
        }
    }
}

/** Only explicit choices are taste signals. Playing a queue stores its metadata, not listening history. */
object DiscoveryPolicy {
    val genres = linkedMapOf(
        "Metal" to "Metallica Master of Puppets", "Rock" to "Foo Fighters Everlong",
        "Pop" to "Dua Lipa Levitating", "Electrónica" to "Daft Punk One More Time",
        "Hip hop" to "Kendrick Lamar Alright", "Salsa" to "Héctor Lavoe El Cantante",
        "Reguetón" to "Daddy Yankee Gasolina", "Jazz" to "John Coltrane Giant Steps",
        "R&B" to "Aretha Franklin Respect", "Country" to "Johnny Cash Ring of Fire",
        "Clásica" to "Arthur Rubinstein Chopin Nocturne Op 9 No 2"
    )
    fun tastes(tracks: List<Track>, playlists: List<Playlist> = emptyList()): List<Track> {
        val selected = playlists.flatMap { it.ids }.toSet()
        return tracks.filter { it.id.matches(Regex("[A-Za-z0-9_-]{11}")) && (it.localUri.isNotBlank() || it.favorite || it.id in selected) }
            .distinctBy { it.id }.sortedBy { it.id }
    }
    fun key(tracks: List<Track>, genre: String?, playlists: List<Playlist> = emptyList()): String {
        val chosen = tastes(tracks, playlists)
        val ids = chosen.map { it.id }.toSet()
        return genre?.let { "genre:$it" } ?: "personal-v2:" + chosen.joinToString(",") { it.id } +
            "|favorites:" + chosen.filter { it.favorite }.joinToString(",") { it.id } +
            "|playlists:" + playlists.map { it.ids.filter { id -> id in ids }.distinct().sorted().joinToString(",") }.distinct().sorted().joinToString(";")
    }
    private fun artist(track: Track) = MusicTitles.artist(track.artist).lowercase(Locale.ROOT).trim()
    fun seeds(tracks: List<Track>, rotation: Long, playlists: List<Playlist> = emptyList()): List<Track> {
        val candidates = tastes(tracks, playlists)
        if (candidates.isEmpty()) return emptyList()
        val playlistGroups = playlists.map { playlist -> val ids = playlist.ids.toSet(); candidates.filter { it.id in ids } }
            .filter { it.isNotEmpty() }.distinctBy { group -> group.map { it.id } }
        val groupOffset = Math.floorMod(rotation, playlistGroups.size.coerceAtLeast(1).toLong()).toInt()
        val selectedGroups = (playlistGroups.drop(groupOffset) + playlistGroups.take(groupOffset)).take(6)
        val groups = (selectedGroups +
            listOf(candidates.filter { it.favorite }, candidates.filter { it.localUri.isNotBlank() }))
            .filter { it.isNotEmpty() }.distinctBy { group -> group.map { it.id } }
            .map { group -> val offset = Math.floorMod(rotation, group.size.toLong()).toInt(); group.drop(offset) + group.take(offset) }
        return (0 until (groups.maxOfOrNull { it.size } ?: 0)).asSequence()
            .flatMap { index -> groups.asSequence().mapNotNull { it.getOrNull(index) } }
            .distinctBy(::artist).take(10).toList()
    }
    fun radar(radios: List<DiscoveryMix>, known: Set<String>): DiscoveryMix? {
        if (radios.isEmpty()) return null
        val pool = (0 until 100).flatMap { index -> radios.mapNotNull { it.tracks.getOrNull(index) } }
            .distinctBy { it.id }.filter { it.id !in known }
        val counts = mutableMapOf<String, Int>()
        val songs = pool.filter { song -> val name = artist(song); val count = counts.getOrDefault(name, 0)
            if (count >= 10) false else { counts[name] = count + 1; true } }.take(200)
        if (songs.isEmpty()) return null
        return DiscoveryMix(radios.first().seed, songs, kind = "radar", artists = radios.flatMap { it.artists }.distinctBy { it.id })
    }
    fun select(seed: Track, candidates: List<Track>, known: Set<String>): List<Track> {
        val distinct = candidates.distinctBy { it.id }.filter { it.id != seed.id && it.id !in known }
        val groups = distinct.groupBy(::artist).entries.sortedBy { if (it.key == artist(seed)) 1 else 0 }
        // Interleave artists so a prolific artist does not occupy the entire first screen.
        return (0..7).flatMap { index -> groups.mapNotNull { it.value.getOrNull(index) } }.take(100)
    }
    fun shelves(radios: List<DiscoveryMix>, library: List<Track>): List<DiscoveryMix> {
        if (radios.isEmpty()) return emptyList()
        val collections = GenreLabels.groups(radios).map { it.copy(kind = "collection") }
        val familiar = library.map(::artist).toSet()
        val newArtists = collections.flatMap { it.tracks }.distinctBy { it.id }.filter { artist(it) !in familiar }.take(90)
        val candidates = mutableListOf<DiscoveryMix>()
        if (newArtists.size >= 8) candidates += radios.first().copy(tracks = newArtists, genre = "", kind = "artists", artists = radios.flatMap { it.artists }.distinctBy { it.id })
        collections.forEach { collection ->
            // Duration is catalog metadata, not an invented mood, tempo or popularity score.
            candidates += collection.copy(kind = "longplay", tracks = collection.tracks.filter { it.seconds >= 300 }.take(36))
            candidates += collection.copy(kind = "shortplay", tracks = collection.tracks.filter { it.seconds in 1..240 }.take(36))
            val peers = radios.filter { it.genre == collection.genre }
            val votes = peers.groupBy { artist(it.seed) }.values.flatMap { group -> group.flatMap { it.tracks }.map(Track::id).distinct() }.groupingBy { it }.eachCount()
            candidates += collection.copy(kind = "crossroads", tracks = collection.tracks.filter { (votes[it.id] ?: 0) >= 2 }.take(40))
        }
        val routes = radios.groupBy { artist(it.seed) }.values.mapIndexed { index, group ->
            group.first().copy(tracks = select(group.first().seed, group.flatMap { it.tracks }, emptySet()), artists = group.flatMap { it.artists }.distinctBy { it.id }, edition = index)
        }
        val result = mutableListOf<DiscoveryMix>()
        fun addUnique(mix: DiscoveryMix, minimum: Int) {
            if (mix.tracks.size < minimum || result.any { similarity(it, mix) >= .9 }) return
            result += mix
        }
        collections.forEach { addUnique(it, 1) }
        candidates.filter { it.tracks.size >= 8 }.take(12).forEach { addUnique(it, 8) }
        routes.forEach { addUnique(it, 1) }
        return result
    }

    private fun similarity(a: DiscoveryMix, b: DiscoveryMix): Double {
        val left = a.tracks.map(Track::id).toSet()
        val right = b.tracks.map(Track::id).toSet()
        return (left intersect right).size.toDouble() / (left + right).size.coerceAtLeast(1)
    }
}

/** Music radio returns related recordings. Accept only explicitly identified catalog audio (ATV). */
object DiscoveryCatalog {
    fun related(id: String): List<Track> = page(id).tracks
    fun page(id: String, continuation: String? = null): CatalogPage {
        require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        val connection = URI("https://music.youtube.com/youtubei/v1/next?prettyPrint=false").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 10_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Origin", "https://music.youtube.com")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0")
            val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20260914.01.00").put("hl", "es").put("gl", "PE")
            val body = JSONObject().put("context", JSONObject().put("client", client).put("user", JSONObject()))
                .put("videoId", id).put("playlistId", "RDAMVM$id").put("isAudioOnly", true).put("params", "wAEB")
            if (continuation != null) body.put("continuation", continuation)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 200) { "Recomendaciones HTTP ${connection.responseCode}" }
            return parsePage(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    fun parse(raw: String): List<Track> = parsePage(raw).tracks
    fun parsePage(raw: String): CatalogPage {
        val root = JSONObject(raw)
        check(!root.has("error"))
        val songs = mutableListOf<Track>()
        fun text(obj: JSONObject?, name: String): String {
            val field = obj?.optJSONObject(name) ?: return ""
            val runs = field.optJSONArray("runs") ?: return field.optString("simpleText")
            return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }.trim()
        }
        fun song(row: JSONObject): Track? {
            val endpoint = row.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint") ?: return null
            val type = endpoint.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")?.optString("musicVideoType")
            if (!type.isNullOrBlank() && type != "MUSIC_VIDEO_TYPE_ATV") return null
            val id = endpoint.optString("videoId")
            val title = text(row, "title")
            val artist = text(row, "shortBylineText")
            if (!id.matches(Regex("[A-Za-z0-9_-]{11}")) || title.isBlank() || artist.isBlank()) return null
            if (Regex("(?i)(official(?:\\s+(?:hd|4k|music))*\\s+video|video\\s+oficial|videoclip)").containsMatchIn(title)) return null
            val images = row.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val art = CoverArt.best(images)
            val duration = text(row, "lengthText").takeIf { it.matches(Regex("\\d{1,3}:\\d{2}(:\\d{2})?")) }
                ?.split(":")?.fold(0L) { total, part -> total * 60 + part.toLong() } ?: 0
            val refs = ArtistRef.runs(row.optJSONObject("longBylineText")?.optJSONArray("runs"))
            return Track(id, title, artist, art, duration, artists = refs)
        }
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val row = value.optJSONObject("playlistPanelVideoRenderer")
                    if (row != null) song(row)?.let(songs::add)
                    else value.keys().forEach { key -> if (key !in setOf("menu", "navigationEndpoint", "trackingParams")) visit(value.opt(key)) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        visit(root.opt("contents"))
        visit(root.opt("continuationContents"))
        val next = (ArtistCatalog.nodes(root.opt("contents"), "nextRadioContinuationData") + ArtistCatalog.nodes(root.opt("continuationContents"), "nextRadioContinuationData"))
            .firstOrNull()?.optString("continuation")?.takeIf { it.isNotBlank() }
        return CatalogPage(songs.distinctBy { it.id }, next)
    }
}

class DiscoveryCache(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "discovery.json"))
    @Synchronized fun read(): DiscoverySnapshot? = runCatching { DiscoverySnapshot.parse(file.readFully().toString(Charsets.UTF_8)) }.getOrNull()
    @Synchronized fun write(snapshot: DiscoverySnapshot) {
        val stream = file.startWrite()
        try { stream.write(snapshot.json().toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
