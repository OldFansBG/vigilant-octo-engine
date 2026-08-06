package com.t212widgets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.t212widgets.data.Sample
import kotlin.math.abs

/** Vertical extent of the plotted series, with a little padding above and below. */
private data class VerticalScale(val min: Double, val range: Double)

private fun scaleOf(samples: List<Sample>): VerticalScale {
    var min = samples.minOf { it.value }
    var max = samples.maxOf { it.value }
    if (max - min < 1e-9) {
        // A dead-flat series would divide by zero; give it headroom so the line sits in the
        // middle rather than collapsing onto an edge.
        min -= 1.0
        max += 1.0
    }
    val pad = (max - min) * 0.12
    return VerticalScale(min - pad, (max - min) + pad * 2)
}

/**
 * A live line chart you can drag a finger across to read exact values.
 *
 * Gesture handling uses [awaitEachGesture] rather than `detectDragGestures` on purpose: the
 * latter only fires once the touch slop is exceeded, so a tap-and-hold would show nothing.
 * Here the crosshair appears on the initial press and follows every move, which is how a
 * trading app behaves. Events are consumed so a parent scroll container cannot steal the
 * gesture halfway through a scrub.
 *
 * The selected sample is resolved during composition and only *drawn* in the canvas — never
 * computed there. Writing state from a draw scope invites a draw/recompose loop, and the
 * parent needs the value anyway to put it in the headline.
 *
 * Rendering is deliberately plain: no chart library, no external dependency, just a Path.
 */
@Composable
fun LiveChart(
    samples: List<Sample>,
    lineColour: Color,
    modifier: Modifier = Modifier,
    onScrubChange: (Sample?) -> Unit = {},
) {
    var scrubX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }

    val scrubbed: Sample? = remember(scrubX, samples, widthPx) {
        val x = scrubX
        if (x == null || widthPx <= 0 || samples.size < 2) {
            null
        } else {
            val first = samples.first().atMs
            val span = (samples.last().atMs - first).coerceAtLeast(1L)
            val targetMs = first + ((x / widthPx).coerceIn(0f, 1f) * span).toLong()
            samples.minBy { abs(it.atMs - targetMs) }
        }
    }

    LaunchedEffect(scrubbed) { onScrubChange(scrubbed) }

    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // requireUnconsumed = false so the crosshair still appears if something
                    // above us has already looked at the down event.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scrubX = down.position.x
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        scrubX = pointer.position.x
                        pointer.consume()
                    }
                    scrubX = null
                }
            },
    ) {
        val density = LocalDensity.current
        Canvas(Modifier.fillMaxSize()) {
            if (samples.size < 2) return@Canvas

            val minAt = samples.first().atMs
            val spanMs = (samples.last().atMs - minAt).coerceAtLeast(1L)
            val scale = scaleOf(samples)

            fun xFor(atMs: Long) = ((atMs - minAt).toFloat() / spanMs) * size.width
            fun yFor(v: Double) = (1f - ((v - scale.min) / scale.range).toFloat()) * size.height

            val line = Path()
            val fill = Path()
            samples.forEachIndexed { i, s ->
                val x = xFor(s.atMs)
                val y = yFor(s.value)
                if (i == 0) {
                    line.moveTo(x, y)
                    fill.moveTo(x, size.height)
                    fill.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    fill.lineTo(x, y)
                }
            }
            fill.lineTo(xFor(samples.last().atMs), size.height)
            fill.close()

            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    listOf(lineColour.copy(alpha = 0.28f), lineColour.copy(alpha = 0f)),
                ),
            )
            drawPath(
                path = line,
                color = lineColour,
                style = Stroke(width = with(density) { 2.dp.toPx() }),
            )

            // The latest point gets a dot, so "now" is obvious at a glance.
            drawCircle(
                color = lineColour,
                radius = with(density) { 3.5.dp.toPx() },
                center = Offset(xFor(samples.last().atMs), yFor(samples.last().value)),
            )

            scrubbed?.let { s ->
                drawCrosshair(xFor(s.atMs), yFor(s.value), lineColour, density.density)
            }
        }
    }
}

private fun DrawScope.drawCrosshair(x: Float, y: Float, colour: Color, density: Float) {
    drawLine(
        color = colour.copy(alpha = 0.45f),
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 1f * density,
    )
    drawCircle(Color.White, radius = 5.5f * density, center = Offset(x, y))
    drawCircle(colour, radius = 4f * density, center = Offset(x, y))
}
