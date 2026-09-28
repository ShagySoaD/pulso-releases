package app.pulso.music

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Public playlist metadata only. Never downloads Spotify audio or uses account cookies. */
data class SpotifyPlaylistPreview(val name: String, val url: String, val songs: List<ImportedSong>, val entries: Int, val skipped: Int, val completeIndex: Boolean = false) {
    fun file() = PlaylistFile(listOf(ImportedPlaylist(name, songs)), skipped)
    val warning get() = if (completeIndex) "La lista contiene $entries entradas; se pudieron leer ${songs.size} canciones." +
        if (skipped > 0) " $skipped entradas no se pudieron recuperar o no son canciones. Puedes volver a consultar antes de continuar." else " Se recuperaron todas las entradas."
        else "Spotify mostró $entries entradas; se pudieron leer ${songs.size} canciones. " +
        "La página pública puede mostrar solo una parte de la playlist (habitualmente hasta 100). " +
        "Comprueba la cantidad en Spotify; no podemos confirmar que esté completa."
}

object SpotifyPlaylist {
    private val idPattern = Regex("[A-Za-z0-9]{22}")
    fun playlistId(input: String): String {
        val raw = input.trim()
        require(raw.length in 1..2048) { "Pega un enlace de playlist de Spotify." }
        if (raw.startsWith("spotify:playlist:")) return raw.removePrefix("spotify:playlist:").also {
            require(idPattern.matches(it)) { "El identificador de la playlist no es válido." }
        }
        val uri = runCatching { URI(raw) }.getOrNull()
        require(uri != null && uri.scheme == "https" && uri.host.equals("open.spotify.com", true) && uri.userInfo == null && uri.port == -1) {
            "Usa el enlace completo https://open.spotify.com/playlist/… de una playlist pública."
        }
        val parts = uri.path.orEmpty().trim('/').split('/').let { if (it.firstOrNull()?.matches(Regex("intl-[A-Za-z-]+")) == true) it.drop(1) else it }
        require(parts.size == 2 && parts[0] == "playlist" && idPattern.matches(parts[1])) { "El enlace debe corresponder a una playlist, no a una canción o álbum." }
        return parts[1]
    }

