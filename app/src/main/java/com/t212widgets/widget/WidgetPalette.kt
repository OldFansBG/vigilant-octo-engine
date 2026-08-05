package com.t212widgets.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider

/**
 * Colours for one widget instance, resolved from its [WidgetTheme].
 *
 * Glance 1.1 has no day/night colour provider outside its own Material theme, so the night
 * state is read from the configuration when the widget is composed and baked into fixed
 * colours. Widgets are recomposed on every refresh — and the system sends an update on a
 * configuration change anyway — so a light/dark switch is picked up promptly.
 *
 * SYSTEM follows the phone. The fixed themes pin both sides, so a widget the user explicitly
 * set to dark stays dark on a light home screen. Gain and loss colours are paired with an
 * explicit +/− on every number, so the figures stay readable without relying on hue alone.
 */
class WidgetPalette(theme: WidgetTheme, accentColour: Long, systemDark: Boolean) {

    private val dark = when (theme) {
        WidgetTheme.DARK, WidgetTheme.TRANSPARENT -> true
        WidgetTheme.LIGHT -> false
        WidgetTheme.SYSTEM -> systemDark
    }

    private fun pick(light: Long, night: Long) = ColorProvider(Color(if (dark) night else light))

    val background: ColorProvider = when (theme) {
        WidgetTheme.TRANSPARENT -> ColorProvider(Color(0x40000000))
        else -> pick(0xFFFFFFFF, 0xFF15181D)
    }

    val onBackground: ColorProvider = when (theme) {
        WidgetTheme.TRANSPARENT -> ColorProvider(Color(0xFFFFFFFF))
        else -> pick(0xFF14161A, 0xFFF2F4F7)
    }

    val muted: ColorProvider = when (theme) {
        WidgetTheme.TRANSPARENT -> ColorProvider(Color(0xCCFFFFFF))
        else -> pick(0xFF5F6B7A, 0xFF98A2B3)
    }

    val gain: ColorProvider = pick(0xFF177245, 0xFF4ADE80)
    val loss: ColorProvider = pick(0xFFC02626, 0xFFFB7185)
    val accent: ColorProvider = ColorProvider(Color(accentColour))

    /** Tint for a value cell: gain, loss, or plain text when colouring is off or neutral. */
    fun forDirection(direction: Int, colourEnabled: Boolean): ColorProvider = when {
        !colourEnabled || direction == 0 -> onBackground
        direction > 0 -> gain
        else -> loss
    }

    companion object {
        fun of(config: WidgetConfig, systemDark: Boolean) =
            WidgetPalette(config.theme, config.accent.argb, systemDark)

        fun isSystemDark(context: Context): Boolean =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }
}
