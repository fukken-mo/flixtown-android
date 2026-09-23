package com.flixtown.tv

import android.content.Context
import com.flixtown.tv.data.AccountStatusStore
import com.flixtown.tv.data.AuthRepository
import com.flixtown.tv.data.AutoplaySettingsStore
import com.flixtown.tv.data.BackendApi
import com.flixtown.tv.data.ConfigRepository
import com.flixtown.tv.data.ContinueWatchingStore
import com.flixtown.tv.data.PairingRepository
import com.flixtown.tv.data.TmdbRepository
import com.flixtown.tv.data.TrickPlayRepository
import com.flixtown.tv.data.XtreamCatalogRepository
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
    val accountStatusStore = AccountStatusStore(context)
    val autoplaySettingsStore = AutoplaySettingsStore(context)
    val authRepository = AuthRepository(configRepository, xtreamRepository, backendApi, secureCredentialStore)
    val pairingRepository = PairingRepository(backendApi, secureCredentialStore)
    val catalogRepository = XtreamCatalogRepository(context, configRepository, secureCredentialStore)
    val continueWatchingStore = ContinueWatchingStore(context)
    val tmdbRepository = TmdbRepository(configRepository)
    val trickPlayRepository = TrickPlayRepository()
}
