package com.flixtown.tv.data.model

import com.google.gson.annotations.SerializedName

/** Raw shape returned by GET /api/v1/config.php. */
data class RemoteConfigDto(
    @SerializedName("app_name") val appName: String?,
    @SerializedName("xtream_base_url") val xtreamBaseUrl: String?,
    @SerializedName("maintenance_mode") val maintenanceMode: Boolean?,
    @SerializedName("maintenance_message") val maintenanceMessage: String?,
    @SerializedName("logo_url") val logoUrl: String?,
    @SerializedName("intro_enabled") val introEnabled: Boolean?,
    @SerializedName("intro_video_url") val introVideoUrl: String?,
    @SerializedName("min_app_version_code") val minAppVersionCode: Int?,
    @SerializedName("latest_app_version_code") val latestAppVersionCode: Int?,
    @SerializedName("force_update") val forceUpdate: Boolean?,
    @SerializedName("update_url") val updateUrl: String?,
    @SerializedName("cashapp_username") val cashAppUsername: String?,
    @SerializedName("cashapp_url") val cashAppUrl: String?,
    @SerializedName("renewal_prices") val renewalPrices: Map<String, Double>?,
    @SerializedName("announcements") val announcements: List<String>?,
    @SerializedName("tmdb_enabled") val tmdbEnabled: Boolean?
)

/** Validated, app-facing config. Only ever built from a [RemoteConfigDto] that passed validation. */
data class RemoteConfig(
    val appName: String,
    val xtreamBaseUrl: String,
    val maintenanceMode: Boolean,
    val maintenanceMessage: String?,
    val logoUrl: String?,
    val introEnabled: Boolean,
    val introVideoUrl: String?,
    val minAppVersionCode: Int,
    val latestAppVersionCode: Int,
    val forceUpdate: Boolean,
    val updateUrl: String?,
    val cashAppUsername: String,
    val cashAppUrl: String,
    val renewalPrices: Map<String, Double>,
    val announcements: List<String>,
    val tmdbEnabled: Boolean,
    val fetchedAtMillis: Long
) {
    companion object {
        /**
         * A malformed or partial response must never destroy the last known
         * good config, so validation happens up front: only a DTO with every
         * required field returns a config; anything else is rejected wholesale.
         */
        fun fromDto(dto: RemoteConfigDto, fetchedAtMillis: Long): RemoteConfig? {
            val xtreamUrl = dto.xtreamBaseUrl?.trim()
            if (dto.appName.isNullOrBlank() || xtreamUrl.isNullOrBlank()) return null
            if (!xtreamUrl.startsWith("http://") && !xtreamUrl.startsWith("https://")) return null

            return RemoteConfig(
                appName = dto.appName,
                xtreamBaseUrl = xtreamUrl.trimEnd('/'),
                maintenanceMode = dto.maintenanceMode ?: false,
                maintenanceMessage = dto.maintenanceMessage,
                logoUrl = dto.logoUrl,
                introEnabled = dto.introEnabled ?: false,
                introVideoUrl = dto.introVideoUrl,
                minAppVersionCode = dto.minAppVersionCode ?: 1,
                latestAppVersionCode = dto.latestAppVersionCode ?: 1,
                forceUpdate = dto.forceUpdate ?: false,
                updateUrl = dto.updateUrl,
                cashAppUsername = dto.cashAppUsername ?: "\$streamtownofficial",
                cashAppUrl = dto.cashAppUrl ?: "https://cash.app/\$streamtownofficial",
                renewalPrices = dto.renewalPrices ?: emptyMap(),
                announcements = dto.announcements ?: emptyList(),
                tmdbEnabled = dto.tmdbEnabled ?: false,
                fetchedAtMillis = fetchedAtMillis
            )
        }
    }
}
