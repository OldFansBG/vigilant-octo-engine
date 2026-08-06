package com.t212widgets.core

import android.content.Context

/** Which Trading 212 environment the stored key belongs to. */
enum class Environment(val label: String, val baseUrl: String) {
    LIVE("Live", "https://live.trading212.com"),
    DEMO("Practice (demo)", "https://demo.trading212.com"),
}

/** Constants for the non-secret preferences below. The API key lives in [SecureStore]. */
object Settings {
    internal const val PREFS = "t212_settings"

    internal const val K_ENV = "environment"
    internal const val K_INTERVAL = "refresh_interval_sec"
    internal const val K_ONLY_SCREEN_ON = "only_when_screen_on"
    internal const val K_REFRESH_ON_UNLOCK = "refresh_on_unlock"
    internal const val K_LIVE_MODE = "live_mode"
    internal const val K_REQUIRE_AUTH = "require_auth_to_reveal"
    internal const val K_ACCOUNT_CCY = "account_currency"

    /**
     * Pacing is enforced per endpoint by RateLimiter, so this is purely how often the widget
     * asks. Five seconds matches the account summary's own limit — asking faster than that
     * cannot produce a fresher summary, only wasted wake-ups.
     *
     * Alarms this tight are best-effort: Android will not deliver them to the second on
     * every device. The live view in the app is the place to watch a value move.
     */
    const val MIN_INTERVAL_SEC = 5
    const val DEFAULT_INTERVAL_SEC = 30

    val INTERVAL_CHOICES = listOf(5, 10, 15, 30, 60, 300, 900)

    fun isConfigured(context: Context): Boolean = SecureStore.hasApiKey(context)
}

private fun Context.settingsPrefs() =
    applicationContext.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)

var Context.environment: Environment
    get() = runCatching {
        Environment.valueOf(settingsPrefs().getString(Settings.K_ENV, null) ?: Environment.LIVE.name)
    }.getOrDefault(Environment.LIVE)
    set(value) { settingsPrefs().edit().putString(Settings.K_ENV, value.name).apply() }

/** Seconds between background refreshes. Clamped to [Settings.MIN_INTERVAL_SEC]. */
var Context.refreshIntervalSec: Int
    get() = settingsPrefs().getInt(Settings.K_INTERVAL, Settings.DEFAULT_INTERVAL_SEC)
        .coerceAtLeast(Settings.MIN_INTERVAL_SEC)
    set(value) {
        settingsPrefs().edit()
            .putInt(Settings.K_INTERVAL, value.coerceAtLeast(Settings.MIN_INTERVAL_SEC)).apply()
    }

/** Pause polling while the screen is off. On by default — saves battery and API quota. */
var Context.onlyWhenScreenOn: Boolean
    get() = settingsPrefs().getBoolean(Settings.K_ONLY_SCREEN_ON, true)
    set(value) { settingsPrefs().edit().putBoolean(Settings.K_ONLY_SCREEN_ON, value).apply() }

/** Fire an immediate refresh the moment the device is unlocked. */
var Context.refreshOnUnlock: Boolean
    get() = settingsPrefs().getBoolean(Settings.K_REFRESH_ON_UNLOCK, true)
    set(value) { settingsPrefs().edit().putBoolean(Settings.K_REFRESH_ON_UNLOCK, value).apply() }

/** Foreground-service polling, for when alarm-based refresh is not tight enough. */
var Context.liveMode: Boolean
    get() = settingsPrefs().getBoolean(Settings.K_LIVE_MODE, false)
    set(value) { settingsPrefs().edit().putBoolean(Settings.K_LIVE_MODE, value).apply() }

/** Require device/biometric auth before the stored key can be replaced or removed. */
var Context.requireAuthToReveal: Boolean
    get() = settingsPrefs().getBoolean(Settings.K_REQUIRE_AUTH, false)
    set(value) { settingsPrefs().edit().putBoolean(Settings.K_REQUIRE_AUTH, value).apply() }

/** Cached from `/equity/account/info` so formatting works before the first fetch. */
var Context.accountCurrency: String
    get() = settingsPrefs().getString(Settings.K_ACCOUNT_CCY, "") ?: ""
    set(value) { settingsPrefs().edit().putString(Settings.K_ACCOUNT_CCY, value).apply() }
