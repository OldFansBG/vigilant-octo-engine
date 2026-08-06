package com.t212widgets.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Documented periods, in milliseconds, used until the server tells us otherwise. */
    private val documentedPeriodMs = mapOf(
        "/api/v0/equity/positions" to 1_000L,
        "/api/v0/equity/account/summary" to 5_000L,
    )

    private const val DEFAULT_PERIOD_MS = 5_000L

    /**
     * Requests are paced deliberately slower than the strict minimum.
     *
     * Trading 212's limiter is a *fixed window*, not a minimum gap: "1 request per 5 seconds"
     * means one request per five-second bucket, and the documentation is explicit that a
     * whole budget may be spent in a burst at the start of a window. Pacing at exactly the
     * documented figure therefore fails about as often as it works — two requests 5.0s apart
     * land in the same bucket whenever the first one arrives late in a window, and the second
     * comes back 429. The margin here is what keeps consecutive requests in consecutive
     * buckets.
     *
     * The limits are also per *account* rather than per key, so this app's requests share a
     * budget with anything else the user runs against the same account.
     */
    private const val SAFETY_FACTOR = 1.25

    /**
     * Hard ceilings on anything derived from server headers.
     *
     * `acquire` suspends until a path is allowed, so a nonsensical header — a reset stamped
     * in milliseconds instead of seconds, a clock skew, a stray large number — could
     * otherwise park a refresh for hours with no visible failure at all: no request, no
     * error, a widget frozen on its last value. Clamping keeps a bad header a nuisance
     * rather than a hang.
     */
    private const val MAX_SPACING_MS = 60_000L
    private const val MAX_BLOCK_MS = 120_000L

    private data class Budget(
        val spacingMs: Long,
        /** The window length the server reports, used to size a blind 429 backoff. */
        val periodMs: Long,
        val lastRequestAtMs: Long = 0L,
        val blockedUntilMs: Long = 0L,
    )

    private val budgets = ConcurrentHashMap<String, Budget>()

    /**
     * One gate per endpoint, so the check-then-record in [acquire] cannot interleave.
     *
     * Without this, two callers can both see "free right now" before either records its
     * attempt and fire simultaneously — which spends a 1-request budget twice and earns a
     * 429. That is not hypothetical: the live screen polls positions while the alarm chain
     * and the unlock trigger can fire on the same endpoint.
     */
    private val gates = ConcurrentHashMap<String, Mutex>()

    private fun budget(path: String): Budget {
        budgets[path]?.let { return it }
        val period = documentedPeriodMs[path] ?: DEFAULT_PERIOD_MS
        return Budget(spacingMs = (period * SAFETY_FACTOR).toLong(), periodMs = period)
    }

    /** Milliseconds until [path] may be called again; 0 when it is free right now. */
    fun waitMs(path: String, now: Long = System.currentTimeMillis()): Long {
        val b = budget(path)
        val untilSpacing = (b.lastRequestAtMs + b.spacingMs) - now
        val untilUnblocked = b.blockedUntilMs - now
        return maxOf(untilSpacing, untilUnblocked, 0L)
    }

    /**
     * Suspends until [path] is allowed, then records the attempt.
     *
     * Waiting and recording happen under the endpoint's own gate, so concurrent callers queue
     * behind each other instead of all deciding at once that the endpoint is free.
     */
    suspend fun acquire(path: String) {
        gates.computeIfAbsent(path) { Mutex() }.withLock {
            while (true) {
                val wait = waitMs(path)
                if (wait <= 0) break
                delay(wait)
            }
            budgets[path] = budget(path).copy(lastRequestAtMs = System.currentTimeMillis())
        }
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

        val periodMs = periodSec * 1000L
        val evenSpacing = (periodMs.toDouble() / limit * SAFETY_FACTOR).toLong().coerceAtLeast(200L)

        val now = System.currentTimeMillis()
        // `x-ratelimit-used` is the other half of the same fact, and is sometimes the only
        // one of the pair present.
        val remaining = conn.headerInt("x-ratelimit-remaining")
            ?: conn.headerInt("x-ratelimit-used")?.let { limit - it }
        val resetAtMs = conn.headerLong("x-ratelimit-reset")?.let { normaliseResetToMs(it, now) }

        val spacing = if (remaining != null && remaining > 0 && resetAtMs != null) {
            // Spread whatever is left evenly over the rest of the window.
            val msToReset = resetAtMs - now
            if (msToReset > 0) maxOf(evenSpacing, msToReset / remaining) else evenSpacing
        } else {
            evenSpacing
        }

        val blockedUntil = if (remaining != null && remaining <= 0 && resetAtMs != null) {
            // Budget spent: wait for the window to roll over.
            resetAtMs
        } else {
            0L
        }

        val b = budget(path)
        budgets[path] = b.copy(
            spacingMs = spacing.coerceIn(200L, MAX_SPACING_MS),
            periodMs = periodMs.coerceIn(1_000L, MAX_BLOCK_MS),
            blockedUntilMs = maxOf(b.blockedUntilMs, blockedUntil).coerceAtMost(now + MAX_BLOCK_MS),
        )
    }

    /**
     * A 429 came back: wait out the window.
     *
     * [observe] has already run on the same response, so if the server sent
     * `x-ratelimit-reset` the block is set from it and this only ever extends that — never
     * shortens it, which would just walk straight into a second 429. `Retry-After` is
     * honoured when present; the documentation does not promise it, so the fallback is a
     * full period rather than a guess derived from the spacing.
     */
    fun onRateLimited(path: String, retryAfterSec: Int?) {
        val b = budget(path)
        val waitMs = ((retryAfterSec?.times(1000L)) ?: b.periodMs).coerceAtMost(MAX_BLOCK_MS)
        budgets[path] = b.copy(
            blockedUntilMs = maxOf(b.blockedUntilMs, System.currentTimeMillis() + waitMs),
        )
    }

    /**
     * `x-ratelimit-reset` is documented as a Unix timestamp in seconds, but a value in
     * milliseconds — or a duration rather than an absolute time — would otherwise be read as
     * a date far in the future and stall the endpoint. Anything that does not land within a
     * plausible window of now is treated as a relative number of seconds instead.
     */
    private fun normaliseResetToMs(raw: Long, now: Long): Long {
        val asSeconds = raw * 1000L
        if (asSeconds in (now - 60_000L)..(now + MAX_BLOCK_MS)) return asSeconds
        if (raw in (now - 60_000L)..(now + MAX_BLOCK_MS)) return raw // already milliseconds
        if (raw in 0..600) return now + raw * 1000L // a duration, in seconds
        return now
    }

    /** Seconds until [path] is free, rounded up. 0 when it is free right now. */
    fun waitSeconds(path: String): Int = ((waitMs(path) + 999L) / 1000L).toInt()

    /** Forgets all pacing state. Used when credentials change. */
    fun reset() {
        budgets.clear()
        gates.clear()
    }

    private fun HttpURLConnection.headerInt(name: String): Int? =
        getHeaderField(name)?.trim()?.toIntOrNull()

    private fun HttpURLConnection.headerLong(name: String): Long? =
        getHeaderField(name)?.trim()?.toLongOrNull()
}
