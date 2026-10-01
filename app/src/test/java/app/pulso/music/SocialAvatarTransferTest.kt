package app.pulso.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SocialAvatarTransferTest {
    private fun photo() = Base64.getEncoder().encodeToString(ByteArray(9000) { (it % 255).toByte() }.apply { this[0] = 0xff.toByte(); this[1] = 0xd8.toByte() })
    @Test fun reassemblesOutOfOrderAndChecksDigest() {
        val photo = photo(); val transfer = SocialAvatarTransfer(); val packets = SocialAvatarTransfer.packets(photo, "v1")
        assertTrue(packets.all { it.size <= 1373 })
        transfer.begin("friend", SocialAvatarTransfer.hash(photo), "v1")
        var result: String? = null
        packets.reversed().forEach { result = transfer.receive("friend", SocialProtocol.decode(it)!!) ?: result }
        assertEquals(photo, result)
    }
    @Test fun ignoresUnrequestedAndOldRevisions() {
        val photo = photo(); val packet = SocialProtocol.decode(SocialAvatarTransfer.packets(photo, "old").first())!!
        val transfer = SocialAvatarTransfer()
        assertNull(transfer.receive("friend", packet))
        transfer.begin("friend", SocialAvatarTransfer.hash(photo), "new")
        assertNull(transfer.receive("friend", packet))
        assertNull(transfer.receive("unknown", packet))
    }
    @Test fun rejectsTamperedAndOversizedChunks() {
        val photo = photo(); val transfer = SocialAvatarTransfer(); val hash = SocialAvatarTransfer.hash(photo)
        transfer.begin("friend", hash, "v1")
        assertNull(transfer.receive("friend", JSONObject().put("hash", hash).put("rev", "v1").put("count", 100000).put("index", 0).put("data", "AAAA")))
        val packets = SocialAvatarTransfer.packets(photo, "v1").map { SocialProtocol.decode(it)!! }
        packets.last().put("data", packets.last().getString("data").replaceFirst('A', 'B'))
        // A changed final fragment cannot be accepted under the original digest.
        packets.last().put("data", "AAAA")
        assertTrue(packets.all { transfer.receive("friend", it) == null })
        assertNull(SocialAvatarTransfer.decode(Base64.getEncoder().encodeToString(ByteArray(17000))))
    }
    @Test fun connectInvitationKeepsFullIdentityAndChecksum() {
        val id = "00".repeat(38)
        assertEquals(id, SocialProtocol.address("pulso://connect/$id"))
        assertEquals(id, SocialProtocol.address("tox:$id"))
        assertTrue(runCatching { SocialProtocol.address("pulso://connect/ABC123") }.isFailure)
    }
}
