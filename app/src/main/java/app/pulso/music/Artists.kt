package app.pulso.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

data class ArtistRef(val id: String, val name: String, val image: String = "") {
    fun json() = JSONObject().put("id", id).put("name", name).put("image", image)
    companion object {
        fun from(j: JSONObject) = ArtistRef(j.getString("id"), j.getString("name"), j.optString("image"))
        fun runs(array: JSONArray?): List<ArtistRef> = (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val run = array?.optJSONObject(i) ?: return@mapNotNull null
            val endpoint = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return@mapNotNull null
            if (endpoint.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType") != "MUSIC_PAGE_TYPE_ARTIST") return@mapNotNull null
            val id = endpoint.optString("browseId"); val name = run.optString("text")
            if (valid(id) && name.isNotBlank()) ArtistRef(id, name) else null
        }.distinctBy { it.id }
        fun valid(id: String) = id.matches(Regex("UC[A-Za-z0-9_-]{22}"))
    }
}

data class ArtistProfile(val artist: ArtistRef, val genre: String, val songs: List<Track>, val related: List<ArtistRef>,
    val counts: Map<String, String> = emptyMap(), val songsBrowse: String = "", val songsParams: String = "") {
    fun json() = JSONObject().put("artist", artist.json()).put("genre", genre).put("songs", JSONArray(songs.map(Track::json)))
        .put("related", JSONArray(related.map(ArtistRef::json))).put("counts", JSONObject(counts)).put("songsBrowse", songsBrowse).put("songsParams", songsParams)
    companion object {
        fun from(j: JSONObject): ArtistProfile {
            val songs = j.getJSONArray("songs"); val related = j.getJSONArray("related"); val counts = j.optJSONObject("counts") ?: JSONObject()
            return ArtistProfile(ArtistRef.from(j.getJSONObject("artist")), j.optString("genre"), (0 until songs.length()).map { Track.from(songs.getJSONObject(it)) },
                (0 until related.length()).map { ArtistRef.from(related.getJSONObject(it)) }, counts.keys().asSequence().associateWith { counts.optString(it) }, j.optString("songsBrowse"), j.optString("songsParams"))
        }
    }
}
data class ArtistScreenState(val open: Boolean = false, val loading: Boolean = false, val title: String = "Artista", val profile: ArtistProfile? = null,
    val error: String = "", val loadingMore: Boolean = false, val moreLoaded: Boolean = false, val next: String? = null)

object CatalogTransport {
    fun post(endpoint: String, body: JSONObject): String {
        require(endpoint in setOf("browse", "next"))
        val connection = URI("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 10_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Origin", "https://music.youtube.com")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20260914.01.00").put("hl", "es").put("gl", "PE")
            body.put("context", JSONObject().put("client", client).put("user", JSONObject()))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 200) { "Catálogo HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}

object ArtistCatalog {
    internal fun text(j: JSONObject?, key: String): String {
        val value = j?.optJSONObject(key) ?: return ""
        val runs = value.optJSONArray("runs") ?: return value.optString("simpleText")
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }.trim()
    }
    internal fun nodes(value: Any?, key: String): List<JSONObject> {
        val found = mutableListOf<JSONObject>()
        fun visit(v: Any?) {
            when (v) {
                is JSONObject -> v.optJSONObject(key)?.let(found::add) ?: v.keys().forEach { if (it !in setOf("menu", "navigationEndpoint", "subscriptionButton")) visit(v.opt(it)) }
                is JSONArray -> (0 until v.length()).forEach { visit(v.opt(it)) }
            }
        }
        visit(value); return found
    }
    fun parse(raw: String, ref: ArtistRef): ArtistProfile {
        val root = JSONObject(raw); check(!root.has("error"))
        val header = root.optJSONObject("header")
        val h = header?.optJSONObject("musicImmersiveHeaderRenderer") ?: header?.optJSONObject("musicVisualHeaderRenderer") ?: header?.optJSONObject("musicHeaderRenderer")
        val name = text(h, "title").ifBlank { ref.name }
        val image = nodes(h, "musicThumbnailRenderer").map { CoverArt.best(it.optJSONObject("thumbnail")?.optJSONArray("thumbnails")) }.firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { ref.image }
        val description = nodes(root.opt("contents"), "musicDescriptionShelfRenderer").firstOrNull()?.let { text(it, "description") }.orEmpty()
        val songs = MusicCatalog.parse(raw)
        val counts = nodes(root.opt("contents"), "musicResponsiveListItemRenderer").mapNotNull { row ->
            val id = row.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
            val columns = row.optJSONArray("flexColumns")
            val count = (0 until (columns?.length() ?: 0)).map { text(columns?.optJSONObject(it)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer"), "text") }
                .firstOrNull { it.contains(Regex("(?i)(reproducciones|plays|views|visualizaciones)")) }
            if (id.isNotBlank() && count != null) id to count else null
        }.toMap()
        val related = nodes(root.opt("contents"), "musicTwoRowItemRenderer").mapNotNull { row ->
            val ep = row.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val id = ep?.optString("browseId").orEmpty()
            val type = ep?.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
            val title = text(row, "title")
            if (type != "MUSIC_PAGE_TYPE_ARTIST" || !ArtistRef.valid(id) || title.isBlank()) null else {
                val art = row.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ArtistRef(id, title, CoverArt.best(art))
            }
        }.distinctBy { it.id }.filterNot { it.id == ref.id }
        val shelf = nodes(root.opt("contents"), "musicShelfRenderer").firstOrNull { it.optJSONArray("contents")?.toString()?.contains("musicResponsiveListItemRenderer") == true }
        val ep = shelf?.optJSONObject("bottomEndpoint")?.optJSONObject("browseEndpoint")
        check(h != null || songs.isNotEmpty()) { "Ficha no disponible" }
        return ArtistProfile(ref.copy(name = name, image = image), GenreLabels.infer(description), songs, related, counts, ep?.optString("browseId").orEmpty(), ep?.optString("params").orEmpty())
    }
    fun page(id: String, params: String, continuation: String? = null): CatalogPage {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,160}")))
        val body = if (continuation != null) JSONObject().put("continuation", continuation) else JSONObject().put("browseId", id).put("params", params)
        return MusicCatalog.parsePage(CatalogTransport.post("browse", body))
    }
}

/** Broad, approximate families derived only from the opening artist description, never song titles. */
object GenreLabels {
    fun infer(description: String): String {
        val opening = description.take(320).lowercase(Locale.ROOT)
        val families = listOf(
            "Metal" to "\\b(?:heavy|thrash|death|black|power|nu|doom|progressive|symphonic) metal\\b|\\bmetal(?:core)?\\b",
            "Reguetón" to "reguet[oó]n|reggaeton", "Salsa" to "\\bsalsa\\b",
            "Hip hop" to "hip[ -]hop|\\brap(?:ero|era|per)?\\b",
            "Electrónica" to "electr[oó]nic[ao]?|\\b(?:house|techno|edm|trance)\\b",
            "Jazz" to "\\bjazz\\b", "Clásica" to "m[uú]sica cl[aá]sica|classical music",
            "Rock" to "\\brock\\b|\\bpunk\\b", "R&B" to "r&b|rhythm and blues|\\bsoul\\b",
            "Pop" to "\\bpop\\b", "Country" to "\\bcountry\\b"
        )
        return families.firstOrNull { Regex(it.second).containsMatchIn(opening) }?.first.orEmpty()
    }
    fun groups(mixes: List<DiscoveryMix>): List<DiscoveryMix> = mixes.groupBy { it.genre.isNotBlank() to it.genre.ifBlank { it.seed.artist } }.map { (_, values) ->
        val counts = mutableMapOf<String, Int>()
        val songs = (0 until 100).flatMap { index -> values.mapNotNull { it.tracks.getOrNull(index) } }.distinctBy { it.id }.filter { track ->
            val artist = MusicTitles.artist(track.artist).lowercase(Locale.ROOT)
            val count = counts.getOrDefault(artist, 0)
            if (count >= 6) false else { counts[artist] = count + 1; true }
        }.take(100)
        values.first().copy(tracks = songs, artists = values.flatMap { it.artists }.distinctBy { it.id })
    }
}

class ArtistRepository(context: Context) {
    private val folder = File(context.cacheDir, "artists-v1").apply { mkdirs() }
    @Synchronized fun get(ref: ArtistRef): ArtistProfile {
        require(ArtistRef.valid(ref.id))
        val file = File(folder, "${ref.id}.json")
        val cached = runCatching { ArtistProfile.from(JSONObject(file.readText())) }.getOrNull()
        if (cached != null && System.currentTimeMillis() - file.lastModified() in 0..86_400_000L) return cached
        return try {
            val profile = ArtistCatalog.parse(CatalogTransport.post("browse", JSONObject().put("browseId", ref.id)), ref)
            runCatching {
                val atomic = android.util.AtomicFile(file); val stream = atomic.startWrite()
                try { stream.write(profile.json().toString().toByteArray()); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
                folder.listFiles()?.filter { it.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
            }
            profile
        } catch (e: Exception) { cached ?: throw e }
    }
}
