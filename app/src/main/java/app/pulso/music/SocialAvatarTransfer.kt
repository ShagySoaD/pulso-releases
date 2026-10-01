package app.pulso.music

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Bounded, versioned avatar transfer on the existing authenticated friend channel. */
internal class SocialAvatarTransfer {
    private data class Pending(val hash: String, val revision: String, var count: Int = 0, val parts: MutableMap<Int, String> = mutableMapOf())
    private val pending = mutableMapOf<String, Pending>()
    fun begin(key: String, hash: String, revision: String) {
        pending.remove(key)
        if (hash.matches(Regex("[0-9A-F]{64}")) && revision.length in 1..40 && pending.size < 100) pending[key] = Pending(hash, revision)
    }
    fun remove(key: String) { pending.remove(key) }
    fun receive(key: String, json: JSONObject): String? {
        val p = pending[key] ?: return null
        if (json.optString("hash") != p.hash || json.optString("rev") != p.revision) return null
        val count = json.optInt("count"); val index = json.optInt("index", -1); val part = json.optString("data")
        if (count !in 1..25 || index !in 0 until count || part.length !in 1..900 || !part.all { it.isLetterOrDigit() && it.code < 128 || it in "+/=" }) return null
        if (p.count != 0 && p.count != count) return null
        p.count = count; p.parts[index] = part
        if (p.parts.size != count) return null
        pending.remove(key)
        val photo = (0 until count).joinToString("") { p.parts[it].orEmpty() }
        val bytes = decode(photo) ?: return null
        return photo.takeIf { hash(bytes) == p.hash }
    }
    companion object {
        const val MAX_BYTES = 16_384
        fun decode(photo: String): ByteArray? = runCatching {
            require(photo.length in 4..21_848)
            Base64.getDecoder().decode(photo).also { require(it.size in 4..MAX_BYTES && it[0] == 0xff.toByte() && it[1] == 0xd8.toByte()) }
        }.getOrNull()
        fun hash(bytes: ByteArray): String = SocialProtocol.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun hash(photo: String): String = decode(photo)?.let(::hash).orEmpty()
        fun packets(photo: String, revision: String): List<ByteArray> {
            val hash = hash(photo); if (hash.isEmpty()) return emptyList()
            val chunks = photo.chunked(900)
            return chunks.mapIndexed { index, part -> SocialProtocol.encode("avatar", JSONObject().put("hash", hash).put("rev", revision).put("index", index).put("count", chunks.size).put("data", part)) }
        }
    }
}
