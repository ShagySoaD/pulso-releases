package app.pulso.music

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.absoluteValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Navy: Color @Composable get() = MaterialTheme.colorScheme.background
private val Panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val Mint: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Orange: Color @Composable get() = MaterialTheme.colorScheme.secondary
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        SocialEngine.invitation(intent)
        intent.getStringExtra("socialContact")?.let { SocialEngine.openRequested.value = it; intent.removeExtra("socialContact") }
        if (intent.getBooleanExtra("showAppUpdates", false)) {
            AppUpdates.openRequested.value = true
            intent.removeExtra("showAppUpdates")
        }
        setContent {
            PulsoTheme(this) { PulseLightHost { Pulso(); StartupAnnouncementHost() } }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SocialEngine.invitation(intent)
        intent.getStringExtra("socialContact")?.let { SocialEngine.openRequested.value = it; intent.removeExtra("socialContact") }
        if (intent.getBooleanExtra("showAppUpdates", false)) {
            AppUpdates.openRequested.value = true
            intent.removeExtra("showAppUpdates")
        }
    }
    override fun onStart() {
        super.onStart()
        AppUpdates.onAppOpened(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun Pulso(vm: MusicViewModel = viewModel()) {
    val transfer: TransferViewModel = viewModel()
    val transferState by transfer.state.collectAsStateWithLifecycle()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(vm) { vm.notices.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() } }
    val state by vm.state.collectAsStateWithLifecycle()
    val explore by vm.explore.state.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.items.collectAsStateWithLifecycle()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val community by vm.homeCommunity.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val discovery by vm.discovery.collectAsStateWithLifecycle()
    val artistScreen by vm.artistScreen.collectAsStateWithLifecycle()
    var selectedMix by remember { mutableStateOf<String?>(null) }
    var homeGenre by remember { mutableStateOf<String?>(null) }
    var homeSongCount by remember { mutableIntStateOf(24) }
    LaunchedEffect(discovery.mixes.map { it.genre }) { if (homeGenre != null && discovery.mixes.none { it.genre == homeGenre }) homeGenre = null }
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val eq by AudioSettings.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val socialRequested by SocialEngine.openRequested.collectAsStateWithLifecycle()
    val socialInvite by SocialEngine.inviteRequested.collectAsStateWithLifecycle()
    val unreadMessages by remember { SocialEngine.state.map { it.friends.sumOf { friend -> friend.unread } }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue = 0)
    var query by remember { mutableStateOf("") }
    fun submitSearch(text: String = query, category: SearchCategory = explore.category) {
        if (text.isBlank()) return
        query = text; vm.suggestions.clear(); keyboard?.hide(); focus.clearFocus()
        vm.explore.search(text, category)
    }
    LaunchedEffect(tab) { if (tab != 1) vm.suggestions.clear() }
    var filter by remember { mutableIntStateOf(0) }
    var openPlayer by remember { mutableStateOf(false) }
    var selectedList by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(library.playlists, selectedList) { if (selectedList != null && library.playlists.none { it.name == selectedList }) selectedList = null }
    var playlistTracks by remember { mutableStateOf<List<Track>?>(null) }
    var playlistName by remember { mutableStateOf("") }
    LaunchedEffect(socialRequested, socialInvite) {
        if (socialRequested != null || socialInvite != null) { tab = 4; openPlayer = false; playlistTracks = null; vm.closeArtist() }
    }
    val updatesRequested by AppUpdates.openRequested.collectAsStateWithLifecycle()
    val settingsListState = rememberLazyListState()
    val messagesListState = rememberLazyListState()
    LaunchedEffect(updatesRequested) {
        if (updatesRequested) {
            tab = 3
            openPlayer = false
            playlistTracks = null
            vm.closeArtist()
            settingsListState.scrollToItem(0)
            AppUpdates.openRequested.value = false
        }
    }
    androidx.activity.compose.BackHandler(enabled = selectedMix != null && tab == 0 && !openPlayer && !artistScreen.open) { selectedMix = null }
    androidx.activity.compose.BackHandler(enabled = explore.playlist != null && tab == 1 && !openPlayer && !artistScreen.open) { vm.explore.back() }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importAudio(it) }
    val importLyrics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importLyrics) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { AppUpdates.refreshNotification(context) }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(tab, filter, selectedList) { if (tab == 2) listState.scrollToItem(0) }
    LaunchedEffect(tab, explore.revision) { if (tab == 1) listState.scrollToItem(0) }
    LaunchedEffect(tab, selectedMix, homeGenre) { if (tab == 0) listState.scrollToItem(0) }
    LaunchedEffect(tab, explore.page.next, explore.loading, explore.error) {
        if (tab == 1 && explore.page.next != null && !explore.loading && explore.error.isBlank()) {
            snapshotFlow { val layout = listState.layoutInfo; layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 5 }
                .distinctUntilChanged().collect { nearEnd -> if (nearEnd) vm.explore.loadMore() }
        }
    }
    fun playlist(tracks: List<Track>) { playlistTracks = tracks; playlistName = "" }
    Scaffold(containerColor = Navy, bottomBar = {
        Column {
            state.current?.let { track ->
                Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 12.dp).clickable { openPlayer = true }) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(track, Modifier.size(44.dp)); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1) }
                            IconButton(onClick = vm::toggle) { Icon(if (state.playRequested) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playRequested) "Pausar" else "Reproducir") }
                            IconButton(onClick = vm::next) { Icon(Icons.Default.SkipNext, "Siguiente") }
                        }
                        LinearProgressIndicator(progress = { if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Mint)
                    }
                }
            }
            NavigationBar(containerColor = Navy) {
                val names = listOf("Inicio", "Buscar", "Biblioteca", "Ajustes", "Mensajes")
                val icons = listOf(Icons.Default.Home, Icons.Default.Search, Icons.Default.LibraryMusic, Icons.Default.Tune, Icons.Default.ChatBubbleOutline)
                listOf(0, 1, 2, 4, 3).forEach { index -> NavigationBarItem(selected = tab == index, onClick = { tab = index; selectedList = null }, icon = {
                    BadgedBox(badge = { if (index == 4 && unreadMessages > 0) Badge { Text(if (unreadMessages > 99) "99+" else unreadMessages.toString()) } }) { Icon(icons[index], names[index]) }
                }, label = { Text(names[index], fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxWidth(), state = when (tab) { 3 -> settingsListState; 4 -> messagesListState; else -> listState }, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    PulsoHeader(listOf("Inicio", "Buscar", "Biblioteca", "Ajustes", "Mensajes")[tab])
                }
                if (tab in 0..2 && (state.busy || state.error)) item {
                    Surface(color = if (state.error) MaterialTheme.colorScheme.errorContainer else Panel, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) { Text(state.message, color = if (state.error) MaterialTheme.colorScheme.onErrorContainer else Muted, fontSize = 13.sp); if (state.busy) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } }
                    }
                }
                when (tab) {
                    0 -> {
                        val filteredMixes = discovery.mixes.filter { homeGenre == null || it.genre == homeGenre }
                        val mix = discovery.mixes.find { it.id == selectedMix }
                        if (mix != null) {
                            item {
                                TextButton(onClick = { selectedMix = null }) { Icon(Icons.Default.ChevronLeft, null); Text("Inicio") }
                                DiscoveryMixCard(mix, { if (mix.kind == "radar") vm.playRadar(mix) else vm.play(mix.tracks.first(), mix.tracks) }, { playlist(mix.tracks); playlistName = "Mix · ${mix.title}" }, null, Modifier.fillMaxWidth())
                            }
                            items(mix.tracks, key = { "mix-${it.id}" }) { song ->
                                val saved = library.tracks.find { it.id == song.id }
                                SongRow(song.copy(localUri = saved?.localUri.orEmpty(), favorite = saved?.favorite == true), vm, { vm.play(song, mix.tracks) }, { playlist(listOf(song)) })
                            }
                        } else {
                            discovery.mixes.firstOrNull { it.kind == "radar" }?.let { radar -> item(key = "radar") {
                                RadarCard(radar, { selectedMix = radar.id }, { vm.playRadar(radar) })
                            } }
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Recomendados", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    if (discovery.loading) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                                    else IconButton(onClick = { vm.refreshDiscovery(true) }, enabled = discovery.genre != null || DiscoveryPolicy.tastes(library.tracks, library.playlists).isNotEmpty()) { Icon(Icons.Default.Refresh, "Actualizar recomendaciones") }
                                }
                                val genres = discovery.mixes.map { it.genre }.filter { it.isNotBlank() }.distinct()
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item { FilterChip(selected = homeGenre == null, onClick = { homeGenre = null; homeSongCount = 24 }, label = { Text("Para ti") }) }
                                    items(genres) { genre -> FilterChip(selected = homeGenre == genre, onClick = { homeGenre = genre; homeSongCount = 24 }, label = { Text(genre) }) }
                                }
                                if (!discovery.loading && discovery.message.isNotBlank()) Text(discovery.message, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                            if (discovery.mixes.isNotEmpty()) {
                                val featured = filteredMixes.filter { it.kind != "radio" && it.kind != "radar" }
                                if (featured.isNotEmpty()) item(key = "featured-mixes") {
                                    HomeReveal("featured") {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            val pager = rememberPagerState(pageCount = { featured.size })
                                            HorizontalPager(state = pager, pageSpacing = 12.dp, key = { featured[it].id }) { page ->
                                                val recommendation = featured[page]
                                                EditorialMixCard(recommendation, { vm.play(recommendation.tracks.first(), recommendation.tracks) }, { playlist(recommendation.tracks); playlistName = recommendation.title }, { selectedMix = recommendation.id },
                                                    Modifier.fillMaxWidth().graphicsLayer {
                                                        val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                                                        scaleX = 1f - offset * .04f; scaleY = scaleX; alpha = 1f - offset * .15f
                                                    }, featured = true)
                                            }
                                            if (featured.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                                featured.forEachIndexed { index, _ -> Box(Modifier.padding(3.dp).size(if (pager.currentPage == index) 8.dp else 5.dp).clip(CircleShape).background(if (pager.currentPage == index) Mint else Muted.copy(alpha = .4f))) }
                                            }
                                        }
                                    }
                                }
                                val routes = filteredMixes.filter { it.kind == "radio" }
                                if (routes.isNotEmpty()) {
                                    item { SectionTitle("Conexiones con tus artistas") }
                                    item(key = "artist-routes") {
                                        HomeReveal("routes") {
                                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                                val cardWidth = minOf(265.dp, maxWidth)
                                                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                                    items(routes, key = { it.id }) { recommendation ->
                                                        EditorialMixCard(recommendation, { vm.play(recommendation.tracks.first(), recommendation.tracks) }, { playlist(recommendation.tracks); playlistName = recommendation.title }, { selectedMix = recommendation.id }, Modifier.width(cardWidth))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                val artists = filteredMixes.flatMap { it.artists }.distinctBy { it.id }.take(16)
                                if (artists.isNotEmpty()) {
                                    item { SectionTitle("Artistas recomendados") }
                                    item(key = "home-artists") { HomeReveal("artists") { ArtistRail(artists, vm::openArtist) } }
                                }
                                if (community.isNotEmpty()) {
                                    item { SectionTitle("Playlists de la comunidad") }
                                    item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(community, key = { it.id }) { remote -> Box(Modifier.width(280.dp)) {
                                            CommunityPlaylistCard(remote) { vm.explore.open(remote); tab = 1 }
                                        } }
                                    } }
                                }
                                item { SectionTitle("Canciones recomendadas") }
                                val allRecommendations = (0 until 90).flatMap { index -> filteredMixes.mapNotNull { it.tracks.getOrNull(index) } }.distinctBy { it.id }
                                val recommendations = allRecommendations.take(homeSongCount)
                                items(recommendations, key = { "discovery-${it.id}" }) { song ->
                                    val saved = library.tracks.find { it.id == song.id }
                                    SongRow(song.copy(localUri = saved?.localUri.orEmpty(), favorite = saved?.favorite == true), vm, { vm.play(song, recommendations) }, { playlist(listOf(song)) })
                                }
                                if (allRecommendations.size > homeSongCount) item { OutlinedButton(onClick = { homeSongCount += 24 }, modifier = Modifier.fillMaxWidth()) { Text("Más descubrimientos") } }
                            }
                        }
                    }

                    1 -> {
                        item {
                            OutlinedTextField(value = query, onValueChange = { query = it; vm.suggestions.update(it) }, modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) vm.suggestions.update(query) }, shape = RoundedCornerShape(16.dp), label = { Text("Canciones, artistas o playlists") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = ""; vm.suggestions.clear() }) { Icon(Icons.Default.Close, "Borrar búsqueda") } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitSearch() }))
                        }
                        if (suggestions.isNotEmpty()) item(key = "suggestions") {
                            Surface(shape = RoundedCornerShape(16.dp), color = Panel) {
                                Column {
                                    suggestions.forEach { suggestion ->
                                        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Buscar $suggestion") { submitSearch(suggestion) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(12.dp)); Text(suggestion, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Icon(Icons.Default.NorthWest, null, tint = Muted, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { submitSearch() }, enabled = query.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Buscar") }
                                if (explore.page.songs.isNotEmpty()) OutlinedButton(onClick = { playlist(explore.page.songs) }) { Text("Guardar selección") }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(SearchCategory.entries) { category -> FilterChip(selected = explore.category == category && explore.playlist == null,
                                    onClick = { if (query.isNotBlank()) submitSearch(query, category) else vm.explore.category(category) }, label = { Text(category.label) }) }
                            }
                        }
                        explore.playlist?.let { remote -> item {
                            TextButton(onClick = vm.explore::back) { Text("Volver a resultados") }
                            SectionTitle(remote.title)
                            Text(remote.author, color = Muted, fontSize = 12.sp)
                            Text("${explore.page.songs.size} canciones cargadas${if (explore.page.next != null) " · hay más" else ""}", color = Muted)
                        } }
                        if (explore.page.songs.isEmpty() && explore.page.artists.isEmpty() && explore.page.playlists.isEmpty() && !explore.loading && explore.error.isBlank())
                            item { EmptyCard(if (explore.searched) "Sin resultados" else "Buscar música", if (explore.searched) "Prueba otra búsqueda o categoría." else "Explora canciones, artistas y playlists de la comunidad.") }
                        items(explore.page.artists, key = { "artist-${it.id}" }) { artist ->
                            Surface(onClick = { vm.openArtist(artist) }, shape = RoundedCornerShape(18.dp), color = Panel) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(artist.image, artist.name, Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.width(14.dp)); Text(artist.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    Icon(Icons.Default.ChevronRight, "Ver artista")
                                }
                            }
                        }
                        items(explore.page.playlists, key = { "community-${it.id}" }) { remote -> CommunityPlaylistCard(remote) { vm.explore.open(remote) } }
                        items(explore.page.songs, key = { "result-${it.id}" }) { song -> SongRow(song.copy(localUri = library.tracks.find { it.id == song.id }?.localUri ?: song.localUri, favorite = library.tracks.find { it.id == song.id }?.favorite ?: song.favorite), vm, { vm.play(song, explore.page.songs) }, { playlist(listOf(song)) }) }
                        item(key = "search-footer") {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (explore.loading) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Buscando…", color = Muted) }
                                else if (explore.error.isNotBlank()) { Text(explore.error, color = Orange); TextButton(onClick = vm.explore::retry) { Text("Reintentar") } }
                                else if (explore.page.next != null) TextButton(onClick = vm.explore::loadMore) { Text("Cargar más") }
                            }
                        }
                    }
                    2 -> {
                        item {
                            OutlinedButton(onClick = transfer::showImport, enabled = !transferState.busy, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FileUpload, null); Spacer(Modifier.width(8.dp)); Text(if (transferState.draft != null) "Continuar importación" else "Importar playlists")
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { importAudio.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Importar audio") }
                                IconButton(onClick = { playlist(emptyList()) }) { Icon(Icons.Default.PlaylistAdd, "Crear playlist") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Todas", "Favoritas", "Descargadas").forEachIndexed { i, name -> FilterChip(selected = filter == i && selectedList == null, onClick = { filter = i; selectedList = null }, label = { Text(name) }) }
                            }
                        }
                        if (filter == 2 && selectedList == null && downloads.any { !it.state.isFinished || it.state == WorkInfo.State.FAILED }) {
                            item { SectionTitle("Descargas") }
                            items(downloads.filter { !it.state.isFinished || it.state == WorkInfo.State.FAILED }.sortedByDescending { it.state == WorkInfo.State.RUNNING }.take(8), key = { it.id }) { work ->
                                val id = work.tags.firstOrNull { it.startsWith("track:") }?.substringAfter("track:")
                                val track = id?.let(Library::track)
                                Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(track?.title ?: "Descarga", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(when (work.state) { WorkInfo.State.SUCCEEDED -> if (track?.localUri.isNullOrBlank()) "Descarga eliminada" else "Guardada en Music/Pulso"; WorkInfo.State.RUNNING -> if (work.progress.getBoolean("waiting", false)) "En cola de descarga" else "Descargando · ${work.progress.getInt("progress", 0).coerceAtLeast(0)} %"; WorkInfo.State.FAILED -> work.outputData.getString("error") ?: "No se pudo descargar"; WorkInfo.State.CANCELLED -> "Cancelada"; else -> "En espera de conexión" }, fontSize = 12.sp, color = if (work.state == WorkInfo.State.FAILED) Orange else Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (!work.state.isFinished) IconButton(onClick = { vm.cancel(work) }) { Icon(Icons.Default.Close, "Cancelar descarga") }
                                        else if (work.state != WorkInfo.State.SUCCEEDED && track != null) IconButton(onClick = { vm.download(track) }) { Icon(Icons.Default.Refresh, "Reintentar descarga") }
                                    }
                                }
                            }
                        }
                        if (filter == 0 && selectedList == null) {
                            item { SectionTitle("Tus playlists") }
                            if (library.playlists.isEmpty()) item { EmptyCard("Crea tu primera playlist", "Pulsa + o añade canciones a una playlist desde el menú de cada canción.") }
                            items(library.playlists, key = { "playlist-${it.name}" }) { p ->
                                val songs = p.ids.mapNotNull { id -> library.tracks.find { it.id == id } }
                                Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { selectedList = p.name }) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        PlaylistArtwork(songs, Modifier.size(88.dp))
                                        Spacer(Modifier.width(16.dp))
                                        Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${songs.size} canciones", color = Muted, fontSize = 12.sp) }
                                        PlaylistMenu(p, vm, { renamed -> if (selectedList == p.name) selectedList = renamed }, { if (selectedList == p.name) selectedList = null })
                                    }
                                }
                            }
                        } else {
                            val tracks = if (selectedList != null) library.playlists.find { it.name == selectedList }?.ids?.mapNotNull { id -> library.tracks.find { it.id == id } }.orEmpty()
                                else library.tracks.filter { if (filter == 1) it.favorite else it.localUri.isNotBlank() && !it.id.startsWith("local-") }.reversed()
                            item {
                                if (selectedList != null) TextButton(onClick = { selectedList = null; filter = 0 }) { Icon(Icons.Default.ChevronLeft, null); Text("Tus playlists") }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(selectedList ?: if (filter == 1) "Favoritas" else "Descargadas", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    library.playlists.firstOrNull { it.name == selectedList }?.let { list ->
                                        PlaylistMenu(list, vm, { selectedList = it }, { selectedList = null; filter = 0 })
                                    }
                                }
                                selectedList?.let { name -> PlaylistOfflineControls(name, tracks, downloads, state, vm) }
                            }
                            if (tracks.isEmpty()) item { EmptyCard("Todavía no hay canciones aquí", when { selectedList != null -> "Añade canciones a esta playlist desde el menú de cada canción."; filter == 1 -> "Pulsa el corazón en la portada del reproductor para guardar tus favoritas."; else -> "Descarga una canción para escucharla sin conexión." }) }
                            items(tracks, key = { "library-${it.id}" }) { track -> SongRow(track, vm, { vm.play(track, tracks) }, { playlist(listOf(track)) }, playlistContext = selectedList) }
                        }
                    }
                    4 -> { item { MessagesScreen { track -> vm.play(track, listOf(track)) } } }
                    3 -> {
                        item { AppUpdateSettings() }
                        item { ThemePicker() }
                        item { BackupControls(transfer) }
                        item {
                            Surface(color = Panel, shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                SectionTitle("Música en línea"); Spacer(Modifier.height(10.dp)); Text("Si una canción no carga, comprueba la conexión musical y vuelve a intentarlo.", color = Muted, fontSize = 13.sp); Spacer(Modifier.height(12.dp)); Button(onClick = vm::updateEngine, enabled = !state.busy) { Icon(Icons.Default.SystemUpdateAlt, null); Spacer(Modifier.width(8.dp)); Text("Restablecer conexión") }
                            } }
                        }
                        if (state.diagnostics.isNotBlank()) item { TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.diagnostics)); vm.message("Diagnóstico copiado.") }) { Text("Copiar diagnóstico") } }
                        item { EqualizerCard(eq) }
                        item { Text("Pulso ${BuildConfig.VERSION_NAME} · Android 10+\nLas descargas se guardan en Music/Pulso. Las letras consultadas se conservan para escucharlas sin conexión.\n\nLa biblioteca es local: no inicia sesión ni modifica tu cuenta de Google.", color = Muted, fontSize = 13.sp) }
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
    if (playlistTracks != null) AlertDialog(onDismissRequest = { playlistTracks = null }, title = { Text("Añadir a playlist") }, text = {
        Column {
            OutlinedTextField(playlistName, { playlistName = it }, label = { Text("Nombre de la playlist") }, singleLine = true)
            library.playlists.take(6).forEach { p -> TextButton(onClick = { playlistName = p.name }) { Text(p.name) } }
        }
    }, confirmButton = { TextButton(enabled = playlistName.isNotBlank(), onClick = { vm.savePlaylist(playlistName, playlistTracks.orEmpty()); playlistTracks = null }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = { playlistTracks = null }) { Text("Cancelar") } })
    if (openPlayer && state.current != null) PlayerDialog(state, vm, { openPlayer = false }, { importLyrics.launch(arrayOf("*/*")) }, { track -> openPlayer = false; playlist(listOf(track)) })
    if (artistScreen.open) ArtistDialog(artistScreen, vm, { tracks -> vm.closeArtist(); playlist(tracks) }, { vm.closeArtist(); openPlayer = true })
    TransferDialog(transfer)
}

