package com.flixtown.tv.core

import android.util.Log
import com.flixtown.tv.BuildConfig

/**
 * Centralized logging so credential-bearing strings never reach logcat, even
 * accidentally. Every call site funnels through [redact] before anything is
 * logged, and logging is compiled out entirely in release builds.
 */
object SafeLog {
    private val SENSITIVE_QUERY_KEYS = listOf(
        "password", "pass", "username", "user",
        "token", "poll_token", "device_token", "api_key", "key"
    )

    /** Xtream stream/API URLs often carry credentials in the path or query string. */
    fun redactUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val query = uri.rawQuery
            if (query.isNullOrEmpty()) {
                redactPathSegments(url)
            } else {
                val redactedQuery = query.split("&").joinToString("&") { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) return@joinToString pair
                    val key = pair.substring(0, idx)
                    if (SENSITIVE_QUERY_KEYS.any { key.equals(it, ignoreCase = true) }) {
                        "$key=***"
                    } else {
                        pair
                    }
                }
                url.substringBefore("?") + "?" + redactedQuery
            }
        } catch (_: Exception) {
            "[redacted-url]"
        }
    }

    /** Xtream "line" style URLs embed username/password as path segments. */
    private fun redactPathSegments(url: String): String {
        val playPattern = Regex("(/(live|movie|series)/)([^/]+)/([^/]+)/")
        return playPattern.replace(url) { match ->
            "${match.groupValues[1]}***/***/"
        }
    }

    fun redact(message: String): String {
        var result = message
        SENSITIVE_QUERY_KEYS.forEach { key ->
            result = Regex("(?i)($key)\\s*[=:]\\s*[^&\\s\"]+").replace(result) { "${it.groupValues[1]}=***" }
        }
        return result
    }

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, redact(message))
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, redact(message), throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        // Errors are useful in release too, but must still never carry secrets.
        Log.e(tag, redact(message), throwable)
    }
}
