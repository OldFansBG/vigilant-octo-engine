package com.t212widgets.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColours = lightColorScheme(
    primary = Color(0xFF2F6FED),
    secondary = Color(0xFF546E7A),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
)

private val DarkColours = darkColorScheme(
    primary = Color(0xFF7FA8FF),
    secondary = Color(0xFFA8BCC6),
    background = Color(0xFF101317),
    surface = Color(0xFF181C22),
)

/** Gains and losses, matched to the widget palette so the app and the home screen agree. */
object PnlColours {
    val gainLight = Color(0xFF177245)
    val gainDark = Color(0xFF4ADE80)
    val lossLight = Color(0xFFC02626)
    val lossDark = Color(0xFFFB7185)

    @Composable
    fun forValue(value: Double): Color {
        val dark = isSystemInDarkTheme()
        return when {
            value > 0 -> if (dark) gainDark else gainLight
            value < 0 -> if (dark) lossDark else lossLight
            else -> MaterialTheme.colorScheme.onSurface
        }
    }
}

@Composable
fun T212Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColours
        else -> LightColours
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
