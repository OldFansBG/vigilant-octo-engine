package com.t212widgets.data

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * "Change today" support.
 *
 * The Trading 212 API exposes no previous-close or intraday-open figure, so a true daily
 * change cannot be read from it. What the app can do honestly is record the first values it
 * observes each calendar day and report movement against those. Anything derived from this
 * is labelled "since first update today" in the UI rather than being passed off as the
 * broker's official day change.
 *
 * The baseline is anchored to local midnight, so the first refresh after midnight (usually
 * the next time the phone is unlocked) sets the day's reference point.
 */
object DailyBaseline {

    private const val PREFS = "t212_baseline"
    private const val K_DAY = "day_key"
    private const val K_TOTAL = "total"
    private const val K_PPL = "ppl"
    private const val K_POSITIONS = "positions"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayKey(nowMs: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = nowMs }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    /** Records the day's opening values on the first snapshot after local midnight. */
    fun observe(context: Context, snapshot: Snapshot) {
        if (snapshot.isEmpty) return
        val p = prefs(context)
        val today = dayKey(snapshot.fetchedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis())
        if (p.getInt(K_DAY, -1) == today) return

        val positions = JSONObject()
        snapshot.positions.forEach { positions.put(it.ticker, it.currentPrice) }
        p.edit()
            .putInt(K_DAY, today)
            .putFloat(K_TOTAL, (snapshot.cash?.total ?: 0.0).toFloat())
            .putFloat(K_PPL, (snapshot.cash?.ppl ?: 0.0).toFloat())
            .putString(K_POSITIONS, positions.toString())
            .apply()
    }

    /** Account total at the first refresh of the current day, or null if not yet recorded. */
    fun accountTotal(context: Context): Double? {
        val p = prefs(context)
        if (p.getInt(K_DAY, -1) != dayKey(System.currentTimeMillis())) return null
        return p.getFloat(K_TOTAL, Float.NaN).toDouble().takeIf { !it.isNaN() }
    }

    /** Open P/L at the first refresh of the current day. */
    fun accountPpl(context: Context): Double? {
        val p = prefs(context)
        if (p.getInt(K_DAY, -1) != dayKey(System.currentTimeMillis())) return null
        return p.getFloat(K_PPL, Float.NaN).toDouble().takeIf { !it.isNaN() }
    }

    /** Price of [ticker] at the first refresh of the current day. */
    fun price(context: Context, ticker: String): Double? {
        val p = prefs(context)
        if (p.getInt(K_DAY, -1) != dayKey(System.currentTimeMillis())) return null
        val raw = p.getString(K_POSITIONS, null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
            ?.takeIf { it.has(ticker) }
            ?.optDouble(ticker)
            ?.takeIf { !it.isNaN() }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
