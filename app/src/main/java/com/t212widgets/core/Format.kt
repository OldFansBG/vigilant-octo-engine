package com.t212widgets.core

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/** Number and money formatting shared by the widgets and the setup screen. */
object Format {

    private val symbols = DecimalFormatSymbols(Locale.UK)

    private val currencySigns = mapOf(
        "GBP" to "£", "GBX" to "p", "USD" to "$", "EUR" to "€", "CHF" to "CHF ",
        "JPY" to "¥", "SEK" to "kr ", "NOK" to "kr ", "DKK" to "kr ", "PLN" to "zł ",
        "CZK" to "Kč ", "HUF" to "Ft ", "RON" to "lei ", "AUD" to "A$", "CAD" to "C$",
    )

    fun sign(currency: String): String =
        currencySigns[currency.uppercase(Locale.ROOT)] ?: if (currency.isEmpty()) "" else "$currency "

    /** `1,234.56` with a fixed number of decimals. */
    fun number(value: Double, decimals: Int = 2): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        return DecimalFormat(pattern, symbols).format(value)
    }

    /** `£1,234.56`. */
    fun money(value: Double, currency: String, decimals: Int = 2): String =
        sign(currency) + number(value, decimals)

    /** `+£12.34` / `−£12.34`, with an explicit sign so gains and losses never look alike. */
    fun signedMoney(value: Double, currency: String, decimals: Int = 2): String {
        val prefix = if (value > 0) "+" else if (value < 0) "−" else ""
        return prefix + sign(currency) + number(abs(value), decimals)
    }

    fun signedPercent(value: Double?, decimals: Int = 2): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "—"
        val prefix = if (value > 0) "+" else if (value < 0) "−" else ""
        return prefix + number(abs(value), decimals) + "%"
    }

    /**
     * Compact money for tight widget cells: `£1.2k`, `£3.4M`. Falls back to the full number
     * below a thousand so small balances stay exact.
     */
    fun compactMoney(value: Double, currency: String): String {
        val a = abs(value)
        val prefix = if (value < 0) "−" else ""
        val s = sign(currency)
        return when {
            a >= 1_000_000_000 -> "$prefix$s${number(a / 1_000_000_000, 2)}B"
            a >= 1_000_000 -> "$prefix$s${number(a / 1_000_000, 2)}M"
            a >= 10_000 -> "$prefix$s${number(a / 1_000, 1)}k"
            else -> prefix + s + number(a, 2)
        }
    }

    /** Quantity: trims pointless zeros but keeps fractional shares readable. */
    fun quantity(value: Double): String = when {
        value == value.toLong().toDouble() -> number(value, 0)
        abs(value) < 1 -> number(value, 6).trimEnd('0').trimEnd('.')
        else -> number(value, 4).trimEnd('0').trimEnd('.')
    }

    /** `just now`, `4m ago`, `2h ago`, `3d ago`. */
    fun relativeTime(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (thenMs <= 0) return "never"
        val secs = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            secs < 20 -> "just now"
            secs < 60 -> "${secs}s ago"
            secs < 3600 -> "${secs / 60}m ago"
            secs < 86_400 -> "${secs / 3600}h ago"
            else -> "${secs / 86_400}d ago"
        }
    }

    /** `every 30s` / `every 5m` for interval pickers. */
    fun interval(seconds: Int): String = when {
        seconds < 60 -> "${seconds}s"
        seconds % 3600 == 0 -> "${seconds / 3600}h"
        seconds % 60 == 0 -> "${seconds / 60}m"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}
