package app.pulso.music

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class PulsoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Library.init(this)
        SocialEngine.init(this)
        registerActivityLifecycleCallbacks(SocialLifecycle())
        AppUpdates.start(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads", "Descargas de música", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
