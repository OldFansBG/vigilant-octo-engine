package com.t212widgets.api

import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-endpoint rate limiting, paced from the server's own headers.
 *
 * Trading 212 limits each endpoint separately and *per account*, so one global floor across
 * every call is both wrong and needlessly slow: it drags the fastest endpoint down to the
 * speed of the slowest. `/equity/positions` allows one request per second and carries live
 * prices, while `/equity/account/summary` allows one per five — throttling both to the
 * slower figure is what makes a portfolio value lurch in big steps instead of drifting.
 *
 * Every authenticated response carries the current budget:
 *
 * ```
 * x-ratelimit-limit      requests allowed in the period
 * x-ratelimit-period     length of the period, in seconds
 * x-ratelimit-remaining  requests left
 * x-ratelimit-reset      unix time when the budget resets
 * ```
 *
 * Those are treated as the source of truth, with the documented figures as the starting
 * guess, so the app paces itself correctly even if Trading 212 retunes a limit.
 */
object RateLimiter {

    /** Documented limits, used until the server tells us otherwise. */
    private val documentedSpacingMs = mapOf(
        "/api/v0/equity/positions" to 1_000L,
        "/api/v0/equity/account/summary" to 5_000L,
    )

    private const val DEFAULT_SPACING_MS = 5_000L

    /**
     * Requests are paced a touch slower than the strict minimum. The limiter is per account,
     * so a second device — or the odd retry — should not be enough to tip us into a 429.
     */
    private const val SAFETY_FACTOR = 1.15

    private data class Budget(
        val spacingMs: Long,
        val lastRequestAtMs: Long = 0L,
        val blockedUntilMs: Long = 0L,
    )

    private val budgets = ConcurrentHashMap<String, Budget>()

    private fun budget(path: String): Budget =
        budgets[path] ?: Budget(spacingMs = documentedSpacingMs[path] ?: DEFAULT_SPACING_MS)

    /** Milliseconds until [path] may be called again; 0 when it is free right now. */
    fun waitMs(path: String, now: Long = System.currentTimeMillis()): Long {
        val b = budget(path)
        val untilSpacing = (b.lastRequestAtMs + b.spacingMs) - now
        val untilUnblocked = b.blockedUntilMs - now
        return maxOf(untilSpacing, untilUnblocked, 0L)
    }

    /** Suspends until [path] is allowed, then records the attempt. */
    suspend fun acquire(path: String) {
        while (true) {
            val wait = waitMs(path)
            if (wait <= 0) break
            delay(wait)
        }
        val b = budget(path)
        budgets[path] = b.copy(lastRequestAtMs = System.currentTimeMillis())
    }

    /** True when [path] could be called right now without waiting. */
    fun isReady(path: String): Boolean = waitMs(path) <= 0

    /**
     * Re-paces [path] from the response headers.
     *
     * `limit` requests per `period` seconds means one request every `period / limit`
     * seconds. When the remaining budget runs low the pacing stretches to reach the reset
     * without a 429, rather than sprinting and then stalling.
     */
    fun observe(path: String, conn: HttpURLConnection) {
        val limit = conn.headerInt("x-ratelimit-limit") ?: return
        val periodSec = conn.headerInt("x-ratelimit-period") ?: return
        if (limit <= 0 || periodSec <= 0) return

        val evenSpacing = (periodSec * 1000.0 / limit * SAFETY_FACTOR).toLong().coerceAtLeast(200L)

        val remaining = conn.headerInt("x-ratelimit-remaining")
        val resetAtSec = conn.headerLong("x-ratelimit-reset")
        val spacing = if (remaining != null && remaining <= 0 && resetAtSec != null) {
            // Budget exhausted: hold off entirely until the window resets.
            evenSpacing
        } else if (remaining != null && resetAtSec != null && remaining > 0) {
            val msToReset = resetAtSec * 1000L - System.currentTimeMillis()
            if (msToReset > 0) maxOf(evenSpacing, msToReset / remaining) else evenSpacing
        } else {
            evenSpacing
        }

        val blockedUntil = if (remaining != null && remaining <= 0 && resetAtSec != null) {
            resetAtSec * 1000L
        } else {
            0L
        }

        val b = budget(path)
        budgets[path] = b.copy(
            spacingMs = spacing,
            blockedUntilMs = maxOf(b.blockedUntilMs, blockedUntil),
        )
    }

    /** A 429 came back: respect `Retry-After`, or back off for one period. */
    fun onRateLimited(path: String, retryAfterSec: Int?) {
        val b = budget(path)
        val waitMs = (retryAfterSec?.times(1000L)) ?: (b.spacingMs * 4)
        budgets[path] = b.copy(blockedUntilMs = System.currentTimeMillis() + waitMs)
    }

    /** Forgets all pacing state. Used when credentials change. */
    fun reset() = budgets.clear()

    private fun HttpURLConnection.headerInt(name: String): Int? =
        getHeaderField(name)?.trim()?.toIntOrNull()

    private fun HttpURLConnection.headerLong(name: String): Long? =
        getHeaderField(name)?.trim()?.toLongOrNull()
}
