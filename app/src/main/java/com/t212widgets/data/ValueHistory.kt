package com.t212widgets.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One recorded observation. */
data class Sample(val atMs: Long, val value: Double)

/**
 * A local time series of everything worth charting.
 *
 * The public API has **no** intraday price or portfolio-value history — only current
 * positions, current account summary, and settled historical events (orders, dividends,
 * transactions). So a "how has it moved" chart cannot be fetched; it has to be recorded.
 * Every successful poll appends a sample here, which means the chart fills in as the app
 * runs and is genuinely the movement *this device observed*, not a reconstruction.
 *
 * Storage is a compact `timestamp,value` CSV per series, written back on a flush interval
 * rather than on every sample, because at one sample per second a write-per-sample would be
 * pointless disk churn.
 */
object ValueHistory {

    /** The whole account's live value. */
    const val SERIES_ACCOUNT = "account"

    /** A single instrument's price: `price:AAPL_US_EQ`. */
    fun seriesForTicker(ticker: String) = "price:$ticker"

    /** Roughly nine hours at one sample per second — a full trading day of live watching. */
    private const val MAX_SAMPLES = 32_000

    /** Don't record a sample more often than this, whatever the caller does. */
    private const val MIN_SAMPLE_SPACING_MS = 900L

    /** Write to disk at most this often; samples live in memory in between. */
    private const val FLUSH_INTERVAL_MS = 15_000L

    private class Series {
        val samples = ArrayDeque<Sample>()
        var lastFlushAtMs = 0L
        var dirty = false
    }

    private val series = ConcurrentHashMap<String, Series>()

    /**
     * Appends a sample, unless it arrived too soon after the previous one or repeats the
     * previous value at the same instant. Returns true when it was actually recorded.
     */
    @Synchronized
    fun record(context: Context, key: String, value: Double, atMs: Long = System.currentTimeMillis()): Boolean {
        if (value.isNaN() || value.isInfinite()) return false
        val s = load(context, key)
        val last = s.samples.lastOrNull()
        if (last != null && atMs - last.atMs < MIN_SAMPLE_SPACING_MS) return false

        s.samples.addLast(Sample(atMs, value))
        while (s.samples.size > MAX_SAMPLES) s.samples.removeFirst()
        s.dirty = true

        if (atMs - s.lastFlushAtMs >= FLUSH_INTERVAL_MS) flush(context, key, s)
        return true
    }

    /** Samples newer than [sinceMs], oldest first. */
    @Synchronized
    fun samples(context: Context, key: String, sinceMs: Long = 0L): List<Sample> =
        load(context, key).samples.filter { it.atMs >= sinceMs }

    /** Most recent sample, or null when nothing has been recorded yet. */
    @Synchronized
    fun latest(context: Context, key: String): Sample? = load(context, key).samples.lastOrNull()

    /** Persists anything still in memory. Call when a live view stops. */
    @Synchronized
    fun flushAll(context: Context) {
        series.forEach { (key, s) -> if (s.dirty) flush(context, key, s) }
    }

    @Synchronized
    fun clear(context: Context) {
        series.clear()
        runCatching { historyDir(context).listFiles()?.forEach { it.delete() } }
    }

    // ---------------------------------------------------------------- internals

    private fun historyDir(context: Context) =
        File(context.applicationContext.filesDir, "history").apply { mkdirs() }

    /** File-system-safe name; tickers contain characters that are fine but colons are not. */
    private fun fileFor(context: Context, key: String) =
        File(historyDir(context), key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".csv")

    private fun load(context: Context, key: String): Series =
        series.getOrPut(key) {
            Series().also { s ->
                runCatching {
                    val file = fileFor(context, key)
                    if (!file.exists()) return@runCatching
                    file.forEachLine { line ->
                        val comma = line.indexOf(',')
                        if (comma <= 0) return@forEachLine
                        val at = line.substring(0, comma).toLongOrNull() ?: return@forEachLine
                        val v = line.substring(comma + 1).toDoubleOrNull() ?: return@forEachLine
                        s.samples.addLast(Sample(at, v))
                    }
                    while (s.samples.size > MAX_SAMPLES) s.samples.removeFirst()
                }
                s.lastFlushAtMs = System.currentTimeMillis()
            }
        }

    private fun flush(context: Context, key: String, s: Series) {
        runCatching {
            val text = buildString(s.samples.size * 24) {
                s.samples.forEach { append(it.atMs).append(',').append(it.value).append('\n') }
            }
            fileFor(context, key).writeText(text)
        }
        s.lastFlushAtMs = System.currentTimeMillis()
        s.dirty = false
    }
}
