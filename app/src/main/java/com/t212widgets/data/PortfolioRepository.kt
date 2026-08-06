package com.t212widgets.data

import android.content.Context
import com.t212widgets.api.ApiError
import com.t212widgets.api.ApiResult
import com.t212widgets.api.RateLimiter
import com.t212widgets.api.T212Client
import com.t212widgets.core.SecureStore
import com.t212widgets.core.accountCurrency
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for portfolio data.
 *
 * Three things this deliberately does:
 *
 *  1. **Single-flight.** Ten widgets waking on the same alarm produce one pair of HTTP round
 *     trips, not ten. Concurrent callers await the in-flight refresh instead of starting
 *     their own.
 *  2. **Per-endpoint pacing.** `/equity/positions` allows one request per second and
 *     `/equity/account/summary` one per five, so they are paced independently by
 *     [RateLimiter]. Holding both to a single shared floor is what made the value move in
 *     visible steps instead of drifting: prices could only ever be as fresh as the slowest
 *     endpoint. Positions can now be polled on their own at 1 Hz.
 *  3. **Stale-while-error.** A failed refresh never destroys the last good snapshot; the
 *     error rides along on it so widgets can show "last updated 4m ago" instead of blanking.
 */
object PortfolioRepository {

    private const val SNAPSHOT_FILE = "snapshot.json"

    /**
     * How long a forced refresh will sit waiting for the rate limiter before reporting back
     * instead. Comfortably inside [com.t212widgets.refresh.RefreshReceiver]'s deadline, so a
     * wait always ends in either fresh data or a message — never a silently killed request.
     */
    private const val FORCED_WAIT_BUDGET_SEC = 8

    private val mutex = Mutex()
    private val cached = AtomicReference<Snapshot?>(null)

    @Volatile private var backoffUntilMs = 0L
    @Volatile private var consecutiveFailures = 0

    /** Last snapshot, from memory or disk. Never performs network I/O. */
    fun cachedSnapshot(context: Context): Snapshot? {
        cached.get()?.let { return it }
        val file = File(context.applicationContext.filesDir, SNAPSHOT_FILE)
        if (!file.exists()) return null
        val loaded = runCatching { file.readText() }.getOrNull()?.let(Snapshot::fromJson)
        if (loaded != null) cached.compareAndSet(null, loaded)
        return loaded
    }

    /** True when a positions poll would actually reach the network right now. */
    fun canRefreshNow(): Boolean =
        System.currentTimeMillis() >= backoffUntilMs && RateLimiter.isReady(T212Client.PATH_POSITIONS)

    /**
     * Fetches the account summary and open positions in parallel, then stores the merged
     * snapshot.
     *
     * @param force ignore the local minimum-spacing floor (used by the manual refresh
     *   button). The 429 backoff is always honoured, forced or not.
     */
    suspend fun refresh(context: Context, force: Boolean = false): Snapshot = mutex.withLock {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val existing = cachedSnapshot(app)

        if (!SecureStore.hasApiKey(app)) {
            return@withLock store(
                app,
                (existing ?: empty()).copy(
                    error = ApiError.NoKey.message,
                    needsAttention = true,
                ),
            )
        }
        // A forced refresh is the user pressing a button. Swallowing it because a previous
        // failure armed a backoff is how the app came to look completely dead: tapping the
        // widget or "Refresh now" made no request at all and produced no feedback. An
        // explicit instruction drops *our* penalty and goes to the network.
        //
        // What it must not drop is the server's. Trading 212's budget is per account and its
        // 429 tells us when the window reopens; forcing through that earns another 429 and
        // extends the block, so pressing the button repeatedly would keep the app throttled
        // rather than fix it. When the wait is short the request below simply waits it out;
        // when it is long the user is told how long, which is at least an honest answer
        // instead of a button that appears to do nothing.
        if (force) {
            backoffUntilMs = 0
            consecutiveFailures = 0
            val waitSec = RateLimiter.waitSeconds(T212Client.PATH_POSITIONS)
            if (waitSec > FORCED_WAIT_BUDGET_SEC) {
                return@withLock store(
                    app,
                    (existing ?: empty()).copy(
                        error = "Rate limited — next update in ${waitSec}s",
                        needsAttention = false,
                    ),
                )
            }
        } else if (now < backoffUntilMs) {
            return@withLock existing ?: empty()
        }

        val client = T212Client(app)

        // Each endpoint waits only for its own budget. A forced refresh still waits — the
        // limit is per account and blowing through it just earns a 429 — but the summary
        // being on a 5s leash no longer holds the 1s positions poll back.
        val (summaryResult, positionsResult) = coroutineScope {
            val summary = async {
                RateLimiter.acquire(T212Client.PATH_SUMMARY)
                client.accountSummary()
            }
            val positions = async {
                RateLimiter.acquire(T212Client.PATH_POSITIONS)
                client.positions()
            }
            summary.await() to positions.await()
        }

        val firstError = listOfNotNull(
            (summaryResult as? ApiResult.Err)?.error,
            (positionsResult as? ApiResult.Err)?.error,
        ).firstOrNull()

        if (firstError != null) {
            noteFailure(firstError)
            // Partial success still beats nothing: keep whichever half came back.
            val merged = (existing ?: empty()).copy(
                summary = (summaryResult as? ApiResult.Ok)?.value ?: existing?.summary,
                positions = (positionsResult as? ApiResult.Ok)?.value
                    ?: existing?.positions.orEmpty(),
                error = firstError.message,
                needsAttention = firstError.needsUserAction(),
            )
            return@withLock store(app, merged)
        }

        consecutiveFailures = 0
        backoffUntilMs = 0

        val summary = (summaryResult as ApiResult.Ok).value
        val positions = (positionsResult as ApiResult.Ok).value

        // The summary carries the account currency, so nothing extra has to be fetched for
        // formatting; cache it so the UI can format before the first refresh completes.
        val currency = summary.currency.ifEmpty { app.accountCurrency }
        if (currency.isNotEmpty()) app.accountCurrency = currency

        val snapshot = Snapshot(
            fetchedAtMs = System.currentTimeMillis(),
            currency = currency,
            summary = summary,
            positions = positions,
            error = null,
            needsAttention = false,
        )
        DailyBaseline.observe(app, snapshot)
        recordHistory(app, snapshot)
        store(app, snapshot)
    }

