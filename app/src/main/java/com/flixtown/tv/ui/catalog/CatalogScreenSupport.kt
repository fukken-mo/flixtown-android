package com.flixtown.tv.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.ui.components.PosterSkeletonCard
import com.flixtown.tv.ui.components.SecondaryActionButton
import com.flixtown.tv.ui.theme.FlixSpacing
import com.flixtown.tv.ui.theme.FtTextSecondary

const val GRID_COLUMNS = 5

@Composable
fun CatalogGridSkeleton() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        contentPadding = PaddingValues(horizontal = FlixSpacing.safeHorizontal, vertical = FlixSpacing.sectionGap - 8.dp),
        horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap),
        verticalArrangement = Arrangement.spacedBy(FlixSpacing.sectionGap)
    ) {
        items(15) { PosterSkeletonCard() }
    }
}

@Composable
fun CatalogErrorRetry(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = FlixSpacing.safeHorizontal, vertical = FlixSpacing.sectionGap - 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Couldn't load: $message",
                style = MaterialTheme.typography.bodyLarge,
                color = FtTextSecondary
            )
            SecondaryActionButton(text = "Retry", onClick = onRetry)
        }
    }
}
