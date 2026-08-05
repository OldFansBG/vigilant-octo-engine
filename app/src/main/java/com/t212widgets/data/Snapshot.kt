package com.t212widgets.data

import com.t212widgets.api.AccountSummary
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
    val summary: AccountSummary?,
    val positions: List<Position>,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = summary == null && positions.isEmpty()

    fun position(ticker: String): Position? = positions.firstOrNull { it.ticker == ticker }

    fun displayName(ticker: String): String =
        position(ticker)?.displayName ?: ticker.substringBefore('_')

    fun toJson(): String {
        val o = JSONObject()
        o.put("fetchedAtMs", fetchedAtMs)
        o.put("currency", currency)
        o.put("error", error ?: JSONObject.NULL)
        summary?.let { s ->
            o.put(
                "summary",
                JSONObject()
                    .put("id", s.id ?: JSONObject.NULL)
                    .put("currency", s.currency)
                    .put("totalValue", s.totalValue)
                    .put(
                        "cash",
                        JSONObject()
                            .put("availableToTrade", s.availableToTrade)
                            .put("inPies", s.inPies)
                            .put("reservedForOrders", s.reservedForOrders),
                    )
                    .put(
                        "investments",
                        JSONObject()
                            .put("currentValue", s.investmentsValue)
                            .put("totalCost", s.investmentsCost)
                            .put("realizedProfitLoss", s.realizedProfitLoss)
                            .put("unrealizedProfitLoss", s.unrealizedProfitLoss),
                    ),
            )
        }
        val arr = JSONArray()
        positions.forEach { p ->
            arr.put(
                JSONObject()
                    .put(
                        "instrument",
                        JSONObject()
                            .put("ticker", p.ticker)
                            .put("name", p.name)
                            .put("isin", p.isin)
                            .put("currency", p.instrumentCurrency),
                    )
                    .put("quantity", p.quantity)
                    .put("quantityAvailableForTrading", p.quantityAvailableForTrading)
                    .put("quantityInPies", p.quantityInPies)
                    .put("currentPrice", p.currentPrice)
                    .put("averagePricePaid", p.averagePricePaid)
                    .put("createdAt", p.createdAt ?: JSONObject.NULL)
                    .put(
                        "walletImpact",
                        JSONObject()
                            .put("currency", p.walletCurrency)
                            .put("currentValue", p.marketValue)
                            .put("totalCost", p.cost)
                            .put("unrealizedProfitLoss", p.unrealizedProfitLoss)
                            .put("fxImpact", p.fxImpact),
                    ),
            )
        }
        o.put("positions", arr)
        return o.toString()
    }

    companion object {
        /**
         * Round-trips the shape written by [toJson], which is deliberately the same shape
         * the API returns — so the same parsers handle both and there is only one mapping to
         * keep correct.
         */
        fun fromJson(raw: String): Snapshot? = runCatching {
            val o = JSONObject(raw)
            Snapshot(
                fetchedAtMs = o.optLong("fetchedAtMs"),
                currency = o.optString("currency", ""),
                summary = o.optJSONObject("summary")?.let(AccountSummary::fromJson),
                positions = o.optJSONArray("positions")?.let(Position::listFromJson).orEmpty(),
                error = if (o.isNull("error")) null else o.optString("error").ifEmpty { null },
            )
        }.getOrNull()
    }
}
