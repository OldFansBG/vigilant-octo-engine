package com.t212widgets.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * `GET /api/v0/equity/account/summary`.
 *
 * Every figure is in the account's primary currency ([currency]).
 */
data class AccountSummary(
    val id: Long?,
    val currency: String,
    val totalValue: Double,
    val availableToTrade: Double,
    val inPies: Double,
    val reservedForOrders: Double,
    val investmentsValue: Double,
    val investmentsCost: Double,
    val realizedProfitLoss: Double,
    val unrealizedProfitLoss: Double,
) {
    /** Unrealised return against what the open positions cost. */
    val unrealizedReturnPct: Double?
        get() = if (investmentsCost == 0.0) null else unrealizedProfitLoss / investmentsCost * 100.0

    companion object {
        fun fromJson(o: JSONObject): AccountSummary {
            val cash = o.optJSONObject("cash")
            val investments = o.optJSONObject("investments")
            return AccountSummary(
                id = if (o.has("id") && !o.isNull("id")) o.optLong("id") else null,
                currency = o.optString("currency", ""),
                totalValue = o.optDouble("totalValue", 0.0).orZero(),
                availableToTrade = cash.num("availableToTrade"),
                inPies = cash.num("inPies"),
                reservedForOrders = cash.num("reservedForOrders"),
                investmentsValue = investments.num("currentValue"),
                investmentsCost = investments.num("totalCost"),
                realizedProfitLoss = investments.num("realizedProfitLoss"),
                unrealizedProfitLoss = investments.num("unrealizedProfitLoss"),
            )
        }
    }
}

/**
 * One open position from `GET /api/v0/equity/positions`.
 *
 * Note which currency each figure is in, because they differ and it matters on a widget:
 * [currentPrice] and [averagePricePaid] are quoted in the *instrument's* currency
 * ([instrumentCurrency]), while everything under `walletImpact` — [marketValue], [cost],
 * [unrealizedProfitLoss], [fxImpact] — is already converted to the *account's* currency
 * ([walletCurrency]) by Trading 212. So position values can be totalled and compared
 * directly, and no FX rate has to be invented.
 */
data class Position(
    val ticker: String,
    val name: String,
    val isin: String,
    val instrumentCurrency: String,
    val quantity: Double,
    val quantityAvailableForTrading: Double,
    val quantityInPies: Double,
    val currentPrice: Double,
    val averagePricePaid: Double,
    val createdAt: String?,
    val walletCurrency: String,
    val marketValue: Double,
    val cost: Double,
    val unrealizedProfitLoss: Double,
    val fxImpact: Double,
) {
    /** Return on cost, in account currency — FX effects included, as the broker reports it. */
    val returnPct: Double?
        get() = if (cost == 0.0) null else unrealizedProfitLoss / cost * 100.0

    /** Symbol part of a `AAPL_US_EQ`-style ticker. */
    val symbol: String get() = ticker.substringBefore('_')

    /** Falls back to the symbol when the instrument has no name attached. */
    val displayName: String get() = name.ifBlank { symbol }

    companion object {
        fun fromJson(o: JSONObject): Position {
            val instrument = o.optJSONObject("instrument")
            val wallet = o.optJSONObject("walletImpact")
            return Position(
                ticker = instrument?.optString("ticker").orEmpty(),
                name = instrument?.optString("name").orEmpty(),
                isin = instrument?.optString("isin").orEmpty(),
                instrumentCurrency = instrument?.optString("currency").orEmpty(),
                quantity = o.optDouble("quantity", 0.0).orZero(),
                quantityAvailableForTrading = o.optDouble("quantityAvailableForTrading", 0.0).orZero(),
                quantityInPies = o.optDouble("quantityInPies", 0.0).orZero(),
                currentPrice = o.optDouble("currentPrice", 0.0).orZero(),
                averagePricePaid = o.optDouble("averagePricePaid", 0.0).orZero(),
                createdAt = o.optStringOrNull("createdAt"),
                walletCurrency = wallet?.optString("currency").orEmpty(),
                marketValue = wallet.num("currentValue"),
                cost = wallet.num("totalCost"),
                unrealizedProfitLoss = wallet.num("unrealizedProfitLoss"),
                fxImpact = wallet.num("fxImpact"),
            )
        }

        fun listFromJson(a: JSONArray): List<Position> =
            (0 until a.length())
                .mapNotNull { i -> a.optJSONObject(i)?.let(::fromJson) }
                .filter { it.ticker.isNotEmpty() }
    }
}

private fun Double.orZero(): Double = if (isNaN() || isInfinite()) 0.0 else this

private fun JSONObject?.num(name: String): Double =
    this?.optDouble(name, 0.0)?.orZero() ?: 0.0

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
