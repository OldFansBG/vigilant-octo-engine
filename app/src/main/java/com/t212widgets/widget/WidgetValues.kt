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
 * Currency handling, which the API makes pleasantly simple: account figures and every
 * `walletImpact` figure are already in the account's primary currency, so values and P/L can
 * be shown and compared directly. Only [PositionField.PRICE] and [PositionField.AVG_PRICE]
 * are quoted in the instrument's own currency, and those two are labelled with it.
 */
object WidgetValues {

    fun metric(context: Context, snapshot: Snapshot, config: WidgetConfig, metric: Metric): Cell {
        val s = snapshot.summary ?: return Cell.EMPTY
        val ccy = snapshot.currency.ifEmpty { s.currency }
        fun money(v: Double) = Cell(fmtMoney(v, ccy, config), 0)
        fun signed(v: Double) = Cell(fmtSignedMoney(v, ccy, config), v.direction())

        return when (metric) {
            Metric.ACCOUNT_TOTAL -> money(s.totalValue)
            Metric.INVESTMENTS_VALUE -> money(s.investmentsValue)
            Metric.INVESTED -> money(s.investmentsCost)
            Metric.FREE_CASH -> money(s.availableToTrade)
            Metric.OPEN_PL -> signed(s.unrealizedProfitLoss)
            Metric.OPEN_PL_PCT -> Cell(
                Format.signedPercent(s.unrealizedReturnPct, config.decimals),
                s.unrealizedProfitLoss.direction(),
            )
            Metric.TODAY_CHANGE -> {
                val base = DailyBaseline.accountTotal(context)
                if (base == null) Cell.PENDING else signed(s.totalValue - base)
            }
            Metric.TODAY_CHANGE_PCT -> {
                val base = DailyBaseline.accountTotal(context)
                if (base == null || base == 0.0) {
                    Cell.PENDING
                } else {
                    val pct = (s.totalValue - base) / base * 100.0
                    Cell(Format.signedPercent(pct, config.decimals), pct.direction())
                }
            }
            Metric.REALISED -> signed(s.realizedProfitLoss)
            Metric.PIE_CASH -> money(s.inPies)
            Metric.RESERVED -> money(s.reservedForOrders)
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
        // walletImpact is already in the account currency; prices are not.
        val accountCcy = position.walletCurrency.ifEmpty { snapshot.currency }
        val localCcy = position.instrumentCurrency

        return when (field) {
            PositionField.PRICE -> Cell(fmtMoney(position.currentPrice, localCcy, config, min = 2))
            PositionField.VALUE -> Cell(fmtMoney(position.marketValue, accountCcy, config))
            PositionField.COST -> Cell(fmtMoney(position.cost, accountCcy, config))
            PositionField.QUANTITY -> Cell(Format.quantity(position.quantity))
            PositionField.AVG_PRICE ->
                Cell(fmtMoney(position.averagePricePaid, localCcy, config, min = 2))
            PositionField.PL -> Cell(
                fmtSignedMoney(position.unrealizedProfitLoss, accountCcy, config),
                position.unrealizedProfitLoss.direction(),
            )
            PositionField.PL_PCT -> Cell(
                Format.signedPercent(position.returnPct, config.decimals),
                position.unrealizedProfitLoss.direction(),
            )
            PositionField.TODAY_PCT -> {
                val pct = todayPct(context, position)
                if (pct == null) Cell.PENDING else Cell(
                    Format.signedPercent(pct, config.decimals),
                    pct.direction(),
                )
            }
            PositionField.FX_PL -> Cell(
                fmtSignedMoney(position.fxImpact, accountCcy, config),
                position.fxImpact.direction(),
            )
        }
    }

    /** Change today as a percentage, used for sorting and for the movers widget. */
    fun todayPct(context: Context, position: Position): Double? {
        val open = DailyBaseline.price(context, position.ticker) ?: return null
        if (open == 0.0) return null
        return (position.currentPrice - open) / open * 100.0
    }

    fun sorted(context: Context, positions: List<Position>, sortBy: SortBy): List<Position> =
        when (sortBy) {
            SortBy.VALUE -> positions.sortedByDescending { it.marketValue }
            SortBy.PL -> positions.sortedByDescending { it.unrealizedProfitLoss }
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
