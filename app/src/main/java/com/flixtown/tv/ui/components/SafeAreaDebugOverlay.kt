package com.flixtown.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        // Large, high-contrast, and boxed on its own opaque dark panel —
        // small light-green labelMedium text directly over a backdrop image
        // is not legible from normal TV viewing distance. This panel is
        // deliberately readable from across a room, same bar as everything
        // else on this screen.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(16.dp)
        ) {
            Text(
                text = "safeHorizontal = $safeHorizontal",
                color = Color(0xFF39FF14),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "(green lines = guide at safeHorizontal from each edge)",
                color = Color(0xFF39FF14),
                fontSize = 16.sp
            )
            Box(modifier = Modifier.padding(top = 8.dp)) {
                Column {
                    measurements.entries.sortedBy { it.key }.forEach { (label, xDp) ->
                        Text(
                            text = "%-16s x = %.1fdp".format(label, xDp),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
