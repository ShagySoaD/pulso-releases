package app.pulso.music

import org.json.JSONObject

internal object SocialProtocol {
    const val MAX_FAVORITES = 40
    private val video = Regex("[A-Za-z0-9_-]{11}")
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
    fun bytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" })
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun address(input: String): String {
        val value = input.trim().removePrefix("pulso://connect/").removePrefix("tox:").filterNot { it.isWhitespace() }.uppercase()
        require(value.length == 76) { "Este código de Connect está incompleto." }
        val b = bytes(value)
        require((0..35 step 2).fold(0) { a, i -> a xor (b[i].toInt() and 255) } == (b[36].toInt() and 255) &&
            (1..35 step 2).fold(0) { a, i -> a xor (b[i].toInt() and 255) } == (b[37].toInt() and 255)) { "Este código de Connect no es válido." }
        return value
    }
    private fun publicCover(value: String): String? = runCatching {
        require(value.length <= 280)
        val uri = java.net.URI(value)
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            (host == "i.ytimg.com" || host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com")))
        value
    }.getOrNull()
    fun song(track: Track): JSONObject? = if (video.matches(track.id)) JSONObject()
        .put("id", track.id).put("title", track.title.take(100)).put("artist", track.artist.take(80)).apply {
            publicCover(track.artwork)?.let { put("artwork", it) }
            if (toString().toByteArray(Charsets.UTF_8).size > 1100) remove("artwork")
        } else null
    fun track(json: JSONObject): Track? {
        val id = json.optString("id")
        if (!video.matches(id)) return null
        return Track(id, json.optString("title").take(100), json.optString("artist").take(80), artwork = publicCover(json.optString("artwork")) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg")
    }
    fun encode(type: String, json: JSONObject = JSONObject()): ByteArray {
        json.put("app", "pulso").put("v", 1).put("type", type)
        val data = json.toString().toByteArray(Charsets.UTF_8)
        require(data.size <= 1372)
        return byteArrayOf(160.toByte()) + data
    }
    fun decode(bytes: ByteArray): JSONObject? = runCatching {
        require(bytes.size in 2..1373 && bytes[0] == 160.toByte())
        JSONObject(bytes.copyOfRange(1, bytes.size).toString(Charsets.UTF_8)).takeIf {
            it.optString("app") == "pulso" && it.optInt("v") == 1 && it.optString("type") in setOf("hello", "profile", "favorite", "now", "avatar", "playlist", "playlistSong")
        }
    }.getOrNull()
}
