package app.pulso.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

internal class SocialNotifications(private val context: Context) {
    init { context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel("social_messages", "Mensajes", NotificationManager.IMPORTANCE_DEFAULT)) }
    fun cancel(key: String) { NotificationManagerCompat.from(context).cancel("social", key.hashCode()) }
    @android.annotation.SuppressLint("MissingPermission")
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun received(key: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).putExtra("socialContact", key)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "social_messages")
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("PULSO · Mensajes")
            .setContentText("Tienes un mensaje nuevo").setContentIntent(pending).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_MESSAGE).build()
        runCatching { manager.notify("social", key.hashCode(), notification) }
    }
}
