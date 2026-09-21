package com.flixtown.tv.data

import android.content.Context
import com.flixtown.tv.core.BackendConstants
import com.flixtown.tv.core.NetworkModule
import com.flixtown.tv.core.SafeLog
import com.flixtown.tv.data.model.RemoteConfig
import com.flixtown.tv.data.model.RemoteConfigDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Loads the backend-controlled app configuration.
 *
 * Startup reads [getCached] synchronously so the UI never blocks on a network
 * round trip, then calls [refresh] in the background. A failed or malformed
 * response from [refresh] is discarded outright and the last known good
 * config on disk is left untouched, so a backend hiccup can never brick the
 * app for customers who already paired successfully.
 */
class ConfigRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCached(): RemoteConfig? {
        val json = prefs.getString(KEY_CACHED_CONFIG, null) ?: return null
        return try {
            NetworkModule.gson.fromJson(json, RemoteConfig::class.java)
        } catch (e: Exception) {
            SafeLog.w(TAG, "Cached config was corrupt, ignoring", e)
            null
        }
    }

    suspend fun refresh(): Result<RemoteConfig> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(BackendConstants.CONFIG_ENDPOINT).get().build()
            NetworkModule.client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    return@withContext Result.failure(IllegalStateException("Config request failed: HTTP ${response.code}"))
                }
                val dto = NetworkModule.gson.fromJson(body, RemoteConfigDto::class.java)
                val config = RemoteConfig.fromDto(dto, System.currentTimeMillis())
                    ?: return@withContext Result.failure(IllegalStateException("Config response was malformed"))

                prefs.edit().putString(KEY_CACHED_CONFIG, NetworkModule.gson.toJson(config)).apply()
                Result.success(config)
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Config refresh failed, keeping last known good config", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "ConfigRepository"
        private const val PREFS_NAME = "flixtown_prefs"
        private const val KEY_CACHED_CONFIG = "cached_remote_config"
    }
}
