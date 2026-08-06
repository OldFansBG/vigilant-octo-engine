package com.t212widgets.data

import android.content.Context
import com.t212widgets.api.ApiError
import com.t212widgets.api.ApiResult
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
 *  2. **Rate-limit respect.** Trading 212 caps `/equity/account/summary` at 1 request per 5s
 *     and `/equity/positions` at 1 per 1s, and answers 429 past that. A minimum spacing is
 *     enforced locally, and a 429 arms an exponential backoff so a rate-limited app does not
 *     spend the next hour hammering a closed door.
 *  3. **Stale-while-error.** A failed refresh never destroys the last good snapshot; the
 *     error rides along on it so widgets can show "last updated 4m ago" instead of blanking.
 */
object PortfolioRepository {

    private const val SNAPSHOT_FILE = "snapshot.json"

    /** Local floor between network refreshes, above the tightest documented endpoint cap. */
    private const val MIN_SPACING_MS = 6_000L

    private val mutex = Mutex()
    private val cached = AtomicReference<Snapshot?>(null)

    @Volatile private var lastFetchAtMs = 0L
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

    /** True when a network refresh would actually happen right now. */
    fun canRefreshNow(): Boolean {
        val now = System.currentTimeMillis()
        return now >= backoffUntilMs && now - lastFetchAtMs >= MIN_SPACING_MS
    }

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
        if (now < backoffUntilMs) return@withLock existing ?: empty()
        if (!force && now - lastFetchAtMs < MIN_SPACING_MS && existing != null) return@withLock existing

        lastFetchAtMs = now
        val client = T212Client(app)

        val (summaryResult, positionsResult) = coroutineScope {
            val summary = async { client.accountSummary() }
            val positions = async { client.positions() }
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
        store(app, snapshot)
    }

    /** Drops every cached artefact. Used when the credentials or environment change. */
    fun invalidate(context: Context) {
        val app = context.applicationContext
        cached.set(null)
        lastFetchAtMs = 0
        backoffUntilMs = 0
        consecutiveFailures = 0
        runCatching { File(app.filesDir, SNAPSHOT_FILE).delete() }
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
            is ApiError.Unauthorised, is ApiError.Forbidden -> now + 300_000L
            // 10s, 20s, 40s … capped at 5 minutes.
            else -> now + (10_000L shl (consecutiveFailures - 1).coerceAtMost(5))
                .coerceAtMost(300_000L)
        }
    }
}
