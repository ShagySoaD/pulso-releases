package app.pulso.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class LyricLine(val millis: Long, val text: String)
object Lrc {
    private val stamp = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
    fun parse(text: String): List<LyricLine> = text.lineSequence().flatMap { line ->
        val content = line.replace(stamp, "").trim()
        stamp.findAll(line).map { m ->
            val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
            LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + fraction, content)
        }
    }.sortedBy { it.millis }.toList()
    fun active(lines: List<LyricLine>, position: Long) = lines.indexOfLast { it.millis <= position }
}
data class Lyrics(val text: String, val lines: List<LyricLine>, val source: String)
object LyricsRepository {
    private fun file(context: Context, id: String) = File(context.filesDir, "lyrics/${id.hashCode()}.json").also { it.parentFile?.mkdirs() }
    fun save(context: Context, id: String, raw: String): Lyrics {
        file(context, id).writeText(JSONObject().put("text", raw).put("source", "Archivo LRC").toString())
        return Lyrics(raw, Lrc.parse(raw), "Archivo LRC")
    }
    fun get(context: Context, track: Track): Lyrics {
        val cached = file(context, track.id)
        if (cached.exists()) { val j = JSONObject(cached.readText()); return Lyrics(j.getString("text"), Lrc.parse(j.getString("text")), j.getString("source")) }
        val q = URLEncoder.encode("${track.artist} ${track.title}", "UTF-8")
        val connection = URI("https://lrclib.net/api/search?q=$q").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "Pulso/0.1 (Android personal music player)")
        try {
            check(connection.responseCode == 200) { "No se pudieron consultar las letras. Puedes importar un LRC." }
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val candidates = (0 until array.length()).map { array.getJSONObject(it) }
            val j = candidates.minByOrNull { if (track.seconds > 0) kotlin.math.abs(it.optDouble("duration") - track.seconds) else if (!it.isNull("syncedLyrics")) 0.0 else 1.0 }
                ?: error("No se encontraron letras. Puedes importar un archivo LRC.")
            val raw = if (!j.isNull("syncedLyrics")) j.getString("syncedLyrics") else j.optString("plainLyrics").takeUnless { it == "null" }.orEmpty()
            check(raw.isNotBlank()) { "Esta canción no tiene letras disponibles." }
            // Search matches may differ from the recording; show the matched title explicitly.
            val source = "LRCLIB · ${j.optString("trackName")} — ${j.optString("artistName")}"
            cached.writeText(JSONObject().put("text", raw).put("source", source).toString())
            return Lyrics(raw, Lrc.parse(raw), source)
        } finally { connection.disconnect() }
    }
}
