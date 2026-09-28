package app.pulso.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AppUpdateState(
    val info: AppUpdateInfo? = null, val checking: Boolean = false,
    val downloading: Boolean = false, val progress: Int = 0,
    val ready: String? = null, val message: String = ""
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
        scope.launch {
            if (info != null) {
                val apk = File(context.cacheDir, "app-updates/update-${info.code}.apk")
                if (apk.isFile && runCatching { verifyApk(context, apk, info.code) }.isSuccess)
                    mutable.update { it.copy(ready = apk.absolutePath) }
            }
            val active = WorkManager.getInstance(context).getWorkInfosForUniqueWork("pulso-apk-download").get()
                .any { !it.state.isFinished }
            if (active) mutable.update { it.copy(downloading = true) }
            check(context, manual = false, notify = false)
        }
    }

    fun checkNow(context: Context) { scope.launch { check(context.applicationContext, manual = true, notify = false) } }

    @android.annotation.SuppressLint("MissingPermission") // Checked before notify; revoked permissions are caught.
    suspend fun check(context: Context, manual: Boolean, notify: Boolean): Boolean = checkLock.withLock {
        if (mutable.value.downloading) return@withLock true
        if (!configured) { mutable.update { it.copy(message = "Canal de actualizaciones pendiente de configurar.") }; return@withLock true }
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("lastCheck", 0L) in 0 until PERIOD) return@withLock true
        mutable.update { it.copy(checking = true, message = "") }
        try {
            val url = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/latest/download/update.json"
            val raw = withContext(Dispatchers.IO) {
                val connection = connection(url)
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
                    ready = if (current.info?.code == newer?.code) current.ready else null,
                    message = when {
                        newer == null -> "PULSO está actualizado."
                        compatible == null -> "La nueva versión no es compatible con este dispositivo."
                        else -> "Versión ${info.name} disponible."
                    })
            }
            if (notify && compatible != null && prefs.getLong("notified", 0) != info.code &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
                    try {
                        manager.notify(NOTICE, notification(context, "PULSO ${info.name} disponible", "Toca para ver las novedades y actualizar.").build())
                        prefs.edit().putLong("notified", info.code).apply()
                    } catch (_: SecurityException) { /* The in-app card remains available without notification permission. */ }
                }
            }
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

    fun shouldPrompt(context: Context, code: Long) = prefs(context).getLong("prompted", 0) != code
    fun prompted(context: Context, code: Long) { prefs(context).edit().putLong("prompted", code).apply() }
    fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun finishedDownload() { mutable.update { it.copy(downloading = false) } }
    val openRequested = MutableStateFlow(false)

    fun download(context: Context, info: AppUpdateInfo) {
        if (mutable.value.downloading) return
        val asset = info.compatible(Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT) ?: return
        mutable.update { it.copy(downloading = true, progress = 0, ready = null, message = "Descargando actualización…") }
        val data = workDataOf("code" to info.code, "name" to info.name, "url" to asset.url,
            "sha256" to asset.sha256, "size" to asset.size, "abi" to asset.abi)
        val work = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>().setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("pulso-apk-download", ExistingWorkPolicy.KEEP, work)
    }

    fun progress(context: Context, percent: Int): ForegroundInfo {
        mutable.update { it.copy(downloading = true, progress = percent) }
        val notice = notification(context, "Actualización de PULSO", "Descargando: $percent %")
            .setProgress(100, percent, false).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).build()
        return ForegroundInfo(DOWNLOAD, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    suspend fun fetchApk(context: Context, data: Data, onProgress: suspend (Int) -> Unit) {
        val code = data.getLong("code", 0)
        val url = data.getString("url").orEmpty()
        val expectedSize = data.getLong("size", 0)
        val sha = data.getString("sha256").orEmpty()
        require(code > installedCode(context) && AppUpdateInfo.trustedAsset(url, BuildConfig.UPDATE_REPOSITORY))
        require(expectedSize in 1..AppUpdateInfo.MAX_APK_BYTES && sha.matches(Regex("[0-9a-f]{64}")))
        val dir = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val part = File(dir, "update-$code.part.apk")
        val apk = File(dir, "update-$code.apk")
        try {
            val connection = connection(url)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var previousPercent = -1
                connection.inputStream.use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= expectedSize)
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                        val percent = (100 * total / expectedSize).toInt()
                        if (percent != previousPercent) { onProgress(percent); previousPercent = percent }
                    }
                } }
                require(total == expectedSize && digest.digest().joinToString("") { "%02x".format(it) } == sha)
            } finally { connection.disconnect() }
            verifyApk(context, part, code)
            if (apk.exists()) check(apk.delete())
            check(part.renameTo(apk))
            mutable.update { it.copy(ready = apk.absolutePath, message = "Descarga verificada. Pulsa Instalar.") }
        } finally {
            part.delete()
            mutable.update { it.copy(downloading = false) }
        }
    }

    private fun verifyApk(context: Context, file: File, code: Long) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("APK inválida")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive.packageName == context.packageName && archive.longVersionCode == code && code > installed.longVersionCode)
        fun signatures(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        require(signatures(archive).isNotEmpty() && signatures(archive) == signatures(installed))
    }

    fun install(context: Context) {
        val path = mutable.value.ready ?: return
        try {
            val file = File(path)
            val info = mutable.value.info ?: return
            verifyApk(context, file, info.code)
            if (!context.packageManager.canRequestPackageInstalls()) {
                message("Permite instalar desde PULSO y vuelve a pulsar Instalar.")
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.playlistfiles", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { message("No se pudo abrir la instalación. Vuelve a descargar la actualización.") }
    }

    private fun connection(initial: String): HttpURLConnection {
        var url = initial
        repeat(6) {
            val uri = URI(url)
            require(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443))
            require(uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "Pulso-Android-Updater")
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank())
                url = uri.resolve(location).toString()
            } else {
                if (status != 200) { connection.disconnect(); error("HTTP $status") }
                return connection
            }
        }
        error("Demasiadas redirecciones")
    }
}

class AppUpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (AppUpdates.check(applicationContext, manual = true, notify = true)) Result.success()
        else if (runAttemptCount < 2) Result.retry() else Result.failure()
}

class AppUpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        setForeground(AppUpdates.progress(applicationContext, 0))
        withContext(Dispatchers.IO) {
            AppUpdates.fetchApk(applicationContext, inputData) { setForeground(AppUpdates.progress(applicationContext, it)) }
        }
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        AppUpdates.message("No se pudo descargar o verificar la APK. Reintenta con conexión y espacio disponible.")
        Result.failure()
    } finally { AppUpdates.finishedDownload() }
}
