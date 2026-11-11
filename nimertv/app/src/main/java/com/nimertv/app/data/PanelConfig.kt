package com.nimertv.app.data

import android.content.Context
import com.nimertv.app.BuildConfig
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI

/*
 * Client for the Nimer TV panel's public configuration endpoint (API v1).
 *
 * Field names below were verified against the live endpoint
 * (api/v1/config.php, November 2026). `update` and `splash.active` were null and
 * `announcements` was empty at that time; their field names follow the client that
 * shipped with the panel and are parsed leniently.
 */

data class PanelServer(val name: String, val url: String, val priority: Int)

data class SplashImage(
    val id: String,
    val name: String,
    val url: String,
    val sha256: String,
    val startMs: Long,
    val endMs: Long,
)

data class UpdateInfo(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val mandatory: Boolean,
    val minSupportedVersionCode: Int,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val signerSha256: String,
)

data class Announcement(
    val key: String,
    val title: String,
    val message: String,
    val popup: Boolean,
    val dismissible: Boolean,
    val priority: Boolean,
    val startMs: Long,
    val endMs: Long,
) {
    fun activeAt(now: Long) = (startMs == 0L || startMs <= now) && (endMs == 0L || now < endMs)
}

data class Support(val whatsapp: String, val email: String, val phone: String, val website: String) {
    val isEmpty get() = whatsapp.isEmpty() && email.isEmpty() && phone.isEmpty() && website.isEmpty()
}

data class PanelConfig(
    val refreshMinutes: Int,
    val appName: String,
    val support: Support,
    val maintenanceEnabled: Boolean,
    val maintenanceMessage: String,
    val loginMessage: String,
    val servers: List<PanelServer>,
    val update: UpdateInfo?,
    val splashDefault: SplashImage?,
    val splashActive: SplashImage?,
    val splashSchedule: List<SplashImage>,
    val announcements: List<Announcement>,
) {
    val refreshMillis: Long get() = refreshMinutes.coerceIn(5, 24 * 60) * 60_000L

    companion object {
        /** Returns null when the document is not a supported panel configuration. */
        fun parse(json: String): PanelConfig? = try {
            parse(JSONObject(json))
        } catch (e: Exception) {
            null
        }

        private fun parse(o: JSONObject): PanelConfig? {
            if (o.optInt("api_version", 0) != 1) return null
            val app = o.optJSONObject("app") ?: return null
            val serversJson = o.optJSONArray("servers") ?: return null

            val servers = serversJson.objects().mapNotNull { s ->
                val url = normalizeServerUrl(s.str("url")) ?: return@mapNotNull null
                PanelServer(s.str("name"), url, s.optInt("priority", Int.MAX_VALUE))
            }.sortedBy { it.priority }.distinctBy { it.url }

            val support = app.optJSONObject("support")
            val maintenance = app.optJSONObject("maintenance")
            val splash = o.optJSONObject("splash")

            return PanelConfig(
                refreshMinutes = o.optInt("refresh_minutes", 60),
                appName = app.str("name").ifEmpty { "Nimer TV" },
                support = Support(
                    support.str("whatsapp"), support.str("email"),
                    support.str("phone"), support.str("website"),
                ),
                maintenanceEnabled = maintenance?.optBoolean("enabled", false) ?: false,
                maintenanceMessage = maintenance.str("message"),
                loginMessage = app.str("login_message"),
                servers = servers,
                update = parseUpdate(o.optJSONObject("update")),
                splashDefault = parseSplash(splash?.optJSONObject("default"), "default"),
                splashActive = parseSplash(splash?.optJSONObject("active"), "active"),
                splashSchedule = splash?.optJSONArray("schedule").objects()
                    .mapNotNull { parseSplash(it, "") }
                    .filter { it.endMs > it.startMs },
                announcements = o.optJSONArray("announcements").objects().mapNotNull(::parseAnnouncement),
            )
        }

        private fun parseUpdate(u: JSONObject?): UpdateInfo? {
            if (u == null) return null
            val code = u.optInt("version_code", 0)
            if (code <= 0) return null
            return UpdateInfo(
                packageName = u.str("package"),
                versionCode = code,
                versionName = u.str("version_name"),
                notes = u.str("notes"),
                mandatory = u.optBoolean("mandatory", false),
                minSupportedVersionCode = u.optInt("min_supported_version_code", 0),
                apkUrl = u.str("apk_url"),
                sha256 = u.str("sha256").lowercase(),
                size = u.optLong("size", 0),
                signerSha256 = u.str("signer_sha256").lowercase().replace(":", ""),
            )
        }

        private fun parseSplash(s: JSONObject?, fallbackId: String): SplashImage? {
            if (s == null) return null
            val url = s.str("image_url")
            val sha = s.str("image_sha256").lowercase()
            // Artwork must come over HTTPS and carry a checksum, otherwise it is ignored.
            if (!url.startsWith("https://") || !sha.matches(Regex("[a-f0-9]{64}"))) return null
            return SplashImage(
                id = s.str("id").ifEmpty { fallbackId },
                name = s.str("name"),
                url = url,
                sha256 = sha,
                startMs = s.optLong("start_ms", 0),
                endMs = s.optLong("end_ms", 0),
            )
        }

        private fun parseAnnouncement(a: JSONObject): Announcement? {
            val title = a.str("title")
            val message = a.str("message")
            if (title.isEmpty() && message.isEmpty()) return null
            return Announcement(
                key = a.str("id") + "@" + a.str("updated_at"),
                title = title,
                message = message,
                popup = a.optBoolean("popup", false),
                dismissible = a.optBoolean("dismissible", true),
                priority = a.optBoolean("priority", false),
                startMs = a.optLong("start_ms", 0),
                endMs = a.optLong("end_ms", 0),
            )
        }

        /** Accepts only http(s) URLs with a host; strips trailing slashes. */
        fun normalizeServerUrl(raw: String): String? {
            val u = raw.trim().trimEnd('/')
            return try {
                val uri = URI(u)
                val scheme = uri.scheme?.lowercase()
                if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()) u else null
            } catch (e: Exception) {
                null
            }
        }
    }
}