    /**
     * Polls **only** open positions, at the 1 Hz that endpoint allows.
     *
     * This is what makes the value move smoothly rather than in jumps. Positions carry both
     * `currentPrice` and `walletImpact.currentValue`, so a full live portfolio value can be
     * recomputed every second without touching the 5-second account summary; the cash side
     * of the account barely changes tick to tick, so re-reading it that often buys nothing.
     *
     * Every successful poll is recorded into [ValueHistory], which is what the chart draws.
     */
    suspend fun refreshPositions(context: Context): Snapshot = mutex.withLock {
        val app = context.applicationContext
        val existing = cachedSnapshot(app) ?: empty()

        // Returns immediately while a backoff is armed. Callers must not busy-loop on that;
        // [canRefreshNow] tells them whether a call would actually reach the network.
        if (!SecureStore.hasApiKey(app)) return@withLock existing
        if (System.currentTimeMillis() < backoffUntilMs) return@withLock existing

        RateLimiter.acquire(T212Client.PATH_POSITIONS)
        when (val result = T212Client(app).positions()) {
            is ApiResult.Err -> {
                noteFailure(result.error)
                store(
                    app,
                    existing.copy(
                        error = result.error.message,
                        needsAttention = result.error.needsUserAction(),
                    ),
                )
            }
            is ApiResult.Ok -> {
                consecutiveFailures = 0
                backoffUntilMs = 0
                val updated = existing.copy(
                    fetchedAtMs = System.currentTimeMillis(),
                    positions = result.value,
                    error = null,
                    needsAttention = false,
                )
                recordHistory(app, updated)
                store(app, updated)
            }
        }
    }

    /**
     * Appends the live account value and each holding's price to the chart history.
     *
     * The account value used is [Snapshot.liveTotalValue] rather than the summary's
     * `totalValue`, so the series tracks the 1 Hz positions data instead of stepping once
     * every five seconds when the summary happens to refresh.
     */
    private fun recordHistory(context: Context, snapshot: Snapshot) {
        val at = snapshot.fetchedAtMs
        snapshot.liveTotalValue?.let { ValueHistory.record(context, ValueHistory.SERIES_ACCOUNT, it, at) }
        snapshot.positions.forEach { p ->
            ValueHistory.record(context, ValueHistory.seriesForTicker(p.ticker), p.currentPrice, at)
        }
    }

    /** Drops every cached artefact. Used when the credentials or environment change. */
    fun invalidate(context: Context) {
        val app = context.applicationContext
        cached.set(null)
        backoffUntilMs = 0
        RateLimiter.reset()
        consecutiveFailures = 0
        runCatching { File(app.filesDir, SNAPSHOT_FILE).delete() }
        ValueHistory.clear(app)
        DailyBaseline.clear(app)
        app.accountCurrency = ""
    }

    // ---------------------------------------------------------------- internals

    private fun empty() = Snapshot(0L, "", null, emptyList())

    private fun store(context: Context, snapshot: Snapshot): Snapshot {
        cached.set(snapshot)
        runCatching {
            File(context.filesDir, SNAPSHOT_FILE).writeText(snapshot.toJson())
        }
        return snapshot
    }

    /** True when the user has to change something before refreshes can ever succeed. */
    private fun ApiError.needsUserAction(): Boolean =
        this is ApiError.NoKey || this is ApiError.Unauthorised || this is ApiError.Forbidden

    private fun noteFailure(error: ApiError) {
        consecutiveFailures++
        val now = System.currentTimeMillis()
        backoffUntilMs = when (error) {
            is ApiError.RateLimited -> now + (error.retryAfterSec?.times(1000L) ?: 60_000L)
            // Rejected credentials will not fix themselves, so wait a while.
            is ApiError.Unauthorised, is ApiError.Forbidden -> now + 300_000L
            // Everything else is probably a blip — a slow handshake, a lost packet, a
            // moment between cells. 5s, 10s, 20s, capped at a minute: long enough to stop
            // hammering, short enough that a widget recovers on its own rather than sitting
            // dead until the user notices.
            else -> now + (5_000L shl (consecutiveFailures - 1).coerceAtMost(4))
                .coerceAtMost(60_000L)
        }
    }
}
