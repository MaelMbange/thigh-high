package io.github.maelmbange.thighhigh.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.maelmbange.thighhigh.MainActivity
import io.github.maelmbange.thighhigh.R

/**
 * Keeps the app alive while it broadcasts or listens, even with another app
 * on screen: without a foreground service Android freezes a background app
 * within seconds (its network threads stop and the connection is lost).
 * The music player's own service only runs while our music plays, which is
 * not the case while listening to someone else, or broadcasting paused.
 *
 * It also holds the Wi-Fi awake (and the CPU), so the stream doesn't
 * stutter with the screen off. Started and stopped by [CastController].
 */
class CastService : Service() {
    private var wifi: WifiManager.WifiLock? = null
    private var cpu: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopAsked?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.cast)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(this, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        running = true
        hold()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun hold() {
        if (wifi == null) {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "thigh-high:cast").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (cpu == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "thigh-high:cast").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onDestroy() {
        running = false
        wifi?.takeIf { it.isHeld }?.release()
        cpu?.takeIf { it.isHeld }?.release()
        wifi = null
        cpu = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "io.github.maelmbange.thighhigh.cast.STOP"
        private const val EXTRA_TEXT = "text"

        /** Called by the notification's "Arrêter" button. */
        @Volatile var onStopAsked: (() -> Unit)? = null

        @Volatile private var running = false

        /**
         * Starts the service, or just updates its notification's [text]: once
         * running it is not started again (not allowed from the background).
         */
        fun show(context: Context, text: String) {
            if (running) {
                runCatching {
                    context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, text))
                }
                return
            }
            val intent = Intent(context, CastService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun hide(context: Context) {
            running = false
            context.stopService(Intent(context, CastService::class.java))
        }

        private fun notification(context: Context, text: String): Notification = with(context) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.cast), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                context, 1,
                Intent(context, CastService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.cast))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .addAction(0, getString(R.string.cast_stop), stop)
                .build()
        }
    }
}
