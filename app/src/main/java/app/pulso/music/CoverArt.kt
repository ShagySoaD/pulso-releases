package app.pulso.music

import org.json.JSONArray
import java.net.URI

object CoverArt {
    /** Resize the same catalog asset; never guess an album from a song's title. */
    fun highQuality(url: String, pixels: Int = 1200): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        if (uri.scheme != "https" || !(host == "googleusercontent.com" || host.endsWith(".googleusercontent.com") || host == "ggpht.com" || host.endsWith(".ggpht.com"))) return url
        val size = pixels.coerceIn(256, 1200)
        val dimensions = Regex("([=-])w(\\d+)-h(\\d+)(?=-|$)")
        val match = dimensions.find(url)
        if (match != null) {
            val width = match.groupValues[2].toDoubleOrNull() ?: return url
            val height = match.groupValues[3].toDoubleOrNull() ?: return url
            if (width <= 0 || height <= 0) return url
            val scale = size / maxOf(width, height)
            return url.replaceRange(match.range, "${match.groupValues[1]}w${(width * scale).toInt().coerceAtLeast(1)}-h${(height * scale).toInt().coerceAtLeast(1)}")
        }
        return url.replace(Regex("([=-])w\\d+(?=-|$)"), "$1w$size")
            .replace(Regex("([=-])h\\d+(?=-|$)"), "$1h$size")
            .replace(Regex("=s\\d+(?=-|$)"), "=s$size")
    }
    fun best(images: JSONArray?): String {
        if (images == null) return ""
        return (0 until images.length()).mapNotNull { images.optJSONObject(it) }
            .filter { it.optString("url").startsWith("https://") }
            .maxByOrNull { it.optLong("width") * it.optLong("height") }?.optString("url").orEmpty()
    }
    fun needsCatalogCover(track: Track): Boolean {
        if (!track.id.matches(Regex("[A-Za-z0-9_-]{11}"))) return false
        val host = runCatching { URI(track.artwork).host }.getOrNull().orEmpty()
        return track.artwork.isBlank() || host == "i.ytimg.com" || host == "img.youtube.com"
    }
    fun prefer(existing: Track?, incoming: Track): String {
        if (existing == null || existing.artwork.isBlank()) return incoming.artwork
        if (incoming.artwork.isBlank() || (needsCatalogCover(incoming) && !needsCatalogCover(existing))) return existing.artwork
        return incoming.artwork
    }
}
