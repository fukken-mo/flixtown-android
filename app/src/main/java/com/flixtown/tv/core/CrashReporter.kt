package com.flixtown.tv.core

import android.content.Context
import android.content.SharedPreferences
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Temporary on-device crash capture for debug builds only. There is no
 * Android Studio attached on the real TV this is being tested on, so this
 * is the only way to get an actual stack trace off the device: install a
 * default [Thread.UncaughtExceptionHandler], write the exception plus
 * whatever navigation context was last recorded to SharedPreferences, then
 * hand off to the previous handler so normal crash/kill behavior is
 * unchanged. [com.flixtown.tv.ui.screens.DebugCrashReportScreen] reads it
 * back on the next launch. Nothing here is ever sent anywhere.
 */
object CrashReporter {
    @Volatile var lastRoute: String = "none"
    @Volatile var lastStreamId: String = "none"
    @Volatile var lastContentType: String = "none"

    private const val PREFS_NAME = "flixtown_crash_report"
    private const val KEY_HAS_REPORT = "has_report"
    private const val KEY_EXCEPTION_CLASS = "exception_class"
    private const val KEY_MESSAGE = "message"
    private const val KEY_STACK_TRACE = "stack_trace"
    private const val KEY_ROUTE = "route"
    private const val KEY_STREAM_ID = "stream_id"
    private const val KEY_CONTENT_TYPE = "content_type"

    data class SavedReport(
        val exceptionClass: String,
        val message: String,
        val stackTrace: String,
        val route: String,
        val streamId: String,
        val contentType: String
    )

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                save(appContext, throwable)
            } catch (_: Exception) {
                // Never let the reporter itself block the real crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun save(context: Context, throwable: Throwable) {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        prefs(context).edit()
            .putBoolean(KEY_HAS_REPORT, true)
            .putString(KEY_EXCEPTION_CLASS, throwable.javaClass.name)
            .putString(KEY_MESSAGE, throwable.message ?: "(no message)")
            .putString(KEY_STACK_TRACE, writer.toString())
            .putString(KEY_ROUTE, lastRoute)
            .putString(KEY_STREAM_ID, lastStreamId)
            .putString(KEY_CONTENT_TYPE, lastContentType)
            .commit()
    }

    fun getSavedReport(context: Context): SavedReport? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_HAS_REPORT, false)) return null
        return SavedReport(
            exceptionClass = p.getString(KEY_EXCEPTION_CLASS, "?") ?: "?",
            message = p.getString(KEY_MESSAGE, "?") ?: "?",
            stackTrace = p.getString(KEY_STACK_TRACE, "") ?: "",
            route = p.getString(KEY_ROUTE, "?") ?: "?",
            streamId = p.getString(KEY_STREAM_ID, "?") ?: "?",
            contentType = p.getString(KEY_CONTENT_TYPE, "?") ?: "?"
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
