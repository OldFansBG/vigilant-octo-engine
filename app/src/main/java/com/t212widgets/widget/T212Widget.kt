package com.t212widgets.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.t212widgets.core.Format
import com.t212widgets.core.SecureStore
import com.t212widgets.data.PortfolioRepository
import com.t212widgets.data.Snapshot
import com.t212widgets.refresh.RefreshScheduler
import com.t212widgets.ui.MainActivity
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one widget provider behind every widget the user places.
 *
 * Rather than shipping a fixed set of providers, a single provider renders whichever
 * [WidgetKind] its instance was configured with. That is what makes the widgets "custom":
 * the user places the same widget several times and each instance is set up to answer a
 * different question, at whatever size they drag it to.
 */
class T212Widget : GlanceAppWidget() {

    /** Exact mode re-composes on every resize, so layouts can adapt to the real cell size. */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }
            .getOrDefault(-1)
        val config = WidgetConfigStore.load(context, appWidgetId)
        val configured = SecureStore.hasApiKey(context)
        val snapshot = PortfolioRepository.cachedSnapshot(context)
        val systemDark = WidgetPalette.isSystemDark(context)

        provideContent {
            if (!configured) {
                SetupPrompt(WidgetPalette.of(config, systemDark))
            } else {
                WidgetBody(context, config, snapshot, systemDark)
            }
        }
    }
}

class T212WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = T212Widget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetConfigStore.delete(context, appWidgetIds)
        // The last widget going away should also stop the polling that fed it.
        RefreshScheduler.reconcile(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshScheduler.reconcile(context)
    }
}

