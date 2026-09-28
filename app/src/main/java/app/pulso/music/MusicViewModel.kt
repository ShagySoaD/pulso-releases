package app.pulso.music

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class MusicState(
    val batchList: String = "", val batchBusy: Boolean = false, val batchMessage: String = "", val busy: Boolean = true, val message: String = "Preparando tu música…", val error: Boolean = false,
    val nextPage: String? = null, val loadingMore: Boolean = false, val pageError: String = "", val searchSerial: Int = 0,
    val results: List<Track> = emptyList(), val resultTitle: String = "", val remotePlaylist: Boolean = false,
    val current: Track? = null, val playing: Boolean = false, val playRequested: Boolean = false, val buffering: Boolean = false, val playbackMessage: String = "", val position: Long = 0, val duration: Long = 0,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF, val queue: List<Track> = emptyList(),
    val diagnostics: String = "", val lyrics: Lyrics? = null, val lyricsMessage: String = "", val engineVersion: String = "Incluido"
)

@UnstableApi
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    val explore = ExploreController(viewModelScope, application)
    val suggestions = SuggestionController(viewModelScope)
    private val communityMutable = MutableStateFlow<List<CommunityPlaylist>>(emptyList())
    val homeCommunity = communityMutable.asStateFlow()
    private var communityJob: Job? = null
    private val mutable = MutableStateFlow(MusicState())
    val state = mutable.asStateFlow()
    val library = Library.state
    private val discoveryMutable = MutableStateFlow(DiscoveryState())
    val discovery = discoveryMutable.asStateFlow()
    private val discoveryCache = DiscoveryCache(application)
    private var discoveryJob: Job? = null
    private var discoveryKey = ""
    private var discoveryRotation = 0L
    private val artistRepository = ArtistRepository(application)
    private val artistMutable = MutableStateFlow(ArtistScreenState())
    val artistScreen = artistMutable.asStateFlow()
    private var artistJob: Job? = null
    private var artistMoreJob: Job? = null
    private var artistRetry: (() -> Unit)? = null
    private val artistHistory = mutableListOf<ArtistScreenState>()
    private val artistPages = mutableSetOf<String>()
    val downloads = WorkManager.getInstance(application).getWorkInfosByTagFlow("downloads").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val controllerFuture = MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private var controller: MediaController? = null
    private var radarQueueIds: MutableSet<String>? = null
    private var lyricsJob: Job? = null
    private var pageJob: Job? = null
    private var activeQuery = ""
    private val consumedPages = mutableSetOf<String>()
    private val pendingDownloads = mutableSetOf<String>()
    private val pendingRemovals = mutableSetOf<String>()
    private val noticeChannel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val notices = noticeChannel.receiveAsFlow()
    private fun notice(text: String) { noticeChannel.trySend(text) }
    init {
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess { c ->
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { sync() }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { lyricsJob?.cancel(); mutable.update { it.copy(lyrics = null, lyricsMessage = "") }; sync() }
                })
                sync()
            }.onFailure { message("No se pudo iniciar el reproductor: ${it.message}", true) }
        }, ContextCompat.getMainExecutor(application))
        viewModelScope.launch { while (isActive) { sync(); delay(500) } }
        viewModelScope.launch {
            discovery.map { it.mixes.firstOrNull { mix -> mix.kind == "radar" }?.tracks.orEmpty() }.distinctUntilChanged().collect { songs ->
                val queued = radarQueueIds ?: return@collect
                val c = controller ?: return@collect
                val currentIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
                if (currentIds.isEmpty() || currentIds.any { it !in queued }) { radarQueueIds = null; return@collect }
                val additions = songs.filter { it.id !in queued && it.id !in pendingRemovals }.take((200 - queued.size).coerceAtLeast(0))
                if (additions.isNotEmpty()) {
                    runCatching {
                        Library.saveAll(additions)
                        c.addMediaItems(additions.map(::media))
                        queued.addAll(additions.map { it.id })
                    }.onFailure {
                        radarQueueIds = null
                        notice("No se pudo ampliar Radar. Puedes seguir escuchando la selección cargada.")
                    }
                }
            }
        }
        viewModelScope.launch {
            PlaybackFeedback.message.collect { text ->
                mutable.update { it.copy(playbackMessage = text) }
                if (text.isNotBlank() && text != "Reconectando…") notice(text)
            }
        }
        viewModelScope.launch {
            var previous = emptyMap<java.util.UUID, WorkInfo.State>()
            downloads.collect { works ->
                works.forEach { work ->
                    val was = previous[work.id]
                    if (was != null && !was.isFinished && work.state.isFinished) {
                        val id = work.tags.firstOrNull { it.startsWith("track:") }?.removePrefix("track:")
                        val title = id?.let(Library::track)?.title ?: "Canción"
                        if (work.state == WorkInfo.State.SUCCEEDED) notice("Guardada: $title")
                        if (work.state == WorkInfo.State.FAILED) notice("No se pudo guardar: $title. Puedes reintentar en Biblioteca.")
                    }
                }
                previous = works.associate { it.id to it.state }
            }
        }
        prepareEngine(false)
        viewModelScope.launch {
            state.map { it.current?.id }.distinctUntilChanged().collectLatest { id ->
                val track = id?.let(Library::track) ?: return@collectLatest
                if (CoverArt.needsCatalogCover(track)) {
                    try {
                        val cover = withContext(Dispatchers.IO) { DiscoveryCatalog.related(track.id).firstOrNull { it.id == track.id }?.artwork }
                        if (!cover.isNullOrBlank() && !CoverArt.needsCatalogCover(track.copy(artwork = cover)))
                            Library.updateArtwork(track.id, track.artwork, cover)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep the existing cover; playback does not depend on this lookup. */ }
                }
            }
        }
        // Load once for this screen session. Library edits must not replace the
        // recommendations the user is browsing; explicit refresh reads fresh tastes.
        refreshDiscovery()
    }
    fun exploreGenre(genre: String?) {
        if (genre != null && genre !in DiscoveryPolicy.genres) return
        discoveryMutable.update { it.copy(genre = genre) }
        refreshDiscovery()
    }
    fun refreshDiscovery(force: Boolean = false) {
        val genre: String? = null
        val librarySnapshot = library.value
        val tracks = librarySnapshot.tracks
        val playlists = librarySnapshot.playlists
        val key = DiscoveryPolicy.key(tracks, genre, playlists)
        if (force || discoveryKey != key) {
            communityJob?.cancel()
            communityMutable.value = emptyList()
            val anchors = DiscoveryPolicy.seeds(tracks, System.currentTimeMillis() / 86_400_000L, playlists).take(3)
            communityJob = viewModelScope.launch {
                anchors.forEach { anchor ->
                    try {
                        val page = withContext(Dispatchers.IO) { ExploreCatalog.search(anchor.artist, SearchCategory.PLAYLISTS) }
                        ensureActive()
                        communityMutable.update { (it + page.playlists.take(4)).distinctBy { p -> p.id }.take(12) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Offline: downloaded songs and cached mixes remain available. */ }
                }
            }
        }
        if (force) discoveryRotation++
        discoveryJob?.cancel()
        val previous = discovery.value
        discoveryKey = key
        if (genre == null && DiscoveryPolicy.tastes(tracks, playlists).isEmpty()) {
            discoveryMutable.value = DiscoveryState(message = "Añade favoritas, crea una playlist o descarga canciones para descubrir música.")
            return
        }
        discoveryMutable.value = previous.copy(loading = true, message = "Buscando música para ti…")
        discoveryJob = viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { discoveryCache.read() }?.takeIf { !force || it.key == key }
                if (cached != null && previous.mixes.isEmpty()) discoveryMutable.update { it.copy(mixes = cached.mixes, updated = cached.time) }
                // Existing recommendations stay stable across reopening as well.
                // Only the refresh button rebuilds them from the current library.
                if (!force && cached?.mixes?.isNotEmpty() == true) {
                    discoveryMutable.update { it.copy(message = "") }
                    return@launch
                }
                val seeds = DiscoveryPolicy.seeds(tracks, System.currentTimeMillis() / 86_400_000L + discoveryRotation, playlists).toMutableList()
                val known = DiscoveryPolicy.tastes(tracks, playlists).map { it.id }.toSet()
                val personalTracks = DiscoveryPolicy.tastes(tracks, playlists)
                val mixes = mutableListOf<DiscoveryMix>()
                fun homeMixes() = listOfNotNull(DiscoveryPolicy.radar(mixes, known)) + DiscoveryPolicy.shelves(mixes, personalTracks)
                var failures = 0
                var seedIndex = 0
                while (seedIndex < seeds.size) {
                    val seed = seeds[seedIndex++]
                    ensureActive()
                    try {
                        val first = withContext(Dispatchers.IO) { DiscoveryCatalog.page(seed.id) }
                        val candidates = first.tracks.toMutableList()
                        var continuation = first.next
                        val seen = mutableSetOf<String>()
                        repeat(2) {
                            val token = continuation?.takeIf { seen.add(it) } ?: return@repeat
                            try {
                                val page = withContext(Dispatchers.IO) { DiscoveryCatalog.page(seed.id, token) }
                                candidates += page.tracks; continuation = page.next
                            }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failures++; continuation = null }
                        }
                        val selected = DiscoveryPolicy.select(seed, candidates, known)
                        if (selected.isNotEmpty()) {
                            val ref = seed.artists.firstOrNull() ?: candidates.find { it.id == seed.id }?.artists?.firstOrNull()
                            val profile = if (ref == null) null else try { withContext(Dispatchers.IO) { artistRepository.get(ref) } }
                                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                            mixes.add(DiscoveryMix(seed, selected, genre ?: profile?.genre.orEmpty(),
                                profile?.let { (it.related + it.artist).distinctBy { a -> a.id } }.orEmpty()))
                            // Expand only the first downloaded seed's radio, without recursive taste drift.
                            if (seedIndex == 1 && seeds.size < 6) {
                                val anchored = seeds.map { MusicTitles.artist(it.artist).lowercase() }.toSet()
                                seeds += first.tracks.filter { it.id !in known && MusicTitles.artist(it.artist).lowercase() !in anchored }
                                    .distinctBy { MusicTitles.artist(it.artist).lowercase() }.take(6 - seeds.size)
                            }
                        } else failures++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failures++ }
                    // Publish the completed selection atomically, not reordered shelves
                    // after every seed while the user may be adding songs.
                    discoveryMutable.update { it.copy(message = "${seedIndex.coerceAtMost(seeds.size)} de ${seeds.size} selecciones") }
                }
                ensureActive()
                if (mixes.isEmpty()) {
                    discoveryMutable.update { it.copy(message = if (it.mixes.isNotEmpty()) "No se pudo actualizar. Conservamos tus últimos mixes; reintenta con conexión." else "No hay recomendaciones disponibles ahora. Vuelve a intentarlo con conexión.") }
                } else {
                    val grouped = homeMixes()
                    val snapshot = DiscoverySnapshot(key, System.currentTimeMillis(), grouped)
                    // A temporary failure of one seed must not suppress its retry for 12 hours.
                    withContext(Dispatchers.IO) { runCatching { discoveryCache.write(if (failures == 0) snapshot else snapshot.copy(time = 0)) } }
                    discoveryMutable.update { it.copy(mixes = grouped, updated = snapshot.time,
                        message = if (failures > 0) "Algunos mixes no cargaron. Puedes actualizar para reintentar." else "") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                discoveryMutable.update { it.copy(message = "No se pudo actualizar. Comprueba tu conexión; las canciones descargadas siguen en Biblioteca.") }
            } finally {
                if (isActive) discoveryMutable.update { it.copy(loading = false) }
            }
        }
    }
    fun openArtist(ref: ArtistRef) = showArtist(ref, null)
    fun openArtist(track: Track) = showArtist(null, track)
    private fun showArtist(ref: ArtistRef?, track: Track?, push: Boolean = true) {
        if (push && artistScreen.value.open && artistScreen.value.profile != null) {
            artistHistory.add(artistScreen.value.copy(loadingMore = false, error = ""))
            if (artistHistory.size > 12) artistHistory.removeAt(0)
        }
        artistJob?.cancel(); artistMoreJob?.cancel(); artistPages.clear()
        artistRetry = { showArtist(ref, track, false) }
        artistMutable.value = ArtistScreenState(open = true, loading = true, title = ref?.name ?: track?.artist ?: "Artista")
        artistJob = viewModelScope.launch {
            try {
                val resolved = ref ?: (track?.id?.let(Library::track) ?: track)?.artists?.firstOrNull() ?: withContext(Dispatchers.IO) {
                    check(track != null && track.id.matches(Regex("[A-Za-z0-9_-]{11}")))
                    DiscoveryCatalog.related(track.id).firstOrNull { it.id == track.id }?.artists?.firstOrNull()
                } ?: error("Artista no identificado")
                val profile = withContext(Dispatchers.IO) { artistRepository.get(resolved) }
                track?.let { Library.updateArtists(it.id, it.artists.ifEmpty { listOf(resolved) }) }
                artistMutable.value = ArtistScreenState(open = true, title = profile.artist.name, profile = profile)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { artistMutable.update { it.copy(loading = false, error = "No se pudo cargar el artista. Reintenta con conexión.") } }
        }
    }
    fun retryArtist() { artistRetry?.invoke() }
    fun closeArtist() {
        artistJob?.cancel(); artistMoreJob?.cancel(); artistHistory.clear(); artistMutable.value = ArtistScreenState()
    }
    fun backArtist() {
        artistJob?.cancel(); artistMoreJob?.cancel(); artistPages.clear()
        if (artistHistory.isEmpty()) closeArtist() else {
            val previous = artistHistory.removeAt(artistHistory.lastIndex)
            artistMutable.value = previous
            artistRetry = { previous.profile?.artist?.let { showArtist(it, null, false) } }
        }
    }
    fun moreArtistSongs() {
        val snapshot = artistScreen.value
        val profile = snapshot.profile ?: return
        if (snapshot.loadingMore || profile.songsBrowse.isBlank() || (snapshot.moreLoaded && snapshot.next == null)) return
        artistMutable.update { it.copy(loadingMore = true, error = "") }
        artistMoreJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { ArtistCatalog.page(profile.songsBrowse, profile.songsParams, snapshot.next) }
                snapshot.next?.let(artistPages::add)
                artistMutable.update { it.copy(profile = profile.copy(songs = (profile.songs + page.tracks).distinctBy { s -> s.id }),
                    moreLoaded = true, next = page.next?.takeUnless { token -> token in artistPages }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { artistMutable.update { it.copy(error = "No se pudieron cargar más canciones.") } }
            finally { if (isActive) artistMutable.update { it.copy(loadingMore = false) } }
        }
    }
    private fun sync() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val current = item?.let { Library.track(it.mediaId) ?: Track(it.mediaId, it.mediaMetadata.title.toString(), it.mediaMetadata.artist.toString(), it.mediaMetadata.artworkUri?.toString().orEmpty()) }
        val queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }.map { Library.track(it.mediaId) ?: Track(it.mediaId, it.mediaMetadata.title.toString(), it.mediaMetadata.artist.toString()) }
        mutable.update { it.copy(current = current, playing = c.isPlaying, playRequested = c.playWhenReady, buffering = c.playbackState == Player.STATE_BUFFERING, position = c.currentPosition.coerceAtLeast(0), duration = c.duration.coerceAtLeast(0), shuffle = c.shuffleModeEnabled, repeat = c.repeatMode, queue = queue) }
    }
    fun message(text: String, error: Boolean = false) { mutable.update { it.copy(message = text, error = error) } }
    fun search(query: String) {
        if (state.value.busy) return
        pageJob?.cancel(); activeQuery = query.trim(); consumedPages.clear()
        mutable.update { it.copy(busy = true, error = false, message = "Buscando…", results = emptyList(), resultTitle = "", remotePlaylist = false, nextPage = null, loadingMore = false, pageError = "", searchSerial = it.searchSerial + 1) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { YouTubeEngine.search(getApplication(), query) }
                mutable.update { it.copy(nextPage = result.next, results = result.tracks, resultTitle = result.name, remotePlaylist = result.playlist, message = if (result.tracks.isEmpty()) "No se encontraron canciones." else if (result.playlist) "${result.tracks.size} canciones · máximo 100 por importación" else "") }
            } catch (e: Exception) { mutable.update { it.copy(diagnostics = "Búsqueda: ${e.javaClass.simpleName}: ${e.message}") }; message(if (e is IllegalArgumentException) "Escribe una canción, un artista o un enlace válido." else "No se pudo buscar. Comprueba tu conexión y vuelve a intentarlo.", true) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun loadMore(retry: Boolean = false) {
        val snapshot = state.value
        val token = snapshot.nextPage ?: return
        if (snapshot.busy || snapshot.loadingMore || (!retry && snapshot.pageError.isNotBlank())) return
        val serial = snapshot.searchSerial
        val query = activeQuery
        mutable.update { it.copy(loadingMore = true, pageError = "") }
        pageJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { MusicCatalog.page(query, token) }
                if (state.value.searchSerial != serial) return@launch
                consumedPages.add(token)
                mutable.update { it.copy(results = (it.results + page.tracks).distinctBy { song -> song.id },
                    nextPage = page.next?.takeUnless { next -> next in consumedPages },
                    pageError = if (page.tracks.isEmpty() && page.next != null && page.next !in consumedPages) "No hubo canciones en esta página. Puedes seguir buscando." else "") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (state.value.searchSerial == serial) mutable.update { it.copy(pageError = "No se pudieron cargar más canciones. Reintenta para continuar.") }
            } finally {
                if (state.value.searchSerial == serial) mutable.update { it.copy(loadingMore = false) }
            }
        }
    }
    private fun media(track: Track): MediaItem {
        val local = Library.track(track.id)?.localUri?.takeIf { it.isNotBlank() } ?: track.localUri
        return MediaItem.Builder().setMediaId(track.id).setUri(local.ifBlank { "pulso://youtube/${track.id}" })
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setArtworkUri(track.artwork.takeIf { it.isNotBlank() }?.let { CoverArt.highQuality(it) }?.let(Uri::parse)).build()).build()
    }
    fun play(track: Track, list: List<Track> = listOf(track)) {
        if (track.id in pendingRemovals) return notice("Espera a que termine de eliminarse la canción.")
        if (!available(track)) return message("Vuelve a importar el archivo de audio para escuchar esta canción.")
        val c = controller ?: return message("El reproductor está iniciando.")
        radarQueueIds = null
        val tracks = list.filter { it.id !in pendingRemovals && available(it) }.ifEmpty { listOf(track) }
        Library.saveAll(tracks)
        c.setMediaItems(tracks.map(::media), tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0), 0)
        c.prepare(); c.play()
    }
    fun playRadar(mix: DiscoveryMix) {
        if (controller == null) return notice("El reproductor está iniciando.")
        val tracks = mix.tracks.filter { it.id !in pendingRemovals && available(it) }.take(200)
        val first = tracks.firstOrNull() ?: return
        play(first, tracks)
        radarQueueIds = tracks.map { it.id }.toMutableSet()
    }
    private fun available(track: Track) = !track.id.startsWith("local-") || (Library.track(track.id)?.localUri ?: track.localUri).isNotBlank()
    fun playNext(track: Track) { if (track.id in pendingRemovals) return; if (!available(track)) return message("Vuelve a importar el archivo de audio."); val c = controller ?: return; Library.save(track); c.addMediaItem((c.currentMediaItemIndex + 1).coerceAtLeast(0), media(track)); message("Sonará después: ${track.title}") }
    fun enqueue(track: Track) { if (track.id in pendingRemovals) return; if (!available(track)) return message("Vuelve a importar el archivo de audio."); Library.save(track); controller?.addMediaItem(media(track)); message("Añadida a la cola: ${track.title}") }
    fun toggle() { controller?.let { if (it.playWhenReady) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun seek(position: Long) { controller?.seekTo(position) }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } }
    fun favorite(track: Track) { if (track.id in pendingRemovals) return; val wasFavorite = Library.track(track.id)?.favorite ?: track.favorite; Library.favorite(track); notice(if (wasFavorite) "Quitada de favoritas" else "Añadida a favoritas") }
    fun download(track: Track) {
        if (track.id in pendingRemovals) return notice("Espera a que termine de eliminarse la canción.")
        if (state.value.batchBusy) return notice("Espera a que termine la operación de la playlist.")
        if (track.id.startsWith("local-")) return
        if (Library.track(track.id)?.localUri?.isNotBlank() == true) return notice("Esta canción ya está guardada.")
        if (track.id in pendingDownloads || downloads.value.any { "track:${track.id}" in it.tags && !it.state.isFinished }) {
            return notice("La canción ya se está guardando.")
        }
        pendingDownloads.add(track.id)
        notice("Guardando: ${track.title}")
        viewModelScope.launch {
            try {
                Library.save(track)
                withContext(Dispatchers.IO) { DownloadWorker.enqueue(getApplication(), track).result.get() }
            } catch (error: Exception) { notice("No se pudo iniciar la descarga. Inténtalo de nuevo.") }
            finally { pendingDownloads.remove(track.id) }
        }
    }
    private fun activeDownloadIds() = downloads.value.filter { !it.state.isFinished }
        .flatMap { it.tags }.filter { it.startsWith("track:") }.map { it.removePrefix("track:") }.toSet() + pendingDownloads
    fun downloadPlaylist(name: String) {
        if (pendingRemovals.isNotEmpty()) return notice("Espera a que termine de eliminarse la canción.")
        if (state.value.batchBusy) return
        val list = Library.state.value.playlists.find { it.name == name } ?: return
        val targets = PlaylistOffline.toDownload(list.ids.mapNotNull(Library::track), activeDownloadIds())
        if (targets.isEmpty()) return notice("La playlist ya está guardada o en la cola de descargas.")
        mutable.update { it.copy(batchList = name, batchBusy = true, batchMessage = "Preparando ${targets.size} descargas…") }
        pendingDownloads.addAll(targets.map { it.id })
        viewModelScope.launch {
            var queued = 0
            var failed = 0
            try {
                for (track in targets) {
                    try {
                        withContext(Dispatchers.IO) { DownloadWorker.enqueue(getApplication(), track).result.get() }
                        queued++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failed++ }
                    mutable.update { it.copy(batchMessage = "Añadidas a la cola: $queued de ${targets.size}") }
                }
                val summary = "$queued canciones añadidas para guardar${if (failed > 0) "; $failed no pudieron añadirse" else ""}."
                mutable.update { it.copy(batchMessage = summary) }; notice(summary)
            } finally {
                pendingDownloads.removeAll(targets.map { it.id }.toSet())
                mutable.update { it.copy(batchBusy = false) }
            }
        }
    }
    fun deletePlaylistDownloads(name: String) {
        if (pendingRemovals.isNotEmpty()) return notice("Espera a que termine de eliminarse la canción.")
        if (state.value.batchBusy) return
        val list = Library.state.value.playlists.find { it.name == name } ?: return
        if (list.ids.any { it in activeDownloadIds() }) return notice("Cancela o espera a que terminen las descargas de esta playlist.")
        val targets = PlaylistOffline.toDelete(list.ids.mapNotNull(Library::track))
        if (targets.isEmpty()) return notice("Esta playlist no tiene descargas que eliminar.")
        mutable.update { it.copy(batchList = name, batchBusy = true, batchMessage = "Eliminando descargas…") }
        viewModelScope.launch {
            var removed = 0
            var failed = 0
            try {
                for (track in targets) {
                    try { eraseSaved(track); removed++ }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failed++ }
                    mutable.update { it.copy(batchMessage = "Eliminadas: $removed de ${targets.size}") }
                }
                val summary = "$removed descargas eliminadas${if (failed > 0) "; $failed no se pudieron borrar" else ""}."
                mutable.update { it.copy(batchMessage = summary) }; notice(summary)
            } finally { mutable.update { it.copy(batchBusy = false) } }
        }
    }
    private suspend fun eraseSaved(saved: Track) {
        val imported = saved.id.startsWith("local-")
        if (controller?.currentMediaItem?.mediaId == saved.id) controller?.pause()
        withContext(Dispatchers.IO) {
            if (imported) Library.forget(saved.id)
            else {
                val uri = Uri.parse(saved.localUri)
                check(uri.scheme == "content" && uri.authority == "media")
                getApplication<Application>().contentResolver.delete(uri, null, null)
                Library.clearDownload(saved.id, saved.localUri)
            }
        }
        controller?.let { c -> (c.mediaItemCount - 1 downTo 0).forEach { index -> if (c.getMediaItemAt(index).mediaId == saved.id) c.removeMediaItem(index) } }
    }
    fun deleteSaved(track: Track) {
        if (track.id in pendingRemovals) return
        if (state.value.batchBusy) return notice("Espera a que termine la operación de la playlist.")
        if (track.id in activeDownloadIds()) return notice("Cancela primero la descarga en curso.")
        viewModelScope.launch {
            try {
                val saved = Library.track(track.id) ?: return@launch
                val imported = saved.id.startsWith("local-")
                if (!imported && saved.localUri.isBlank()) return@launch
                eraseSaved(saved)
                notice(if (imported) "Canción quitada de la biblioteca. El archivo original se conserva." else "Descarga eliminada: ${saved.title}")
            } catch (error: Exception) { notice("No se pudo eliminar. El archivo puede requerir permiso desde el gestor de archivos.") }
        }
    }
    fun cancel(work: WorkInfo) { WorkManager.getInstance(getApplication()).cancelWorkById(work.id) }
    fun savePlaylist(name: String, tracks: List<Track>) { if (name.isBlank()) return; if (tracks.any { it.id in pendingRemovals }) return notice("Espera a que termine de eliminarse la canción."); Library.playlist(name.trim(), tracks); message("Playlist guardada: ${name.trim()}") }
    fun removeSongEverywhere(track: Track) {
        if (state.value.batchBusy) return notice("Espera a que termine la operación de la playlist.")
        if (track.id in activeDownloadIds()) return notice("Cancela o espera a que termine la descarga antes de eliminar la canción.")
        if (!pendingRemovals.add(track.id)) return
        notice("Eliminando: ${track.title}")
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                // The observed flow may lag behind WorkManager; check its current state before deletion.
                val downloading = withContext(Dispatchers.IO) {
                    WorkManager.getInstance(context).getWorkInfosByTag("track:${track.id}").get().any { !it.state.isFinished }
                }
                if (downloading) { notice("Cancela o espera a que termine la descarga antes de eliminar la canción."); return@launch }
                val saved = Library.track(track.id) ?: return@launch
                if (controller?.currentMediaItem?.mediaId == saved.id) controller?.pause()
                withContext(Dispatchers.IO) {
                    SongRemoval.remove(saved, { song ->
                        val uri = Uri.parse(song.localUri)
                        check(uri.scheme == "content") { "El archivo no permite el borrado desde PULSO." }
                        val resolver = context.contentResolver
                        try {
                            if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                                check(android.provider.DocumentsContract.deleteDocument(resolver, uri)) { "Android no permitió eliminar el archivo." }
                            } else {
                                val deleted = resolver.delete(uri, null, null)
                                if (deleted == 0) {
                                    // A missing file is already removed; an accessible file must not be forgotten.
                                    resolver.openFileDescriptor(uri, "r")?.use { }
                                    error("No se pudo confirmar que el archivo se haya eliminado.")
                                }
                            }
                        } catch (_: java.io.FileNotFoundException) { /* Already absent. */ }
                    }, Library::forget)
                }
                controller?.let { c ->
                    (c.mediaItemCount - 1 downTo 0).forEach { index -> if (c.getMediaItemAt(index).mediaId == saved.id) c.removeMediaItem(index) }
                }
                notice("Canción eliminada de PULSO: ${saved.title}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { notice("Android no permitió borrar el archivo. Revisa sus permisos o elimínalo desde Archivos y vuelve a intentar.") }
            catch (_: Exception) { notice("No se completó la eliminación. Revisa el archivo y el espacio disponible, y vuelve a intentar.") }
            finally { pendingRemovals.remove(track.id) }
        }
    }
    fun renamePlaylist(old: String, name: String, finished: (Boolean) -> Unit) = viewModelScope.launch {
        val success = try {
            withContext(Dispatchers.IO) { Library.renamePlaylist(old, name) }
            notice("Playlist renombrada."); true
        } catch (error: IllegalArgumentException) { notice(error.message ?: "No se pudo renombrar la playlist."); false }
        catch (_: Exception) { notice("No se pudo guardar el cambio. Revisa el espacio disponible."); false }
        finished(success)
    }
    fun deletePlaylist(name: String, finished: (Boolean) -> Unit) = viewModelScope.launch {
        val success = try {
            withContext(Dispatchers.IO) { Library.deletePlaylist(name) }
            notice("Playlist eliminada. Las canciones descargadas se conservaron."); true
        } catch (_: Exception) { notice("No se pudo eliminar la playlist. Reintenta."); false }
        finished(success)
    }
    fun sharePlaylist(name: String, finished: (Intent?) -> Unit) = viewModelScope.launch {
        try {
            val context = getApplication<Application>()
            val uri = withContext(Dispatchers.IO) {
                val json = PlaylistManagement.share(Library.state.value, name)
                val directory = java.io.File(context.cacheDir, "playlist-shares").apply { check(isDirectory || mkdirs()) }
                directory.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }?.forEach { it.delete() }
                val file = java.io.File(directory, "${java.util.UUID.randomUUID()}-${PlaylistManagement.filename(name)}")
                file.writeText(json, Charsets.UTF_8)
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.playlistfiles", file)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                clipData = android.content.ClipData.newUri(context.contentResolver, name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            finished(Intent.createChooser(intent, "Compartir playlist"))
        } catch (_: Exception) { notice("No se pudo preparar el archivo para compartir."); finished(null) }
    }
    fun importAudio(uris: List<Uri>) = viewModelScope.launch {
        val imported = withContext(Dispatchers.IO) { uris.count { uri ->
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(getApplication(), uri)
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: run {
                        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "Audio local" } ?: "Audio local"
                    }
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Archivo local"
                    val seconds = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000
                    val restored = Library.state.value.tracks.filter { it.id.startsWith("local-") && it.localUri.isBlank() && it.title == title && it.artist == artist && kotlin.math.abs(it.seconds - seconds) <= 1 }.singleOrNull()
                    Library.save(Track(restored?.id ?: "local-${uri.toString().hashCode()}", title, artist, seconds = seconds, localUri = uri.toString()))
                } finally { retriever.release() }
            }.isSuccess
        } }
        message("$imported de ${uris.size} archivos añadidos.", imported < uris.size)
    }
    fun lyrics(track: Track) {
        lyricsJob?.cancel()
        mutable.update { it.copy(lyricsMessage = "Buscando letras…", lyrics = null) }
        lyricsJob = viewModelScope.launch {
            try { val result = withContext(Dispatchers.IO) { LyricsRepository.get(getApplication(), track) }; mutable.update { if (it.current?.id == track.id) it.copy(lyrics = result, lyricsMessage = result.source) else it } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(lyricsMessage = e.message ?: "Letras no disponibles.") } }
        }
    }
    fun importLyrics(uri: Uri) { val track = state.value.current ?: return; viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { val raw = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("No se pudo abrir el archivo."); LyricsRepository.save(getApplication(), track.id, raw) } }
            .onSuccess { lyrics -> mutable.update { it.copy(lyrics = lyrics, lyricsMessage = lyrics.source) } }.onFailure { message(it.message ?: "LRC inválido", true) }
    } }
    fun updateEngine() { if (!state.value.busy) prepareEngine(true) }
    private fun prepareEngine(update: Boolean) {
        mutable.update { it.copy(busy = true, message = "Preparando tu música…", error = false) }
        viewModelScope.launch {
            try {
                val ready = withContext(Dispatchers.IO) {
                    EngineStartup.start({ YouTubeEngine.verify(getApplication()) }, if (update) ({ YouTubeEngine.update(getApplication()); Unit }) else null)
                }
                mutable.update { it.copy(engineVersion = ready.version, diagnostics = ready.updateWarning?.let { warning -> "Actualización: $warning" }.orEmpty(), message = if (ready.updateWarning == null) "Tu música está lista." else "Tu música está lista. La actualización puede reintentarse más tarde.", error = false) }
            } catch (error: Exception) {
                android.util.Log.e("Pulso", "Engine startup failed", error)
                val detail = generateSequence<Throwable>(error) { it.cause }.take(6).joinToString("\n") { "${it.javaClass.simpleName}: ${it.message}" }
                mutable.update { it.copy(error = true, diagnostics = "Pulso 0.2.4 · Android ${android.os.Build.VERSION.RELEASE}\nABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n$detail", message = "No se pudo iniciar el audio en línea. Reintenta en Ajustes; allí puedes copiar el diagnóstico.") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() { MediaController.releaseFuture(controllerFuture); super.onCleared() }
}
