package com.t212widgets.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single open position, as returned by `GET /api/v0/equity/portfolio`.
 *
 * Currency caveat, and it matters for anything you put on a widget: [averagePrice] and
 * [currentPrice] are quoted in the *instrument's* currency, while [ppl] and [fxPpl] are in
 * the *account's* currency. The API exposes no FX rate, so a position's market value cannot
 * be converted to the account currency — [marketValueLocal] is therefore labelled with the
 * instrument currency wherever it is displayed, and account-level totals always come from
 * `/equity/account/cash` instead of from summing positions.
 */
data class Position(
    val ticker: String,
    val quantity: Double,
    val averagePrice: Double,
    val currentPrice: Double,
    val ppl: Double,
    val fxPpl: Double?,
    val initialFillDate: String?,
    val pieQuantity: Double?,
    val maxBuy: Double?,
    val maxSell: Double?,
) {
    /** Value in the instrument's own currency. */
    val marketValueLocal: Double get() = quantity * currentPrice

    /** Cost basis in the instrument's own currency. */
    val costLocal: Double get() = quantity * averagePrice

    /**
     * Return percentage. Computed from local-currency cost vs local-currency value, which
     * makes it FX-neutral — the number a holder normally wants ("how is the stock doing"),
     * rather than the FX-contaminated account-currency figure.
     */
    val returnPct: Double?
        get() = if (costLocal == 0.0) null else (marketValueLocal - costLocal) / costLocal * 100.0

    /** Symbol part of a `AAPL_US_EQ`-style ticker. */
    val symbol: String get() = ticker.substringBefore('_')

    companion object {
        fun fromJson(o: JSONObject) = Position(
            ticker = o.optString("ticker"),
            quantity = o.optDouble("quantity", 0.0),
            averagePrice = o.optDouble("averagePrice", 0.0),
            currentPrice = o.optDouble("currentPrice", 0.0),
            ppl = o.optDouble("ppl", 0.0),
            fxPpl = o.optDoubleOrNull("fxPpl"),
            initialFillDate = o.optStringOrNull("initialFillDate"),
            pieQuantity = o.optDoubleOrNull("pieQuantity"),
            maxBuy = o.optDoubleOrNull("maxBuy"),
            maxSell = o.optDoubleOrNull("maxSell"),
        )

        fun listFromJson(a: JSONArray): List<Position> =
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let(::fromJson)
            }.filter { it.ticker.isNotEmpty() }
    }
}

/** `GET /api/v0/equity/account/cash`. All amounts are in the account currency. */
data class AccountCash(
    val free: Double,
    val total: Double,
    val invested: Double,
    val ppl: Double,
    val result: Double,
    val pieCash: Double,
    val blocked: Double?,
) {
    /** Unrealised return on the invested amount. */
    val investedReturnPct: Double?
        get() = if (invested == 0.0) null else ppl / invested * 100.0

    companion object {
        fun fromJson(o: JSONObject) = AccountCash(
            free = o.optDouble("free", 0.0),
            total = o.optDouble("total", 0.0),
            invested = o.optDouble("invested", 0.0),
            ppl = o.optDouble("ppl", 0.0),
            result = o.optDouble("result", 0.0),
            pieCash = o.optDouble("pieCash", 0.0),
            blocked = o.optDoubleOrNull("blocked"),
        )
    }
}

/** `GET /api/v0/equity/account/info`. */
data class AccountInfo(
    val id: Long?,
    val currencyCode: String,
) {
    companion object {
        fun fromJson(o: JSONObject) = AccountInfo(
            id = if (o.has("id") && !o.isNull("id")) o.optLong("id") else null,
            currencyCode = o.optString("currencyCode", ""),
        )
    }
}

internal fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (!has(name) || isNull(name)) null else optDouble(name).takeIf { !it.isNaN() }

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
