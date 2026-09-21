package com.flixtown.tv

import android.content.Context
import com.flixtown.tv.data.AuthRepository
import com.flixtown.tv.data.BackendApi
import com.flixtown.tv.data.ConfigRepository
import com.flixtown.tv.data.PairingRepository
import com.flixtown.tv.data.XtreamRepository
import com.flixtown.tv.security.SecureCredentialStore

/**
 * Minimal hand-rolled dependency graph. No DI framework: the object count in
 * this app is small enough that a service locator is simpler to read, and
 * avoids the APK/build-time cost of annotation processing.
 */
class AppGraph(context: Context) {
    val configRepository = ConfigRepository(context)
    val xtreamRepository = XtreamRepository()
    val backendApi = BackendApi()
    val secureCredentialStore = SecureCredentialStore(context)
    val authRepository = AuthRepository(configRepository, xtreamRepository, backendApi, secureCredentialStore)
    val pairingRepository = PairingRepository(backendApi, secureCredentialStore)
}
