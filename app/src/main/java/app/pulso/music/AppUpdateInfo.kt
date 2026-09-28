package app.pulso.music

import org.json.JSONObject
import java.net.URI

internal data class UpdateAsset(val abi: String, val url: String, val sha256: String, val size: Long)
internal data class AppUpdateInfo(val code: Long, val name: String, val notes: String, val minSdk: Int, val assets: List<UpdateAsset>) {
    fun compatible(abis: List<String>, sdk: Int): UpdateAsset? =
        if (sdk < minSdk) null else abis.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.abi == abi } }

    companion object {
        const val MAX_APK_BYTES = 400L * 1024 * 1024
        fun validRepository(repo: String) = repo.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}"))
        fun trustedAsset(url: String, repo: String): Boolean = runCatching {
            val uri = URI(url)
            validRepository(repo) && uri.scheme == "https" && uri.host == "github.com" &&
                uri.userInfo == null && uri.port == -1 && uri.fragment == null && uri.query == null &&
                uri.path.startsWith("/$repo/releases/download/") &&
                uri.path.removePrefix("/$repo/releases/download/").split('/').let {
                    it.size == 2 && it.all { part -> part.matches(Regex("[A-Za-z0-9_.-]+")) && part != "." && part != ".." }
                }
        }.getOrDefault(false)

        fun parse(raw: String, repo: String): AppUpdateInfo {
            require(raw.toByteArray(Charsets.UTF_8).size <= 65536)
            val root = JSONObject(raw)
            require(root.getInt("schema") == 1 && root.getString("packageName") == "app.pulso.music")
            val code = root.getLong("versionCode")
            val name = root.getString("versionName")
            val minSdk = root.getInt("minSdk")
            require(code in 1..Int.MAX_VALUE.toLong() && name.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")) && minSdk in 29..100)
            val entries = root.getJSONArray("assets")
            require(entries.length() in 1..3)
            val assets = (0 until entries.length()).map {
                val item = entries.getJSONObject(it)
                val asset = UpdateAsset(item.getString("abi"), item.getString("url"), item.getString("sha256").lowercase(), item.getLong("size"))
                require(asset.abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
                require(trustedAsset(asset.url, repo) && asset.url.endsWith(".apk"))
                require(asset.sha256.matches(Regex("[0-9a-f]{64}")) && asset.size in 1..MAX_APK_BYTES)
                asset
            }
            require(assets.distinctBy { it.abi }.size == assets.size)
            return AppUpdateInfo(code, name, root.optString("notes").take(4000), minSdk, assets)
        }
    }
}