    // Short links are resolved only through Spotify-controlled HTTPS hosts, with bounded redirects.
    fun resolveId(input: String): String {
        if (!input.trim().startsWith("https://spotify.link/")) return playlistId(input)
        var uri = URI(input.trim())
        repeat(5) {
            require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.host in setOf("spotify.link", "open.spotify.com")) { "El enlace redirigió fuera de Spotify." }
            if (uri.host == "open.spotify.com") return playlistId(uri.toString())
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                require(connection.responseCode in listOf(301, 302, 303, 307, 308)) { "No se pudo resolver el enlace corto. Copia el enlace completo desde Spotify." }
                uri = uri.resolve(connection.getHeaderField("Location") ?: error("Enlace corto incompleto."))
            } finally { connection.disconnect() }
        }
        error("Demasiadas redirecciones. Usa el enlace completo de la playlist.")
    }

    suspend fun fetch(input: String, progress: (Int, Int) -> Unit = { _, _ -> }): SpotifyPlaylistPreview = coroutineScope {
        val id = resolveId(input)
        fetchId(id, ::get, progress)
    }
    internal suspend fun fetchId(id: String, request: (String, String?) -> String, progress: (Int, Int) -> Unit = { _, _ -> }): SpotifyPlaylistPreview = coroutineScope {
        require(idPattern.matches(id))
        val html = request("https://open.spotify.com/embed/playlist/$id", null)
        val preview = parse(html, id)
        val root = document(html)
        val session = root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")?.optJSONObject("settings")?.optJSONObject("session")
        // Only the anonymous session supplied to every visitor of this public page is used.
        val token = session?.takeIf { it.optBoolean("isAnonymous") }?.optString("accessToken").orEmpty()
        if (token.isBlank()) return@coroutineScope preview
        val index = try { parseIndex(request("https://spclient.wg.spotify.com/playlist/v2/playlist/$id", token)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@coroutineScope preview }
        val entity = entity(root)
        val rows = entity.getJSONArray("trackList")
        val cached = (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { row -> song(row)?.let { row.optString("uri") to it } } }.toMap()
        val unique = index.uris.filter { it.matches(Regex("spotify:track:[A-Za-z0-9]{22}")) }.distinct()
        val completed = AtomicInteger(0)
        val slots = Semaphore(4)
        val limited = AtomicBoolean(false)
        val started = System.nanoTime()
        val metadata = unique.map { uri -> async(Dispatchers.IO) {
            slots.withPermit {
                ensureActive()
                val track = cached[uri] ?: if (limited.get() || System.nanoTime() - started > 180_000_000_000L) null else try {
                    delay(150)
                    val item = entity(document(request("https://open.spotify.com/embed/track/${uri.substringAfterLast(':')}", null)))
                    if (item.optString("uri") == uri) song(item) else null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RateLimited) { limited.set(true); null }
                catch (_: Exception) { null }
                progress(completed.incrementAndGet(), unique.size)
                uri to track
            }
        } }.awaitAll().toMap()
        val songs = index.uris.mapNotNull { metadata[it] }
        check(songs.isNotEmpty()) { "No se pudieron recuperar las canciones. Reintenta con conexión o usa un archivo CSV/JSON." }
        preview.copy(songs = songs, entries = index.total, skipped = index.total - songs.size, completeIndex = true)
    }

    internal class RateLimited : IllegalStateException("Spotify limitó las consultas. Espera un momento y vuelve a intentarlo.")

    internal data class Index(val total: Int, val uris: List<String>)
    internal fun parseIndex(raw: String): Index {
        val root = JSONObject(raw)
        val total = root.getInt("length")
        require(total in 0..PlaylistFiles.MAX_SONGS) { "La playlist supera el límite de 5000 entradas." }
        val contents = root.getJSONObject("contents")
        val items = contents.getJSONArray("items")
        check(contents.optInt("pos", -1) == 0 && !contents.optBoolean("truncated", true) && items.length() == total) { "Spotify no devolvió el índice completo." }
        return Index(total, (0 until items.length()).map { items.optJSONObject(it)?.optString("uri").orEmpty() })
    }

    private fun get(url: String, token: String? = null): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 25_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", if (token == null) "text/html" else "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            if (status == 429) throw RateLimited()
            check(status == 200) { when (status) {
                404 -> "La playlist no está disponible públicamente. Comprueba el enlace y su privacidad."
                429 -> "Spotify limitó las consultas. Espera un momento y vuelve a intentarlo."
                else -> "Spotify no permitió consultar la playlist (HTTP $status). Puedes importar un archivo CSV o JSON."
            } }
            return connection.inputStream.use { inputStream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = inputStream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 8 * 1048576) { "La respuesta de Spotify es demasiado grande." }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }

    private fun document(html: String): JSONObject {
        val data = Regex("<script\\b[^>]*\\bid\\s*=\\s*[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1) ?: error("Spotify no mostró las canciones en su página pública. Prueba con un archivo CSV o JSON.")
        return runCatching { JSONObject(data) }.getOrElse { error("Spotify devolvió una página que no se pudo interpretar.") }
    }
    private fun entity(root: JSONObject): JSONObject = root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: error("El formato de Spotify cambió o la playlist no está disponible.")
    internal fun song(row: JSONObject): ImportedSong? {
        val title = row.optString("title").trim()
        val artists = row.optJSONArray("artists")
        val artist = row.optString("subtitle").ifBlank { (0 until (artists?.length() ?: 0)).mapNotNull { artists?.optJSONObject(it)?.optString("name") }.joinToString(", ") }.replace('\u00a0', ' ').trim()
        if (!row.optString("uri").matches(Regex("spotify:track:[A-Za-z0-9]{22}")) || title.isBlank() || artist.isBlank() || title == "null" || artist == "null") return null
        check(title.length <= 1000 && artist.length <= 500) { "Spotify devolvió metadatos demasiado largos." }
        return ImportedSong(title, artist, seconds = (row.optLong("duration") / 1000).coerceIn(0, 86400))
    }
    fun parse(html: String, id: String): SpotifyPlaylistPreview {
        require(idPattern.matches(id))
        val entity = entity(document(html))
        check(entity.optString("uri") == "spotify:playlist:$id") { "Spotify devolvió una playlist distinta a la solicitada." }
        val rows = entity.optJSONArray("trackList") ?: error("Spotify no mostró canciones. La playlist puede ser privada o no estar disponible.")
        check(rows.length() <= PlaylistFiles.MAX_SONGS) { "La playlist supera el límite de 5000 canciones." }
        val songs = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            song(row)
        }
        check(songs.isNotEmpty()) { "No se encontraron canciones importables. La playlist puede estar vacía, ser privada o contener podcasts." }
        val name = entity.optString("name").ifBlank { entity.optString("title") }.trim().ifBlank { "Playlist de Spotify" }.take(80)
        return SpotifyPlaylistPreview(name, "https://open.spotify.com/playlist/$id", songs, rows.length(), rows.length() - songs.size)
    }
}
