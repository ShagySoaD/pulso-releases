package app.pulso.music

import androidx.annotation.Keep
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native peers, media validation, and a temporary Connect contact removed after testing. */
@RunWith(AndroidJUnit4::class)
class ToxIntegrationTest {
    @Test fun connectReceivesAvatarAndClearsUnreadOnOpeningChat() {
        val peer = ToxNative.create(byteArrayOf())
        assertTrue(peer != 0L)
        val listener = Listener()
        val peerKey = SocialProtocol.hex(ToxNative.address(peer)).take(64)
        val existingKeys = SocialEngine.state.value.friends.map { it.key }.toSet()
        fun await(label: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) { ToxNative.iterate(peer, listener); Thread.sleep(30) }
            assertTrue(label, condition())
        }
        try {
            SocialEngine.visibility(true)
            await("Connect ready") { SocialEngine.state.value.ready && SocialEngine.state.value.address.isNotEmpty() && SocialEngine.state.value.status == "Conectado" }
            val key = SocialProtocol.bytes("2016A0F2797EE3A8B004BA623F11AAFC8146F1B8F45107232A1A1AECCE856674")
            ToxNative.bootstrap(peer, "144.172.88.203", 33445, key, false)
            ToxNative.bootstrap(peer, "144.172.88.203", 443, key, true)
            await("Temporary peer connected") { listener.connected }
            SocialEngine.add(SocialProtocol.hex(ToxNative.address(peer)))
            await("Temporary request received") { listener.request != null }
            val friend = ToxNative.add(peer, listener.request!!, true)
            assertTrue(friend >= 0)
            await("Temporary contact online") { listener.friendOnline && SocialEngine.state.value.friends.any { it.key == peerKey && it.online } }
            assertTrue(ToxNative.send(peer, friend, "Mensaje de verificación".toByteArray()) >= 0)
            await("Unread count") { SocialEngine.state.value.friends.first { it.key == peerKey }.unread == 1 }
            SocialEngine.viewing(peerKey)
            await("Opening chat clears unread") { SocialEngine.state.value.friends.first { it.key == peerKey }.unread == 0 }
            val bitmap = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val stream = java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, stream); bitmap.recycle()
            val photo = java.util.Base64.getEncoder().encodeToString(stream.toByteArray())
            val profile = SocialProtocol.encode("profile", org.json.JSONObject().put("avatar", 3).put("photoHash", SocialAvatarTransfer.hash(photo)).put("rev", "avatar-test").put("bio", "Prueba local").put("favorites", false))
            assertTrue(ToxNative.packet(peer, friend, profile))
            SocialAvatarTransfer.packets(photo, "avatar-test").forEach { assertTrue(ToxNative.packet(peer, friend, it)) }
            await("Photo received and validated") { SocialEngine.state.value.friends.first { it.key == peerKey }.photo == photo }
            assertEquals(3, SocialEngine.state.value.friends.first { it.key == peerKey }.avatar)
        } finally {
            SocialEngine.viewing(null)
            if (SocialEngine.state.value.friends.any { it.key == peerKey }) {
                SocialEngine.remove(peerKey)
                await("Temporary contact removed") { SocialEngine.state.value.friends.none { it.key == peerKey } }
            }
            assertTrue(SocialEngine.state.value.friends.map { it.key }.containsAll(existingKeys))
            SocialEngine.visibility(false)
            ToxNative.close(peer)
        }
    }
    @Test fun qrImageRoundTripAndProfilePhotoBounds() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = SocialMedia.qr("00".repeat(38))
        val file = java.io.File(context.cacheDir, "connect-qr-test.png")
        try {
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals("00".repeat(38), SocialMedia.readQr(context, android.net.Uri.fromFile(file)))
            val photo = SocialMedia.photo(context, android.net.Uri.fromFile(file))
            assertTrue(SocialAvatarTransfer.decode(photo)!!.size <= SocialAvatarTransfer.MAX_BYTES)
            val decoded = SocialMedia.decodePhoto(photo)!!
            assertEquals(192, decoded.width); assertEquals(192, decoded.height); decoded.recycle()
            assertNull(SocialMedia.decodePhoto(java.util.Base64.getEncoder().encodeToString(byteArrayOf(-1, -40, 0, 0))))
        } finally { bitmap.recycle(); file.delete() }
    }
    @Test fun encryptedVaultRoundTripAndTamperDetection() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "social-vault-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        try {
            val vault = SocialVault(isolated)
            assertNull(vault.read())
            vault.write(org.json.JSONObject().put("secret", "private-test-identity"))
            assertEquals("private-test-identity", vault.read()!!.getString("secret"))
            val file = java.io.File(directory, "pulso-social.v1")
            val data = file.readBytes()
            assertFalse(data.toString(Charsets.UTF_8).contains("private-test-identity"))
            data[data.lastIndex] = (data.last().toInt() xor 1).toByte(); file.writeBytes(data)
            assertTrue(runCatching { vault.read() }.isFailure)
        } finally { directory.deleteRecursively() }
    }
    @Keep class Listener {
        var connected = false
        var friendOnline = false
        var request: ByteArray? = null
        var message = ""
        var receipt = -1L
        var packet: ByteArray? = null
        @Keep fun onEvent(type: Int, friend: Long, data: ByteArray, value: Long) {
            when (type) {
                0 -> connected = value != 0L
                1 -> request = data
                2 -> message = data.toString(Charsets.UTF_8)
                3 -> friendOnline = value != 0L
                5 -> receipt = value
                6 -> packet = data
            }
        }
    }
    @Test fun identityPersistsAndRejectsCorruption() {
        val first = ToxNative.create(byteArrayOf())
        assertTrue(first != 0L)
        val id: ByteArray; val saved: ByteArray
        try { id = ToxNative.address(first); saved = ToxNative.save(first); assertEquals(76, SocialProtocol.address(SocialProtocol.hex(id)).length) }
        finally { ToxNative.close(first) }
        val reopened = ToxNative.create(saved)
        assertTrue(reopened != 0L)
        try { assertArrayEquals(id, ToxNative.address(reopened)) } finally { ToxNative.close(reopened) }
        assertEquals(0L, ToxNative.create(byteArrayOf(1, 2, 3)))
    }
    @Test fun twoPeersExchangeRequestsTextReceiptsAndProfilePackets() {
        val a = ToxNative.create(byteArrayOf()); val b = ToxNative.create(byteArrayOf())
        assertTrue(a != 0L && b != 0L)
        val la = Listener(); val lb = Listener()
        try {
            // The same public bootstrap/relay path used by PULSO, on the phone's real network.
            listOf(a, b).forEach { handle ->
                val key = SocialProtocol.bytes("7E5668E0EE09E19F320AD47902419331FFEE147BB3606769CFBE921A2A2FD34C")
                ToxNative.bootstrap(handle, "144.217.167.73", 33445, key, false)
                ToxNative.bootstrap(handle, "144.217.167.73", 33445, key, true)
                val backup = SocialProtocol.bytes("2016A0F2797EE3A8B004BA623F11AAFC8146F1B8F45107232A1A1AECCE856674")
                ToxNative.bootstrap(handle, "144.172.88.203", 33445, backup, false)
                ToxNative.bootstrap(handle, "144.172.88.203", 443, backup, true)
            }
            fun await(label: String, timeout: Long = 90_000, condition: () -> Boolean) {
                val until = android.os.SystemClock.elapsedRealtime() + timeout
                while (!condition() && android.os.SystemClock.elapsedRealtime() < until) {
                    ToxNative.iterate(a, la); ToxNative.iterate(b, lb); Thread.sleep(25)
                }
                assertTrue(label, condition())
            }
            await("Both test identities must connect to Tox") { la.connected && lb.connected }
            val friendB = ToxNative.add(a, ToxNative.address(b), false)
            assertTrue(friendB >= 0)
            await("The second identity must receive the request") { lb.request != null }
            val friendA = ToxNative.add(b, lb.request!!, true)
            assertTrue(friendA >= 0)
            await("Both contacts must come online") { la.friendOnline && lb.friendOnline }
            val message = "Prueba PULSO · música 🎸 日本語"
            val receipt = ToxNative.send(a, friendB, message.toByteArray())
            assertTrue(receipt >= 0)
            await("Unicode text and delivery receipt") { lb.message == message && la.receipt == receipt }
            val profile = SocialProtocol.encode("profile", org.json.JSONObject().put("bio", "Música").put("favorites", false).put("rev", "test"))
            assertTrue(ToxNative.packet(b, friendA, profile))
            await("Profile packet") { la.packet != null }
            assertArrayEquals(profile, la.packet)
            assertEquals("profile", SocialProtocol.decode(la.packet!!)!!.getString("type"))
            assertTrue(ToxNative.remove(a, friendB))
            assertEquals(-1L, ToxNative.find(a, ToxNative.address(b).copyOfRange(0, 32)))
        } finally { if (a != 0L) ToxNative.close(a); if (b != 0L) ToxNative.close(b) }
    }
}
