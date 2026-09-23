package com.flixtown.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the player should auto-advance to the next episode (Up Next
 * overlay + countdown) — plain SharedPreferences, same shape as
 * [AccountStatusStore]. Defaults to enabled, matching every mainstream
 * streaming app's default.
 */
class AutoplaySettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _autoPlayEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val autoPlayEnabled: StateFlow<Boolean> = _autoPlayEnabled

    fun setAutoPlayEnabled(enabled: Boolean) {
        _autoPlayEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "flixtown_autoplay_settings"
        private const val KEY_ENABLED = "auto_play_next_episode"
    }
}
