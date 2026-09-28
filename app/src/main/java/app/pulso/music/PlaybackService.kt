package app.pulso.music

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.Equalizer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqState(val supported: Boolean = false, val enabled: Boolean = false, val frequencies: List<Int> = emptyList(), val levels: List<Float> = emptyList(), val min: Float = -15f, val max: Float = 15f)
object AudioSettings {
    val state = MutableStateFlow(EqState())
    var apply: ((Boolean, List<Float>) -> Unit)? = null
    fun change(enabled: Boolean, levels: List<Float>) { apply?.invoke(enabled, levels) }
}

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var equalizer: Equalizer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recovery = PlaybackRecovery()
    private var generation = 0
    private val stablePlayback = Runnable { if (player.isPlaying) recovery.reset() }
    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0").setConnectTimeoutMs(20000).setReadTimeoutMs(20000)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme != "pulso") spec else {
                val id = spec.uri.lastPathSegment ?: error("Canción inválida")
                val audio = YouTubeEngine.resolve(applicationContext, id)
                spec.withUri(android.net.Uri.parse(audio.url)).withRequestHeaders(audio.headers)
            }
        }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(resolving)).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                generation++
                handler.removeCallbacks(stablePlayback)
                PlaybackFeedback.message.value = ""
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) recovery.reset()
            }
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                if (!ready) generation++ // Invalidate pending recovery after pause, focus loss or unplugging.
                if (ready && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    recovery.reset()
                    PlaybackFeedback.message.value = ""
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                handler.removeCallbacks(stablePlayback)
                if (playing) {
                    PlaybackFeedback.message.value = ""
                    handler.postDelayed(stablePlayback, 10_000)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("PulsoPlayback", "Playback failed: ${error.errorCodeName}", error)
                val item = player.currentMediaItem ?: return
                val expected = ++generation
                handler.post {
                    if (generation != expected || player.currentMediaItem != item || !player.playWhenReady) return@post
                    val remote = item.localConfiguration?.uri?.scheme == "pulso"
                    val next = player.nextMediaItemIndex
                    val nextId = if (next != C.INDEX_UNSET) player.getMediaItemAt(next).mediaId else null
                    when (recovery.decide(item.mediaId, remote, online(), nextId)) {
                        PlaybackRecovery.Action.RETRY -> {
                            YouTubeEngine.invalidate(item.mediaId)
                            PlaybackFeedback.message.value = "Reconectando…"
                            player.prepare()
                        }
                        PlaybackRecovery.Action.NEXT -> {
                            player.seekTo(next, C.TIME_UNSET)
                            player.prepare()
                            PlaybackFeedback.message.value = "La canción anterior no estaba disponible."
                        }
                        PlaybackRecovery.Action.OFFLINE -> {
                            player.pause()
                            PlaybackFeedback.message.value = "Sin conexión. Pulsa reproducir cuando vuelva la red o elige una descarga."
                        }
                        PlaybackRecovery.Action.STOP -> {
                            player.pause()
                            PlaybackFeedback.message.value = "No se pudo continuar. Reintenta o elige otra canción."
                        }
                    }
                }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) { setupEqualizer(audioSessionId) }
        })
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(intent).build()
    }
    private fun setupEqualizer(id: Int) {
        equalizer?.release(); equalizer = null
        val prefs = getSharedPreferences("audio", MODE_PRIVATE)
        runCatching {
            val eq = Equalizer(0, id)
            equalizer = eq
            val count = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val levels = (0 until count).map { prefs.getFloat("band$it", 0f).coerceIn(range[0] / 100f, range[1] / 100f) }
            val enabled = prefs.getBoolean("enabled", false)
            levels.forEachIndexed { index, value -> eq.setBandLevel(index.toShort(), (value * 100).toInt().toShort()) }
            eq.enabled = enabled
            AudioSettings.state.value = EqState(true, enabled, (0 until count).map { eq.getCenterFreq(it.toShort()) / 1000 }, levels, range[0] / 100f, range[1] / 100f)
            AudioSettings.apply = { active, values ->
                runCatching {
                    eq.enabled = active
                    val safe = (0 until count).map { values.getOrElse(it) { 0f }.coerceIn(range[0] / 100f, range[1] / 100f) }
                    val editor = prefs.edit().putBoolean("enabled", active)
                    safe.forEachIndexed { i, v -> eq.setBandLevel(i.toShort(), (v * 100).toInt().toShort()); editor.putFloat("band$i", v) }
                    editor.apply(); AudioSettings.state.value = AudioSettings.state.value.copy(enabled = active, levels = safe)
                }
            }
        }.onFailure { AudioSettings.state.value = EqState(); AudioSettings.apply = null }
    }
    private fun online(): Boolean {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() { generation++; handler.removeCallbacksAndMessages(null); PlaybackFeedback.message.value = ""; AudioSettings.apply = null; equalizer?.release(); session?.release(); player.release(); super.onDestroy() }
}
