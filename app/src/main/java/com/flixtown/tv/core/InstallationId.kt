package com.flixtown.tv.core

import android.content.Context
import java.util.UUID

/**
 * A stable, privacy-safe identifier for this app installation.
 *
 * Deliberately NOT derived from hardware (MAC, serial, IMEI, fingerprint):
 * those are either unavailable on modern Android, require sensitive
 * permissions, or double as tracking identifiers. A random UUID generated on
 * first launch and persisted locally is sufficient to key a device session on
 * the backend, and resets cleanly on reinstall.
 */
object InstallationId {
    private const val PREFS_NAME = "flixtown_prefs"
    private const val KEY_INSTALLATION_ID = "installation_id"

    @Volatile
    private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_INSTALLATION_ID, null)
            val id = existing ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_INSTALLATION_ID, it).apply()
            }
            cached = id
            return id
        }
    }
}
