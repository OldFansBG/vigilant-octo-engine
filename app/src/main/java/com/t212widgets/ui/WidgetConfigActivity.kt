package com.t212widgets.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t212widgets.api.Position
import com.t212widgets.core.Format
import com.t212widgets.core.SecureStore
import com.t212widgets.data.PortfolioRepository
import com.t212widgets.data.Snapshot
import com.t212widgets.refresh.RefreshScheduler
import com.t212widgets.widget.Accent
import com.t212widgets.widget.Metric
import com.t212widgets.widget.PositionField
import com.t212widgets.widget.SortBy
import com.t212widgets.widget.WidgetConfig
import com.t212widgets.widget.WidgetConfigStore
import com.t212widgets.widget.T212WidgetReceiver
import com.t212widgets.widget.WidgetKind
import com.t212widgets.widget.WidgetTheme
import com.t212widgets.widget.WidgetValues

/**
 * The widget builder.
 *
 * Launched by the launcher when a widget is dropped on the home screen, and again whenever
 * the user reconfigures one. Everything chosen here is scoped to a single `appWidgetId`, so
 * placing the widget four times gives four independently designed widgets.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Cancelled unless the user makes it all the way to Save: this is what tells the
        // launcher to drop the placeholder if they back out.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            T212Theme {
                ConfigScreen(
                    initial = WidgetConfigStore.load(this, appWidgetId),
                    onCancel = { finish() },
                    onSave = ::commit,
                )
            }
        }
    }

    /**
     * Persist, redraw, and hand the launcher its RESULT_OK.
     *
     * Everything that must happen after this point is handed to a component that outlives
     * the activity: `finish()` tears down the composition and cancels anything launched in
     * its scope, so the redraw goes out as a broadcast to the widget receiver and the first
     * data fetch goes to WorkManager.
     */
    private fun commit(config: WidgetConfig) {
        WidgetConfigStore.save(this, appWidgetId, config)
        RefreshScheduler.reconcile(this)
        RefreshScheduler.refreshSoon(this)

        sendBroadcast(
            Intent(this, T212WidgetReceiver::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId)),
        )

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(
    initial: WidgetConfig,
    onCancel: () -> Unit,
    onSave: (WidgetConfig) -> Unit,
) {
    val context = LocalContext.current

    var config by remember { mutableStateOf(initial) }
    var snapshot by remember { mutableStateOf(PortfolioRepository.cachedSnapshot(context)) }
    val configured = remember { SecureStore.hasApiKey(context) }

    // Pull fresh holdings so the ticker picker lists what the user actually owns rather than
    // whatever happened to be cached last week.
    LaunchedEffect(Unit) {
        if (configured) {
            PortfolioRepository.refresh(context)
            snapshot = PortfolioRepository.cachedSnapshot(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Build your widget") },
                actions = {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = { onSave(config) }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!configured) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("No API key yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            "You can design the widget now, but it will stay blank until you " +
                                "add a read-only API key in the app.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Preview(config, snapshot)

            // --------------------------------------------------------- what to show
            ConfigCard("What should it show?") {
                WidgetKind.entries.forEach { kind ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = config.kind == kind,
                            onClick = { config = config.withKindDefaults(kind) },
                            label = { Text(kind.label) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(kind.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // --------------------------------------------------------- holding picker
            if (config.kind == WidgetKind.POSITION) {
                ConfigCard("Which holding?") {
                    val positions = snapshot?.positions.orEmpty().sortedBy { it.symbol }
                    if (positions.isEmpty()) {
                        Text(
                            "No holdings loaded yet. Open the app, connect your key, then " +
                                "come back — or type a ticker below.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.ticker,
                            onValueChange = { config = config.copy(ticker = it.trim()) },
                            label = { Text("Ticker, e.g. AAPL_US_EQ") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            positions.forEach { p ->
                                FilterChip(
                                    selected = config.ticker == p.ticker,
                                    onClick = { config = config.copy(ticker = p.ticker) },
                                    label = { Text(p.symbol) },
                                )
                            }
                        }
                    }
                }
            }

            // --------------------------------------------------------- figures
            when (config.kind) {
                WidgetKind.SUMMARY, WidgetKind.CASH -> ConfigCard("Figures, in order") {
                    Text(
                        "The first one is the big number; the rest are listed underneath. Tap " +
                            "to add, tap a selected chip to remove.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OrderedPicker(
                        all = Metric.entries,
                        selected = config.metrics,
                        label = { it.label },
                        onChange = { config = config.copy(metrics = it) },
                    )
                }

                WidgetKind.POSITION -> ConfigCard("Figures, in order") {
                    Text(
                        "The first one is the big number shown under the holding's name.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OrderedPicker(
                        all = PositionField.entries,
                        selected = config.positionFields,
                        label = { it.label },
                        onChange = { config = config.copy(positionFields = it) },
                    )
                }

                WidgetKind.POSITIONS_LIST, WidgetKind.MOVERS -> ConfigCard("Columns") {
                    Text(
                        "Shown to the right of each holding. Two fits comfortably on a " +
                            "phone-width widget; three needs a wide one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OrderedPicker(
                        all = PositionField.entries,
                        selected = config.listFields,
                        label = { it.label },
                        onChange = { config = config.copy(listFields = it) },
                    )
                    Spacer(Modifier.height(12.dp))
                    if (config.kind == WidgetKind.POSITIONS_LIST) {
                        Text("Sort by", fontWeight = FontWeight.Medium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SortBy.entries.forEach { sort ->
                                FilterChip(
                                    selected = config.sortBy == sort,
                                    onClick = { config = config.copy(sortBy = sort) },
                                    label = { Text(sort.label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("Rows: ${config.maxRows}", fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 4, 6, 8, 10, 15, 25).forEach { n ->
                            FilterChip(
                                selected = config.maxRows == n,
                                onClick = { config = config.copy(maxRows = n) },
                                label = { Text("$n") },
                            )
                        }
                    }
                }
            }

            // --------------------------------------------------------- appearance
            ConfigCard("Appearance") {
                OutlinedTextField(
                    value = config.title,
                    onValueChange = { config = config.copy(title = it) },
                    label = { Text("Title (leave blank for the default)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Text("Theme", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidgetTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = config.theme == theme,
                            onClick = { config = config.copy(theme = theme) },
                            label = { Text(theme.label) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Accent", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Accent.entries.forEach { accent ->
                        FilterChip(
                            selected = config.accent == accent,
                            onClick = { config = config.copy(accent = accent) },
                            label = { Text(accent.label) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Decimal places: ${config.decimals}", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..4).forEach { d ->
                        FilterChip(
                            selected = config.decimals == d,
                            onClick = { config = config.copy(decimals = d) },
                            label = { Text("$d") },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                ConfigToggle("Header row", config.showHeader) { config = config.copy(showHeader = it) }
                ConfigToggle("Refresh button", config.showRefreshButton) {
                    config = config.copy(showRefreshButton = it)
                }
                ConfigToggle("“Updated x ago” line", config.showUpdatedAt) {
                    config = config.copy(showUpdatedAt = it)
                }
                ConfigToggle("Colour gains and losses", config.colourPnl) {
                    config = config.copy(colourPnl = it)
                }
                ConfigToggle("Shorten big numbers (1.2k)", config.compactNumbers) {
                    config = config.copy(compactNumbers = it)
                }
                ConfigToggle("Hide the amounts (privacy)", config.hideValues) {
                    config = config.copy(hideValues = it)
                }
            }

            Button(
                onClick = { onSave(config) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add widget") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * A rough text rendering of what the widget will contain, using real data when it is already
 * cached. Not pixel-accurate — it is there so the user can tell whether they picked the
 * figures they meant before committing.
 */
@Composable
private fun Preview(config: WidgetConfig, snapshot: Snapshot?) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                config.title.ifBlank { config.kind.label },
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            if (snapshot == null || snapshot.isEmpty) {
                Text("Preview appears once data has loaded", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            when (config.kind) {
                WidgetKind.SUMMARY, WidgetKind.CASH ->
                    config.metrics.forEachIndexed { index, metric ->
                        val cell = WidgetValues.metric(context, snapshot, config, metric)
                        PreviewLine(metric.short, cell.text, big = index == 0, direction = cell.direction)
                    }

                WidgetKind.POSITION -> {
                    val position = snapshot.position(config.ticker)
                    if (position == null) {
                        Text("Pick a holding above", style = MaterialTheme.typography.bodySmall)
                    } else {
                        config.positionFields.forEachIndexed { index, field ->
                            val cell = WidgetValues.positionField(context, snapshot, config, position, field)
                            PreviewLine(field.short, cell.text, big = index == 0, direction = cell.direction)
                        }
                    }
                }

                WidgetKind.POSITIONS_LIST, WidgetKind.MOVERS -> {
                    val rows = WidgetValues.sorted(context, snapshot.positions, config.sortBy)
                        .take(minOf(config.maxRows, 4))
                    rows.forEach { p: Position ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(p.symbol, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            config.listFields.forEach { field ->
                                val cell = WidgetValues.positionField(context, snapshot, config, p, field)
                                Text(
                                    cell.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (config.colourPnl) {
                                        PnlColours.forValue(cell.direction.toDouble())
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                    if (config.maxRows > 4) {
                        Text(
                            "…and ${config.maxRows - 4} more rows",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (config.showUpdatedAt) {
                Spacer(Modifier.height(4.dp))
                Text(
                    Format.relativeTime(snapshot.fetchedAtMs),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String, big: Boolean, direction: Int) {
    val colour = PnlColours.forValue(direction.toDouble())
    if (big) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colour,
        )
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = colour)
        }
    }
}

/**
 * Multi-select that remembers the order things were picked in, because for these widgets the
 * order *is* the layout: first item becomes the headline, the rest stack under it.
 */
@Composable
private fun <T> OrderedPicker(
    all: List<T>,
    selected: List<T>,
    label: (T) -> String,
    onChange: (List<T>) -> Unit,
) {
    if (selected.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            selected.forEachIndexed { index, item ->
                InputChip(
                    selected = true,
                    onClick = { onChange(selected.filterIndexed { i, _ -> i != index }) },
                    label = { Text("${index + 1}. ${label(item)}") },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    val remaining = all.filterNot { it in selected }
    if (remaining.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            remaining.forEach { item ->
                AssistChip(
                    onClick = { onChange(selected + item) },
                    label = { Text("+ ${label(item)}") },
                )
            }
        }
    }
}

@Composable
private fun ConfigCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ConfigToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Switching kind swaps in figures that make sense for it, so a user flipping from "Account
 * summary" to "Cash" is not left with a widget configured to show holdings counts.
 */
private fun WidgetConfig.withKindDefaults(kind: WidgetKind): WidgetConfig = when (kind) {
    WidgetKind.SUMMARY -> copy(
        kind = kind,
        metrics = listOf(Metric.ACCOUNT_TOTAL, Metric.OPEN_PL, Metric.TODAY_CHANGE),
    )
    WidgetKind.CASH -> copy(
        kind = kind,
        metrics = listOf(Metric.FREE_CASH, Metric.INVESTED, Metric.PIE_CASH),
    )
    WidgetKind.POSITION -> copy(
        kind = kind,
        positionFields = listOf(
            PositionField.PRICE, PositionField.PL, PositionField.PL_PCT, PositionField.QUANTITY,
        ),
    )
    WidgetKind.POSITIONS_LIST -> copy(
        kind = kind,
        listFields = listOf(PositionField.VALUE, PositionField.PL_PCT),
        sortBy = SortBy.VALUE,
    )
    WidgetKind.MOVERS -> copy(
        kind = kind,
        listFields = listOf(PositionField.TODAY_PCT, PositionField.PL),
        sortBy = SortBy.TODAY,
    )
}
