package com.t212widgets.data

import com.t212widgets.api.AccountCash
import com.t212widgets.api.Position
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the widgets render, in one serialisable blob.
 *
 * Widgets are drawn in a different process context from the fetcher and may be recreated at
 * any time, so the last good snapshot is persisted to disk. [error] is kept *alongside* the
 * data rather than replacing it: when a refresh fails the widget keeps showing the last
 * known figures with a staleness marker, which is far more useful than blanking out.
 */
data class Snapshot(
    val fetchedAtMs: Long,
    val currency: String,
    val cash: AccountCash?,
    val positions: List<Position>,
    val names: Map<String, String> = emptyMap(),
    val currencies: Map<String, String> = emptyMap(),
    val error: String? = null,
) {
    val isEmpty: Boolean get() = cash == null && positions.isEmpty()

    fun position(ticker: String): Position? = positions.firstOrNull { it.ticker == ticker }

    fun displayName(ticker: String): String = names[ticker] ?: ticker.substringBefore('_')

    fun currencyOf(ticker: String): String = currencies[ticker] ?: ""

    fun toJson(): String {
        val o = JSONObject()
        o.put("fetchedAtMs", fetchedAtMs)
        o.put("currency", currency)
        o.put("error", error ?: JSONObject.NULL)
        cash?.let {
            o.put(
                "cash",
                JSONObject()
                    .put("free", it.free)
                    .put("total", it.total)
                    .put("invested", it.invested)
                    .put("ppl", it.ppl)
                    .put("result", it.result)
                    .put("pieCash", it.pieCash)
                    .put("blocked", it.blocked ?: JSONObject.NULL),
            )
        }
        val arr = JSONArray()
        positions.forEach { p ->
            arr.put(
                JSONObject()
                    .put("ticker", p.ticker)
                    .put("quantity", p.quantity)
                    .put("averagePrice", p.averagePrice)
                    .put("currentPrice", p.currentPrice)
                    .put("ppl", p.ppl)
                    .put("fxPpl", p.fxPpl ?: JSONObject.NULL)
                    .put("initialFillDate", p.initialFillDate ?: JSONObject.NULL)
                    .put("pieQuantity", p.pieQuantity ?: JSONObject.NULL),
            )
        }
        o.put("positions", arr)
        o.put("names", JSONObject(names as Map<*, *>))
        o.put("currencies", JSONObject(currencies as Map<*, *>))
        return o.toString()
    }

    companion object {
        fun fromJson(raw: String): Snapshot? = runCatching {
            val o = JSONObject(raw)
            val cash = o.optJSONObject("cash")?.let(AccountCash::fromJson)
            val positions = o.optJSONArray("positions")?.let(Position::listFromJson).orEmpty()
            Snapshot(
                fetchedAtMs = o.optLong("fetchedAtMs"),
                currency = o.optString("currency", ""),
                cash = cash,
                positions = positions,
                names = o.optJSONObject("names").toStringMap(),
                currencies = o.optJSONObject("currencies").toStringMap(),
                error = if (o.isNull("error")) null else o.optString("error").ifEmpty { null },
            )
        }.getOrNull()
    }
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val out = HashMap<String, String>(length())
    keys().forEach { k -> out[k] = optString(k) }
    return out
}
