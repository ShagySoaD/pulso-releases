package app.pulso.music

import android.content.Context
import android.util.Base64
import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class SocialMessage(val id: String, val text: String, val mine: Boolean, val at: Long, val status: String = "Recibido")
internal data class SocialPlaylist(val id: String, val name: String, val tracks: List<Track> = emptyList())
internal data class SocialFriend(val key: String, val name: String = "Contacto", val online: Boolean = false,
    val avatar: Int = 0, val photo: String = "",
    val bio: String = "", val compatible: Boolean = false, val favorites: List<Track> = emptyList(),
    val sharesFavorites: Boolean = false, val sharesPlaylists: Boolean = false, val playlists: List<SocialPlaylist> = emptyList(), val now: Track? = null, val nowAt: Long = 0,
    val unread: Int = 0, val messages: List<SocialMessage> = emptyList())
internal data class SocialState(val ready: Boolean = false, val enabled: Boolean = false, val address: String = "",
    val avatar: Int = 0, val photo: String = "",
    val name: String = "", val bio: String = "", val shareFavorites: Boolean = false, val shareNow: Boolean = false, val sharePlaylists: Boolean = false,
    val status: String = "Desactivado", val error: String = "", val friends: List<SocialFriend> = emptyList(), val requests: List<String> = emptyList())

