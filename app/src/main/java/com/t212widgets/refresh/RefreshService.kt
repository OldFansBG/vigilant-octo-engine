package com.t212widgets.refresh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.t212widgets.R
import com.t212widgets.core.Format
import com.t212widgets.core.liveMode
import com.t212widgets.core.refreshIntervalSec
import com.t212widgets.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional "live mode": a foreground service that polls on a plain loop.
 *
 * Alarms are good enough for almost everyone, but they are still subject to the OS deciding
 * when to deliver them. A foreground service is the only way Android lets an app poll on a
 * genuinely fixed cadence, and it costs a permanent notification and battery — so this is
 * opt-in, clearly labelled, and off by default.
 *
 * Android 15 caps `dataSync` foreground services at roughly six hours per day. Rather than
 * being force-stopped, the service handles [onTimeout], turns the setting back off and hands
 * over to the alarm chain, which keeps working indefinitely.
 */
class RefreshService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !liveMode) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        if (job?.isActive != true) {
            job = scope.launch {
                while (isActive) {
                    if (RefreshScheduler.shouldPollNow(this@RefreshService, ScreenState.isScreenOn(this@RefreshService))) {
                        runCatching { RefreshScheduler.refreshAndRedraw(this@RefreshService) }
                    }
                    delay(refreshIntervalSec * 1000L)
                }
            }
        }
        return START_STICKY
    }

    /** Android 15+ hands the service a graceful shutdown when the daily budget runs out. */
    override fun onTimeout(startId: Int) {
        liveMode = false
        RefreshScheduler.scheduleNextTick(this, screenOn = ScreenState.isScreenOn(this))
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live updates", NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = "Shown while the widgets are polling continuously."
                        setShowBadge(false)
                    },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Widgets updating live")
            .setContentText("Every ${Format.interval(refreshIntervalSec)} · tap to change")
            .setSmallIcon(R.drawable.ic_stat_refresh)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "t212_live"
        private const val NOTIFICATION_ID = 4212
        private const val ACTION_STOP = "com.t212widgets.action.STOP_LIVE"

        fun start(context: Context) {
            val intent = Intent(context, RefreshService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, RefreshService::class.java).setAction(ACTION_STOP),
                )
            }
            runCatching { context.stopService(Intent(context, RefreshService::class.java)) }
        }
    }
}
