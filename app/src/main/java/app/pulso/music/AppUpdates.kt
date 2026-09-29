package app.pulso.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

internal data class AppUpdateState(
    val info: AppUpdateInfo? = null, val checking: Boolean = false,
    val message: String = ""
)

internal object AppUpdates {
    private val mutable = MutableStateFlow(AppUpdateState())
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkLock = Mutex()
    private const val CHANNEL = "app-updates"
    private const val NOTICE = 7101
    private const val DOWNLOAD = 7102
    private const val PERIOD = 12 * 60 * 60 * 1000L
    val configured get() = AppUpdateInfo.validRepository(BuildConfig.UPDATE_REPOSITORY)
    private fun prefs(context: Context) = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    fun installedCode(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun start(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Actualizaciones de PULSO", NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (!configured) return
        val work = PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("pulso-update-check", ExistingPeriodicWorkPolicy.KEEP, work)
        // Reload metadata for the in-app card even when the next network check is not due.
        val saved = prefs(context).getString("manifest", null)
        val info = saved?.let { runCatching { AppUpdateInfo.parse(it, BuildConfig.UPDATE_REPOSITORY) }.getOrNull() }
            ?.takeIf { it.code > installedCode(context) && it.compatible(Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT) != null }
        mutable.update { it.copy(info = info) }
        // Cancel jobs persisted by versions that downloaded APKs inside PULSO.
        WorkManager.getInstance(context).cancelUniqueWork("pulso-apk-download")
        context.getSystemService(NotificationManager::class.java).cancel(DOWNLOAD)
        refreshNotification(context)
    }
    fun checkNow(context: Context) { scope.launch { check(context.applicationContext, manual = true, notify = false) } }

    fun onAppOpened(context: Context) {
        refreshNotification(context)
        scope.launch { check(context.applicationContext, manual = true, notify = true) }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun refreshNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val info = mutable.value.info?.takeIf { it.code > installedCode(context) }
        if (info == null) { manager.cancel(NOTICE); return }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        try {
            manager.notify(NOTICE, notification(context, "PULSO ${info.name} disponible",
                "Toca para abrir Ajustes y revisar la actualización.")
                .setAutoCancel(false).setOngoing(true).setOnlyAlertOnce(true).build())
        } catch (_: SecurityException) { /* Settings remain accessible without notification permission. */ }
    }

    @android.annotation.SuppressLint("MissingPermission") // Checked before notify; revoked permissions are caught.
    suspend fun check(context: Context, manual: Boolean, notify: Boolean): Boolean = checkLock.withLock {
        if (!configured) { mutable.update { it.copy(message = "Canal de actualizaciones pendiente de configurar.") }; return@withLock true }
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("lastCheck", 0L) in 0 until PERIOD) return@withLock true
        mutable.update { it.copy(checking = true, message = "") }
        try {
            val url = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/latest/download/update.json"
            val raw = withContext(Dispatchers.IO) {
                val connection = UpdateTransfer.connection(url)
                try {
                    connection.inputStream.use {
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= 65536)
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toByteArray().toString(Charsets.UTF_8)
                    }
                } finally { connection.disconnect() }
            }
            val info = AppUpdateInfo.parse(raw, BuildConfig.UPDATE_REPOSITORY)
            val newer = info.takeIf { it.code > installedCode(context) }
            val compatible = newer?.compatible(Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT)
            prefs.edit().putLong("lastCheck", now).putString("manifest", raw).apply()
            mutable.update { current ->
                current.copy(info = if (compatible != null) newer else null,
                    message = when {
                        newer == null -> "PULSO está actualizado."
                        compatible == null -> "La nueva versión no es compatible con este dispositivo."
                        else -> "Versión ${info.name} disponible."
                    })
            }
            refreshNotification(context)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.update { it.copy(message = "No se pudo consultar la actualización. Comprueba la conexión o reintenta más tarde.") }
            false
        } finally { mutable.update { it.copy(checking = false) } }
    }

    private fun notification(context: Context, title: String, text: String): NotificationCompat.Builder {
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).putExtra("showAppUpdates", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, NOTICE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_pulso_mark)
            .setContentTitle(title).setContentText(text).setContentIntent(pending).setAutoCancel(true)
    }

    fun message(text: String) { mutable.update { it.copy(message = text) } }
    val openRequested = MutableStateFlow(false)

    fun openDownload(context: Context, info: AppUpdateInfo) {
        val url = info.browserDownloadUrl(Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT,
            installedCode(context), BuildConfig.UPDATE_REPOSITORY)
        if (url == null) {
            message("No hay una descarga compatible. Busca actualizaciones de nuevo.")
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            message("Descarga abierta. Al terminar, abre la APK desde Descargas e instala sin desinstalar PULSO.")
        } catch (_: android.content.ActivityNotFoundException) {
            message("No se encontró una aplicación para abrir el enlace. Instala o habilita un navegador.")
        } catch (_: SecurityException) {
            message("Android no permitió abrir el enlace. Revisa tu navegador predeterminado.")
        }
    }
}
class AppUpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (AppUpdates.check(applicationContext, manual = true, notify = true)) Result.success()
        else if (runAttemptCount < 2) Result.retry() else Result.failure()
}

// Retained only so WorkManager can safely resolve old persisted requests after an update.
class AppUpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}