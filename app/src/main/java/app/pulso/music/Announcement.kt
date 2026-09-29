package app.pulso.music

import org.json.JSONObject
import java.net.URI
import java.time.Instant

internal data class Announcement(
    val id: String, val enabled: Boolean, val title: String, val message: String,
    val imageUrl: String, val frequency: String, val expiresAt: Instant?
) {
    fun shouldShow(now: Instant, dismissedId: String) = enabled &&
        (expiresAt == null || now < expiresAt) && (frequency != "once" || dismissedId != id)

    companion object {
        const val MAX_BYTES = 32768
        const val CACHE_MILLIS = 24 * 60 * 60 * 1000L
        fun cacheFresh(savedAt: Long, now: Long) = savedAt > 0 && now >= savedAt && now - savedAt < CACHE_MILLIS
        fun parse(raw: String, repository: String): Announcement {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val json = JSONObject(raw)
            require(json.getInt("schema") == 1)
            val enabled = json.getBoolean("enabled")
            if (!enabled) return Announcement("", false, "", "", "", "every_launch", null)
            val id = json.getString("id")
            val title = json.getString("title")
            val message = json.getString("message")
            val image = json.optString("imageUrl", "")
            val frequency = json.optString("frequency", "every_launch")
            val expiry = json.optString("expiresAt", "").takeIf { it.isNotBlank() }?.let(Instant::parse)
            require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            require(title.isNotBlank() && title.length <= 120 && message.length <= 4000)
            require(message.isNotBlank() || image.isNotBlank())
            require(frequency in setOf("once", "every_launch"))
            if (image.isNotBlank()) {
                val uri = URI(image)
                require(AppUpdateInfo.validRepository(repository) && uri.scheme == "https" &&
                    uri.host == "raw.githubusercontent.com" && uri.userInfo == null && uri.port == -1 &&
                    uri.query == null && uri.fragment == null &&
                    uri.path.startsWith("/$repository/main/anuncios/") && uri.normalize().path == uri.path &&
                    uri.rawPath == uri.path && uri.path.matches(Regex("[A-Za-z0-9_./-]+")) &&
                    listOf(".png", ".jpg", ".jpeg", ".webp").any { uri.path.endsWith(it, true) })
            }
            return Announcement(id, enabled, title, message, image, frequency, expiry)
        }
    }
}
