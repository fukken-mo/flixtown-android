package com.flixtown.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * TEMPORARY diagnostic for the Details-screen edge-spacing investigation —
 * NOT a permanent part of the design. Flip [SHOW_SAFE_AREA_GUIDES] to false
 * (or delete this file + its call sites) once the real on-device X position
 * has been confirmed against FlixSpacing.safeHorizontal.
 *
 * Draws a bright vertical line exactly [SafeAreaDebugOverlay]'s
 * [safeHorizontal] in from each screen edge, plus a small on-screen readout
 * of every measured element's ACTUAL runtime X position (via
 * [reportXPosition]) — so the padding value can be checked against what the
 * TV is actually rendering, not just what the source claims it should be.
 * Every measurement is real (onGloballyPositioned + positionInRoot,
 * converted through the real screen density), not inferred from source
 * reading.
 */
const val SHOW_SAFE_AREA_GUIDES = true

/** Records this composable's own left-edge X position (in dp, relative to the screen root) under [label]. */
@Composable
fun Modifier.reportXPosition(label: String, measurements: MutableMap<String, Float>): Modifier {
    val density = LocalDensity.current
    return this.onGloballyPositioned { coordinates ->
        val xDp = with(density) { coordinates.positionInRoot().x.toDp().value }
        measurements[label] = xDp
    }
}

@Composable
fun rememberSafeAreaMeasurements(): MutableMap<String, Float> = remember { mutableStateMapOf() }

/** Caller must size this (e.g. `Modifier.fillMaxSize()`) to match the screen root it's measuring against. */
@Composable
fun SafeAreaDebugOverlay(
    safeHorizontal: Dp,
    measurements: Map<String, Float>,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val leftX = safeHorizontal.toPx()
            val rightX = size.width - safeHorizontal.toPx()
            drawLine(
                color = Color(0xFF39FF14),
                start = Offset(leftX, 0f),
                end = Offset(leftX, size.height),
                strokeWidth = 3f
            )
            drawLine(
                color = Color(0xFF39FF14),
                start = Offset(rightX, 0f),
                end = Offset(rightX, size.height),
                strokeWidth = 3f
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 8.dp, start = 8.dp)
        ) {
            Text(
                text = "safeHorizontal=$safeHorizontal (guide lines)",
                color = Color(0xFF39FF14),
                style = MaterialTheme.typography.labelMedium
            )
            measurements.entries.sortedBy { it.key }.forEach { (label, xDp) ->
                Text(
                    text = "$label.x = %.1fdp".format(xDp),
                    color = Color(0xFF39FF14),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
