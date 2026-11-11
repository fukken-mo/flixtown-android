package com.nimertv.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.nimertv.app.data.Catalog
import com.nimertv.app.data.Channel
import com.nimertv.app.data.Http
import com.nimertv.app.data.Library
import com.nimertv.app.data.PanelRepository
import com.nimertv.app.data.PanelSnapshot
import com.nimertv.app.data.Session
import com.nimertv.app.data.SessionStore
import com.nimertv.app.data.SplashStore
import com.nimertv.app.data.Updater
import com.nimertv.app.data.XtreamClient
import java.io.File

/** One playable movie or episode. */
data class VodItem(
    val key: String,
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val poster: String,
    val ext: String,
    val seriesId: Int = 0,
)

/** What the player should play; handed over in memory within the process. */
sealed class PlayRequest {
    data class Live(val channels: List<Channel>, val index: Int) : PlayRequest()
    data class Vod(val items: List<VodItem>, val index: Int, val startOver: Boolean) : PlayRequest()
}

class NimerApp : Application(), ImageLoaderFactory {
    lateinit var panelRepository: PanelRepository
        private set
    lateinit var splashStore: SplashStore
        private set
    lateinit var sessionStore: SessionStore
        private set
    lateinit var updater: Updater
        private set

    /** Latest panel configuration (cached copy until the first sync completes). */
    var panel by mutableStateOf<PanelSnapshot?>(null)

    var homeStyle by mutableStateOf(0)
        private set

    fun chooseHomeStyle(style: Int) {
        homeStyle = style
        sessionStore.homeStyle = style
    }

    var session by mutableStateOf<Session?>(null)
        private set
    var library: Library? = null
        private set
    var catalog: Catalog? = null
        private set

    val client = XtreamClient { panel?.config?.servers?.map { it.url }.orEmpty() }

    @Volatile
    var playRequest: PlayRequest? = null

    override fun onCreate() {
        super.onCreate()
        panelRepository = PanelRepository(this)
        splashStore = SplashStore(this)
        sessionStore = SessionStore(this)
        updater = Updater(this)
        panel = panelRepository.cached()
        homeStyle = sessionStore.homeStyle
    }

    fun signIn(s: Session, persist: Boolean = true) {
        if (persist) sessionStore.save(s)
        client.session = s
        if (session?.user != s.user || library == null) {
            library = Library(this, s.user)
            catalog = Catalog(client)
        }
        session = s
    }

    fun signOut() {
        sessionStore.clear()
        client.session = null
        session = null
        library = null
        catalog = null
        playRequest = null
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Http.provider)
        .crossfade(true)
        .respectCacheHeaders(false)
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .build()
}
