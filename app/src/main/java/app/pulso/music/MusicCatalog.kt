package app.pulso.music

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class CatalogPage(val tracks: List<Track>, val next: String? = null)

/** Public song search. No account cookies or playback URLs are sent to this endpoint. */
object MusicCatalog {
    fun search(query: String): List<Track> = page(query).tracks
    fun page(query: String, continuation: String? = null): CatalogPage {
        val suffix = continuation?.let { val token = URLEncoder.encode(it, "UTF-8"); "&ctoken=$token&continuation=$token" }.orEmpty()
        val connection = URI("https://music.youtube.com/youtubei/v1/search?prettyPrint=false$suffix").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Origin", "https://music.youtube.com")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0")
            connection.setRequestProperty("Accept", "application/json")
            val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20260914.01.00").put("hl", "es").put("gl", "PE")
            val body = JSONObject().put("context", JSONObject().put("client", client).put("user", JSONObject()))
            if (continuation == null) body.put("query", MusicTitles.clean(query)).put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 200) { "Catálogo HTTP ${connection.responseCode}" }
            return parsePage(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun parse(raw: String): List<Track> = parsePage(raw).tracks
    fun parsePage(raw: String, allowVideos: Boolean = false): CatalogPage {
        val root = JSONObject(raw)
        check(!root.has("error")) { "La búsqueda no está disponible por ahora." }
        val rows = mutableListOf<JSONObject>()
        var next: String? = null
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val row = value.optJSONObject("musicResponsiveListItemRenderer")
                    if (row != null) rows.add(row) else {
                        val token = value.optJSONObject("nextContinuationData")?.optString("continuation")
                            ?: value.optJSONObject("continuationCommand")?.optString("token")
                        if (!token.isNullOrBlank()) next = token
                        value.keys().forEach { visit(value.opt(it)) }
                    }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        // Only result shelves, never action menus or suggestions.
        visit(root.opt("contents"))
        visit(root.opt("continuationContents"))
        visit(root.opt("onResponseReceivedActions"))
        visit(root.opt("onResponseReceivedEndpoints"))
        return CatalogPage(rows.mapNotNull { song(it, allowVideos) }.distinctBy { it.id }, next)
    }

    private fun song(row: JSONObject, allowVideos: Boolean): Track? {
        val endpoint = row.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
            ?: row.optJSONArray("flexColumns")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
            ?: row.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint") ?: return null
        val type = endpoint.optJSONObject("watchEndpointMusicSupportedConfigs")
            ?.optJSONObject("watchEndpointMusicConfig")?.optString("musicVideoType")
        // Prefer catalog audio in song search; a missing type is accepted only with artist metadata.
        if (!allowVideos && !type.isNullOrBlank() && type != "MUSIC_VIDEO_TYPE_ATV") return null
        val id = endpoint.optString("videoId")
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        val columns = row.optJSONArray("flexColumns") ?: return null
        fun runs(index: Int): List<JSONObject> {
            val array = columns.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs") ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }
        val title = runs(0).joinToString("") { it.optString("text") }.trim()
        if (!allowVideos && Regex("(?i)(official(?:\\s+(?:hd|4k|music))*\\s+video|video\\s+oficial|videoclip)").containsMatchIn(title)) return null
        val details = runs(1)
        val artists = details.filter {
            it.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType") == "MUSIC_PAGE_TYPE_ARTIST"
        }.map { it.optString("text") }.filter { it.isNotBlank() }.distinct()
        if (title.isBlank() || (!allowVideos && artists.isEmpty())) return null
        val fixedDuration = row.optJSONArray("fixedColumns")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
        val duration = (details.map { it.optString("text") } + fixedDuration).firstOrNull { it.matches(Regex("\\d{1,3}:\\d{2}(:\\d{2})?")) }
            ?.split(":")?.fold(0L) { total, part -> total * 60 + part.toLong() } ?: 0
        val images = row.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val art = CoverArt.best(images)
        // Catalog titles are already editorial metadata: preserve remix/live/featured credits.
        val artistRuns = columns.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
        val author = artists.joinToString(", ").ifBlank {
            details.firstOrNull { it.optString("text").isNotBlank() && it.optString("text") != " • " }?.optString("text") ?: "Artista desconocido"
        }
        return Track(id, title, author, art, duration, artists = ArtistRef.runs(artistRuns))
    }
}

object MusicTitles {
    private val promo = Regex("(?i)\\s*[\\[(](?:official\\s+(?:(?:music|lyric)\\s+)?(?:video|audio)|video\\s+oficial|audio\\s+oficial|lyrics?|lyric\\s+video|visualizer|4k|hd)[\\])]\\s*")
    private val suffix = Regex("(?i)\\s*(?:[-|–]\\s*)?(?:official\\s+(?:music\\s+)?(?:video|audio)|video\\s+oficial|audio\\s+oficial)\\s*$")
    fun clean(title: String): String = title.replace(promo, " ").replace(suffix, "").replace(Regex("\\s+"), " ").trim().ifBlank { title.trim() }
    fun artist(name: String): String = name.replace(Regex("(?i)\\s*-\\s*Topic$"), "").trim()
}
