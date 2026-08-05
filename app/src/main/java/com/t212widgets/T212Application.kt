package com.t212widgets

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.t212widgets.refresh.RefreshReceiver
import com.t212widgets.refresh.RefreshScheduler

class T212Application : Application() {

    /**
     * `SCREEN_ON` and `SCREEN_OFF` have not been deliverable to manifest-declared receivers
     * since Android 8, so they are registered here instead. The process is kept around by
     * the alarm chain, and any gap is covered by the alarm tick itself — the runtime
     * registration is an optimisation for instant freshness, not the mechanism.
     */
    private val screenReceiver = RefreshReceiver()

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        RefreshScheduler.reconcile(this)
    }
}
