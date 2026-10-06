package app.pulso.music

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

internal data class OfficialMessage(val id: String, val title: String, val text: String, val at: Long, val image: String)
internal data class OfficialFeed(val revision: Long, val messages: List<OfficialMessage>)
internal data class OfficialState(val messages: List<OfficialMessage> = emptyList(), val unread: Int = 0, val refreshing: Boolean = false, val error: String = "")

internal object OfficialMessages {
    private const val PUBLIC_KEY = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA5/KEJbPC2S+YMvFkGWR5gzzIizMvUtw6SAegKZz482G1ewMGUNW2f7DBIglhQFK7HWxFTrDqF/nXmf8/mgZljLFvpA8Q2stBVCNfS3RLL9BKlldMKck8W19cbzhTlKbEnlu0XAzShZxqNb5q8xYL69msLjdc8qGJyRqlUfmIgvaGOGSF1NN4M1cq+xAqtEhuATe5eAtPtvbMWZoHjibukVIE6qTP2Kwoo+32a84A0ixosd6gPY3n29/G3hfkQx5gvFawbglNJm4p9roVrN39lfAIQ2hQVa0tvzihWP3GW7hTcnn08/yhYEbupJmWyVKLmhBrtamf4Jb3RfzC/p2Ac8Hv8TDRIwPk3iVzOHf7KP7WvMfujV5C05kLtEjmwZ6gDPXGs37ugBnuf5RBJsC3mRTD8yuZ4GKM7vIENuz27UerYdtJtsh/0Fxf0A+JxCCp7QsN4fKH4mnK44wKRtUmdNStz+SzM1klCmfL2k7O/qccILHFF/4tHYvZ4OwxvTZbAgMBAAE="
    private const val MAX_ENVELOPE = 1_500_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val networkLock = Mutex()
    private val mutable = MutableStateFlow(OfficialState())
    val state = mutable.asStateFlow()
    private lateinit var context: Context
    private var loaded = false
    private var current: OfficialFeed? = null
    private var currentRaw = ""
    private var read = emptySet<String>()
    private var lastAttempt = 0L
    private val cache get() = AtomicFile(File(context.filesDir, "official-messages.json"))
    private val preferences get() = context.getSharedPreferences("official-messages", Context.MODE_PRIVATE)

    fun init(app: Context) {
        context = app.applicationContext
        scope.launch { lock.withLock { load() } }
    }
    private fun publish(error: String = "", refreshing: Boolean = false) {
        val messages = current?.messages.orEmpty()
        mutable.value = OfficialState(messages, messages.count { it.id !in read }, refreshing, error)
    }
    private fun load() {
        if (loaded) return
        read = preferences.getStringSet("read", emptySet()).orEmpty().toSet()
        val bundled = runCatching { context.assets.open("pulso-oficial.json").bufferedReader().use { it.readText() } }.getOrNull()
        val stored = runCatching { cache.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        listOfNotNull(bundled, stored).forEach { raw ->
            runCatching { decode(raw) }.getOrNull()?.let { feed ->
                if (feed.revision >= (current?.revision ?: 0L)) { current = feed; currentRaw = raw }
            }
        }
        loaded = true
        publish()
    }
    fun refresh(force: Boolean = false) = scope.launch {
        if (!networkLock.tryLock()) return@launch
        try {
            val now = System.currentTimeMillis()
            val requested = lock.withLock {
                load()
                if (!force && now - lastAttempt in 0..30_000) false
                else { lastAttempt = now; publish(refreshing = true); true }
            }
            if (!requested) return@launch
            try {
                val url = "https://raw.githubusercontent.com/${BuildConfig.UPDATE_REPOSITORY}/main/pulso-oficial.json?t=$now"
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                val raw = try {
                    connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = false; connection.useCaches = false
                    connection.setRequestProperty("Cache-Control", "no-cache")
                    connection.setRequestProperty("User-Agent", "Pulso-Official")
                    check(connection.responseCode == 200)
                    connection.inputStream.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer); if (count < 0) break
                            require(output.size() + count <= MAX_ENVELOPE)
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                } finally { connection.disconnect() }
                val feed = decode(raw)
                lock.withLock {
                    require(feed.revision >= (current?.revision ?: 0))
                    if (feed.revision == current?.revision) require(raw == currentRaw)
                    if (raw != currentRaw) {
                        val stream = cache.startWrite()
                        try { stream.write(raw.toByteArray(Charsets.UTF_8)); cache.finishWrite(stream) }
                        catch (e: Exception) { cache.failWrite(stream); throw e }
                        current = feed; currentRaw = raw
                    }
                    publish()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { lock.withLock { publish(error = "No se pudieron consultar las novedades. Puedes reintentar.") } }
        } finally { networkLock.unlock() }
    }
    fun markRead(ids: Set<String>) = scope.launch {
        lock.withLock {
            load()
            val valid = current?.messages.orEmpty().map { it.id }.toSet()
            val next = (read + ids.intersect(valid)).intersect(valid)
            if (next != read) {
                if (preferences.edit().putStringSet("read", next).commit()) read = next
                publish(error = mutable.value.error, refreshing = mutable.value.refreshing)
            }
        }
    }
    internal fun decode(raw: String): OfficialFeed {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_ENVELOPE)
        val envelope = JSONObject(raw)
        require(envelope.getInt("schema") == 1)
        val bytes = Base64.decode(envelope.getString("payload"), Base64.DEFAULT)
        require(bytes.size <= 1_048_576)
        val signature = Base64.decode(envelope.getString("signature"), Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        require(Signature.getInstance("SHA256withRSA").run { initVerify(key); update(bytes); verify(signature) })
        val payload = JSONObject(bytes.toString(Charsets.UTF_8))
        require(payload.getInt("schema") == 1 && payload.getString("channel") == "pulso-oficial")
        val revision = payload.getLong("revision"); require(revision > 0)
        val array = payload.getJSONArray("messages"); require(array.length() <= 2000)
        val messages = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id"); require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            val title = item.getString("title"); val text = item.getString("text")
            require(title.length in 1..120 && text.length in 1..4000)
            val at = item.getLong("at"); require(at > 0 && at <= revision)
            val image = item.optString("image")
            if (image.isNotEmpty()) {
                val uri = URI(image)
                require(image.length <= 1000 && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && (uri.port == -1 || uri.port == 443))
            }
            OfficialMessage(id, title, text, at, image)
        }
        require(messages.distinctBy { it.id }.size == messages.size)
        return OfficialFeed(revision, messages.sortedWith(compareBy<OfficialMessage> { it.at }.thenBy { it.id }))
    }
}
