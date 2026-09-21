package com.flixtown.tv.data

import com.flixtown.tv.core.NetworkModule
import com.flixtown.tv.core.SafeLog
import com.flixtown.tv.data.model.XtreamAuthResponseDto
import com.flixtown.tv.data.model.XtreamAuthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Talks directly to the customer's Xtream Codes panel. Xtream remains the
 * single source of truth for account status/expiration, independent of
 * anything cached on the Flix Town backend.
 */
class XtreamRepository {

    suspend fun authenticate(baseUrl: String, username: String, password: String): XtreamAuthResult =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/player_api.php".toHttpUrlOrNull()
                    ?: return@withContext XtreamAuthResult.ServerError("Invalid Xtream server URL")

                val requestUrl = url.newBuilder()
                    .addQueryParameter("username", username)
                    .addQueryParameter("password", password)
                    .build()

                val request = Request.Builder().url(requestUrl).get().build()
                NetworkModule.client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body.isNullOrBlank()) {
                        return@withContext XtreamAuthResult.ServerError("Xtream server returned HTTP ${response.code}")
                    }

                    val dto = NetworkModule.gson.fromJson(body, XtreamAuthResponseDto::class.java)
                    val userInfo = dto.userInfo
                        ?: return@withContext XtreamAuthResult.ServerError("Unexpected Xtream response")

                    if (userInfo.auth != 1) {
                        return@withContext XtreamAuthResult.InvalidCredentials(userInfo.message)
                    }

                    val status = userInfo.status ?: "Unknown"
                    XtreamAuthResult.Success(
                        status = status,
                        expiresAtEpochSeconds = userInfo.expDateEpochSeconds?.toLongOrNull(),
                        maxConnections = userInfo.maxConnections?.toIntOrNull(),
                        isActive = status.equals("Active", ignoreCase = true)
                    )
                }
            } catch (e: Exception) {
                SafeLog.w(TAG, "Xtream authentication failed", e)
                XtreamAuthResult.NetworkError(e)
            }
        }

    companion object {
        private const val TAG = "XtreamRepository"
    }
}
