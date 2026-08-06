package com.t212widgets.refresh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.t212widgets.core.refreshOnUnlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single receiver for every event that should move the data forward.
 *
 * `SCREEN_ON` / `USER_PRESENT` cannot be declared in a manifest since Android 8, so those
 * two are registered at runtime by [com.t212widgets.T212Application]; boot, package
 * replacement and the scheduler's own alarm tick arrive through the manifest entry.
 */
class RefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val action = intent?.action

        when {
            RefreshScheduler.isTick(intent) -> tick(app, screenOn = ScreenState.isScreenOn(app))

            action == Intent.ACTION_SCREEN_ON -> {
                ScreenState.set(true)
                tick(app, screenOn = true, force = true)
            }

            action == Intent.ACTION_SCREEN_OFF -> {
                ScreenState.set(false)
                // Drop straight to the idle cadence rather than waiting for the pending
                // fast tick to fire and do nothing.
                RefreshScheduler.scheduleNextTick(app, screenOn = false)
            }

            action == Intent.ACTION_USER_PRESENT -> {
                ScreenState.set(true)
                if (app.refreshOnUnlock) tick(app, screenOn = true, force = true)
            }

            action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                RefreshScheduler.reconcile(app)
            }
        }
    }

    /**
     * Refresh, redraw, then book the next tick.
     *
     * `goAsync` buys roughly ten seconds of broadcast lifetime, which comfortably covers two
     * parallel HTTP calls. The next alarm is booked in a `finally` so a slow or failing
     * network can never break the chain and leave the widgets frozen forever.
     */
    private fun tick(context: Context, screenOn: Boolean, force: Boolean = false) {
        // Book the next tick *first*. Doing it in a `finally` looked equivalent but was not:
        // if the system kills the process mid-refresh, the finally never runs and the chain
        // dies until the 15-minute fallback notices. Scheduling up front makes the chain
        // survive anything that can happen to this process.
        RefreshScheduler.scheduleNextTick(context, screenOn = screenOn)

        if (!RefreshScheduler.shouldPollNow(context, screenOn)) return

        val pending = goAsync()
        scope.launch {
            try {
                // Comfortably longer than the client's own connect+read timeouts, so a slow
                // request fails as a timeout with a truthful message rather than being
                // cancelled here and mislabelled.
                withTimeoutOrNull(REFRESH_DEADLINE_MS) {
                    RefreshScheduler.refreshAndRedraw(context, force = force)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Backstop only — the HTTP client's own timeouts should always fire first. */
        const val REFRESH_DEADLINE_MS = 30_000L
    }
}

/**
 * Last known screen state.
 *
 * `PowerManager.isInteractive` is authoritative but the value is also cached here because a
 * tick that arrives immediately after `SCREEN_OFF` should see the new state without another
 * system call.
 */
object ScreenState {
    @Volatile private var cached: Boolean? = null

    fun set(on: Boolean) { cached = on }

    fun isScreenOn(context: Context): Boolean {
        cached?.let { return it }
        val power = context.getSystemService(android.os.PowerManager::class.java)
        return (power?.isInteractive ?: true).also { cached = it }
    }
}