/** Owns the native handle, encryption and every network operation on one worker. */
internal object SocialEngine {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Pulso-Tox").apply { isDaemon = true } }
    private val mutable = MutableStateFlow(SocialState())
    val state = mutable.asStateFlow()
    val openRequested = MutableStateFlow<String?>(null)
    val inviteRequested = MutableStateFlow<String?>(null)
    fun invitation(intent: android.content.Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "pulso" || uri.host != "connect") return
        runCatching { SocialProtocol.address(uri.toString()) }.getOrNull()?.let { inviteRequested.value = it }
        intent.data = null
    }
    private var model = SocialState()
    private var handle = 0L
    private var saved = byteArrayOf()
    private lateinit var vault: SocialVault
    private lateinit var notifications: SocialNotifications
    private var visible = false
    private var playing = false
    private var current: Track? = null
    private var fatal = false
    private var initialized = false
    private var connected = false
    private var bootstrapAt = 0L
    private var nextIterate = 0L
    private var lastSnapshot = ""
    private var snapshotAt = 0L
    private var saveAt = 0L
    private val nextSend = mutableMapOf<String, Long>()
    private var dirty = false
    private val blocked = mutableSetOf<String>()
    private val receipts = mutableMapOf<Pair<String, Long>, String>()
    private val packets = ArrayDeque<Pair<String, ByteArray>>()
    private val revisions = mutableMapOf<String, String>()
    private val avatars = SocialAvatarTransfer()
    private var openChat: String? = null
    private fun publish() { mutable.value = model }
    private fun editFriend(key: String, action: (SocialFriend) -> SocialFriend) {
        model = model.copy(friends = model.friends.map { if (it.key == key) action(it) else it }); dirty = true
    }
    private fun job(action: () -> Unit) { worker.execute {
        if (fatal) return@execute
        try { action(); publish() } catch (e: Exception) { model = model.copy(error = e.message ?: "No se pudo completar la operación."); publish() }
    } }
    fun init(context: Context) {
        worker.execute {
            if (initialized) return@execute
            initialized = true
            vault = SocialVault(context.applicationContext)
            notifications = SocialNotifications(context.applicationContext)
            try {
                vault.read()?.let(::restore)
                model = model.copy(ready = true, enabled = true, status = "Conectando…")
            } catch (_: Exception) {
                fatal = true
                model = model.copy(error = "No se pudo abrir la identidad cifrada de Mensajes. No se ha sustituido ni borrado.")
            }
            publish()
            fun loop() {
                tickSafely()
                if (!fatal) worker.schedule({ loop() }, if (handle == 0L) 1000L else 50L, TimeUnit.MILLISECONDS)
            }
            loop()
        }
    }
    fun visibility(value: Boolean) = job {
        visible = value
        if (value) openChat?.let { key -> editFriend(key) { it.copy(unread = 0) }; notifications.cancel(key) }
    }
    fun playback(active: Boolean, track: Track?) = job {
        if (playing != active || current?.id != track?.id) snapshotAt = 0
        playing = active; current = track
    }
    fun enable(value: Boolean) = job { model = model.copy(enabled = value, error = ""); persist() }
    fun profile(name: String, bio: String, favorites: Boolean, now: Boolean, avatar: Int = model.avatar, photo: String = model.photo, playlists: Boolean = model.sharePlaylists) = job {
        require(name.isNotBlank()) { "Escribe un nombre." }
        require(photo.isEmpty() || SocialMedia.decodePhoto(photo)?.also { it.recycle() } != null) { "Elige otra foto." }
        model = model.copy(name = name.trim().take(40), bio = bio.trim().take(120), shareFavorites = favorites, shareNow = now, sharePlaylists = playlists, avatar = avatar.coerceIn(0, 7), photo = photo, error = "")
        if (handle != 0L) ToxNative.name(handle, model.name.toByteArray())
        lastSnapshot = ""; snapshotAt = 0; persist()
    }
    fun clearError() = job { model = model.copy(error = "") }
    fun add(input: String) = job {
        requireEngine()
        val address = SocialProtocol.address(input)
        val key = address.take(64)
        require(address != model.address) { "Ese es tu propio código." }
        require(model.friends.none { it.key == key }) { "Ya tienes este contacto." }
        require(model.friends.size < 100) { "No se pueden añadir más contactos." }
        val number = ToxNative.add(handle, SocialProtocol.bytes(address), false)
        require(number >= 0) { "No se pudo añadir el contacto. Revisa su código ($number)." }
        blocked.remove(key)
        model = model.copy(friends = model.friends + SocialFriend(key), error = "")
        persist()
    }
    fun accept(key: String) = job {
        requireEngine()
        require(key in model.requests && model.friends.size < 100)
        require(ToxNative.add(handle, SocialProtocol.bytes(key), true) >= 0) { "No se pudo aceptar la solicitud." }
        model = model.copy(requests = model.requests - key, friends = model.friends + SocialFriend(key))
        persist()
    }
    fun reject(key: String) = job { blocked.add(key); model = model.copy(requests = model.requests - key); persist() }
    fun remove(key: String) = job {
        requireEngine()
        val friend = ToxNative.find(handle, SocialProtocol.bytes(key))
        if (friend >= 0) require(ToxNative.remove(handle, friend))
        blocked.add(key)
        model = model.copy(friends = model.friends.filterNot { it.key == key }, requests = model.requests - key)
        packets.removeAll { it.first == key }; receipts.keys.removeAll { it.first == key }; revisions.remove(key); avatars.remove(key)
        persist()
    }
    fun viewing(key: String?) = job { openChat = key; if (key != null) { editFriend(key) { it.copy(unread = 0) }; notifications.cancel(key) } }
    fun send(key: String, text: String) = job {
        val clean = text.trim()
        require(model.enabled) { "Espera un momento y vuelve a enviar." }
        require(clean.isNotBlank() && clean.toByteArray().size <= 1372) { "Acorta un poco el mensaje." }
        require(model.friends.any { it.key == key })
        require(model.friends.first { it.key == key }.messages.count { it.status == "Pendiente" } < 50) { "Ya hay 50 mensajes pendientes para este contacto." }
        editFriend(key) { it.copy(messages = (it.messages + SocialMessage(UUID.randomUUID().toString(), clean, true, System.currentTimeMillis(), "Pendiente")).takeLast(200)) }
        persist()
    }
    private fun requireEngine() { require(handle != 0L) { "Conectando. Inténtalo en un momento." } }
    private fun tickSafely() {
        if (fatal || !model.ready) return
        try { tick(); publish() } catch (e: Throwable) {
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            fatal = true
            if (handle != 0L) { runCatching { ToxNative.close(handle) }; handle = 0 }
            model = model.copy(ready = false, status = "No disponible", error = "No se pudo abrir Connect. Reinicia PULSO.")
            publish()
        }
    }
    private fun tick() {
        val wanted = model.enabled && (visible || playing)
        if (!wanted) {
            if (handle != 0L) {
                persist(); ToxNative.close(handle); handle = 0; connected = false
                packets.clear(); receipts.clear(); revisions.clear()
                model = model.copy(friends = model.friends.map { it.copy(online = false, compatible = false, now = null, favorites = emptyList(), playlists = emptyList(), sharesFavorites = false, sharesPlaylists = false) })
            }
            model = model.copy(status = if (model.enabled) "En pausa" else "Desactivado")
            return
        }
        val now = System.currentTimeMillis()
        if (handle == 0L) {
            handle = ToxNative.create(saved)
            check(handle != 0L) { "No se pudo abrir Connect." }
            model = model.copy(address = SocialProtocol.hex(ToxNative.address(handle)), status = "Conectando…")
            ToxNative.name(handle, model.name.ifBlank { "Usuario PULSO" }.toByteArray())
            persist(); bootstrapAt = 0; nextIterate = 0; lastSnapshot = ""
        }
        if (!connected && now - bootstrapAt > 60_000) { bootstrap(); bootstrapAt = now }
        if (now >= nextIterate) nextIterate = now + ToxNative.iterate(handle, this).coerceIn(20, 1000)
        if (now - bootstrapAt > 25_000 && !connected) model = model.copy(status = "Sin conexión")
        model.friends.filter { it.online }.forEach { friend ->
            val pending = friend.messages.firstOrNull { it.mine && it.status == "Pendiente" }
            if (pending != null && now >= (nextSend[friend.key] ?: 0L)) {
                // Persist before handing to Tox: an interrupted send is never blindly duplicated.
                editFriend(friend.key) { it.copy(messages = it.messages.map { m -> if (m.id == pending.id) m.copy(status = "Sin confirmar") else m }) }; persist()
                val id = ToxNative.send(handle, ToxNative.find(handle, SocialProtocol.bytes(friend.key)), pending.text.toByteArray())
                editFriend(friend.key) { it.copy(messages = it.messages.map { m -> if (m.id == pending.id) m.copy(status = if (id >= 0) "Enviado" else "Pendiente") else m }) }
                if (id >= 0) receipts[friend.key to id] = pending.id
                nextSend[friend.key] = now + if (id >= 0) 200 else 5_000
            }
        }
        if (now - snapshotAt > 5_000) {
            val signature = "${model.name}|${model.bio}|${model.avatar}|${SocialAvatarTransfer.hash(model.photo)}|${model.shareFavorites}|${model.shareNow}|${model.sharePlaylists}|" +
                (if (model.shareFavorites) Library.state.value.tracks.filter { it.favorite }.mapNotNull(SocialProtocol::song).take(40).joinToString() else "") +
                (if (model.sharePlaylists) sharedPlaylists().joinToString { list -> list.name + list.tracks.mapNotNull(SocialProtocol::song).joinToString() } else "")
            if (signature != lastSnapshot) {
                model.friends.filter { it.online && it.compatible }.forEach { share(it.key) }
                lastSnapshot = signature
            } else if (model.shareNow) model.friends.filter { it.online && it.compatible }.forEach { queueNow(it.key) }
            model = model.copy(friends = model.friends.map { if (now - it.nowAt > 90_000) it.copy(now = null) else it })
            snapshotAt = now
        }
        repeat(4) {
            val packet = packets.firstOrNull() ?: return@repeat
            val friend = model.friends.firstOrNull { it.key == packet.first && it.online }
            if (friend == null) packets.removeFirst()
            else if (ToxNative.packet(handle, ToxNative.find(handle, SocialProtocol.bytes(friend.key)), packet.second)) packets.removeFirst()
        }
        if (dirty && now - saveAt > 1000) persist()
    }
    @Keep fun onEvent(type: Int, number: Long, data: ByteArray, value: Long) {
        when (type) {
            0 -> { connected = value != 0L; model = model.copy(status = if (connected) "Conectado" else "Reconectando…") }
            1 -> {
                val key = SocialProtocol.hex(data)
                if (key !in blocked && model.requests.size < 50 && model.friends.none { it.key == key } && key !in model.requests) {
                    model = model.copy(requests = model.requests + key); dirty = true
                }
            }
            else -> {
                val key = ToxNative.key(handle, number)?.let(SocialProtocol::hex) ?: return
                if (model.friends.none { it.key == key }) return
                when (type) {
                    2 -> {
                        if (data.size > 1372) return
                        editFriend(key) { it.copy(unread = if (openChat == key && visible) 0 else (it.unread + 1).coerceAtMost(999), messages = (it.messages + SocialMessage(UUID.randomUUID().toString(), data.toString(Charsets.UTF_8), false, System.currentTimeMillis())).takeLast(200)) }
                        if (!visible) notifications.received(key)
                    }
                    3 -> {
                        avatars.remove(key)
                        editFriend(key) { it.copy(online = value != 0L, compatible = false, now = null, favorites = emptyList(), playlists = emptyList(), sharesFavorites = false, sharesPlaylists = false) }
                        packets.removeAll { it.first == key }
                        if (value != 0L) packets.add(key to SocialProtocol.encode("hello"))
                    }
                    4 -> editFriend(key) { it.copy(name = data.toString(Charsets.UTF_8).take(40).ifBlank { "Contacto" }) }
                    5 -> receipts.remove(key to value)?.let { id -> editFriend(key) { it.copy(messages = it.messages.map { m -> if (m.id == id) m.copy(status = "Entregado") else m }) } }
                    6 -> receive(key, data)
                }
            }
        }
    }
    private fun receive(key: String, data: ByteArray) {
        val json = SocialProtocol.decode(data) ?: return
        when (json.optString("type")) {
            "hello" -> { val was = model.friends.first { it.key == key }.compatible; editFriend(key) { it.copy(compatible = true) }; if (!was) share(key) }
            "profile" -> {
                revisions[key] = json.optString("rev").take(40)
                val hash = json.optString("photoHash")
                val existing = model.friends.first { it.key == key }.photo
                val keepPhoto = hash.isNotEmpty() && hash == SocialAvatarTransfer.hash(existing)
                avatars.begin(key, if (keepPhoto) "" else hash, revisions[key].orEmpty())
                editFriend(key) { it.copy(compatible = true, bio = json.optString("bio").take(120), avatar = json.optInt("avatar").coerceIn(0, 7), photo = if (keepPhoto) existing else "", sharesFavorites = json.optBoolean("favorites"), favorites = emptyList(), sharesPlaylists = json.optBoolean("playlists"), playlists = emptyList()) }
            }
            "avatar" -> avatars.receive(key, json)?.let { photo ->
                if (SocialMedia.decodePhoto(photo)?.also { it.recycle() } != null) editFriend(key) { it.copy(photo = photo) }
            }
            "favorite" -> {
                if (json.optString("rev") != revisions[key]) return
                val track = json.optJSONObject("song")?.let(SocialProtocol::track) ?: return
                editFriend(key) { if (it.sharesFavorites && it.favorites.size < 40 && it.favorites.none { t -> t.id == track.id }) it.copy(favorites = it.favorites + track) else it }
            }
            "playlist" -> {
                if (json.optString("rev") != revisions[key]) return
                val id = json.optString("id").takeIf { it.matches(Regex("[0-9]{1,2}")) } ?: return
                val name = json.optString("name").trim().take(80).ifBlank { "Playlist" }
                editFriend(key) { if (it.sharesPlaylists && it.playlists.size < 12 && it.playlists.none { list -> list.id == id }) it.copy(playlists = it.playlists + SocialPlaylist(id, name)) else it }
            }
            "playlistSong" -> {
                if (json.optString("rev") != revisions[key]) return
                val id = json.optString("id")
                val track = json.optJSONObject("song")?.let(SocialProtocol::track) ?: return
                editFriend(key) { friend ->
                    if (!friend.sharesPlaylists || friend.playlists.sumOf { it.tracks.size } >= 500) friend
                    else friend.copy(playlists = friend.playlists.map { list -> if (list.id == id && list.tracks.size < 100 && list.tracks.none { it.id == track.id }) list.copy(tracks = list.tracks + track) else list })
                }
            }
            "now" -> editFriend(key) { it.copy(now = json.optJSONObject("song")?.let(SocialProtocol::track), nowAt = System.currentTimeMillis()) }
        }
    }
    private fun share(key: String) {
        packets.removeAll { it.first == key && SocialProtocol.decode(it.second)?.optString("type") != "hello" }
        val revision = UUID.randomUUID().toString()
        packets.add(key to SocialProtocol.encode("profile", JSONObject().put("bio", model.bio).put("favorites", model.shareFavorites).put("playlists", model.sharePlaylists).put("rev", revision).put("avatar", model.avatar).put("photoHash", SocialAvatarTransfer.hash(model.photo))))
        SocialAvatarTransfer.packets(model.photo, revision).forEach { packets.add(key to it) }
        if (model.shareFavorites) Library.state.value.tracks.filter { it.favorite }.mapNotNull(SocialProtocol::song).take(40).forEach {
            packets.add(key to SocialProtocol.encode("favorite", JSONObject().put("rev", revision).put("song", it)))
        }
        queueNow(key)
        if (model.sharePlaylists) sharedPlaylists().forEach { list ->
            packets.add(key to SocialProtocol.encode("playlist", JSONObject().put("rev", revision).put("id", list.id).put("name", list.name)))
            list.tracks.mapNotNull(SocialProtocol::song).forEach { song ->
                packets.add(key to SocialProtocol.encode("playlistSong", JSONObject().put("rev", revision).put("id", list.id).put("song", song)))
            }
        }
    }
    private fun sharedPlaylists(): List<SocialPlaylist> {
        val library = Library.state.value
        val songs = library.tracks.associateBy { it.id }
        var remaining = 500
        return library.playlists.take(12).mapIndexed { index, list ->
            val tracks = list.ids.distinct().mapNotNull(songs::get).filter { SocialProtocol.song(it) != null }.take(minOf(100, remaining))
            remaining -= tracks.size
            SocialPlaylist(index.toString(), list.name.take(80), tracks)
        }
    }
    private fun queueNow(key: String) {
        packets.removeAll { it.first == key && SocialProtocol.decode(it.second)?.optString("type") == "now" }
        val song = if (model.shareNow && playing) current?.let(SocialProtocol::song) else null
        packets.addFirst(key to SocialProtocol.encode("now", JSONObject().put("song", song ?: JSONObject.NULL)))
    }
    private fun bootstrap() {
        // Public community bootstrap nodes, checked against nodes.tox.chat on 2026-09-30.
        listOf(
            Triple("144.217.167.73", 33445, "7E5668E0EE09E19F320AD47902419331FFEE147BB3606769CFBE921A2A2FD34C"),
            Triple("172.105.109.31", 33445, "D46E97CF995DC1820B92B7D899E152A217D36ABE22730FEA4B6BF1BFC06C617C"),
            Triple("144.172.88.203", 443, "2016A0F2797EE3A8B004BA623F11AAFC8146F1B8F45107232A1A1AECCE856674")
        ).forEach { (host, port, key) ->
            ToxNative.bootstrap(handle, host, 33445, SocialProtocol.bytes(key), false)
            ToxNative.bootstrap(handle, host, port, SocialProtocol.bytes(key), true)
        }
    }
    private fun persist() {
        if (handle != 0L) saved = ToxNative.save(handle)
        val json = JSONObject().put("v", 1).put("saved", Base64.encodeToString(saved, Base64.NO_WRAP))
            .put("enabled", model.enabled).put("address", model.address).put("name", model.name).put("bio", model.bio)
            .put("avatar", model.avatar).put("photo", model.photo)
            .put("favorites", model.shareFavorites).put("now", model.shareNow).put("playlists", model.sharePlaylists).put("requests", JSONArray(model.requests)).put("blocked", JSONArray(blocked.toList()))
        json.put("friends", JSONArray(model.friends.map { f -> JSONObject().put("key", f.key).put("name", f.name).put("unread", f.unread).put("avatar", f.avatar).put("photo", f.photo).put("bio", f.bio)
            .put("messages", JSONArray(f.messages.map { m -> JSONObject().put("id", m.id).put("text", m.text).put("mine", m.mine).put("at", m.at).put("status", m.status) })) }))
        try { vault.write(json) } catch (e: Exception) { fatal = true; if (handle != 0L) { ToxNative.close(handle); handle = 0 }; model = model.copy(status = "Error de almacenamiento"); throw e }
        dirty = false; saveAt = System.currentTimeMillis()
    }
    private fun restore(json: JSONObject) {
        require(json.getInt("v") == 1)
        saved = Base64.decode(json.getString("saved"), Base64.NO_WRAP)
        val friends = json.getJSONArray("friends")
        require(friends.length() <= 100)
        model = SocialState(enabled = json.optBoolean("enabled"), address = json.optString("address"), name = json.optString("name"), bio = json.optString("bio"),
            avatar = json.optInt("avatar").coerceIn(0, 7), photo = json.optString("photo"),
            shareFavorites = json.optBoolean("favorites"), shareNow = json.optBoolean("now"), sharePlaylists = json.optBoolean("playlists"), friends = (0 until friends.length()).map { i ->
                val f = friends.getJSONObject(i); val messages = f.getJSONArray("messages")
                SocialFriend(f.getString("key"), f.optString("name"), avatar = f.optInt("avatar").coerceIn(0, 7), photo = f.optString("photo"), bio = f.optString("bio"), unread = f.optInt("unread"), messages = (0 until messages.length()).map { j -> val m = messages.getJSONObject(j)
                    SocialMessage(m.getString("id"), m.getString("text"), m.getBoolean("mine"), m.getLong("at"), m.getString("status").let { if (it == "Enviado") "Sin confirmar" else it }) }.takeLast(200))
            }, requests = json.getJSONArray("requests").let { a -> (0 until a.length()).map { a.getString(it) }.take(50) })
        json.getJSONArray("blocked").let { a -> (0 until a.length()).forEach { blocked.add(a.getString(it)) } }
        require(saved.isNotEmpty() || model.address.isEmpty())
    }
}
