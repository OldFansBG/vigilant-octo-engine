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
 *
 * [needsAttention] separates "you have to go and fix something" (a rejected key, a missing
 * permission) from "this will very likely sort itself out" (a slow request, a rate limit).
 * Only the former is worth putting a warning on the home screen for; the latter, shown
 * eagerly, makes a working widget look broken.
 */
data class Snapshot(
    val fetchedAtMs: Long,
    val currency: String,
    val summary: AccountSummary?,
    val positions: List<Position>,
    val error: String? = null,
    val needsAttention: Boolean = false,
) {
    val isEmpty: Boolean get() = summary == null && positions.isEmpty()

    /**
     * Account value using the freshest position prices available.
     *
     * The summary's own `totalValue` is only as new as the last 5-second summary poll, while
     * positions refresh every second. Swapping the summary's investments component for the
     * live sum of `walletImpact.currentValue` keeps the cash side exactly as reported and
     * updates the invested side at the positions cadence — so the figure drifts with the
     * market instead of stepping whenever the summary catches up.
     *
     * Both sides are already in the account currency, so this is a straight substitution
     * with no conversion invented anywhere.
     */
    val liveTotalValue: Double?
        get() {
            val s = summary ?: return null
            if (positions.isEmpty()) return s.totalValue
            val livePositions = positions.sumOf { it.marketValue }
            return s.totalValue - s.investmentsValue + livePositions
        }

    /** Live unrealised P/L, on the same freshest-prices basis as [liveTotalValue]. */
    val liveUnrealizedProfitLoss: Double?
        get() {
            val s = summary ?: return null
            if (positions.isEmpty()) return s.unrealizedProfitLoss
            return positions.sumOf { it.unrealizedProfitLoss }
        }

    fun position(ticker: String): Position? = positions.firstOrNull { it.ticker == ticker }

    fun displayName(ticker: String): String =
        position(ticker)?.displayName ?: ticker.substringBefore('_')

    fun toJson(): String {
        val o = JSONObject()
        o.put("fetchedAtMs", fetchedAtMs)
        o.put("currency", currency)
        o.put("error", error ?: JSONObject.NULL)
        o.put("needsAttention", needsAttention)
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
                needsAttention = o.optBoolean("needsAttention", false),
            )
        }.getOrNull()
    }
}
