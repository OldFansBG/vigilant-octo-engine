package com.t212widgets.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.t212widgets.core.Format
import com.t212widgets.core.accountCurrency
import com.t212widgets.data.PortfolioRepository
import com.t212widgets.data.Sample
import com.t212widgets.data.Snapshot
import com.t212widgets.data.ValueHistory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** How far back the chart looks. */
private enum class Range(val label: String, val millis: Long) {
    M5("5m", 5 * 60_000L),
    M15("15m", 15 * 60_000L),
    H1("1h", 60 * 60_000L),
    H6("6h", 6 * 60 * 60_000L),
    ALL("All", Long.MAX_VALUE),
}

/**
 * The live view: a value that moves tick by tick, and a chart you can scrub.
 *
 * While this screen is on top it polls `/equity/positions` at the 1 Hz that endpoint
 * permits — far faster than the widgets, which are bounded by what Android will let an
 * alarm do. Polling stops the moment the screen is not resumed, so it never runs in the
 * background draining battery.
 */
class LiveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The value on screen is as sensitive as anything in the setup screen.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Watching a live chart with the display timing out every 30s is useless.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent { T212Theme { LiveScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var snapshot by remember { mutableStateOf(PortfolioRepository.cachedSnapshot(context)) }
    var tick by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableStateOf(true) }
    var seriesKey by remember { mutableStateOf(ValueHistory.SERIES_ACCOUNT) }
    var range by remember { mutableStateOf(Range.M15) }
    var scrub by remember { mutableStateOf<Sample?>(null) }

    // Poll only while actually on screen.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    ValueHistory.flushAll(context)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ValueHistory.flushAll(context)
        }
    }

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (coroutineContext.isActive) {
            val updated = PortfolioRepository.refreshPositions(context)
            snapshot = updated
            tick++
            // RateLimiter paces the endpoint to its 1 Hz from inside the call, so the delay
            // here only exists to stop this loop spinning when a call returns without
            // touching the network — which is what a failure backoff does.
            delay(
                when {
                    !PortfolioRepository.canRefreshNow() -> 1_000L
                    updated.error != null -> 3_000L
                    else -> 150L
                },
            )
        }
    }

    val currency = snapshot?.currency?.ifEmpty { context.accountCurrency } ?: context.accountCurrency
    val positions = snapshot?.positions.orEmpty()
    val since = if (range == Range.ALL) 0L else System.currentTimeMillis() - range.millis
    val samples = remember(tick, seriesKey, range) { ValueHistory.samples(context, seriesKey, since) }

    val isAccount = seriesKey == ValueHistory.SERIES_ACCOUNT
    val seriesCurrency = if (isAccount) {
        currency
    } else {
        positions.firstOrNull { ValueHistory.seriesForTicker(it.ticker) == seriesKey }
            ?.instrumentCurrency.orEmpty()
    }

    val liveValue: Double? = if (isAccount) {
        snapshot?.liveTotalValue
    } else {
        positions.firstOrNull { ValueHistory.seriesForTicker(it.ticker) == seriesKey }?.currentPrice
    }

    // While scrubbing, the headline shows the point under the finger instead of "now".
    val shown = scrub?.value ?: liveValue
    val first = samples.firstOrNull()?.value
    val change = if (first != null && shown != null) shown - first else null
    val changePct = if (first != null && first != 0.0 && change != null) change / first * 100.0 else null

    Scaffold(topBar = { TopAppBar(title = { Text("Live") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
        ) {
            Text(
                if (isAccount) "Account value" else seriesKey.removePrefix("price:").substringBefore('_'),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                shown?.let { Format.money(it, seriesCurrency, 2) } ?: "—",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        if (change != null) {
                            append(Format.signedMoney(change, seriesCurrency, 2))
                            if (changePct != null) append("  ").append(Format.signedPercent(changePct, 2))
                        } else {
                            append("Collecting data…")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = change?.let { PnlColours.forValue(it) }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.fillMaxWidth(0.02f))
                Text(
                    scrub?.let { "  at ${clockFormat.format(Date(it.atMs))}" }
                        ?: "  over ${range.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            LiveChart(
                samples = samples,
                lineColour = PnlColours.forValue(change ?: 0.0),
                modifier = Modifier.fillMaxWidth().height(220.dp),
                onScrubChange = { scrub = it },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (samples.size < 2) {
                    "The API has no intraday history, so this chart is built from what the " +
                        "app records while it is open. Give it a few seconds."
                } else {
                    "${samples.size} points · touch and drag the chart to read any moment"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Range.entries.forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = { range = r },
                        label = { Text(r.label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Show", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = isAccount,
                    onClick = { seriesKey = ValueHistory.SERIES_ACCOUNT },
                    label = { Text("Account") },
                )
                positions.sortedBy { it.symbol }.forEach { p ->
                    val key = ValueHistory.seriesForTicker(p.ticker)
                    FilterChip(
                        selected = seriesKey == key,
                        onClick = { seriesKey = key },
                        label = { Text(p.symbol) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                snapshot?.error?.let { "⚠ $it" }
                    ?: "Updating every second — the fastest Trading 212 allows.",
                style = MaterialTheme.typography.bodySmall,
                color = if (snapshot?.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
