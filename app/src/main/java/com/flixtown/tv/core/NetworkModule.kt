package com.flixtown.tv.core

import com.flixtown.tv.BuildConfig
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * One shared OkHttp client and Gson instance for the whole app. Reusing the
 * client keeps connection pooling/DNS caching warm instead of paying
 * TLS-handshake cost on every request, which matters on cheap TV hardware.
 */
object NetworkModule {

    val gson: Gson by lazy { Gson() }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .apply { if (BuildConfig.DEBUG) addInterceptor(RedactingLoggingInterceptor()) }
            .build()
    }

    /** Debug-only request/response logging that redacts credentials before printing. */
    private class RedactingLoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val startNs = System.nanoTime()
            SafeLog.d("HTTP", "--> ${request.method} ${SafeLog.redactUrl(request.url.toString())}")
            val response = chain.proceed(request)
            val tookMs = (System.nanoTime() - startNs) / 1_000_000
            SafeLog.d("HTTP", "<-- ${response.code} ${SafeLog.redactUrl(request.url.toString())} (${tookMs}ms)")
            return response
        }
    }
}
