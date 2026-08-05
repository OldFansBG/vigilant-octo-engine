package com.t212widgets.widget

import android.content.Context
import com.t212widgets.api.Position
import com.t212widgets.core.Format
import com.t212widgets.data.DailyBaseline
import com.t212widgets.data.Snapshot

/**
 * A number ready to be drawn: the text, and whether it should be tinted as a gain or a loss.
 * [direction] is 0 for neutral figures (a balance, a quantity) so those never get coloured.
 */
data class Cell(val text: String, val direction: Int = 0) {
    companion object {
        /** The figure is not in the snapshot at all. */
        val EMPTY = Cell("—", 0)

        /** The figure needs a baseline that today has not produced yet. */
        val PENDING = Cell("…", 0)
    }
}

/**
 * Turns a [Snapshot] into the strings a widget shows.
 *
 * All the "which currency is this actually in" reasoning lives here: account-level figures
 * come from `/account/cash` in the account currency, per-holding prices and values stay in
 * the instrument's own currency and are labelled with it.
 */
object WidgetValues {

    fun metric(context: Context, snapshot: Snapshot, config: WidgetConfig, metric: Metric): Cell {
        val cash = snapshot.cash
        val ccy = snapshot.currency
        fun money(v: Double) = Cell(fmtMoney(v, ccy, config), 0)
        fun signed(v: Double) = Cell(fmtSignedMoney(v, ccy, config), v.direction())

        return when (metric) {
            Metric.ACCOUNT_TOTAL -> cash?.let { money(it.total) } ?: Cell.EMPTY
            Metric.INVESTED -> cash?.let { money(it.invested) } ?: Cell.EMPTY
            Metric.FREE_CASH -> cash?.let { money(it.free) } ?: Cell.EMPTY
            Metric.OPEN_PL -> cash?.let { signed(it.ppl) } ?: Cell.EMPTY
            Metric.OPEN_PL_PCT -> cash?.let {
                Cell(Format.signedPercent(it.investedReturnPct, config.decimals), it.ppl.direction())
            } ?: Cell.EMPTY
            Metric.TODAY_CHANGE -> {
                val base = DailyBaseline.accountTotal(context)
                val now = cash?.total
                if (base == null || now == null) Cell.PENDING else signed(now - base)
            }
            Metric.TODAY_CHANGE_PCT -> {
                val base = DailyBaseline.accountTotal(context)
                val now = cash?.total
                if (base == null || now == null || base == 0.0) {
                    Cell.PENDING
                } else {
                    val pct = (now - base) / base * 100.0
                    Cell(Format.signedPercent(pct, config.decimals), pct.direction())
                }
            }
            Metric.REALISED -> cash?.let { signed(it.result) } ?: Cell.EMPTY
            Metric.PIE_CASH -> cash?.let { money(it.pieCash) } ?: Cell.EMPTY
            Metric.BLOCKED -> cash?.blocked?.let { money(it) } ?: Cell.EMPTY
            Metric.POSITION_COUNT -> Cell(snapshot.positions.size.toString(), 0)
        }
    }

    fun positionField(
        context: Context,
        snapshot: Snapshot,
        config: WidgetConfig,
        position: Position,
        field: PositionField,
    ): Cell {
        val accountCcy = snapshot.currency
        val localCcy = snapshot.currencyOf(position.ticker)

        return when (field) {
            PositionField.PRICE -> Cell(fmtMoney(position.currentPrice, localCcy, config, min = 2))
            PositionField.VALUE -> Cell(fmtMoney(position.marketValueLocal, localCcy, config))
            PositionField.QUANTITY -> Cell(Format.quantity(position.quantity))
            PositionField.AVG_PRICE -> Cell(fmtMoney(position.averagePrice, localCcy, config, min = 2))
            PositionField.PL -> Cell(
                fmtSignedMoney(position.ppl, accountCcy, config),
                position.ppl.direction(),
            )
            PositionField.PL_PCT -> Cell(
                Format.signedPercent(position.returnPct, config.decimals),
                (position.returnPct ?: 0.0).direction(),
            )
            PositionField.TODAY_PCT -> {
                val open = DailyBaseline.price(context, position.ticker)
                if (open == null || open == 0.0) {
                    Cell.PENDING
                } else {
                    val pct = (position.currentPrice - open) / open * 100.0
                    Cell(Format.signedPercent(pct, config.decimals), pct.direction())
                }
            }
            PositionField.FX_PL -> position.fxPpl?.let {
                Cell(fmtSignedMoney(it, accountCcy, config), it.direction())
            } ?: Cell.EMPTY
        }
    }

    /** Change today as a fraction, used for sorting and for the movers widget. */
    fun todayPct(context: Context, position: Position): Double? {
        val open = DailyBaseline.price(context, position.ticker) ?: return null
        if (open == 0.0) return null
        return (position.currentPrice - open) / open * 100.0
    }

    fun sorted(context: Context, positions: List<Position>, sortBy: SortBy): List<Position> =
        when (sortBy) {
            SortBy.VALUE -> positions.sortedByDescending { it.marketValueLocal }
            SortBy.PL -> positions.sortedByDescending { it.ppl }
            SortBy.PL_PCT -> positions.sortedByDescending { it.returnPct ?: Double.NEGATIVE_INFINITY }
            SortBy.TODAY -> positions.sortedByDescending {
                todayPct(context, it) ?: Double.NEGATIVE_INFINITY
            }
            SortBy.NAME -> positions.sortedBy { it.symbol }
        }

    private fun fmtMoney(value: Double, currency: String, config: WidgetConfig, min: Int = 0): String {
        if (config.hideValues) return "••••"
        val decimals = config.decimals.coerceAtLeast(min)
        return if (config.compactNumbers) {
            Format.compactMoney(value, currency)
        } else {
            Format.money(value, currency, decimals)
        }
    }

    private fun fmtSignedMoney(value: Double, currency: String, config: WidgetConfig): String {
        if (config.hideValues) return "••••"
        return if (config.compactNumbers) {
            (if (value > 0) "+" else "") + Format.compactMoney(value, currency)
        } else {
            Format.signedMoney(value, currency, config.decimals)
        }
    }

    private fun Double.direction(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}
