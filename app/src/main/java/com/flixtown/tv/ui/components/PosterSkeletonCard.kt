package com.flixtown.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtSurfaceElevated

/**
 * A poster-shaped skeleton placeholder (no fake copy, no gray debug box):
 * a subtly gradient-shaded 2:3 poster tile plus two skeleton text bars.
 * Used only while the catalog is actually loading — once real data
 * arrives, rows render [PosterCard] instead.
 */
@Composable
fun PosterSkeletonCard() {
    FlixFocusSurface(
        onClick = {},
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.width(DEFAULT_POSTER_WIDTH)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.verticalGradient(listOf(FtSurfaceElevated, FtSurface)))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(FtSurface)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            SkeletonBar(width = 120.dp)
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonBar(width = 76.dp)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SkeletonBar(width: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(9.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(FtSurfaceElevated)
    )
}
