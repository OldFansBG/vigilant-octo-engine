package com.t212widgets.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The shape of a widget: what it is fundamentally showing. */
enum class WidgetKind(val label: String, val description: String) {
    SUMMARY("Account summary", "Totals for the whole account — pick which figures to show"),
    POSITION("Single holding", "One stock, in detail"),
    POSITIONS_LIST("Holdings list", "Your positions in a scrollable list"),
    MOVERS("Today's movers", "Biggest risers and fallers since this morning"),
    CASH("Cash", "Free funds, invested and pie cash"),
}

/** An account-level number that a SUMMARY or CASH widget can display. */
enum class Metric(val label: String, val short: String) {
    ACCOUNT_TOTAL("Account value", "Value"),
    INVESTED("Invested", "Invested"),
    FREE_CASH("Free funds", "Free"),
    OPEN_PL("Open P/L", "P/L"),
    OPEN_PL_PCT("Open P/L %", "P/L %"),
    TODAY_CHANGE("Change today", "Today"),
    TODAY_CHANGE_PCT("Change today %", "Today %"),
    REALISED("Realised result", "Realised"),
    PIE_CASH("Pie cash", "Pies"),
    BLOCKED("Blocked", "Blocked"),
    POSITION_COUNT("Number of holdings", "Holdings"),
}

/** A per-holding number that POSITION and POSITIONS_LIST widgets can display. */
enum class PositionField(val label: String, val short: String) {
    PRICE("Current price", "Price"),
    VALUE("Market value", "Value"),
    QUANTITY("Quantity", "Qty"),
    AVG_PRICE("Average price", "Avg"),
    PL("Profit / loss", "P/L"),
    PL_PCT("Return %", "Return"),
    TODAY_PCT("Change today %", "Today"),
    FX_PL("FX impact", "FX"),
}

enum class SortBy(val label: String) {
    VALUE("Market value"),
    PL("Profit / loss"),
    PL_PCT("Return %"),
    TODAY("Change today"),
    NAME("Name"),
}

enum class WidgetTheme(val label: String) {
    SYSTEM("Match system"),
    DARK("Dark"),
    LIGHT("Light"),
    TRANSPARENT("Transparent"),
}

enum class Accent(val label: String, val argb: Long) {
    BLUE("Blue", 0xFF2F6FED),
    GREEN("Green", 0xFF1F9D55),
    PURPLE("Purple", 0xFF7C4DFF),
    ORANGE("Orange", 0xFFEF6C00),
    SLATE("Slate", 0xFF546E7A),
}

/**
 * Everything the user chose for one widget instance, keyed by its `appWidgetId`.
 *
 * Two widgets of the same kind can look completely different — different metrics, order,
 * density, colours — which is the whole point of "custom widgets": you place several and
 * each one answers a different question.
 */
data class WidgetConfig(
    val kind: WidgetKind = WidgetKind.SUMMARY,
    val title: String = "",
    val ticker: String = "",
    val metrics: List<Metric> = listOf(Metric.ACCOUNT_TOTAL, Metric.OPEN_PL, Metric.TODAY_CHANGE),
    val positionFields: List<PositionField> = listOf(
        PositionField.PRICE, PositionField.VALUE, PositionField.PL, PositionField.PL_PCT,
    ),
    val listFields: List<PositionField> = listOf(PositionField.VALUE, PositionField.PL_PCT),
    val sortBy: SortBy = SortBy.VALUE,
    val maxRows: Int = 6,
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
    val accent: Accent = Accent.BLUE,
    val decimals: Int = 2,
    val compactNumbers: Boolean = false,
    val showHeader: Boolean = true,
    val showUpdatedAt: Boolean = true,
    val colourPnl: Boolean = true,
    val showRefreshButton: Boolean = true,
    val hideValues: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("kind", kind.name)
        .put("title", title)
        .put("ticker", ticker)
        .put("metrics", JSONArray(metrics.map { it.name }))
        .put("positionFields", JSONArray(positionFields.map { it.name }))
        .put("listFields", JSONArray(listFields.map { it.name }))
        .put("sortBy", sortBy.name)
        .put("maxRows", maxRows)
        .put("theme", theme.name)
        .put("accent", accent.name)
        .put("decimals", decimals)
        .put("compactNumbers", compactNumbers)
        .put("showHeader", showHeader)
        .put("showUpdatedAt", showUpdatedAt)
        .put("colourPnl", colourPnl)
        .put("showRefreshButton", showRefreshButton)
        .put("hideValues", hideValues)
        .toString()

    companion object {
        fun fromJson(raw: String): WidgetConfig? = runCatching {
            val o = JSONObject(raw)
            val d = WidgetConfig()
            WidgetConfig(
                kind = o.enum(WidgetKind.entries, "kind", d.kind),
                title = o.optString("title", d.title),
                ticker = o.optString("ticker", d.ticker),
                metrics = o.enumList(Metric.entries, "metrics", d.metrics),
                positionFields = o.enumList(PositionField.entries, "positionFields", d.positionFields),
                listFields = o.enumList(PositionField.entries, "listFields", d.listFields),
                sortBy = o.enum(SortBy.entries, "sortBy", d.sortBy),
                maxRows = o.optInt("maxRows", d.maxRows).coerceIn(1, 30),
                theme = o.enum(WidgetTheme.entries, "theme", d.theme),
                accent = o.enum(Accent.entries, "accent", d.accent),
                decimals = o.optInt("decimals", d.decimals).coerceIn(0, 6),
                compactNumbers = o.optBoolean("compactNumbers", d.compactNumbers),
                showHeader = o.optBoolean("showHeader", d.showHeader),
                showUpdatedAt = o.optBoolean("showUpdatedAt", d.showUpdatedAt),
                colourPnl = o.optBoolean("colourPnl", d.colourPnl),
                showRefreshButton = o.optBoolean("showRefreshButton", d.showRefreshButton),
                hideValues = o.optBoolean("hideValues", d.hideValues),
            )
        }.getOrNull()
    }
}

/** Per-widget configuration storage, keyed by `appWidgetId`. */
object WidgetConfigStore {

    private const val PREFS = "t212_widgets"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context, appWidgetId: Int): WidgetConfig =
        prefs(context).getString(key(appWidgetId), null)?.let(WidgetConfig::fromJson) ?: WidgetConfig()

    fun exists(context: Context, appWidgetId: Int): Boolean =
        prefs(context).contains(key(appWidgetId))

    fun save(context: Context, appWidgetId: Int, config: WidgetConfig) {
        prefs(context).edit().putString(key(appWidgetId), config.toJson()).apply()
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        prefs(context).edit().apply {
            appWidgetIds.forEach { remove(key(it)) }
        }.apply()
    }

    private fun key(appWidgetId: Int) = "widget_$appWidgetId"
}

private fun <T : Enum<T>> JSONObject.enum(values: List<T>, name: String, fallback: T): T {
    val raw = optString(name, "")
    return values.firstOrNull { it.name == raw } ?: fallback
}

private fun <T : Enum<T>> JSONObject.enumList(values: List<T>, name: String, fallback: List<T>): List<T> {
    val arr = optJSONArray(name) ?: return fallback
    val out = (0 until arr.length()).mapNotNull { i ->
        values.firstOrNull { it.name == arr.optString(i) }
    }
    return out.ifEmpty { fallback }
}
