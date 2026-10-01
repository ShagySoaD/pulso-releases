package app.pulso.music

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SocialProtocolTest {
    @Test fun toxAddressRequiresChecksum() {
        val bytes = ByteArray(38) { if (it < 36) it.toByte() else 0 }
        for (i in 0..35) bytes[36 + i % 2] = (bytes[36 + i % 2].toInt() xor bytes[i].toInt()).toByte()
        val valid = SocialProtocol.hex(bytes)
        assertEquals(valid, SocialProtocol.address("tox:${valid.lowercase()}\n"))
        assertTrue(runCatching { SocialProtocol.address(valid.dropLast(2) + "FF") }.isFailure)
        assertTrue(runCatching { SocialProtocol.address("device-model-id") }.isFailure)
    }
    @Test fun favoritesNeverExposeLocalFiles() {
        val track = Track("abcdefghijk", "Title", "Artist", "file:///private/cover", localUri = "content://private/audio", favorite = true)
        val json = SocialProtocol.song(track)!!
        assertEquals(setOf("id", "title", "artist"), json.keys().asSequence().toSet())
        assertFalse(json.toString().contains("private"))
        assertNull(SocialProtocol.song(track.copy(id = "local:123")))
        assertNull(SocialProtocol.track(JSONObject().put("id", "https://malicious.example")))
    }
    @Test fun protocolRejectsOtherAppsVersionsAndOversizedPackets() {
        assertNotNull(SocialProtocol.decode(SocialProtocol.encode("hello")))
        val wrong = byteArrayOf(160.toByte()) + "{\"app\":\"other\",\"v\":1,\"type\":\"hello\"}".toByteArray()
        assertNull(SocialProtocol.decode(wrong))
        assertNull(SocialProtocol.decode(byteArrayOf(160.toByte(), 0, 1)))
        assertNull(SocialProtocol.decode(ByteArray(1400) { 160.toByte() }))
        assertTrue(runCatching { SocialProtocol.encode("profile", JSONObject().put("bio", "ñ".repeat(1400))) }.isFailure)
    }
    @Test fun unicodeMetadataStaysWithinNativePacketLimit() {
        val song = SocialProtocol.song(Track("abcdefghijk", "🎸".repeat(100), "日".repeat(100)))!!
        val packet = SocialProtocol.encode("favorite", JSONObject().put("song", song).put("rev", "test"))
        assertTrue(packet.size <= 1373)
        assertEquals("abcdefghijk", SocialProtocol.track(SocialProtocol.decode(packet)!!.getJSONObject("song"))!!.id)
    }
}
