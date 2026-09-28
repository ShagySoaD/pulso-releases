package app.pulso.music

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

enum class SearchCategory(val label: String, val parameter: String) {
    SONGS("Canciones", "EgWKAQIIAWoKEAkQBRAKEAMQBA=="),
    ARTISTS("Artistas", "EgWKAQIgAWoKEAkQChAFEAMQBA=="),
    PLAYLISTS("Playlists", "EgeKAQQoAEABagoQAxAEEAoQCRAF")
}
data class CommunityPlaylist(val id: String, val title: String, val author: String, val artwork: String)
data class ExplorePage(val songs: List<Track> = emptyList(), val artists: List<ArtistRef> = emptyList(),
    val playlists: List<CommunityPlaylist> = emptyList(), val next: String? = null)

/** Separate search shelves: a continuation always belongs to its original category. */
object ExploreCatalog {
    fun search(query: String, category: SearchCategory, next: String? = null): ExplorePage {
        val body = if (next == null) JSONObject().put("query", query.trim()).put("params", category.parameter)
            else JSONObject().put("continuation", next)
        return parse(request("search", body), category)
    }
    fun playlist(id: String, next: String? = null): ExplorePage {
        require(id.matches(Regex("VL[A-Za-z0-9_-]+")))
        val body = if (next == null) JSONObject().put("browseId", id) else JSONObject().put("continuation", next)
        return parsePlaylist(request("browse", body))
    }
    fun parsePlaylist(raw: String): ExplorePage {
        val root = JSONObject(raw)
        check(!root.has("error")) { "Playlist no disponible" }
        val shelves = JSONArray()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val shelf = value.optJSONObject("musicPlaylistShelfRenderer")
                        ?: value.optJSONObject("musicPlaylistShelfContinuation")
                        ?: value.optJSONObject("musicShelfContinuation")
                        ?: value.optJSONObject("appendContinuationItemsAction")
                    if (shelf != null) shelves.put(shelf)
                    else value.keys().forEach { key -> if (key !in setOf("menu", "navigationEndpoint", "responseContext")) visit(value.opt(key)) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        listOf("contents", "continuationContents", "onResponseReceivedActions", "onResponseReceivedEndpoints").forEach { visit(root.opt(it)) }
        val page = MusicCatalog.parsePage(JSONObject().put("contents", shelves).toString(), allowVideos = true)
        return ExplorePage(songs = page.tracks, next = page.next)
    }
    private fun request(endpoint: String, body: JSONObject): String {
        val connection = URI("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 15000; connection.readTimeout = 25000
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Origin", "https://music.youtube.com")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            body.put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20260914.01.00").put("hl", "es").put("gl", "PE")))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 200) { "Catálogo HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    fun parse(raw: String, category: SearchCategory): ExplorePage {
        val root = JSONObject(raw)
        check(!root.has("error")) { "Catálogo no disponible" }
        val artists = mutableListOf<ArtistRef>()
        val playlists = mutableListOf<CommunityPlaylist>()
        var next: String? = null
        fun text(value: JSONObject?) = value?.optJSONArray("runs")?.let { runs ->
            (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        } ?: value?.optString("simpleText").orEmpty()
        fun row(value: JSONObject) {
            fun column(index: Int) = value.optJSONArray("flexColumns")?.optJSONObject(index)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            val titleNode = value.optJSONObject("title") ?: column(0)
            val title = text(titleNode).trim()
            val browse = value.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?: titleNode?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val id = browse?.optString("browseId").orEmpty()
            if (title.isBlank()) return
            val thumb = (value.optJSONObject("thumbnail") ?: value.optJSONObject("thumbnailRenderer"))
                ?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val art = CoverArt.best(thumb)
            if (category == SearchCategory.ARTISTS && ArtistRef.valid(id)) artists += ArtistRef(id, title, art)
            if (category == SearchCategory.PLAYLISTS && id.startsWith("VL")) {
                val subtitle = value.optJSONObject("subtitle") ?: column(1)
                val runs = subtitle?.optJSONArray("runs")
                val author = (0 until (runs?.length() ?: 0)).mapNotNull { runs?.optJSONObject(it) }
                    .firstOrNull { it.optJSONObject("navigationEndpoint")?.has("browseEndpoint") == true }?.optString("text").orEmpty()
                playlists += CommunityPlaylist(id, title, author.ifBlank { text(subtitle) }, art)
            }
        }
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val renderer = value.optJSONObject("musicResponsiveListItemRenderer") ?: value.optJSONObject("musicTwoRowItemRenderer")
                    if (renderer != null) row(renderer) else {
                        val token = value.optJSONObject("nextContinuationData")?.optString("continuation")
                            ?: value.optJSONObject("continuationCommand")?.optString("token")
                        if (!token.isNullOrBlank()) next = token
                        value.keys().forEach { visit(value.opt(it)) }
                    }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        listOf("contents", "continuationContents", "onResponseReceivedActions", "onResponseReceivedEndpoints").forEach { visit(root.opt(it)) }
        return ExplorePage(if (category == SearchCategory.SONGS) MusicCatalog.parse(raw) else emptyList(),
            artists.distinctBy { it.id }, playlists.distinctBy { it.id }, next)
    }
}
