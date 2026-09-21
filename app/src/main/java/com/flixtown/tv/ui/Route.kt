package com.flixtown.tv.ui

sealed interface Route {
    data object Loading : Route
    data class Intro(val videoUrl: String) : Route
    data class Maintenance(val message: String?) : Route
    data class UpdateRequired(val updateUrl: String?, val forced: Boolean) : Route
    data object Login : Route
    data class RenewalRequired(val xtreamStatus: String) : Route
    data object Home : Route
    data object ConfigUnavailable : Route
}
