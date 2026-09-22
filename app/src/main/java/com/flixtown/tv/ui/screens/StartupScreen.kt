package com.flixtown.tv.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.flixtown.tv.R
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurfaceElevated

/**
 * Deliberately minimal: this screen is on screen only as long as the config
 * cache lookup (instant) or, on first launch only, a single network round
 * trip takes.
 */
@Composable
fun StartupScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FtBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.flixtown_logo),
                contentDescription = "Flix Town",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(120.dp)
            )
            Box(modifier = Modifier.height(24.dp))
            PulsingBar()
        }
    }
}

@Composable
private fun PulsingBar() {
    val transition = rememberInfiniteTransition(label = "startup-pulse")
    val fraction by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "startup-pulse-fraction"
    )

    Box(
        modifier = Modifier
            .width(160.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(FtSurfaceElevated)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = (160 * (1 - fraction)).dp)
                .clip(RoundedCornerShape(2.dp))
                .background(FtAccent)
        )
    }
}
