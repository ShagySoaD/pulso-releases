package app.pulso.music

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest

class UpdateTransferTest {
    private val bytes = ByteArray(150000) { (it % 251).toByte() }
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    @Test fun copiesBinaryContentAndVerifiesFullDigest() = runBlocking {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        UpdateTransfer.copyVerified(bytes.inputStream(), output, bytes.size.toLong(), sha(bytes)) { progress += it }
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun rejectsTruncatedOversizedAndTamperedDownloads() = runBlocking {
        for (data in listOf(bytes.copyOf(1000), bytes + byteArrayOf(1), bytes.copyOf().apply { this[0] = 42 })) {
            try {
                UpdateTransfer.copyVerified(data.inputStream(), ByteArrayOutputStream(), bytes.size.toLong(), sha(bytes)) { }
                fail("Unverified content accepted")
            } catch (_: UpdateFailure) { }
        }
    }

    @Test fun redirectChecksAcceptSignedAssetsButRejectOtherHosts() {
        assertTrue(UpdateTransfer.allowed(URI("https://release-assets.githubusercontent.com/a.apk?signature=abc")))
        for (url in listOf("http://github.com/a", "https://github.com.evil.test/a", "https://user@github.com/a", "https://github.com:8443/a"))
            assertFalse(url, UpdateTransfer.allowed(URI(url)))
    }

    @Test fun livePublishedApkDownloadsWithProductionTransport() = runBlocking {
        assumeTrue(System.getenv("PULSO_LIVE_UPDATES") == "1")
        val manifest = UpdateTransfer.connection("https://github.com/ShagySoaD/pulso-releases/releases/latest/download/update.json")
        val info = try { AppUpdateInfo.parse(manifest.inputStream.bufferedReader().use { it.readText() }, "ShagySoaD/pulso-releases") }
            finally { manifest.disconnect() }
        val asset = info.compatible(listOf("arm64-v8a"), 29)!!
        val connection = UpdateTransfer.connection(asset.url)
        try {
            connection.inputStream.use { input ->
                val sink = object : java.io.OutputStream() {
                    override fun write(b: Int) { }
                    override fun write(b: ByteArray, off: Int, len: Int) { }
                }
                UpdateTransfer.copyVerified(input, sink, asset.size, asset.sha256) { }
            }
        } finally { connection.disconnect() }
    }
}
