package app.pulso.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AnnouncementTest {
    private val repo = "ShagySoaD/pulso-releases"
    private fun json() = JSONObject("""{"schema":1,"enabled":true,"id":"welcome-1","title":"Bienvenido","message":"Gracias","frequency":"every_launch","expiresAt":"","imageUrl":""}""")
    private val now = Instant.parse("2026-09-29T12:00:00Z")

    @Test fun repetitionAndNewIdentity() {
        assertTrue(Announcement.parse(json().toString(), repo).shouldShow(now, "welcome-1"))
        val once = Announcement.parse(json().put("frequency", "once").toString(), repo)
        assertFalse(once.shouldShow(now, "welcome-1"))
        assertTrue(once.shouldShow(now, "old-welcome"))
    }
    @Test fun disabledAndExpiredNeverShow() {
        assertFalse(Announcement.parse("""{"schema":1,"enabled":false}""", repo).shouldShow(now, ""))
        val notice = Announcement.parse(json().put("expiresAt", now.toString()).toString(), repo)
        assertFalse(notice.shouldShow(now, ""))
        assertTrue(notice.shouldShow(now.minusSeconds(1), ""))
    }
    @Test fun cacheHasBoundedOfflineLifetime() {
        val timestamp = now.toEpochMilli()
        assertTrue(Announcement.cacheFresh(timestamp, timestamp + 1000))
        assertFalse(Announcement.cacheFresh(timestamp, timestamp + Announcement.CACHE_MILLIS))
        assertFalse(Announcement.cacheFresh(timestamp, timestamp - 1))
        assertFalse(Announcement.cacheFresh(0, timestamp))
    }
    @Test fun rejectsMalformedOrOversizedAnnouncements() {
        val bad = listOf("not json", json().put("schema", 2).toString(),
            json().put("frequency", "unknown").toString(), json().put("title", "").toString(),
            json().put("message", "a".repeat(4001)).toString(), json().put("expiresAt", "tomorrow").toString(),
            " ".repeat(Announcement.MAX_BYTES + 1))
        bad.forEach { raw -> assertTrue(runCatching { Announcement.parse(raw, repo) }.isFailure) }
    }
    @Test fun acceptsRepositoryImagesAndRejectsForeignOrExecutableContent() {
        val prefix = "https://raw.githubusercontent.com/$repo/main/anuncios/"
        assertEquals(prefix + "welcome.png", Announcement.parse(json().put("imageUrl", prefix + "welcome.png").toString(), repo).imageUrl)
        listOf("http://raw.githubusercontent.com/$repo/main/anuncios/a.png", "https://evil.test/a.png",
            prefix + "../secret.png", prefix + "%2e%2e/secret.png", prefix + "page.html",
            prefix + "a.png?other=1", "https://raw.githubusercontent.com/other/repo/main/anuncios/a.png")
            .forEach { url -> assertTrue(url, runCatching { Announcement.parse(json().put("imageUrl", url).toString(), repo) }.isFailure) }
    }
}
