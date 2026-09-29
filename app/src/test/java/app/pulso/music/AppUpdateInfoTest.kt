package app.pulso.music

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateInfoTest {
    private val repo = "ShagySoaD/pulso-releases"
    private fun payload(): JSONObject = JSONObject().put("schema", 1).put("packageName", "app.pulso.music")
        .put("versionCode", 24).put("versionName", "0.7.12").put("minSdk", 29).put("notes", "Novedades")
        .put("assets", JSONArray(listOf("arm64-v8a", "armeabi-v7a", "x86_64").map { abi ->
            JSONObject().put("abi", abi).put("url", "https://github.com/$repo/releases/download/v0.7.12/Pulso-$abi.apk")
                .put("sha256", "a".repeat(64)).put("size", 100000000L)
        }))
    @Test fun choosesDeviceAbiPriorityAndChecksAndroidVersion() {
        val info = AppUpdateInfo.parse(payload().toString(), repo)
        assertEquals("arm64-v8a", info.compatible(listOf("arm64-v8a", "armeabi-v7a"), 29)?.abi)
        assertEquals("armeabi-v7a", info.compatible(listOf("armeabi-v7a"), 29)?.abi)
        assertEquals("x86_64", info.compatible(listOf("x86_64"), 35)?.abi)
        assertNull(info.compatible(listOf("arm64-v8a"), 28))
        assertNull(info.compatible(listOf("mips"), 35))
    }
    @Test fun rejectsForeignRepositoriesAndUnsafeDownloadUrls() {
        listOf("http://github.com/$repo/releases/download/v1/app.apk",
            "https://github.com.evil.test/$repo/releases/download/v1/app.apk",
            "https://github.com/other/repo/releases/download/v1/app.apk",
            "https://user@github.com/$repo/releases/download/v1/app.apk",
            "https://github.com/$repo/releases/download/../app.apk",
            "https://github.com/$repo/releases/download/v1/app.apk?redirect=evil",
            "https://github.com:443/$repo/releases/download/v1/app.apk").forEach { url ->
            assertFalse(url, AppUpdateInfo.trustedAsset(url, repo))
        }
    }
    @Test fun rejectsMalformedOrIncompleteManifests() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("schema", 99) }, { it.put("packageName", "other.app") },
            { it.put("versionCode", 0) }, { it.put("versionName", "broken") },
            { it.getJSONArray("assets").getJSONObject(0).put("sha256", "invalid") },
            { it.getJSONArray("assets").getJSONObject(0).put("size", AppUpdateInfo.MAX_APK_BYTES + 1) },
            { it.getJSONArray("assets").getJSONObject(0).put("abi", "x86_64") },
            { it.put("assets", JSONArray()) }
        )
        changes.forEach { change ->
            val json = payload(); change(json)
            assertTrue(runCatching { AppUpdateInfo.parse(json.toString(), repo) }.isFailure)
        }
        assertTrue(runCatching { AppUpdateInfo.parse(" ".repeat(65537), repo) }.isFailure)
    }
    @Test fun notesAreBoundedAndDigestNormalized() {
        val json = payload().put("notes", "n".repeat(5000))
        json.getJSONArray("assets").getJSONObject(0).put("sha256", "A".repeat(64))
        val info = AppUpdateInfo.parse(json.toString(), repo)
        assertEquals(4000, info.notes.length)
        assertEquals("a".repeat(64), info.assets.first().sha256)
    }
    @Test fun browserDownloadOnlyUsesNewCompatibleOfficialApk() {
        val info = AppUpdateInfo.parse(payload().toString(), repo)
        assertEquals("https://github.com/$repo/releases/download/v0.7.12/Pulso-arm64-v8a.apk",
            info.browserDownloadUrl(listOf("arm64-v8a", "armeabi-v7a"), 29, 23, repo))
        assertEquals("https://github.com/$repo/releases/download/v0.7.12/Pulso-x86_64.apk",
            info.browserDownloadUrl(listOf("x86_64"), 35, 23, repo))
        assertNull(info.browserDownloadUrl(listOf("arm64-v8a"), 29, 24, repo))
        assertNull(info.browserDownloadUrl(listOf("arm64-v8a"), 28, 23, repo))
        assertNull(info.browserDownloadUrl(listOf("mips"), 29, 23, repo))
        val foreign = info.copy(assets = listOf(info.assets.first().copy(url = "https://evil.test/update.apk")))
        assertNull(foreign.browserDownloadUrl(listOf("arm64-v8a"), 29, 23, repo))
    }
}
