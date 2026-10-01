package app.pulso.music

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Includes our QR camera activity and avoids reconnects during rotations. */
internal class SocialLifecycle : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private val handler = Handler(Looper.getMainLooper())
    private val pause = Runnable { if (started == 0) SocialEngine.visibility(false) }
    override fun onActivityStarted(activity: Activity) { started++; handler.removeCallbacks(pause); SocialEngine.visibility(true) }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0); if (started == 0) handler.postDelayed(pause, 2000) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
