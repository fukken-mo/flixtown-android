package com.flixtown.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.flixtown.tv.data.model.CastMember
import com.flixtown.tv.data.model.imageUrl
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextMuted
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

private val PORTRAIT_SIZE = 96.dp
private const val PLACEHOLDER_COUNT = 6

/**
 * Horizontal actor row. [cast] is `null` while TMDB credits (or the plain
 * Xtream name fallback) are still being resolved — that renders quiet
 * placeholder portraits instead of nothing, so the section never pops in and
 * shifts everything below it. An empty (non-null) list hides the section
 * entirely: some titles genuinely have no cast data anywhere.
 */
@Composable
fun CastRow(cast: List<CastMember>?) {
    if (cast != null && cast.isEmpty()) return
    Column {
        SectionHeader(title = "Cast")
        Spacer(modifier = Modifier.height(16.dp))
        if (cast == null) {
            LazyRow(
                contentPadding = PaddingValues(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(PLACEHOLDER_COUNT) { PlaceholderChip() }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(cast, key = { it.id }) { member -> ActorChip(member) }
            }
        }
    }
}

@Composable
private fun PlaceholderChip() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(104.dp)) {
        Box(
            modifier = Modifier
                .size(PORTRAIT_SIZE)
                .clip(CircleShape)
                .background(FtSurfaceElevated)
        )
    }
}

@Composable
private fun ActorChip(member: CastMember) {
    // No scale/lift/zIndex: the selected state reads entirely from the
    // border + focusedContainerColor tv-material3 already drives natively,
    // same IBO-restrained treatment as every other focusable card.
    val imageUrl = member.imageUrl

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(104.dp)
    ) {
        Surface(
            onClick = {},
            modifier = Modifier.size(PORTRAIT_SIZE),
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = FtSurfaceElevated,
                contentColor = FtTextPrimary,
                focusedContainerColor = FtSurfaceElevated,
                focusedContentColor = FtTextPrimary
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(border = BorderStroke(1.5.dp, FtAccent), shape = CircleShape)
            )
        ) {
            if (imageUrl == null) {
                InitialsBox(member.name)
            } else {
                SubcomposeAsyncImage(
                    model = imageUrl,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (painter.state) {
                        is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                        is AsyncImagePainter.State.Error -> InitialsBox(member.name)
                        else -> Box(modifier = Modifier.fillMaxSize().background(FtSurfaceElevated))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = member.name,
            style = MaterialTheme.typography.labelMedium.copy(color = FtTextPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (!member.character.isNullOrBlank()) {
            Text(
                text = member.character,
                style = MaterialTheme.typography.labelMedium,
                color = FtTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun InitialsBox(name: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = initialsFor(name),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = FtTextMuted
        )
    }
}

private fun initialsFor(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
        parts.size == 1 -> parts.first().take(2).uppercase()
        else -> "?"
    }
}
