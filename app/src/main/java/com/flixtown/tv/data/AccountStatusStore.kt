package com.flixtown.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Caches the Xtream account expiration date for display on Home (a subtle
 * "subscription active until ..." label). Not a secret — plain
 * SharedPreferences is fine here, unlike credentials/tokens.
 *
 * Populated from [StartupViewModel]'s existing background Xtream validation
 * check; nothing about the auth/routing flow itself changes to support this.
 */
class AccountStatusStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _expiresAtEpochSeconds = MutableStateFlow(readCached())
    val expiresAtEpochSeconds: StateFlow<Long?> = _expiresAtEpochSeconds

    fun save(epochSeconds: Long?) {
        _expiresAtEpochSeconds.value = epochSeconds
        prefs.edit().apply {
            if (epochSeconds == null) remove(KEY_EXP) else putLong(KEY_EXP, epochSeconds)
        }.apply()
    }

    private fun readCached(): Long? {
        val value = prefs.getLong(KEY_EXP, -1L)
        return if (value <= 0) null else value
    }

    companion object {
        private const val PREFS_NAME = "flixtown_account_status"
        private const val KEY_EXP = "exp_date_epoch_seconds"
    }
}
