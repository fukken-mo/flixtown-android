package com.flixtown.tv.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists everything sensitive: Xtream credentials, the Flix Town device
 * token, and in-flight pairing secrets. Every value is individually
 * AES/GCM-encrypted via [KeystoreCipher] before it touches SharedPreferences,
 * so the prefs file itself never holds plaintext secrets.
 *
 * Expiration must never wipe credentials here — only an explicit sign-out or
 * re-pair should call [clearAccount].
 */
class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Xtream account credentials -----------------------------------------

    fun saveXtreamCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_XTREAM_USERNAME, KeystoreCipher.encrypt(username))
            .putString(KEY_XTREAM_PASSWORD, KeystoreCipher.encrypt(password))
            .apply()
    }

    fun getXtreamUsername(): String? = decryptOrNull(KEY_XTREAM_USERNAME)
    fun getXtreamPassword(): String? = decryptOrNull(KEY_XTREAM_PASSWORD)

    fun hasXtreamCredentials(): Boolean =
        prefs.contains(KEY_XTREAM_USERNAME) && prefs.contains(KEY_XTREAM_PASSWORD)

    // --- Flix Town device session -------------------------------------------

    fun saveDeviceToken(token: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, KeystoreCipher.encrypt(token)).apply()
    }

    fun getDeviceToken(): String? = decryptOrNull(KEY_DEVICE_TOKEN)

    // --- In-flight QR pairing state ------------------------------------------
    // Persisted (encrypted) so an activity recreation mid-pairing can resume
    // polling instead of forcing the customer to scan a fresh QR code.

    fun savePendingPairing(pairingId: String, pollToken: String) {
        prefs.edit()
            .putString(KEY_PENDING_PAIRING_ID, KeystoreCipher.encrypt(pairingId))
            .putString(KEY_PENDING_POLL_TOKEN, KeystoreCipher.encrypt(pollToken))
            .apply()
    }

    fun getPendingPairingId(): String? = decryptOrNull(KEY_PENDING_PAIRING_ID)
    fun getPendingPollToken(): String? = decryptOrNull(KEY_PENDING_POLL_TOKEN)

    fun clearPendingPairing() {
        prefs.edit()
            .remove(KEY_PENDING_PAIRING_ID)
            .remove(KEY_PENDING_POLL_TOKEN)
            .apply()
    }

    /** Explicit sign-out / re-pair only. Never called just because an account expired. */
    fun clearAccount() {
        prefs.edit()
            .remove(KEY_XTREAM_USERNAME)
            .remove(KEY_XTREAM_PASSWORD)
            .remove(KEY_DEVICE_TOKEN)
            .apply()
    }

    private fun decryptOrNull(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            KeystoreCipher.decrypt(stored)
        } catch (_: Exception) {
            // Key invalidated (e.g. lock screen removed) or corrupted value: treat as absent
            // rather than crash the app.
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "flixtown_secure_prefs"
        private const val KEY_XTREAM_USERNAME = "xtream_username"
        private const val KEY_XTREAM_PASSWORD = "xtream_password"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_PENDING_PAIRING_ID = "pending_pairing_id"
        private const val KEY_PENDING_POLL_TOKEN = "pending_poll_token"
    }
}
