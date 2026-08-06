package com.t212widgets.refresh

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.t212widgets.core.SecureStore
import com.t212widgets.core.liveMode
import com.t212widgets.core.onlyWhenScreenOn
import com.t212widgets.core.refreshIntervalSec
import com.t212widgets.data.PortfolioRepository
import com.t212widgets.widget.placedWidgetCount
import com.t212widgets.widget.updateAllWidgets
import java.util.concurrent.TimeUnit

/**
 * The answer to "the official widget updates too slowly".
 *
 * Android caps a widget's own `updatePeriodMillis` at 30 minutes and WorkManager's shortest
 * periodic job at 15, which is why broker widgets tend to feel stale. This scheduler layers
 * three mechanisms so the number you see is fresh at the moment you actually look at it:
 *
 *  1. **A self-rescheduling alarm** at the user's chosen interval (down to 15s). Each tick
 *     refreshes and books the next one, so the cadence is not tied to the widget framework.
 *     Exact alarms are used when the OS grants them and a timing window otherwise.
 *  2. **Screen-on and unlock triggers.** The instant the display comes on, a refresh fires.
 *     By the time the home screen is drawn the figures are current — and while the phone is
 *     in a pocket nothing polls at all, which is where the battery savings come from.
 *  3. **A 15-minute WorkManager job** as a floor. If the OS defers alarms (Doze, battery
 *     saver, an aggressive OEM), this still drags the data forward periodically.
 *
 * Optional on top of that is [RefreshService], a foreground service that polls continuously
 * for users who want a live ticker and will accept the notification and battery cost.
 */
object RefreshScheduler {

    private const val ACTION_TICK = "com.t212widgets.action.TICK"
    private const val REQUEST_CODE = 4212
    private const val WORK_NAME = "t212-widget-fallback"
    private const val IMMEDIATE_WORK_NAME = "t212-widget-immediate"

    /**
     * Brings every scheduling mechanism in line with the current settings. Safe to call as
     * often as you like — it is idempotent and is the only entry point that should be used.
     */
    fun reconcile(context: Context) {
        val app = context.applicationContext
        val shouldRun = SecureStore.hasApiKey(app) && placedWidgetCount(app) > 0

        if (!shouldRun) {
            cancelAlarm(app)
            WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(app).cancelUniqueWork(IMMEDIATE_WORK_NAME)
            RefreshService.stop(app)
            return
        }

        scheduleNextTick(app, screenOn = true)
        scheduleFallbackWork(app)

        if (app.liveMode) RefreshService.start(app) else RefreshService.stop(app)
    }

    /** Idle cadence used while the screen is off, purely to keep the alarm chain alive. */
    private const val SCREEN_OFF_INTERVAL_SEC = 900

    /**
     * Books the next alarm tick. Called after every refresh to keep the chain going.
     *
     * The chain is never allowed to stop, even when polling is paused for a dark screen: a
     * broken chain would leave the widgets frozen until the 15-minute fallback job noticed.
     * While the screen is off the tick just backs off to [SCREEN_OFF_INTERVAL_SEC] and skips
     * the network call, which also keeps the process around to hear `SCREEN_ON`.
     */
    fun scheduleNextTick(context: Context, screenOn: Boolean = true) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val seconds = if (screenOn || !app.onlyWhenScreenOn) {
            app.refreshIntervalSec
        } else {
            maxOf(SCREEN_OFF_INTERVAL_SEC, app.refreshIntervalSec)
        }
        val intervalMs = seconds * 1000L
        val triggerAt = System.currentTimeMillis() + intervalMs

        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        runCatching {
            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    tickIntent(app),
                )
            } else {
                // Without the exact-alarm permission, ask for a window instead of a point in
                // time. Slightly less punctual, no permission prompt, still far tighter than
                // the 30-minute widget framework minimum.
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    (intervalMs / 4).coerceAtLeast(5_000L),
                    tickIntent(app),
                )
            }
        }.onFailure {
            // Some OEM builds throw on exact alarms even after canScheduleExactAlarms().
            runCatching {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, tickIntent(app))
            }
        }
    }

    /**
     * Queues an immediate one-off refresh that outlives the caller.
     *
     * Used by the widget builder: the configuration activity finishes the moment the user
     * taps Save, which cancels anything launched in its own scope, so the first fetch for a
     * brand-new widget has to be owned by something else.
     */
    fun refreshSoon(context: Context) {
        // Not marked expedited: below API 31 WorkManager would demand a getForegroundInfo()
        // implementation for that and crash without one. A plain one-off request starts
        // essentially straight away anyway.
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelAlarm(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(app))
    }

    /**
     * Refreshes and redraws. Returns once the widgets have the new numbers.
     *
     * @param force bypass the local minimum-spacing floor (manual refresh only).
     */
    suspend fun refreshAndRedraw(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        PortfolioRepository.refresh(app, force = force)
        updateAllWidgets(app)
    }

    /** True when polling should be running right now, given the screen-state preference. */
    fun shouldPollNow(context: Context, screenOn: Boolean): Boolean =
        !context.onlyWhenScreenOn || screenOn

    private fun scheduleFallbackWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, RefreshReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun isTick(intent: Intent?): Boolean = intent?.action == ACTION_TICK
}
