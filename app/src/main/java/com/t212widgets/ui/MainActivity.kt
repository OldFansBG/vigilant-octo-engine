package com.t212widgets.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.t212widgets.api.ApiError
import com.t212widgets.api.ApiResult
import com.t212widgets.api.T212Client
import com.t212widgets.core.Credentials
import com.t212widgets.core.Environment
import com.t212widgets.core.Format
import com.t212widgets.core.SecureStore
import com.t212widgets.core.Settings
import com.t212widgets.core.accountCurrency
import com.t212widgets.core.environment
import com.t212widgets.core.liveMode
import com.t212widgets.core.onlyWhenScreenOn
import com.t212widgets.core.refreshIntervalSec
import com.t212widgets.core.refreshOnUnlock
import com.t212widgets.core.requireAuthToReveal
import com.t212widgets.data.PortfolioRepository
import com.t212widgets.refresh.RefreshScheduler
import com.t212widgets.widget.placedWidgetCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup and status screen.
 *
 * The window carries `FLAG_SECURE`, so the credentials cannot appear in a screenshot, a
 * screen recording or the recent-apps thumbnail. It extends [FragmentActivity] because
 * `BiometricPrompt` needs a fragment host.
 */
class MainActivity : FragmentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            T212Theme {
                SetupScreen(
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    authenticate = ::authenticate,
                )
            }
        }
    }

    /**
     * Device credential / biometric gate for destructive actions. Falls through to the
     * action when the device has no lock configured — there would be nothing to verify
     * against, and blocking the user out of their own settings helps nobody.
     */
    private fun authenticate(reason: String, onSuccess: () -> Unit) {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            onSuccess()
            return
        }
        val prompt = BiometricPrompt(
            this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Confirm it's you")
                .setSubtitle(reason)
                .setAllowedAuthenticators(allowed)
                .build(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(
    onRequestNotifications: () -> Unit,
    authenticate: (String, () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyInput by remember { mutableStateOf("") }
    var secretInput by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(SecureStore.hasApiKey(context)) }
    var fingerprint by remember { mutableStateOf(SecureStore.fingerprint(context)) }
    var environment by remember { mutableStateOf(context.environment) }
    var interval by remember { mutableStateOf(context.refreshIntervalSec) }
    var screenOnOnly by remember { mutableStateOf(context.onlyWhenScreenOn) }
    var onUnlock by remember { mutableStateOf(context.refreshOnUnlock) }
    var live by remember { mutableStateOf(context.liveMode) }
    var lockSettings by remember { mutableStateOf(context.requireAuthToReveal) }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var helpText by remember { mutableStateOf<List<String>?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(PortfolioRepository.cachedSnapshot(context)) }
    val widgetCount = remember { placedWidgetCount(context) }

    LaunchedEffect(hasKey) {
        if (hasKey) {
            RefreshScheduler.reconcile(context)
            snapshot = PortfolioRepository.cachedSnapshot(context)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Trading 212 widgets") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ------------------------------------------------------------ API key
            SectionCard("API credentials") {
                Text(
                    "In the Trading 212 app: Settings → API (Beta) → generate a key. You get " +
                        "an API Key and an API Secret — the secret is shown once, so copy it " +
                        "there and then. Enable the read permissions you want; leave the " +
                        "ordering ones off and the credentials cannot place a trade.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Older keys issued without a secret still work — leave the secret box " +
                        "empty for those.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))

                if (hasKey) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Credentials stored", fontWeight = FontWeight.Medium)
                            Text(
                                "${fingerprint ?: "••••"} · ${environment.label}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = {
                            val remove = {
                                SecureStore.clear(context)
                                PortfolioRepository.invalidate(context)
                                RefreshScheduler.reconcile(context)
                                hasKey = false
                                fingerprint = null
                                snapshot = null
                                status = "Removed from this device"
                                statusIsError = false
                            }
                            if (lockSettings) {
                                authenticate("Remove the stored credentials", remove)
                            } else {
                                remove()
                            }
                        }) { Text("Remove") }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(if (hasKey) "Replace — API Key" else "API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secretInput,
                    onValueChange = { secretInput = it },
                    label = { Text("API Secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Environment.entries.forEach { env ->
                        FilterChip(
                            selected = environment == env,
                            onClick = {
                                environment = env
                                context.environment = env
                                PortfolioRepository.invalidate(context)
                            },
                            label = { Text(env.label) },
                        )
                    }
                }
                Text(
                    "Just a starting guess — both are tried automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(12.dp))
                val entered = Credentials.of(keyInput, secretInput)
                Button(
                    enabled = !busy && (!entered.isEmpty || hasKey),
                    onClick = {
                        val proceed: () -> Unit = {
                            busy = true
                            status = null
                            helpText = null
                            scope.launch {
                                val credentials = if (entered.isEmpty) {
                                    SecureStore.readCredentials(context)
                                } else {
                                    entered
                                }
                                val result = withContext(Dispatchers.IO) {
                                    credentials?.let {
                                        T212Client(context).detectConnection(it, environment)
                                    }
                                }
                                busy = false
                                when (result) {
                                    null -> {
                                        status = "Nothing to test"
                                        statusIsError = true
                                    }
                                    is ApiResult.Ok -> {
                                        val connection = result.value
                                        if (!entered.isEmpty) {
                                            SecureStore.saveCredentials(context, entered)
                                            PortfolioRepository.invalidate(context)
                                        }
                                        // The probe may have found the credentials belong to
                                        // the other environment; adopt what actually worked.
                                        context.environment = connection.environment
                                        environment = connection.environment
                                        if (connection.currencyCode.isNotEmpty()) {
                                            context.accountCurrency = connection.currencyCode
                                        }
                                        keyInput = ""
                                        secretInput = ""
                                        hasKey = true
                                        fingerprint = SecureStore.fingerprint(context)
                                        statusIsError = false
                                        val ccy = connection.currencyCode
                                        status = "Connected · ${connection.environment.label}" +
                                            if (ccy.isEmpty()) "" else " · $ccy"
                                        RefreshScheduler.reconcile(context)
                                        RefreshScheduler.refreshAndRedraw(context, force = true)
                                        snapshot = PortfolioRepository.cachedSnapshot(context)
                                    }
                                    is ApiResult.Err -> {
                                        statusIsError = true
                                        status = result.error.message
                                        helpText = troubleshoot(result.error, entered)
                                    }
                                }
                            }
                        }
                        // Replacing credentials that are already installed is the sensitive
                        // case; testing the existing ones changes nothing and needs no gate.
                        if (hasKey && !entered.isEmpty && lockSettings) {
                            authenticate("Replace the stored credentials", proceed)
                        } else {
                            proceed()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(if (entered.isEmpty && hasKey) "Test connection" else "Save and connect")
                }

                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = if (statusIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                helpText?.let { lines ->
                    Spacer(Modifier.height(8.dp))
                    lines.forEach { BulletText(it) }
                }
            }

            // ------------------------------------------------------------ status
            if (hasKey) {
                SectionCard("Live data") {
                    val s = snapshot
                    val summary = s?.summary
                    if (s == null || summary == null) {
                        Text(s?.error ?: "No data fetched yet.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val ccy = s.currency.ifEmpty { context.accountCurrency }
                        StatRow("Account value", Format.money(summary.totalValue, ccy))
                        StatRow("Investments", Format.money(summary.investmentsValue, ccy))
                        StatRow(
                            "Open P/L",
                            Format.signedMoney(summary.unrealizedProfitLoss, ccy),
                            value = summary.unrealizedProfitLoss,
                        )
                        StatRow("Free funds", Format.money(summary.availableToTrade, ccy))
                        StatRow("Holdings", s.positions.size.toString())
                        StatRow("Last update", Format.relativeTime(s.fetchedAtMs))
                        s.error?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            context.startActivity(Intent(context, LiveActivity::class.java))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open live view") }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Updates every second and charts the movement — drag a finger across " +
                            "the chart to read any moment.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                RefreshScheduler.refreshAndRedraw(context, force = true)
                                snapshot = PortfolioRepository.cachedSnapshot(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Refresh now") }
                }
            }

            // ------------------------------------------------------------ updates
            SectionCard("Update speed") {
                Text(
                    "Android will not let any widget refresh itself faster than every 30 " +
                        "minutes, which is why broker widgets feel stale. This app schedules " +
                        "its own updates instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Text("Refresh every ${Format.interval(interval)}", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Settings.INTERVAL_CHOICES.forEach { choice ->
                        FilterChip(
                            selected = interval == choice,
                            onClick = {
                                interval = choice
                                context.refreshIntervalSec = choice
                                RefreshScheduler.reconcile(context)
                            },
                            label = { Text(Format.interval(choice)) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    "Only while the screen is on",
                    "Nothing polls in your pocket. Combined with the setting below, the " +
                        "numbers are already current by the time you see the home screen.",
                    screenOnOnly,
                ) {
                    screenOnOnly = it
                    context.onlyWhenScreenOn = it
                    RefreshScheduler.reconcile(context)
                }
                ToggleRow(
                    "Refresh the moment you unlock",
                    "Fires an immediate update on unlock, so the first look is never stale.",
                    onUnlock,
                ) {
                    onUnlock = it
                    context.refreshOnUnlock = it
                }
                ToggleRow(
                    "Live mode (foreground service)",
                    "Polls on a fixed cadence that the system cannot defer. Adds a permanent " +
                        "notification and uses noticeably more battery. Android also limits " +
                        "this to about six hours a day, after which it hands back to the " +
                        "normal scheduler automatically.",
                    live,
                ) {
                    live = it
                    context.liveMode = it
                    if (it) onRequestNotifications()
                    RefreshScheduler.reconcile(context)
                }

                ExactAlarmNotice()
                BatteryOptimisationNotice()
            }

            // ------------------------------------------------------------ widgets
            SectionCard("Widgets") {
                Text(
                    if (widgetCount == 0) {
                        "No widgets placed yet. Long-press your home screen, choose Widgets, " +
                            "find “Trading 212 widgets”, and drop one on. You will be asked " +
                            "what it should show."
                    } else {
                        "$widgetCount widget${if (widgetCount == 1) "" else "s"} placed. Add more " +
                            "for different views — each one is configured separately."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "To change an existing widget, long-press it and tap Reconfigure (or " +
                        "remove and re-add it on launchers without that option).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ------------------------------------------------------------ security
            SectionCard("Security") {
                BulletText("The key and secret are encrypted together with AES-256-GCM using a key held in the Android Keystore — on most phones inside the secure element, where it cannot be extracted.")
                BulletText("Only the ciphertext is written to storage. Backups and cloud restore are disabled for this app, so nothing can leave the device that way.")
                BulletText("This screen sets FLAG_SECURE: no screenshots, no screen recording, and a blank thumbnail in the recents list.")
                BulletText("Traffic goes to trading212.com over HTTPS only. Cleartext is blocked at the platform level and redirects are never followed, so the credentials can never be replayed to another host.")
                BulletText("No analytics, no crash reporting, no server of ours in the middle.")
                BulletText("Release builds strip every logging call, so nothing can end up in logcat.")
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    "Require unlock to change credentials",
                    "Asks for your fingerprint, face or PIN before the stored credentials can " +
                        "be replaced or removed.",
                    lockSettings,
                ) {
                    lockSettings = it
                    context.requireAuthToReveal = it
                }
            }

            Text(
                "Not affiliated with, endorsed by, or connected to Trading 212. " +
                    "Figures come from your own account through the official public API and " +
                    "may lag the app — do not trade on them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Android 12+ needs explicit consent before an app may schedule alarms to the second. */
@Composable
private fun ExactAlarmNotice() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
    var granted by remember { mutableStateOf(alarmManager.canScheduleExactAlarms()) }
    LaunchedEffect(Unit) { granted = alarmManager.canScheduleExactAlarms() }
    if (granted) return

    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text("Alarms are approximate", fontWeight = FontWeight.Medium)
    Text(
        "Updates still happen, but the system may shift them by a few seconds. Allowing " +
            "exact alarms makes the interval you picked hold precisely.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = {
        runCatching {
            context.startActivity(
                Intent(ACTION_REQUEST_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        }
    }) { Text("Allow exact alarms") }
}

/** Battery optimisation is the single biggest cause of "my widget stopped updating". */
@Composable
private fun BatteryOptimisationNotice() {
    val context = LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    var exempt by remember {
        mutableStateOf(power?.isIgnoringBatteryOptimizations(context.packageName) ?: true)
    }
    LaunchedEffect(Unit) {
        exempt = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }
    if (exempt) return

    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text("Battery optimisation is on", fontWeight = FontWeight.Medium)
    Text(
        "Some phones — Samsung, Xiaomi, OnePlus and Huawei especially — will silently stop " +
            "background updates for optimised apps. Exempting this app is the difference " +
            "between updates every minute and updates when you happen to open something.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
    }) { Text("Open battery settings") }
}

// ------------------------------------------------------------------ small pieces

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, text: String, value: Double? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = value?.let { PnlColours.forValue(it) } ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BulletText(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("•", modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Referenced by value rather than through `android.provider.Settings` so the constant does
 * not drag an API-31 symbol into code paths that run on older releases.
 */
private const val ACTION_REQUEST_EXACT_ALARM = "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"

/**
 * What to actually try next, in the order most likely to help.
 *
 * A bare "401" is useless to someone who has just pasted credentials they believe are
 * correct, and by this point the app has already ruled things out on their behalf: the probe
 * tried both environments before giving up.
 */
private fun troubleshoot(error: ApiError, entered: Credentials): List<String>? = when (error) {
    is ApiError.Unauthorised -> buildList {
        if (entered.isLegacy) {
            add(
                "No API Secret was entered. Keys generated recently come as a Key *and* a " +
                    "Secret, and the pair is required — paste the secret into the second box.",
            )
            add(
                "If you no longer have the secret, generate a new key in Trading 212: the " +
                    "secret is only ever shown once, at creation.",
            )
        }
        add("Both Live and Practice were tried, so this is not an environment mix-up.")
        add(
            "Re-copy both values. Copying from a browser or chat often adds an invisible " +
                "character, which is not visible in a password field.",
        )
        add(
            "Check the key still exists: Trading 212 app → Settings → API (Beta). Generating " +
                "a new key invalidates the old one, and so can changing your password.",
        )
        add(
            "The API only works on Invest and Stocks ISA accounts — not CFD, and not SIPP.",
        )
        add(
            "If you restricted the key to specific IPs when creating it, your phone's mobile " +
                "or Wi-Fi address will not match. Generate one without an IP restriction.",
        )
    }
    is ApiError.Forbidden -> listOf(
        "The credentials are valid — they just are not allowed to read this data.",
        "In Trading 212 → Settings → API (Beta), edit the key and enable the account and " +
            "portfolio read permissions, then press Test connection again.",
    )
    is ApiError.RateLimited -> listOf(
        "Trading 212 caps the account summary at one request every 5 seconds, and the cap " +
            "is per account — every widget, this screen, and anything else you run against " +
            "the same account share it. Testing twice in quick succession is enough to hit " +
            "it. Nothing is wrong with your key.",
        "The app waits out the window on its own and carries on; you do not have to do " +
            "anything. If you see it constantly, raise the refresh interval below.",
    )
    is ApiError.Offline -> listOf(
        "The phone reports no working internet connection. Check Wi-Fi or mobile data.",
    )
    is ApiError.Timeout -> listOf(
        "The phone reached Trading 212 but got no answer in time — usually a slow " +
            "connection or a brief outage on their side. Try again in a moment.",
        "Your widgets keep showing the last good figures in the meantime.",
    )
    is ApiError.Unreachable -> listOf(
        "The request never reached Trading 212. Check the phone is online.",
        "A VPN or a filtering DNS service can block trading212.com — try turning it off.",
    )
    is ApiError.Http -> listOf(
        "Trading 212 returned an unexpected status. This is usually their end; try again " +
            "shortly.",
    )
    else -> null
}
