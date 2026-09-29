package app.pulso.music

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal class UpdateFailure(message: String) : IOException(message)

internal object UpdateTransfer {
    fun allowed(uri: URI) = uri.scheme == "https" && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) && uri.host in setOf(
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    fun connection(initial: String): HttpURLConnection {
        var uri = URI(initial)
        repeat(6) {
            if (!allowed(uri)) throw UpdateFailure("La descarga redirige a un servidor no autorizado.")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 30000
                connection.readTimeout = 90000
                connection.setRequestProperty("User-Agent", "Pulso-Android-Updater")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) throw UpdateFailure("El servidor no indicó dónde descargar la actualización.")
                    uri = uri.resolve(location)
                    connection.disconnect()
                } else {
                    if (status != 200) {
                        throw UpdateFailure("El servidor de actualizaciones respondió HTTP $status. Reintenta más tarde.")
                    }
                    return connection
                }
            } catch (e: Exception) { connection.disconnect(); throw e }
        }
        throw UpdateFailure("El servidor devolvió demasiadas redirecciones.")
    }

    suspend fun copyVerified(input: InputStream, output: OutputStream, expectedSize: Long, sha: String,
        progress: suspend (Int) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var total = 0L
        var previous = -1
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > expectedSize) throw UpdateFailure("La APK recibida tiene un tamaño diferente al publicado. No se instalará.")
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            val percent = (100 * total / expectedSize).toInt()
            if (percent != previous) { progress(percent); previous = percent }
        }
        if (total != expectedSize) throw UpdateFailure("La descarga quedó incompleta. Vuelve a descargarla.")
        val actual = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        if (actual != sha) throw UpdateFailure("La descarga no coincide con la huella publicada. No se instalará; vuelve a descargarla.")
    }
}
