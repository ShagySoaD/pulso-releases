package app.pulso.music

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SearchResult(val name: String, val tracks: List<Track>, val playlist: Boolean, val next: String? = null)
data class ResolvedAudio(val url: String, val headers: Map<String, String>, val expires: Long)

object YouTubeEngine {
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val streams = ConcurrentHashMap<String, ResolvedAudio>()
    fun invalidate(id: String) { streams.remove(id) }
    @Synchronized fun init(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("engine", 0)
        if (prefs.getInt("bundledRevision", 0) < 3) {
            // Replace only the executable, never the user's music or library.
            val target = File(app.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
            target.parentFile!!.mkdirs()
            val atomic = android.util.AtomicFile(target)
            val stream = atomic.startWrite()
            try {
                app.resources.openRawResource(R.raw.ytdlp).use { it.copyTo(stream) }
                atomic.finishWrite(stream)
                check(prefs.edit().putInt("bundledRevision", 3).commit())
            } catch (error: Exception) { atomic.failWrite(stream); throw error }
        }
        YoutubeDL.init(app)
        FFmpeg.init(app)
    }
    fun verify(context: Context): String {
        init(context)
        val folder = File(context.applicationInfo.nativeLibraryDir)
        listOf("libpython.so", "libpython.zip.so", "libqjs.so", "libffmpeg.so", "libffmpeg.zip.so").forEach {
            check(File(folder, it).length() > 0) { "Falta un componente instalado: $it" }
        }
        val version = execute(context, YoutubeDLRequest("--version"), seconds = 30).trim()
        check(version.matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2}.*"))) { "El motor no respondió a la comprobación de ejecución." }
        return version
    }
    private fun request(target: String) = YoutubeDLRequest(target).apply {
        addOption("--ignore-config"); addOption("--socket-timeout", "20"); addOption("--retries", "2")
        addOption("--no-warnings"); addOption("--no-check-formats")
    }
    private fun execute(context: Context, request: YoutubeDLRequest, id: String = UUID.randomUUID().toString(), seconds: Long = 120, progress: ((Float, Long, String) -> Unit)? = null): String {
        init(context)
        val timeout = timer.schedule({ YoutubeDL.destroyProcessById(id) }, seconds, TimeUnit.SECONDS)
        try { return YoutubeDL.execute(request, id, progress).out }
        finally { timeout.cancel(false) }
    }
    fun search(context: Context, input: String): SearchResult {
        val target = YouTubeInput.target(input)
        if (target.startsWith("ytsearch")) {
            val page = MusicCatalog.page(input.trim())
            return SearchResult("Canciones", page.tracks, false, page.next)
        }
        val request = request(target).apply { addOption("--flat-playlist"); addOption("--dump-single-json"); addOption("--playlist-end", "100"); addOption("--skip-download") }
        val json = JSONObject(execute(context, request))
        val entries = json.optJSONArray("entries")
        val tracks = if (entries == null) listOfNotNull(parseTrack(json)) else (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.let(::parseTrack) }
        return SearchResult(json.optString("title", "Resultados"), tracks.distinctBy { it.id }, !target.startsWith("ytsearch") && entries != null)
    }
    private fun parseTrack(j: JSONObject): Track? {
        val id = j.optString("id")
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        val title = j.optString("track").takeUnless { it.isBlank() || it == "null" } ?: j.optString("title", "Sin título")
        val artist = MusicTitles.artist(listOf("artist", "uploader", "channel").map { j.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" } ?: "Artista desconocido")
        val cleanTitle = MusicTitles.clean(title).removePrefix("$artist - ").trim().ifBlank { title }
        val cover = CoverArt.best(j.optJSONArray("thumbnails")).ifBlank { j.optString("thumbnail").takeIf { it.startsWith("https://") }.orEmpty() }
        return Track(id, cleanTitle, artist, cover.ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }, j.optDouble("duration", 0.0).toLong())
    }
    fun resolve(context: Context, id: String): ResolvedAudio {
        streams[id]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return it }
        require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        val r = request("https://www.youtube.com/watch?v=$id").apply { addOption("--no-playlist"); addOption("-f", "bestaudio[ext=m4a]/bestaudio"); addOption("--dump-single-json") }
        val json = JSONObject(execute(context, r))
        val url = json.optString("url")
        require(url.startsWith("https://")) { "YouTube no devolvió un audio reproducible. Actualiza el motor en Ajustes." }
        val h = json.optJSONObject("http_headers") ?: JSONObject()
        return ResolvedAudio(url, h.keys().asSequence().associateWith { h.getString(it) }, System.currentTimeMillis() + 15 * 60_000).also { streams[id] = it }
    }
    fun download(context: Context, track: Track, folder: File, processId: String, progress: (Float, Long, String) -> Unit): File {
        require(track.id.matches(Regex("[A-Za-z0-9_-]{11}")))
        folder.mkdirs()
        val request = request("https://www.youtube.com/watch?v=${track.id}").apply {
            addOption("--no-playlist"); addOption("-f", "bestaudio"); addOption("-x"); addOption("--audio-format", "mp3"); addOption("--audio-quality", "192K")
            addOption("--embed-metadata"); addOption("--no-mtime"); addOption("-o", File(folder, "audio.%(ext)s").absolutePath)
        }
        execute(context, request, processId, 1800, progress)
        return File(folder, "audio.mp3").also { check(it.isFile && it.length() > 0) { "No se pudo guardar el audio." } }
    }
    fun update(context: Context): String { init(context); YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE); streams.clear(); return YoutubeDL.versionName(context) ?: "Actualizado" }
}
