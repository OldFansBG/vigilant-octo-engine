package com.t212widgets.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.t212widgets.core.SecureStore
import com.t212widgets.widget.placedWidgetCount

/**
 * The floor under the alarm chain.
 *
 * Doze, battery saver and some OEM power managers will defer alarms; WorkManager survives
 * all of them. Fifteen minutes is the shortest period the platform allows, so this is a
 * safety net rather than the primary path — it exists so a widget can never sit stale
 * indefinitely just because an alarm was dropped.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!SecureStore.hasApiKey(context) || placedWidgetCount(context) == 0) {
            return Result.success()
        }
        return try {
            RefreshScheduler.refreshAndRedraw(context)
            // Re-arm the alarm chain in case it was broken by a process kill.
            RefreshScheduler.scheduleNextTick(context, screenOn = ScreenState.isScreenOn(context))
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