private fun JSONObject?.str(key: String): String {
    if (this == null || isNull(key)) return ""
    return optString(key, "").trim()
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

/** A configuration together with the time it was last confirmed by the panel. */
data class PanelSnapshot(val config: PanelConfig, val fetchedAt: Long, val fresh: Boolean)

/**
 * Fetches the panel configuration over HTTPS with ETag revalidation and keeps the last
 * good copy on disk so the app still starts when the panel is unreachable.
 */
class PanelRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("panel_config", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "panel_config_v1.json")

    fun cached(): PanelSnapshot? {
        val cfg = try {
            if (cacheFile.exists()) PanelConfig.parse(cacheFile.readText()) else null
        } catch (e: Exception) {
            null
        } ?: return null
        return PanelSnapshot(cfg, prefs.getLong("fetched_at", 0), fresh = false)
    }

    /** Blocking. Throws when the panel is unreachable and nothing is cached. */
    @Throws(IOException::class)
    fun fetch(): PanelSnapshot {
        val cached = cached()
        try {
            val builder = Request.Builder().url(CONFIG_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "NimerTV/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            val etag = prefs.getString("etag", null)
            if (etag != null && cached != null) builder.header("If-None-Match", etag)
            Http.panel.newCall(builder.build()).execute().use { resp ->
                val now = System.currentTimeMillis()
                if (resp.code == 304 && cached != null) {
                    prefs.edit().putLong("fetched_at", now).apply()
                    return PanelSnapshot(cached.config, now, fresh = true)
                }
                if (!resp.isSuccessful) throw IOException("Panel returned HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                if (body.length > 2_000_000) throw IOException("Panel response too large")
                val cfg = PanelConfig.parse(body) ?: throw IOException("Unsupported panel configuration")
                val tmp = File(context.filesDir, cacheFile.name + ".tmp")
                tmp.writeText(body)
                if (!tmp.renameTo(cacheFile)) {
                    cacheFile.delete()
                    tmp.renameTo(cacheFile)
                }
                prefs.edit().putString("etag", resp.header("ETag")).putLong("fetched_at", now).apply()
                return PanelSnapshot(cfg, now, fresh = true)
            }
        } catch (e: Exception) {
            if (cached != null) return cached
            throw if (e is IOException) e else IOException(e.message ?: "Panel error", e)
        }
    }

    companion object {
        /** Change only if the panel moves. Must be HTTPS. */
        const val CONFIG_URL = "https://panelsandapps.com/panels/nimertv2027/api/v1/config.php"
    }
}
