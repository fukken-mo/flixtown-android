package com.flixtown.tv.ui.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.flixtown.tv.core.SafeLog

/**
 * ExoPlayer cannot play a youtube.com/youtu.be URL directly (it's an HTML
 * page, not a media container), so a YouTube trailer opens in the
 * YouTube app or browser instead — the standard approach IPTV/TV apps use
 * rather than embedding a full YouTube player.
 */
fun openYouTubeVideo(context: Context, videoId: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        SafeLog.w("YouTubeIntent", "No app could handle the YouTube trailer intent", e)
    }
}