/** Tap target on the refresh chip: forces a fetch, ignoring the local spacing floor. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // Broadcast callbacks get roughly ten seconds; give up cleanly rather than being
        // killed mid-write, and let the next scheduled tick pick it up instead.
        withTimeoutOrNull(9_000) { PortfolioRepository.refresh(context, force = true) }
        T212Widget().updateAll(context)
    }
}

// ------------------------------------------------------------------ composables

@Composable
private fun SetupPrompt(palette: WidgetPalette) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(palette.background)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Trading 212 widgets",
                style = TextStyle(color = palette.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                "Tap to add your API key",
                style = TextStyle(color = palette.muted, fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun WidgetBody(
    context: Context,
    config: WidgetConfig,
    snapshot: Snapshot?,
    systemDark: Boolean,
) {
    val palette = WidgetPalette.of(config, systemDark)
    val width = LocalSize.current.width
    val height = LocalSize.current.height
    val tight = width < 180.dp || height < 100.dp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(palette.background)
            .cornerRadius(16.dp)
            .padding(horizontal = if (tight) 10.dp else 14.dp, vertical = if (tight) 8.dp else 12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (config.showHeader) {
            WidgetHeader(config, palette, snapshot, tight)
            Spacer(GlanceModifier.height(if (tight) 6.dp else 8.dp))
        }

        if (snapshot == null || snapshot.isEmpty) {
            EmptyState(palette, snapshot?.error)
            return@Column
        }

        Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            when (config.kind) {
                WidgetKind.SUMMARY, WidgetKind.CASH ->
                    MetricGrid(context, config, palette, snapshot, tight, height)
                WidgetKind.POSITION ->
                    SingleHolding(context, config, palette, snapshot, tight)
                WidgetKind.POSITIONS_LIST ->
                    HoldingsList(context, config, palette, snapshot, WidgetValues.sorted(context, snapshot.positions, config.sortBy))
                WidgetKind.MOVERS ->
                    HoldingsList(context, config, palette, snapshot, movers(context, snapshot, config))
            }
        }

        if (config.showUpdatedAt) {
            Spacer(GlanceModifier.height(4.dp))
            FooterLine(palette, snapshot)
        }
    }
}

@Composable
private fun WidgetHeader(
    config: WidgetConfig,
    palette: WidgetPalette,
    snapshot: Snapshot?,
    tight: Boolean,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(if (tight) 6.dp else 8.dp)
                .background(palette.accent)
                .cornerRadius(4.dp),
        ) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(
            config.title.ifBlank { defaultTitle(config, snapshot) },
            maxLines = 1,
            style = TextStyle(
                color = palette.onBackground,
                fontSize = if (tight) 12.sp else 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        if (config.showRefreshButton) {
            Text(
                "↻",
                style = TextStyle(color = palette.muted, fontSize = if (tight) 13.sp else 15.sp),
                modifier = GlanceModifier
                    .clickable(actionRunCallback<RefreshAction>())
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/** Account-level figures, one per row, or two per row when the widget is wide enough. */
@Composable
private fun MetricGrid(
    context: Context,
    config: WidgetConfig,
    palette: WidgetPalette,
    snapshot: Snapshot,
    tight: Boolean,
    height: androidx.compose.ui.unit.Dp,
) {
    val metrics = config.metrics.ifEmpty { listOf(Metric.ACCOUNT_TOTAL) }
    // The first metric is the headline; the rest are supporting detail.
    val headline = metrics.first()
    val rest = metrics.drop(1)
    val headlineCell = WidgetValues.metric(context, snapshot, config, headline)
    val roomForLabels = height >= 110.dp

    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (roomForLabels) {
            Text(
                headline.label,
                style = TextStyle(color = palette.muted, fontSize = 11.sp),
                maxLines = 1,
            )
        }
        Text(
            headlineCell.text,
            maxLines = 1,
            style = TextStyle(
                color = palette.forDirection(headlineCell.direction, config.colourPnl),
                fontSize = if (tight) 20.sp else 26.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (rest.isNotEmpty()) {
            Spacer(GlanceModifier.height(if (tight) 4.dp else 8.dp))
            rest.forEach { metric ->
                val cell = WidgetValues.metric(context, snapshot, config, metric)
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        metric.short,
                        maxLines = 1,
                        style = TextStyle(color = palette.muted, fontSize = 12.sp),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Text(
                        cell.text,
                        maxLines = 1,
                        style = TextStyle(
                            color = palette.forDirection(cell.direction, config.colourPnl),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SingleHolding(
    context: Context,
    config: WidgetConfig,
    palette: WidgetPalette,
    snapshot: Snapshot,
    tight: Boolean,
) {
    val position = snapshot.position(config.ticker)
    if (position == null) {
        Column {
            Text(
                if (config.ticker.isBlank()) "No holding chosen" else "${config.ticker.substringBefore('_')} not held",
                style = TextStyle(color = palette.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                "Long-press the widget to reconfigure",
                style = TextStyle(color = palette.muted, fontSize = 11.sp),
                maxLines = 2,
            )
        }
        return
    }

    val fields = config.positionFields.ifEmpty { listOf(PositionField.PRICE) }
    val headlineField = fields.first()
    val headline = WidgetValues.positionField(context, snapshot, config, position, headlineField)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        Text(
            snapshot.displayName(position.ticker),
            maxLines = 1,
            style = TextStyle(color = palette.muted, fontSize = 11.sp),
        )
        Text(
            headline.text,
            maxLines = 1,
            style = TextStyle(
                color = palette.forDirection(headline.direction, config.colourPnl),
                fontSize = if (tight) 20.sp else 26.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(GlanceModifier.height(if (tight) 4.dp else 8.dp))
        fields.drop(1).forEach { field ->
            val cell = WidgetValues.positionField(context, snapshot, config, position, field)
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    field.short,
                    maxLines = 1,
                    style = TextStyle(color = palette.muted, fontSize = 12.sp),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    cell.text,
                    maxLines = 1,
                    style = TextStyle(
                        color = palette.forDirection(cell.direction, config.colourPnl),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
private fun HoldingsList(
    context: Context,
    config: WidgetConfig,
    palette: WidgetPalette,
    snapshot: Snapshot,
    positions: List<com.t212widgets.api.Position>,
) {
    if (positions.isEmpty()) {
        Text("No open positions", style = TextStyle(color = palette.muted, fontSize = 12.sp))
        return
    }
    val fields = config.listFields.ifEmpty { listOf(PositionField.PL_PCT) }
    val rows = positions.take(config.maxRows)

    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(count = rows.size) { index ->
            val position = rows[index]
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    position.symbol,
                    maxLines = 1,
                    style = TextStyle(
                        color = palette.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
                fields.forEach { field ->
                    val cell = WidgetValues.positionField(context, snapshot, config, position, field)
                    Text(
                        cell.text,
                        maxLines = 1,
                        style = TextStyle(
                            color = palette.forDirection(cell.direction, config.colourPnl),
                            fontSize = 12.sp,
                        ),
                        modifier = GlanceModifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(palette: WidgetPalette, error: String?) {
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            error ?: "Waiting for first update…",
            maxLines = 3,
            style = TextStyle(color = palette.muted, fontSize = 12.sp),
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(
            "Tap ↻ to retry",
            style = TextStyle(color = palette.accent, fontSize = 11.sp),
            modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

@Composable
private fun FooterLine(palette: WidgetPalette, snapshot: Snapshot) {
    val stale = snapshot.error != null
    Text(
        buildString {
            if (stale) append("⚠ ").append(snapshot.error).append(" · ")
            append(Format.relativeTime(snapshot.fetchedAtMs))
        },
        maxLines = 1,
        style = TextStyle(
            color = if (stale) palette.loss else palette.muted,
            fontSize = 10.sp,
        ),
    )
}

// ------------------------------------------------------------------ helpers

private fun defaultTitle(config: WidgetConfig, snapshot: Snapshot?): String = when (config.kind) {
    WidgetKind.SUMMARY -> "Portfolio"
    WidgetKind.CASH -> "Cash"
    WidgetKind.MOVERS -> "Movers today"
    WidgetKind.POSITIONS_LIST -> "Holdings"
    WidgetKind.POSITION -> snapshot?.displayName(config.ticker) ?: config.ticker.substringBefore('_')
}

/**
 * Biggest movers since this morning's baseline: the top risers, then the top fallers, so a
 * single widget shows both ends of the day rather than only one.
 */
private fun movers(
    context: Context,
    snapshot: Snapshot,
    config: WidgetConfig,
): List<com.t212widgets.api.Position> {
    val scored = snapshot.positions.mapNotNull { p ->
        WidgetValues.todayPct(context, p)?.let { p to it }
    }
    if (scored.isEmpty()) return snapshot.positions.take(config.maxRows)
    val sorted = scored.sortedByDescending { it.second }
    val half = (config.maxRows + 1) / 2
    val risers = sorted.take(half).map { it.first }
    val fallers = sorted.takeLast(config.maxRows - risers.size).map { it.first }.reversed()
    return (risers + fallers).distinct()
}

/** Nudge every placed widget to redraw from the current snapshot. */
suspend fun updateAllWidgets(context: Context) {
    T212Widget().updateAll(context)
}

/** How many of this app's widgets are currently on a home screen. */
fun placedWidgetCount(context: Context): Int =
    android.appwidget.AppWidgetManager.getInstance(context)
        .getAppWidgetIds(android.content.ComponentName(context, T212WidgetReceiver::class.java))
        ?.size ?: 0