@Composable private fun SectionTitle(text: String) { Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
@Composable private fun ArtistRail(artists: List<ArtistRef>, open: (ArtistRef) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(artists, key = { it.id }) { artist ->
            Column(Modifier.width(116.dp).clickable(onClickLabel = "Ver ${artist.name}") { open(artist) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ArtistPortrait(artist, Modifier.size(108.dp))
                Text(artist.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
@Composable private fun ArtistPortrait(artist: ArtistRef, modifier: Modifier) {
    val url = CoverArt.highQuality(artist.image, 600)
    var fallback by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, null, tint = Muted, modifier = Modifier.fillMaxSize(.5f))
        if (artist.image.isNotBlank()) AsyncImage(model = if (fallback) artist.image else url, contentDescription = artist.name, contentScale = ContentScale.Crop,
            onError = { if (url != artist.image) fallback = true }, modifier = Modifier.fillMaxSize())
    }
}
@UnstableApi
@Composable private fun ArtistDialog(screen: ArtistScreenState, vm: MusicViewModel, playlist: (List<Track>) -> Unit, player: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val playback by vm.state.collectAsStateWithLifecycle()
    val scroll = rememberLazyListState()
    LaunchedEffect(screen.title) { scroll.scrollToItem(0) }
    Dialog(onDismissRequest = vm::backArtist, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ThemeDialogBars()
        Surface(Modifier.fillMaxSize(), color = Navy) {
            Column(Modifier.safeDrawingPadding().fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::backArtist) { Icon(Icons.Default.ArrowBack, "Volver") }
                    Text(screen.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::closeArtist) { Icon(Icons.Default.Close, "Cerrar artista") }
                }
                LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxWidth().weight(1f), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (screen.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    val profile = screen.profile
                    if (screen.error.isNotBlank()) item {
                        Text(screen.error, color = Orange, fontSize = 13.sp)
                        TextButton(onClick = if (profile == null) vm::retryArtist else vm::moreArtistSongs) { Text("Reintentar") }
                    }
                    if (profile != null) {
                        item {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ArtistPortrait(profile.artist, Modifier.size(160.dp))
                                Text(profile.artist.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                if (profile.genre.isNotBlank()) Text(profile.genre, color = Mint, fontSize = 14.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = { profile.songs.firstOrNull()?.let { vm.play(it, profile.songs) } }, enabled = profile.songs.isNotEmpty()) { Icon(Icons.Default.PlayArrow, null); Text("Reproducir") }
                                    IconButton(onClick = { playlist(profile.songs) }, enabled = profile.songs.isNotEmpty()) { Icon(Icons.Default.PlaylistAdd, "Guardar canciones en playlist") }
                                }
                            }
                        }
                        item { SectionTitle("Populares") }
                        if (profile.songs.isEmpty()) item { Text("No hay audios disponibles.", color = Muted) }
                        items(profile.songs, key = { "artist-song-${it.id}" }) { song ->
                            val saved = library.tracks.find { it.id == song.id }
                            SongRow(song.copy(localUri = saved?.localUri.orEmpty(), favorite = saved?.favorite == true), vm,
                                { vm.play(song, profile.songs) }, { playlist(listOf(song)) }, profile.counts[song.id])
                        }
                        if (screen.loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        else if (profile.songsBrowse.isNotBlank() && (!screen.moreLoaded || screen.next != null)) item {
                            OutlinedButton(onClick = vm::moreArtistSongs, modifier = Modifier.fillMaxWidth()) { Text("Más canciones") }
                        }
                        if (profile.related.isNotEmpty()) {
                            item { SectionTitle("Artistas similares") }
                            item { ArtistRail(profile.related, vm::openArtist) }
                        }
                    }
                }
                playback.current?.let { current ->
                    Surface(color = Panel, modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clickable(onClickLabel = "Abrir reproductor", onClick = player))
                            IconButton(onClick = vm::toggle) { Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playback.playing) "Pausar" else "Reproducir") }
                            IconButton(onClick = player) { Icon(Icons.Default.ExpandLess, "Abrir reproductor") }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun DiscoveryMixCard(mix: DiscoveryMix, play: () -> Unit, save: () -> Unit, open: (() -> Unit)?, modifier: Modifier = Modifier) {
    EditorialMixCard(mix, play, save, open, modifier, featured = true)
}
@UnstableApi
@Composable private fun PlaylistOfflineControls(name: String, tracks: List<Track>, works: List<WorkInfo>, state: MusicState, vm: MusicViewModel) {
    var confirmDelete by remember(name) { mutableStateOf(false) }
    val ids = tracks.map { it.id }.toSet()
    val activeIds = works.filter { !it.state.isFinished }.flatMap { it.tags }
        .filter { it.startsWith("track:") }.map { it.removePrefix("track:") }.filter { it in ids }.toSet()
    val pending = PlaylistOffline.toDownload(tracks, activeIds).size
    val saved = PlaylistOffline.toDelete(tracks).size
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${tracks.count { it.localUri.isNotBlank() }} de ${tracks.size} disponibles sin conexión", color = Mint, fontSize = 13.sp)
            if (activeIds.isNotEmpty()) Text("${activeIds.size} en cola o guardando", color = Muted, fontSize = 12.sp)
            Button(onClick = { vm.downloadPlaylist(name) }, enabled = pending > 0 && !state.batchBusy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Descargar todas ($pending pendientes)")
            }
            OutlinedButton(onClick = { confirmDelete = true }, enabled = saved > 0 && activeIds.isEmpty() && !state.batchBusy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Eliminar descargas ($saved)")
            }
            if (state.batchList == name && state.batchMessage.isNotBlank()) Text(state.batchMessage, color = Muted, fontSize = 12.sp)
            if (state.batchBusy && state.batchList == name) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (saved > 0 && activeIds.isNotEmpty()) Text("Espera a que terminen las descargas o cancélalas antes de eliminarlas.", color = Muted, fontSize = 12.sp)
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Eliminar descargas de $name") },
        text = { Text("Se borrarán $saved archivos descargados del teléfono. Tus playlists y favoritas se conservarán. La copia sin conexión también dejará de estar disponible en otras listas que contengan esas canciones. Los archivos importados no se borran.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePlaylistDownloads(name) }, enabled = !state.batchBusy && activeIds.isEmpty()) { Text("Eliminar descargas") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
}
@Composable private fun EmptyCard(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(22.dp)) { Icon(Icons.Default.MusicNote, null, tint = Mint); Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); Text(detail, color = Muted, fontSize = 13.sp) }
}
@Composable private fun StatCard(title: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Panel).clickable(onClick = onClick).padding(18.dp)) { Text(count.toString(), fontSize = 26.sp, color = Mint, fontWeight = FontWeight.Bold); Text(title, color = Muted, fontSize = 13.sp) }
}
@Composable internal fun Artwork(track: Track, modifier: Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, Panel))), contentAlignment = Alignment.Center) {
        val pixels = with(androidx.compose.ui.platform.LocalDensity.current) { maxOf(maxWidth, maxHeight).toPx() }
        val quality = if (pixels <= 256) 256 else if (pixels <= 600) 600 else 1200
        val upgraded = CoverArt.highQuality(track.artwork, quality)
        var fallback by remember(upgraded) { mutableStateOf(false) }
        PulsoMark(Modifier.fillMaxSize(.4f))
        if (track.artwork.isNotBlank()) AsyncImage(model = if (fallback) track.artwork else upgraded, contentDescription = "Carátula de ${track.title}", contentScale = ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, onError = { if (!fallback && upgraded != track.artwork) fallback = true }, modifier = Modifier.fillMaxSize())
    }
}
@Composable internal fun PlaylistArtwork(songs: List<Track>, modifier: Modifier) {
    val covers = songs.filter { it.artwork.isNotBlank() }.distinctBy { it.artwork }.take(4)
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Panel)) {
        when (covers.size) {
            0 -> Icon(Icons.Default.LibraryMusic, "Playlist sin portada", tint = Mint, modifier = Modifier.align(Alignment.Center).size(36.dp))
            1 -> Artwork(covers.first(), Modifier.fillMaxSize())
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(2) { row -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(2) { column ->
                        val cover = covers[(row * 2 + column) % covers.size]
                        Artwork(cover, Modifier.weight(1f).fillMaxHeight())
                    }
                } }
            }
        }
    }
}
@UnstableApi
@Composable private fun SongRow(track: Track, vm: MusicViewModel, play: () -> Unit, playlist: () -> Unit, subtitle: String? = null, playlistContext: String? = null) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRemoveSong by remember(track.id, playlistContext) { mutableStateOf(false) }
    val works by vm.downloads.collectAsStateWithLifecycle()
    val active = works.firstOrNull { "track:${track.id}" in it.tags && !it.state.isFinished }
    if (confirmRemoveSong && playlistContext != null) AlertDialog(onDismissRequest = { confirmRemoveSong = false },
        title = { Text("Eliminar canción de PULSO") },
        text = { Text("Se eliminará «${track.title}» de esta playlist, de las demás playlists, de favoritas y de la cola. También se borrará su archivo de audio del teléfono, si existe. Esta acción no se puede deshacer.") },
        confirmButton = { TextButton(onClick = { confirmRemoveSong = false; vm.removeSongEverywhere(track) }, enabled = active == null) { Text("Eliminar de PULSO", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmRemoveSong = false }) { Text("Cancelar") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(if (track.id.startsWith("local-")) "Quitar canción" else "Eliminar descarga") },
        text = { Text(if (track.id.startsWith("local-")) "Se quitará de la biblioteca y de tus playlists. El archivo original se conservará." else "Se borrará el audio de ${track.title} del teléfono. La canción seguirá en tus playlists y podrás descargarla de nuevo.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSaved(track) }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = play).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track, Modifier.size(56.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Spacer(Modifier.height(3.dp)); Text(subtitle?.takeIf { it.isNotBlank() } ?: track.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (track.id.startsWith("local-") && track.localUri.isBlank()) Text("Vuelve a importar el archivo de audio", color = Orange, fontSize = 11.sp)
            if (active != null) {
                val percent = active.progress.getInt("progress", -1)
                Text(if (active.progress.getBoolean("waiting", false)) "En cola de descarga" else if (active.state != WorkInfo.State.RUNNING) "En cola · esperando conexión" else if (percent < 0) "Preparando descarga…" else if (percent >= 99) "Guardando archivo…" else "Guardando · $percent %", color = Mint, fontSize = 12.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        if (track.localUri.isNotBlank() && !track.id.startsWith("local-")) Icon(Icons.Default.CheckCircle, "Descargada", tint = Mint, modifier = Modifier.size(18.dp))
        if (track.favorite) Icon(Icons.Default.Favorite, "Favorita", tint = Mint, modifier = Modifier.size(15.dp))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones de ${track.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (track.favorite) "Quitar de favoritas" else "Añadir a favoritas") }, onClick = { vm.favorite(track); menu = false })
                DropdownMenuItem(text = { Text("Reproducir después") }, onClick = { vm.playNext(track); menu = false })
                DropdownMenuItem(text = { Text("Añadir al final de la cola") }, onClick = { vm.enqueue(track); menu = false })
                DropdownMenuItem(text = { Text("Añadir a playlist") }, onClick = { playlist(); menu = false })
                if (playlistContext != null) DropdownMenuItem(text = { Text("Quitar de esta playlist", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    enabled = active == null, onClick = { menu = false; confirmRemoveSong = true })
                if (!track.id.startsWith("local-")) {
                    if (track.artists.isEmpty()) DropdownMenuItem(text = { Text("Ver artista") }, onClick = { menu = false; vm.openArtist(track) })
                    else track.artists.forEach { artist -> DropdownMenuItem(text = { Text("Ver ${artist.name}") }, onClick = { menu = false; vm.openArtist(artist) }) }
                }
                if (!track.id.startsWith("local-")) DropdownMenuItem(text = { Text(if (active != null) "Guardando…" else if (track.localUri.isBlank()) "Descargar canción" else "Descargada ✓") }, enabled = track.localUri.isBlank() && active == null, onClick = { vm.download(track); menu = false })
                if (track.localUri.isNotBlank()) DropdownMenuItem(text = { Text(if (track.id.startsWith("local-")) "Quitar de biblioteca" else "Eliminar descarga") }, enabled = active == null, onClick = { menu = false; confirmDelete = true })
                if (active != null) DropdownMenuItem(text = { Text("Cancelar descarga") }, onClick = { vm.cancel(active); menu = false })
            }
        }
    }
}

@Composable private fun EqualizerCard(eq: EqState) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Ecualizador", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold); Switch(checked = eq.enabled, enabled = eq.supported, onCheckedChange = { AudioSettings.change(it, eq.levels) }) }
        if (!eq.supported) Text("Reproduce una canción para activar las bandas disponibles en tu dispositivo.", color = Muted, fontSize = 13.sp)
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { AudioSettings.change(true, eq.levels.map { 0f }) }) { Text("Plano") }
                TextButton(onClick = { AudioSettings.change(true, eq.frequencies.map { if (it < 300) 5f else 0f }) }) { Text("Graves") }
                TextButton(onClick = { AudioSettings.change(true, eq.frequencies.map { if (it in 500..4000) 3f else -1f }) }) { Text("Voz") }
            }
            eq.frequencies.forEachIndexed { i, hz ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (hz >= 1000) "${hz / 1000f} kHz" else "$hz Hz", Modifier.width(60.dp), fontSize = 11.sp, color = Muted)
                    Slider(value = eq.levels[i], onValueChange = { value -> AudioSettings.change(eq.enabled, eq.levels.toMutableList().also { it[i] = value }) }, enabled = eq.enabled, valueRange = eq.min..eq.max, modifier = Modifier.weight(1f))
                    Text("${eq.levels[i].toInt()} dB", Modifier.width(40.dp), fontSize = 11.sp)
                }
            }
        }
    } }
}

