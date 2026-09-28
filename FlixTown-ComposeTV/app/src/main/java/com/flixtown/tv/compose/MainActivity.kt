package com.flixtown.tv.compose

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFE52254), background = Color(0xFF090C16),
                surface = Color(0xFF151B2A), onSurface = Color.White
            )) {
                HomeScreen(onPlay = { title ->
                    // Only launch playable entries; real catalog URLs come from the panel integration.
                    if (title.streamUrl.isNotBlank()) {
                        startActivity(Intent(this, PlayerActivity::class.java).apply {
                            putExtra(PlayerActivity.EXTRA_URL, title.streamUrl)
                            putExtra(PlayerActivity.EXTRA_TITLE, title.name)
                        })
                    }
                })
            }
        }
    }
}
