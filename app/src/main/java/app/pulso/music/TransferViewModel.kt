package app.pulso.music

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class ImportDraft(val file: PlaylistFile, val results: Map<String, SongMatch> = emptyMap(), val sourceUrl: String = "", val sourceNotice: String = "") {
    val found get() = file.songs.count { results[it.key]?.chosen != null }
    fun json(): String = JSONObject()
        .put("source", JSONObject().put("playlists", JSONArray(file.playlists.map { JSONObject().put("name", it.name).put("songs", JSONArray(it.songs.map(ImportedSong::json))) })))
        .put("skipped", file.skipped).put("sourceUrl", sourceUrl).put("sourceNotice", sourceNotice)
        .put("results", JSONArray(results.map { (key, value) -> JSONObject().put("key", key).put("error", value.error)
            .put("chosen", value.chosen?.json()).put("suggestions", JSONArray(value.suggestions.map(Track::json))) })).toString()
    fun library(): LibraryState = LibraryState(results.values.mapNotNull { it.chosen }.distinctBy { it.id },
        file.playlists.map { list -> Playlist(list.name, list.songs.mapNotNull { results[it.key]?.chosen?.id }.distinct()) })
    companion object {
        fun parse(raw: String): ImportDraft {
            val root = JSONObject(raw)
            val file = PlaylistFiles.parse(root.getJSONObject("source").toString(), "Importación.json").copy(skipped = root.optInt("skipped"))
            val results = root.getJSONArray("results")
            return ImportDraft(file, (0 until results.length()).associate { index ->
                val row = results.getJSONObject(index); val options = row.getJSONArray("suggestions")
                row.getString("key") to SongMatch(row.optJSONObject("chosen")?.let(Track::from), (0 until options.length()).map { Track.from(options.getJSONObject(it)) }, row.optBoolean("error"))
            }, root.optString("sourceUrl"), root.optString("sourceNotice"))
        }
    }
}
data class TransferState(val screen: String = "", val busy: Boolean = false, val draft: ImportDraft? = null,
    val backup: LibraryBackup? = null, val message: String = "", val current: String = "", val running: Boolean = false,
    val spotify: SpotifyPlaylistPreview? = null)

class TransferViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(TransferState(busy = true))
    val state = mutable.asStateFlow()
    private val pending = AtomicFile(File(application.filesDir, "playlist-import.json"))
    private val writing = Mutex()
    private var searchJob: Job? = null
    init {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { runCatching { ImportDraft.parse(pending.readFully().toString(Charsets.UTF_8)) }.getOrNull() }
            mutable.update { it.copy(draft = restored, busy = false) }
        }
    }
    fun showImport() { mutable.update { it.copy(screen = "import", backup = null, message = "") } }
    fun hide() { mutable.update { it.copy(screen = "") } }
    fun consultSpotify(link: String) {
        if (mutable.value.busy || mutable.value.running || mutable.value.draft != null) return
        mutable.update { it.copy(busy = true, spotify = null, message = "Consultando la playlist pública…") }
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) { SpotifyPlaylist.fetch(link) { done, total ->
                    mutable.update { it.copy(message = "Leyendo canciones de Spotify: $done de $total…") }
                } }
                mutable.update { it.copy(spotify = preview, message = "") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "No se pudo consultar Spotify. Comprueba tu conexión.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun importSpotify() {
        val preview = mutable.value.spotify ?: return
        if (mutable.value.busy || mutable.value.running || mutable.value.draft != null) return
        mutable.update { it.copy(busy = true, draft = ImportDraft(PlaylistFiles.combine(listOf(preview.file())), sourceUrl = preview.url, sourceNotice = preview.warning), message = "") }
        viewModelScope.launch {
            try { persist(); mutable.update { it.copy(spotify = null) } }
            catch (_: Exception) { mutable.update { it.copy(message = "No se pudo guardar el borrador. Revisa el espacio disponible.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun persist() = writing.withLock {
        val draft = mutable.value.draft
        withContext(Dispatchers.IO) {
            if (draft == null) pending.delete() else {
                val output = pending.startWrite()
                try { output.write(draft.json().toByteArray(Charsets.UTF_8)); pending.finishWrite(output) }
                catch (error: Exception) { pending.failWrite(output); throw error }
            }
        }
    }
    private fun filename(uri: Uri): String = getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else "Playlist"
    } ?: "Playlist"
    private fun read(uri: Uri, maximum: Int): String {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val size = input.read(buffer); if (size < 0) break; require(output.size() + size <= maximum) { "El archivo supera el límite de ${maximum / 1048576} MB." }; output.write(buffer, 0, size) }
            output.toByteArray()
        } ?: error("No se pudo abrir el archivo.")
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return try { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: java.nio.charset.CharacterCodingException) { error("Guarda el archivo con codificación UTF-8 o UTF-16.") }
    }
    fun openPlaylists(uris: List<Uri>) {
        if (uris.isEmpty() || mutable.value.busy || mutable.value.running) return
        mutable.update { it.copy(busy = true, message = "Leyendo playlists…", screen = "import") }
        viewModelScope.launch {
            try {
                require(uris.size <= 20) { "Selecciona hasta 20 archivos por vez." }
                val file = withContext(Dispatchers.IO) {
                    var total = 0
                    PlaylistFiles.combine(uris.map {
                        val parsed = PlaylistFiles.parse(read(it, 8 * 1048576), filename(it))
                        total += parsed.playlists.sumOf { list -> list.songs.size }
                        require(total <= PlaylistFiles.MAX_SONGS) { "Importa hasta 5000 canciones por vez." }
                        parsed
                    })
                }
                mutable.update { it.copy(draft = ImportDraft(file), spotify = null, message = "") }; persist()
            } catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "No se pudo leer la playlist.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun search(retry: Boolean = false) {
        if (mutable.value.busy || mutable.value.running || mutable.value.draft == null) return
        if (retry) mutable.update { state -> state.copy(draft = state.draft?.let { it.copy(results = it.results.filterValues { match -> match.chosen != null || match.suggestions.isNotEmpty() }) }) }
        mutable.update { it.copy(running = true, message = "Buscando coincidencias…") }
        searchJob = viewModelScope.launch {
            var failures = 0; var changes = 0
            val songs = mutable.value.draft!!.file.songs
            fun localKey(title: String, artist: String) = PlaylistFiles.normalize(title) + ":" + PlaylistFiles.normalize(MusicTitles.artist(artist))
            val local = Library.state.value.tracks.filter { !it.id.startsWith("local-") }.groupBy { localKey(it.title, it.artist) }
            try {
                for (song in songs) {
                    ensureActive()
                    if (mutable.value.draft?.results?.containsKey(song.key) == true) continue
                    mutable.update { it.copy(current = "${song.title} · ${song.artist}") }
                    val result = try {
                        val saved = SongMatching.resolve(song, local[localKey(song.title, song.artist)].orEmpty())
                        val resolved = if (saved.chosen != null) saved else withContext(Dispatchers.IO) { SongMatching.resolve(song, MusicCatalog.search("${song.title} ${song.artist}")) }
                        failures = 0; resolved
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failures++; SongMatch(error = true) }
                    mutable.update { state -> state.copy(draft = state.draft?.let { it.copy(results = it.results + (song.key to result)) }) }
                    changes++
                    if (changes % 10 == 0) persist()
                    if (failures >= 3) { mutable.update { it.copy(message = "La búsqueda se pausó por problemas de conexión. Puedes reintentar los pendientes.") }; break }
                    delay(300)
                }
                if (failures < 3) mutable.update { it.copy(message = "Búsqueda terminada. Revisa las coincidencias pendientes antes de guardar.") }
            } catch (_: CancellationException) { mutable.update { it.copy(message = "Importación pausada. Puedes continuar aquí.") } }
            catch (_: Exception) { mutable.update { it.copy(message = "No se pudo continuar. Los resultados encontrados siguen disponibles.") } }
            finally {
                withContext(NonCancellable) { runCatching { persist() }.onFailure { mutable.update { it.copy(message = "No se pudo guardar el progreso. Revisa el espacio disponible.") } } }
                mutable.update { it.copy(running = false, current = "") }
            }
        }
    }
    fun pause() { searchJob?.cancel() }
    fun choose(song: ImportedSong, track: Track) {
        if (mutable.value.running || mutable.value.busy) return
        val draft = mutable.value.draft ?: return
        val match = draft.results[song.key] ?: return
        if (track !in match.suggestions) return
        mutable.update { it.copy(draft = draft.copy(results = draft.results + (song.key to match.copy(chosen = track)))) }
        viewModelScope.launch { runCatching { persist() }.onFailure { mutable.update { it.copy(message = "No se pudo guardar la elección. Revisa el espacio disponible.") } } }
    }
    fun clearChoice(song: ImportedSong) {
        if (mutable.value.running || mutable.value.busy) return
        val draft = mutable.value.draft ?: return
        val match = draft.results[song.key] ?: return
        mutable.update { it.copy(draft = draft.copy(results = draft.results + (song.key to match.copy(chosen = null)))) }
        viewModelScope.launch { runCatching { persist() }.onFailure { mutable.update { it.copy(message = "No se pudo guardar la elección.") } } }
    }
    fun discard() {
        if (mutable.value.running || mutable.value.busy) return
        mutable.update { it.copy(draft = null, spotify = null, message = "") }
        viewModelScope.launch { persist() }
    }
    fun saveImport() {
        val draft = mutable.value.draft ?: return
        if (mutable.value.running || mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Library.merge(draft.library()) }
                val missing = draft.file.songs.size - draft.found
                mutable.update { it.copy(message = "${draft.file.playlists.size} playlists guardadas · ${draft.found} canciones encontradas${if (missing > 0) " · $missing pendientes" else ""}.") }
                // Keep incomplete drafts so omitted songs can be reviewed/retried later.
                if (missing == 0) mutable.update { it.copy(draft = null) }
                persist()
            } catch (_: Exception) { mutable.update { it.copy(message = "No se pudo guardar la importación. Revisa el espacio disponible y reintenta.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun preferences(): PortablePreferences {
        val context = getApplication<Application>()
        val audio = context.getSharedPreferences("audio", Context.MODE_PRIVATE)
        val count = (audio.all.keys.mapNotNull { it.removePrefix("band").toIntOrNull() }.maxOrNull()?.plus(1) ?: 0).coerceAtMost(32)
        val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val custom = ThemeChoice.entries.mapNotNull { choice ->
            ThemeColors.decode(appearance.getString("custom_${choice.id}", null))?.let { choice.id to it.encode() }
        }.toMap()
        return PortablePreferences(ThemeChoice.fromId(appearance.getString("theme", null)).id,
            audio.getBoolean("enabled", false), (0 until count).map { audio.getFloat("band$it", 0f) }, custom)
    }
    fun exportBackup(uri: Uri) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = "Creando respaldo…") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = BackupFiles.create(Library.state.value, preferences(), System.currentTimeMillis())
                    BackupFiles.parse(json)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("Archivo no disponible")
                }
                mutable.update { it.copy(message = "Respaldo guardado. Consérvalo fuera del teléfono antes de restablecerlo.") }
            } catch (_: Exception) { mutable.update { it.copy(message = "No se pudo completar el respaldo. Reintenta eligiendo otra ubicación.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun openBackup(uri: Uri) {
        if (mutable.value.busy || mutable.value.running) return
        mutable.update { it.copy(busy = true, screen = "restore", backup = null, message = "Leyendo respaldo…") }
        viewModelScope.launch {
            try {
                val backup = withContext(Dispatchers.IO) { BackupFiles.parse(read(uri, BackupFiles.MAX_BYTES)) }
                mutable.update { it.copy(backup = backup, message = "") }
            } catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "Respaldo inválido. Tu biblioteca no cambió.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun restore(includePreferences: Boolean) {
        val backup = mutable.value.backup ?: return
        if (mutable.value.busy || mutable.value.running) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Library.merge(BackupFiles.restoreLibrary(backup)) }
                if (includePreferences) {
                    val context = getApplication<Application>()
                    val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
                        .putString("theme", backup.preferences.theme)
                    ThemeChoice.entries.forEach { choice -> appearance.putString("custom_${choice.id}", backup.preferences.customThemes[choice.id]) }
                    appearance.apply()
                    val editor = context.getSharedPreferences("audio", Context.MODE_PRIVATE).edit().clear().putBoolean("enabled", backup.preferences.equalizer)
                    backup.preferences.bands.forEachIndexed { index, value -> editor.putFloat("band$index", value) }; editor.apply()
                    AudioSettings.change(backup.preferences.equalizer, backup.preferences.bands)
                }
                mutable.update { it.copy(backup = null, screen = "", message = "Respaldo restaurado. Tus playlists y favoritas están en Biblioteca.") }
            } catch (_: Exception) { mutable.update { it.copy(message = "No se completó la restauración. Puedes reintentar; no se borró la biblioteca existente.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