@UnstableApi
@Composable private fun PlayerDialog(state: MusicState, vm: MusicViewModel, close: () -> Unit, importLrc: () -> Unit, playlist: (Track) -> Unit) {
    val savedLibrary by vm.library.collectAsStateWithLifecycle()
    val current = state.current ?: return
    val track = savedLibrary.tracks.find { it.id == current.id } ?: current
    var section by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val lyricsList = rememberLazyListState()
    val active = state.lyrics?.let { Lrc.active(it.lines, state.position) } ?: -1
    LaunchedEffect(active, section) { if (section == 1 && active >= 0) lyricsList.animateScrollToItem(active) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ThemeDialogBars()
        Surface(Modifier.fillMaxSize(), color = Navy) {
            Column(Modifier.safeDrawingPadding().fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Default.ExpandMore, "Cerrar reproductor") }
                    Text("REPRODUCTOR", Modifier.weight(1f), color = Mint, letterSpacing = 2.sp, fontSize = 12.sp)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones de la canción") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Añadir a playlist") }, onClick = { menu = false; playlist(track) })
                            if (!track.id.startsWith("local-")) {
                                if (track.artists.isEmpty()) DropdownMenuItem(text = { Text("Ver artista") }, onClick = { menu = false; close(); vm.openArtist(track) })
                                else track.artists.forEach { artist -> DropdownMenuItem(text = { Text("Ver ${artist.name}") }, onClick = { menu = false; close(); vm.openArtist(artist) }) }
                                DropdownMenuItem(text = { Text(if (track.localUri.isBlank()) "Descargar" else "Descargada") }, enabled = track.localUri.isBlank(), onClick = { menu = false; vm.download(track) })
                            }
                            DropdownMenuItem(text = { Text(if (track.favorite) "Quitar de favoritas" else "Añadir a favoritas") }, onClick = { menu = false; vm.favorite(track) })
                            DropdownMenuItem(text = { Text("Añadir a la cola") }, onClick = { menu = false; vm.enqueue(track) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Canción", "Letras", "Cola").forEachIndexed { i, name -> FilterChip(selected = section == i, onClick = { section = i; if (i == 1 && state.lyrics == null) vm.lyrics(track) }, label = { Text(name) }) } }
                if (state.error) Text(state.message, color = Orange, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (state.playbackMessage.isNotBlank()) Text(state.playbackMessage, color = Orange, fontSize = 12.sp)
                if (state.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.weight(1f).widthIn(max = 650.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (section) {
                        0 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true).widthIn(max = 340.dp)) {
                                Artwork(track, Modifier.fillMaxSize().pulseLight(12.dp, enabled = state.playRequested && !state.buffering))
                                Surface(color = Navy.copy(alpha = .9f), shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                                    IconToggleButton(checked = track.favorite, onCheckedChange = { vm.favorite(track) }) {
                                        Icon(if (track.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (track.favorite) "Quitar de favoritas" else "Añadir a favoritas", tint = if (track.favorite) Orange else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                if (track.localUri.isNotBlank() && !track.id.startsWith("local-")) Surface(color = Navy.copy(alpha = .9f), shape = CircleShape, modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
                                    Icon(Icons.Default.CheckCircle, "Descargada", tint = Mint, modifier = Modifier.padding(8.dp).size(24.dp))
                                }
                            }
                            Spacer(Modifier.height(24.dp)); Text(track.title, fontWeight = FontWeight.Bold, fontSize = 25.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(6.dp)); Text(track.artist, color = Muted)
                            Spacer(Modifier.height(10.dp)); if (track.localUri.isBlank() && !track.id.startsWith("local-")) TextButton(onClick = { vm.download(track) }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Guardar sin conexión") }
                        }
                        1 -> Column(Modifier.fillMaxSize()) {
                            Text(state.lyricsMessage, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 10.dp))
                            if (state.lyrics?.lines?.isNotEmpty() == true) LazyColumn(state = lyricsList, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(vertical = 32.dp)) {
                                itemsIndexed(state.lyrics.lines) { index, line -> Text(line.text.ifBlank { "♪" }, color = if (index == active) Mint else Muted, fontSize = 25.sp, fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.clickable { vm.seek(line.millis) }) }
                            } else LazyColumn(Modifier.weight(1f)) { item { Text(state.lyrics?.text ?: "Las letras aparecerán aquí cuando estén disponibles.", fontSize = 21.sp, lineHeight = 32.sp, color = Muted) } }
                            TextButton(onClick = importLrc) { Text("Importar archivo LRC") }
                        }
                        else -> LazyColumn(Modifier.fillMaxSize()) { itemsIndexed(state.queue) { index, song ->
                            ListItem(headlineContent = { Text(song.title, maxLines = 2) }, supportingContent = { Text(song.artist) }, leadingContent = { Text("${index + 1}", color = Mint) }, modifier = Modifier.clickable { vm.play(song, state.queue) }, colors = ListItemDefaults.colors(containerColor = Navy))
                        } }
                    }
                }
                Column(Modifier.widthIn(max = 650.dp).fillMaxWidth().padding(vertical = 16.dp)) {
                    Slider(value = state.position.coerceAtMost(state.duration).toFloat(), onValueChange = { vm.seek(it.toLong()) }, enabled = state.duration > 0, valueRange = 0f..state.duration.coerceAtLeast(1).toFloat())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(clock(state.position), color = Muted, fontSize = 12.sp); Text(clock(state.duration), color = Muted, fontSize = 12.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = vm::shuffle) { Icon(Icons.Default.Shuffle, "Aleatorio", tint = if (state.shuffle) Mint else Muted) }
                        IconButton(onClick = vm::previous) { Icon(Icons.Default.SkipPrevious, "Anterior", modifier = Modifier.size(34.dp)) }
                        FilledIconButton(onClick = vm::toggle, modifier = Modifier.size(68.dp)) { Icon(if (state.playRequested) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playRequested) "Pausar" else "Reproducir", modifier = Modifier.size(36.dp)) }
                        IconButton(onClick = vm::next) { Icon(Icons.Default.SkipNext, "Siguiente", modifier = Modifier.size(34.dp)) }
                        IconButton(onClick = vm::repeat) { Icon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repetir", tint = if (state.repeat != Player.REPEAT_MODE_OFF) Mint else Muted) }
                    }
                }
            }
        }
    }
}
private fun clock(millis: Long) = "${millis / 60000}:${(millis / 1000 % 60).toString().padStart(2, '0')}"
