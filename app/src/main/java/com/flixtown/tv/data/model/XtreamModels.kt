package com.flixtown.tv.data.model

import com.google.gson.annotations.SerializedName

/** Shape of the `user_info` object from Xtream's player_api.php. */
data class XtreamUserInfoDto(
    @SerializedName("auth") val auth: Int?,
    @SerializedName("status") val status: String?,
    @SerializedName("exp_date") val expDateEpochSeconds: String?,
    @SerializedName("max_connections") val maxConnections: String?,
    @SerializedName("message") val message: String?
)

data class XtreamAuthResponseDto(
    @SerializedName("user_info") val userInfo: XtreamUserInfoDto?
)

sealed interface XtreamAuthResult {
    data class Success(
        val status: String,
        val expiresAtEpochSeconds: Long?,
        val maxConnections: Int?,
        val isActive: Boolean
    ) : XtreamAuthResult

    data class InvalidCredentials(val message: String?) : XtreamAuthResult
    data class NetworkError(val cause: Throwable) : XtreamAuthResult
    data class ServerError(val message: String) : XtreamAuthResult
}
