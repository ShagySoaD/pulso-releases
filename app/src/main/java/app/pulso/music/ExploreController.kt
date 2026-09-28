package app.pulso.music

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExploreState(val query: String = "", val category: SearchCategory = SearchCategory.SONGS,
    val page: ExplorePage = ExplorePage(), val playlist: CommunityPlaylist? = null,
    val loading: Boolean = false, val error: String = "", val revision: Int = 0, val searched: Boolean = false)

@UnstableApi
class ExploreController(private val scope: CoroutineScope, private val context: Context) {
    private val mutable = MutableStateFlow(ExploreState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var previous: ExploreState? = null
    private val consumed = mutableSetOf<String>()
    private var previousTokens = emptySet<String>()
    fun search(query: String, category: SearchCategory = state.value.category) {
        if (query.isBlank()) return
        job?.cancel(); previous = null; consumed.clear()
        mutable.value = ExploreState(query.trim(), category, revision = state.value.revision + 1, searched = true)
        load(false)
    }
    fun category(category: SearchCategory) {
        if (state.value.query.isBlank()) {
            job?.cancel(); previous = null; consumed.clear()
            mutable.value = ExploreState(category = category, revision = state.value.revision + 1)
        }
        else search(state.value.query, category)
    }
    fun open(playlist: CommunityPlaylist) {
        job?.cancel()
        previous = state.value.copy(loading = false)
        previousTokens = consumed.toSet(); consumed.clear()
        mutable.value = ExploreState(query = state.value.query, category = state.value.category, playlist = playlist,
            revision = state.value.revision + 1, searched = true)
        load(false)
    }
    fun back() {
        job?.cancel(); consumed.clear(); consumed.addAll(previousTokens)
        mutable.value = previous?.copy(revision = state.value.revision + 1) ?: ExploreState()
        previous = null
    }
    fun loadMore() { if (state.value.page.next != null) load(true) }
    fun retry() = load(state.value.page.next != null)
    private fun load(more: Boolean) {
        val snapshot = state.value
        if (snapshot.loading) return
        val token = if (more) snapshot.page.next ?: return else null
        mutable.value = snapshot.copy(loading = true, error = "")
        job = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    when {
                        snapshot.playlist != null -> ExploreCatalog.playlist(snapshot.playlist.id, token)
                        snapshot.query.startsWith("https://", true) -> {
                            val result = YouTubeEngine.search(context, snapshot.query)
                            ExplorePage(songs = result.tracks)
                        }
                        else -> ExploreCatalog.search(snapshot.query, snapshot.category, token)
                    }
                }
                ensureActive()
                if (token != null) consumed.add(token)
                val old = if (more) snapshot.page else ExplorePage()
                mutable.value = snapshot.copy(page = ExplorePage(
                    (old.songs + page.songs).distinctBy { it.id }, (old.artists + page.artists).distinctBy { it.id },
                    (old.playlists + page.playlists).distinctBy { it.id }, page.next?.takeUnless { it in consumed }), loading = false,
                    error = if (page.songs.isEmpty() && page.artists.isEmpty() && page.playlists.isEmpty() && page.next != null && page.next !in consumed)
                        "Esta página no contiene resultados compatibles. Reintenta para seguir cargando." else "")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.value = snapshot.copy(loading = false, error = "No se pudo cargar. Comprueba la conexión y reintenta.")
            }
        }
    }
}
