package app.pulso.music

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.io.ByteArrayOutputStream

/** Public read-only endpoint. Publishing credentials never belong in the client APK. */
internal object StartupAnnouncement {
    private val mutex = Mutex()
    private var loaded = false
    var current by mutableStateOf<Announcement?>(null)
        private set
    var dismissed by mutableStateOf(false)
        private set
    private fun preferences(context: Context) = context.getSharedPreferences("announcements", Context.MODE_PRIVATE)

    suspend fun load(context: Context) = mutex.withLock {
        if (loaded) return@withLock
        val result = withContext(Dispatchers.IO) {
            val prefs = preferences(context)
            val repo = BuildConfig.UPDATE_REPOSITORY
            if (!AppUpdateInfo.validRepository(repo)) return@withContext null
            val now = System.currentTimeMillis()
            val fetched = try {
                val connection = URL("https://raw.githubusercontent.com/$repo/main/anuncio.json").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.setRequestProperty("Cache-Control", "no-cache")
                    connection.setRequestProperty("Accept", "application/json")
                    check(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            require(output.size() + count <= Announcement.MAX_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val raw = bytes.toString(Charsets.UTF_8)
                    val announcement = Announcement.parse(raw, repo)
                    prefs.edit().putString("cached", raw).putLong("fetchedAt", now).apply()
                    announcement
                } finally { connection.disconnect() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (Announcement.cacheFresh(prefs.getLong("fetchedAt", 0), now))
                    runCatching { Announcement.parse(prefs.getString("cached", "") ?: "", repo) }.getOrNull()
                else null
            }
            fetched?.takeIf { it.shouldShow(Instant.ofEpochMilli(now), prefs.getString("dismissedId", "") ?: "") }
        }
        current = result
        loaded = true
    }

    fun dismiss(context: Context) {
        current?.let { preferences(context).edit().putString("dismissedId", it.id).apply() }
        dismissed = true
    }
}

@Composable internal fun StartupAnnouncementHost() {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) { StartupAnnouncement.load(context) }
    val announcement = StartupAnnouncement.current ?: return
    if (StartupAnnouncement.dismissed || !announcement.shouldShow(Instant.now(), "")) return
    AlertDialog(
        onDismissRequest = { StartupAnnouncement.dismiss(context) },
        icon = { PulsoMark(Modifier.size(40.dp)) },
        title = { Text(announcement.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (announcement.imageUrl.isNotBlank()) AsyncImage(
                    model = announcement.imageUrl, contentDescription = announcement.title,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = ContentScale.Fit
                )
                if (announcement.message.isNotBlank()) Text(announcement.message)
            }
        },
        confirmButton = { TextButton(onClick = { StartupAnnouncement.dismiss(context) }) { Text("Continuar") } }
    )
}