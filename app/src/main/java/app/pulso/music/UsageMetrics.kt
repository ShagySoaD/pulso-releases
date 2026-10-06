package app.pulso.music

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ApiFailure(val status: Int) : Exception("El servidor respondió HTTP $status.")
internal object PulsoHttp {
    fun request(url: String, method: String = "GET", body: String? = null,
                headers: Map<String, String> = emptyMap(), limit: Int = 2_500_000): String {
        require(url.startsWith("https://"))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000; connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode !in 200..299) throw ApiFailure(connection.responseCode)
            return connection.inputStream.use { stream ->
                val bytes = boundedBytes(stream, limit)
                bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }
}

internal fun boundedBytes(stream: java.io.InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        require(output.size() + n <= limit) { "El archivo supera el tamaño permitido." }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}

// Session survives restarts, but is excluded from backups by the application manifest.
internal class MetricsVault(context: Context) {
    private val prefs = context.getSharedPreferences("metrics-session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("pulso-metrics", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pulso-metrics", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject? {
        val raw = prefs.getString("session", null) ?: return null
        val parts = raw.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return JSONObject(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8))
    }
    fun write(session: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("session", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }
}

internal object MetricsApi {
    val configured get() = BuildConfig.METRICS_URL.isNotBlank() && BuildConfig.METRICS_KEY.isNotBlank()
    fun call(path: String, body: JSONObject, token: String? = null): JSONObject {
        check(configured) { "Estadísticas no configuradas en esta compilación." }
        val headers = mutableMapOf("apikey" to BuildConfig.METRICS_KEY)
        token?.let { headers["Authorization"] = "Bearer $it" }
        val raw = PulsoHttp.request(BuildConfig.METRICS_URL + path, "POST", body.toString(), headers)
        return if (raw.isBlank() || raw == "null") JSONObject() else JSONObject(raw)
    }
    fun login(email: String, password: String) = call("/auth/v1/token?grant_type=password",
        JSONObject().put("email", email.trim()).put("password", password))
    fun statistics(token: String) = call("/rest/v1/rpc/pulso_statistics", JSONObject(), token)
}

internal object UsageMetrics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null
    private var context: Context? = null
    private var foreground = false
    private var access: String? = null
    private var expires = 0L
    fun start(c: Context) { context = c.applicationContext; foreground = true; schedule() }
    fun stop() { foreground = false; job?.cancel(); job = null }
    private fun schedule() {
        job?.cancel()
        val c = context ?: return
        if (!foreground || !MetricsApi.configured) return
        job = scope.launch {
            while (isActive) {
                mutex.withLock {
                    try {
                        val token = session(c)
                        MetricsApi.call("/rest/v1/rpc/pulso_heartbeat", JSONObject(), token)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Network failure never interrupts music or creates a new identity. */ }
                }
                delay(120_000)
            }
        }
    }
    private fun session(c: Context): String {
        access?.takeIf { System.currentTimeMillis() < expires }?.let { return it }
        val vault = MetricsVault(c)
        val previous = vault.read()
        val fresh = if (previous == null) MetricsApi.call("/auth/v1/signup", JSONObject())
        else MetricsApi.call("/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", previous.getString("refresh_token")))
        vault.write(fresh)
        val token = fresh.getString("access_token")
        access = token
        expires = System.currentTimeMillis() + (fresh.optLong("expires_in", 3600) - 90).coerceAtLeast(0) * 1000
        return token
    }
    @Composable fun Settings() {
        if (!MetricsApi.configured) return
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Estadísticas de conexión", style = MaterialTheme.typography.titleMedium)
                Text("Se registran automáticamente para conocer el volumen de usuarios conectados a PULSO. Solo se envían un identificador aleatorio y la última conexión, no tus canciones, mensajes ni contactos.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
