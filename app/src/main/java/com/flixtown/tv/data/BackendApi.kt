package com.flixtown.tv.data

import com.flixtown.tv.core.BackendConstants
import com.flixtown.tv.core.NetworkModule
import com.flixtown.tv.core.SafeLog
import com.flixtown.tv.data.model.AuthOutcome
import com.flixtown.tv.data.model.PairAckResponseDto
import com.flixtown.tv.data.model.PairStartResponseDto
import com.flixtown.tv.data.model.PairStatusResponseDto
import com.flixtown.tv.data.model.PairingPollResult
import com.flixtown.tv.data.model.PairingSession
import com.flixtown.tv.data.model.RegisterDeviceResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request

/** Raw HTTP calls to the Flix Town control backend's activation/pairing APIs. */
class BackendApi {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun startPairing(installationId: String, deviceModel: String): Result<PairingSession> =
        withContext(Dispatchers.IO) {
            try {
                val payload = mapOf(
                    "installation_id" to installationId,
                    "device_model" to deviceModel
                )
                val body = NetworkModule.gson.toJson(payload).toRequestBody(jsonMediaType)
                val request = Request.Builder().url(BackendConstants.PAIR_START_ENDPOINT).post(body).build()

                NetworkModule.client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                        return@withContext Result.failure(IllegalStateException("Pairing start failed: HTTP ${response.code}"))
                    }
                    val dto = NetworkModule.gson.fromJson(responseBody, PairStartResponseDto::class.java)
                    if (dto.pairingId.isNullOrBlank() || dto.publicCode.isNullOrBlank() || dto.pollToken.isNullOrBlank()) {
                        return@withContext Result.failure(IllegalStateException(dto.error ?: "Malformed pairing response"))
                    }
                    val expiresAt = System.currentTimeMillis() + (dto.expiresInSeconds ?: 600) * 1000L
                    Result.success(
                        PairingSession(
                            pairingId = dto.pairingId,
                            publicCode = dto.publicCode,
                            pollToken = dto.pollToken,
                            expiresAtMillis = expiresAt
                        )
                    )
                }
            } catch (e: Exception) {
                SafeLog.w(TAG, "startPairing failed", e)
                Result.failure(e)
            }
        }

    suspend fun pollPairingStatus(pairingId: String, pollToken: String): PairingPollResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = mapOf("pairing_id" to pairingId, "poll_token" to pollToken)
                val body = NetworkModule.gson.toJson(payload).toRequestBody(jsonMediaType)
                val request = Request.Builder().url(BackendConstants.PAIR_STATUS_ENDPOINT).post(body).build()

                NetworkModule.client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrBlank()) {
                        return@withContext PairingPollResult.Error("Empty response from server")
                    }
                    val dto = NetworkModule.gson.fromJson(responseBody, PairStatusResponseDto::class.java)
                    when (dto.status) {
                        "pending" -> PairingPollResult.Pending
                        "completed" -> {
                            val username = dto.xtreamUsername
                            val password = dto.xtreamPassword
                            val tempToken = dto.tempDeviceToken
                            if (username.isNullOrBlank() || password.isNullOrBlank() || tempToken.isNullOrBlank()) {
                                PairingPollResult.Error("Completed pairing was missing credentials")
                            } else {
                                PairingPollResult.Completed(username, password, tempToken)
                            }
                        }
                        "expired" -> PairingPollResult.Expired
                        else -> PairingPollResult.Error(dto.error ?: "Unknown pairing status")
                    }
                }
            } catch (e: Exception) {
                SafeLog.w(TAG, "pollPairingStatus failed", e)
                PairingPollResult.Error(e.message ?: "Network error")
            }
        }

    /**
     * Final step of pairing: acknowledges receipt of credentials so the
     * backend can promote the temporary device token to a permanent session
     * and delete the pairing record. Retry-safe: acking twice for the same
     * pairing_id is idempotent on the backend.
     */
    suspend fun ackPairing(pairingId: String, pollToken: String, tempDeviceToken: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val payload = mapOf(
                    "pairing_id" to pairingId,
                    "poll_token" to pollToken,
                    "temp_device_token" to tempDeviceToken
                )
                val body = NetworkModule.gson.toJson(payload).toRequestBody(jsonMediaType)
                val request = Request.Builder().url(BackendConstants.PAIR_ACK_ENDPOINT).post(body).build()

                NetworkModule.client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                        return@withContext Result.failure(IllegalStateException("Pairing ack failed: HTTP ${response.code}"))
                    }
                    val dto = NetworkModule.gson.fromJson(responseBody, PairAckResponseDto::class.java)
                    val token = dto.deviceToken
                    if (token.isNullOrBlank()) {
                        Result.failure(IllegalStateException(dto.error ?: "Ack did not return a device token"))
                    } else {
                        Result.success(token)
                    }
                }
            } catch (e: Exception) {
                SafeLog.w(TAG, "ackPairing failed", e)
                Result.failure(e)
            }
        }

    /** Manual-login path: register/authorize this installation after a successful Xtream auth check. */
    suspend fun registerDevice(
        installationId: String,
        deviceModel: String,
        xtreamUsername: String,
        xtreamPassword: String
    ): AuthOutcome = withContext(Dispatchers.IO) {
        try {
            val payload = mapOf(
                "installation_id" to installationId,
                "device_model" to deviceModel,
                "xtream_username" to xtreamUsername,
                "xtream_password" to xtreamPassword
            )
            val body = NetworkModule.gson.toJson(payload).toRequestBody(jsonMediaType)
            val request = Request.Builder().url(BackendConstants.AUTH_REGISTER_ENDPOINT).post(body).build()

            NetworkModule.client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext AuthOutcome.NetworkError("Empty response from server")
                }
                val dto = NetworkModule.gson.fromJson(responseBody, RegisterDeviceResponseDto::class.java)
                val token = dto.deviceToken
                if (!response.isSuccessful || token.isNullOrBlank()) {
                    return@withContext AuthOutcome.InvalidCredentials(dto.error ?: "Registration failed")
                }
                AuthOutcome.Success(deviceToken = token, accountActive = dto.status.equals("active", ignoreCase = true))
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "registerDevice failed", e)
            AuthOutcome.NetworkError(e.message ?: "Network error")
        }
    }

    companion object {
        private const val TAG = "BackendApi"
    }
}
