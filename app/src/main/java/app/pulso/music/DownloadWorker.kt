package app.pulso.music

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val processId = id.toString()
    private val notificationId = id.hashCode()
    private fun notification(title: String, percent: Int) = NotificationCompat.Builder(applicationContext, "downloads")
        .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(if (percent < 0) "Preparando audio…" else "$percent %")
        .setProgress(100, percent.coerceAtLeast(0), percent < 0).setOngoing(true)
        .addAction(android.R.drawable.ic_delete, "Cancelar", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
    override suspend fun doWork(): Result = coroutineScope {
        val raw = inputData.getString("track") ?: return@coroutineScope Result.failure()
        val track = Track.from(JSONObject(raw))
        val existing = Library.track(track.id)?.localUri.orEmpty()
        if (existing.isNotBlank() && runCatching { applicationContext.contentResolver.openInputStream(android.net.Uri.parse(existing))?.use { true } == true }.getOrDefault(false)) return@coroutineScope Result.success()
        setForeground(ForegroundInfo(notificationId, notification(track.title, -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        val guard = launch(Dispatchers.IO) { try { awaitCancellation() } finally { com.yausername.youtubedl_android.YoutubeDL.destroyProcessById(processId) } }
        val folder = File(applicationContext.cacheDir, "downloads/$processId")
        try {
            setProgress(workDataOf("waiting" to true))
            transferSlots.withPermit {
            setProgress(workDataOf("waiting" to false, "progress" to -1))
            withContext(Dispatchers.IO) {
                var last = -2
                val audio = YouTubeEngine.download(applicationContext, track, folder, processId) { progress, _, _ ->
                    if (isStopped) com.yausername.youtubedl_android.YoutubeDL.destroyProcessById(processId)
                    val percent = progress.toInt().coerceIn(0, 100)
                    if (percent != last) { last = percent; setProgressAsync(workDataOf("progress" to percent)); applicationContext.getSystemService(NotificationManager::class.java).notify(notificationId, notification(track.title, percent)) }
                }
                ensureActive()
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100) + ".mp3")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Pulso")
                    put(MediaStore.Audio.Media.TITLE, track.title); put(MediaStore.Audio.Media.ARTIST, track.artist)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = applicationContext.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("No se pudo crear el archivo de música.")
                try {
                    resolver.openOutputStream(uri)?.use { output -> audio.inputStream().use { it.copyTo(output) } } ?: error("No se pudo escribir el audio.")
                    values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0); resolver.update(uri, values, null, null)
                    Library.save(track.copy(localUri = uri.toString()))
                } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
            }
            }
            Result.success(workDataOf("title" to track.title))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.w("Pulso", "Download failed", e); Result.failure(workDataOf("error" to "No se pudo descargar. Comprueba la conexión y el espacio disponible; luego reintenta.")) }
        finally { withContext(NonCancellable + Dispatchers.IO) { guard.cancelAndJoin(); folder.deleteRecursively() } }
    }
    companion object {
        private val transferSlots = Semaphore(2)
        fun enqueue(context: Context, track: Track): Operation {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("track" to track.json().toString()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("downloads").addTag("track:${track.id}").build()
            return WorkManager.getInstance(context).enqueueUniqueWork("download:${track.id}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
