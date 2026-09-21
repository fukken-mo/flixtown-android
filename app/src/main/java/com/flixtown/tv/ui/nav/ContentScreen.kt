package com.flixtown.tv.ui.nav

/**
 * The in-Home screen graph. Deliberately not androidx.navigation-compose:
 * the graph is small and flat, and a hand-rolled stack keeps full control
 * over per-screen scroll/focus restoration (see [ScrollMemory]) without a
 * new dependency.
 */
sealed interface ContentScreen {
    data object Home : ContentScreen
    data class Movies(val categoryId: String? = null) : ContentScreen
    data class SeriesBrowse(val categoryId: String? = null) : ContentScreen
    data class MovieDetails(val streamId: Int) : ContentScreen
    data class SeriesDetails(val seriesId: Int) : ContentScreen
    data object Search : ContentScreen
    data object Settings : ContentScreen
}

enum class NavSection(val label: String) {
    Home("Home"),
    Movies("Movies"),
    Series("Series"),
    Search("Search"),
    Settings("Settings")
}

fun sectionFor(screen: ContentScreen): NavSection = when (screen) {
    is ContentScreen.Home -> NavSection.Home
    is ContentScreen.Movies, is ContentScreen.MovieDetails -> NavSection.Movies
    is ContentScreen.SeriesBrowse, is ContentScreen.SeriesDetails -> NavSection.Series
    is ContentScreen.Search -> NavSection.Search
    is ContentScreen.Settings -> NavSection.Settings
}
