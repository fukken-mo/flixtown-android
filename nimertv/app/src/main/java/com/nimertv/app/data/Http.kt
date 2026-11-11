package com.nimertv.app.data

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object Http {
    /** Panel, splash artwork and APK downloads. HTTPS only, no redirects to other schemes. */
    val panel: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    /** Xtream provider API. Large catalogues can take a while to generate. */
    val provider: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Short timeouts for sign-in, so failover to a backup DNS is quick. */
    val providerAuth: OkHttpClient = provider.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
