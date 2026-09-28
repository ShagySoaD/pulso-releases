package app.pulso.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

object SearchSuggestions {
    fun eligible(query: String) = query.trim().length in 2..120 && !query.contains("://")
    fun fetch(query: String): List<String> {
        if (!eligible(query)) return emptyList()
        val connection = URI("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=es&q=" + URLEncoder.encode(query.trim(), "UTF-8"))
            .toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000; connection.readTimeout = 4000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            check(connection.responseCode == 200)
            return parse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }
    fun parse(raw: String): List<String> {
        val list = JSONArray(raw).optJSONArray(1) ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            when (val value = list.opt(index)) { is String -> value; is JSONArray -> value.optString(0); else -> null }
        }.map(String::trim).filter { it.isNotBlank() }.distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(6)
    }
}

/** Kept separate from result pagination so a late page cannot overwrite the typed prefix. */
class SuggestionController(private val scope: CoroutineScope,
    private val fetch: suspend (String) -> List<String> = { withContext(Dispatchers.IO) { SearchSuggestions.fetch(it) } },
    private val debounceMs: Long = 250) {
    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val items = mutable.asStateFlow()
    private var job: Job? = null
    private var revision = 0
    fun clear() { revision++; job?.cancel(); mutable.value = emptyList() }
    fun update(query: String) {
        clear()
        if (!SearchSuggestions.eligible(query)) return
        val request = revision
        job = scope.launch {
            delay(debounceMs)
            try {
                val suggestions = fetch(query.trim())
                ensureActive()
                if (revision == request) mutable.value = suggestions.take(6)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Suggestions are optional; manual search remains available. */ }
        }
    }
}
