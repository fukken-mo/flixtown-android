package com.flixtown.tv.data.model

import com.google.gson.annotations.SerializedName

// ---- POST /api/v1/pair/start.php ----

data class PairStartResponseDto(
    @SerializedName("pairing_id") val pairingId: String?,
    @SerializedName("public_code") val publicCode: String?,
    @SerializedName("poll_token") val pollToken: String?,
    @SerializedName("expires_in_seconds") val expiresInSeconds: Int?,
    @SerializedName("error") val error: String?
)

data class PairingSession(
    val pairingId: String,
    val publicCode: String,
    val pollToken: String,
    val expiresAtMillis: Long
)

// ---- POST /api/v1/pair/status.php ----

data class PairStatusResponseDto(
    @SerializedName("status") val status: String?, // pending | completed | expired | error
    @SerializedName("xtream_username") val xtreamUsername: String?,
    @SerializedName("xtream_password") val xtreamPassword: String?,
    @SerializedName("temp_device_token") val tempDeviceToken: String?,
    @SerializedName("error") val error: String?
)

sealed interface PairingPollResult {
    data object Pending : PairingPollResult
    data class Completed(
        val xtreamUsername: String,
        val xtreamPassword: String,
        val tempDeviceToken: String
    ) : PairingPollResult

    data object Expired : PairingPollResult
    data class Error(val message: String) : PairingPollResult
}

// ---- POST /api/v1/pair/ack.php ----

data class PairAckResponseDto(
    @SerializedName("device_token") val deviceToken: String?,
    @SerializedName("error") val error: String?
)

// ---- POST /api/v1/auth/register.php ----

data class RegisterDeviceResponseDto(
    @SerializedName("device_token") val deviceToken: String?,
    @SerializedName("status") val status: String?, // active | expired | invalid
    @SerializedName("error") val error: String?
)

sealed interface AuthOutcome {
    data class Success(val deviceToken: String, val accountActive: Boolean) : AuthOutcome
    data class InvalidCredentials(val message: String) : AuthOutcome
    data class NetworkError(val message: String) : AuthOutcome
}
