package com.t212widgets.data

import android.content.Context
import com.t212widgets.api.ApiError
import com.t212widgets.api.ApiResult
import com.t212widgets.api.T212Client
import com.t212widgets.core.SecureStore
import com.t212widgets.core.accountCurrency
import com.t212widgets.core.fetchInstrumentNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for portfolio data.
 *
 * Three things this deliberately does:
 *
 *  1. **Single-flight.** Ten widgets waking on the same alarm produce one HTTP round trip,
 *     not ten. Concurrent callers await the in-flight refresh instead of starting their own.
 *  2. **Rate-limit respect.** Trading 212 caps `/equity/portfolio` at 1 request per 5s and
 *     `/account/cash` at 1 per 2s, and answers 429 past that. A minimum spacing is enforced
 *     locally, and a 429 arms an exponential backoff so a rate-limited app does not spend
 *     the next hour hammering a closed door.
 *  3. **Stale-while-error.** A failed refresh never destroys the last good snapshot; the
 *     error rides along on it so widgets can show "last updated 4m ago" instead of blanking.
 */
object PortfolioRepository {

    private const val SNAPSHOT_FILE = "snapshot.json"
    private const val NAMES_FILE = "instrument_names.json"

    /** Local floor between network refreshes, above Trading 212's documented 5s cap. */
    private const val MIN_SPACING_MS = 6_000L

    /** Instrument metadata is ~15k rows and rate-limited to 1/50s; refresh it rarely. */
    private const val NAMES_TTL_MS = 7L * 24 * 60 * 60 * 1000

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
     * Fetches cash and portfolio in parallel and stores the merged snapshot.
     *
     * @param force ignore the local minimum-spacing floor (used by the manual refresh
     *   button). The 429 backoff is always honoured, forced or not.
     */
    suspend fun refresh(context: Context, force: Boolean = false): Snapshot = mutex.withLock {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val existing = cachedSnapshot(app)

        if (!SecureStore.hasApiKey(app)) {
            return@withLock store(app, (existing ?: empty()).copy(error = ApiError.NoKey.message))
        }
        if (now < backoffUntilMs) return@withLock existing ?: empty()
        if (!force && now - lastFetchAtMs < MIN_SPACING_MS && existing != null) return@withLock existing

        lastFetchAtMs = now
        val client = T212Client(app)

        val (cashResult, portfolioResult) = coroutineScope {
            val cash = async { client.accountCash() }
            val portfolio = async { client.portfolio() }
            cash.await() to portfolio.await()
        }

        val firstError = listOfNotNull(
            (cashResult as? ApiResult.Err)?.error,
            (portfolioResult as? ApiResult.Err)?.error,
        ).firstOrNull()

        if (firstError != null) {
            noteFailure(firstError)
            // Partial success still beats nothing: keep whichever half came back.
            val merged = (existing ?: empty()).copy(
                cash = (cashResult as? ApiResult.Ok)?.value ?: existing?.cash,
                positions = (portfolioResult as? ApiResult.Ok)?.value ?: existing?.positions.orEmpty(),
                error = firstError.message,
            )
            return@withLock store(app, merged)
        }

        consecutiveFailures = 0
        backoffUntilMs = 0

        val cash = (cashResult as ApiResult.Ok).value
        val positions = (portfolioResult as ApiResult.Ok).value

        var currency = app.accountCurrency
        if (currency.isEmpty()) {
            (client.accountInfo() as? ApiResult.Ok)?.value?.currencyCode
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    currency = it
                    app.accountCurrency = it
                }
        }

        // Names are cosmetic; a failure here must never cost the user their numbers.
        val meta = runCatching {
            instrumentMeta(app, client, positions.map { it.ticker }.toSet())
        }.getOrDefault(emptyMap<String, String>() to emptyMap())

        val snapshot = Snapshot(
            fetchedAtMs = System.currentTimeMillis(),
            currency = currency,
            cash = cash,
            positions = positions,
            names = meta.first,
            currencies = meta.second,
            error = null,
        )
        DailyBaseline.observe(app, snapshot)
        store(app, snapshot)
    }

    /** Drops every cached artefact. Used when the API key or environment changes. */
    fun invalidate(context: Context) {
        val app = context.applicationContext
        cached.set(null)
        lastFetchAtMs = 0
        backoffUntilMs = 0
        consecutiveFailures = 0
        runCatching { File(app.filesDir, SNAPSHOT_FILE).delete() }
        runCatching { File(app.filesDir, NAMES_FILE).delete() }
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

    private fun noteFailure(error: ApiError) {
        consecutiveFailures++
        val now = System.currentTimeMillis()
        backoffUntilMs = when (error) {
            is ApiError.RateLimited -> now + (error.retryAfterSec?.times(1000L) ?: 60_000L)
            is ApiError.Unauthorised, is ApiError.Forbidden -> now + 300_000L
            // 10s, 20s, 40s … capped at 5 minutes.
            else -> now + (10_000L shl (consecutiveFailures - 1).coerceAtMost(5)).coerceAtMost(300_000L)
        }
    }

    /**
     * Ticker -> (display name, instrument currency), cached on disk for [NAMES_TTL_MS].
     * Returns whatever is cached if the download is skipped, disabled, or fails: names are a
     * nicety and must never hold up the numbers.
     */
    private suspend fun instrumentMeta(
        context: Context,
        client: T212Client,
        tickers: Set<String>,
    ): Pair<Map<String, String>, Map<String, String>> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, NAMES_FILE)
        val cachedNames = runCatching { file.readText() }.getOrNull()?.let(::parseNames)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < NAMES_TTL_MS
        val covered = cachedNames != null && tickers.all { it in cachedNames.first }

        if (!context.fetchInstrumentNames) return@withContext (cachedNames ?: (emptyMap<String, String>() to emptyMap()))
        if (fresh && covered) return@withContext cachedNames!!
        if (tickers.isEmpty()) return@withContext (cachedNames ?: (emptyMap<String, String>() to emptyMap()))

        when (val r = client.instrumentNames(tickers)) {
            is ApiResult.Ok -> {
                val names = r.value.mapValues { it.value.name }
                val currencies = r.value.mapValues { it.value.currencyCode }
                runCatching { file.writeText(serialiseNames(names, currencies)) }
                names to currencies
            }
            is ApiResult.Err -> cachedNames ?: (emptyMap<String, String>() to emptyMap())
        }
    }

    private fun parseNames(raw: String): Pair<Map<String, String>, Map<String, String>>? =
        runCatching {
            val o = org.json.JSONObject(raw)
            val names = HashMap<String, String>()
            val currencies = HashMap<String, String>()
            val n = o.optJSONObject("names")
            val c = o.optJSONObject("currencies")
            n?.keys()?.forEach { names[it] = n.optString(it) }
            c?.keys()?.forEach { currencies[it] = c.optString(it) }
            names as Map<String, String> to (currencies as Map<String, String>)
        }.getOrNull()

    private fun serialiseNames(
        names: Map<String, String>,
        currencies: Map<String, String>,
    ): String = org.json.JSONObject()
        .put("names", org.json.JSONObject(names as Map<*, *>))
        .put("currencies", org.json.JSONObject(currencies as Map<*, *>))
        .toString()
}
