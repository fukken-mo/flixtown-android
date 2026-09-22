package com.flixtown.tv

import android.app.Application
import com.flixtown.tv.core.CrashReporter

class FlixTownApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        graph = AppGraph(this)
    }
}
