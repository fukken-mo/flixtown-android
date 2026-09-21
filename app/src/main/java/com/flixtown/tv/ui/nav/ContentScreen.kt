package com.flixtown.tv.ui.nav

/**
 * The in-Home screen graph. Deliberately not androidx.navigation-compose:
 * the graph is small and flat, and a hand-rolled stack keeps full control
 * over per-screen behavior without a new dependency. Scroll/filter-state
 * restoration on Back is handled by wrapping each top-level screen in
 * `rememberSaveableStateHolder()` at the call site (see HomeShellScreen).
 */
sealed interface ContentScreen {
    data object Home : ContentScreen
    data class Movies(val categoryId: String? = null) : ContentScreen
    data class SeriesBrowse(val categoryId: String? = null) : ContentScreen
    data class MovieDetails(val streamId: Int) : ContentScreen
    data class SeriesDetails(val seriesId: Int) : ContentScreen
    data object Search : ContentScreen
    data object Settings : ContentScreen

    /**
     * Real playback. Carries a fully resolved stream URL (never re-derived
     * from a bare ID inside the player) plus enough metadata to drive
     * Continue Watching without the player needing catalog access.
     */
    data class Player(
        val contentId: Int,
        val mediaType: String, // "movie" | "episode"
        val title: String,
        val posterUrl: String?,
        val streamUrl: String,
        val seriesId: Int? = null,
        val season: Int? = null,
        val episodeNumber: Int? = null,
        val resumePositionMs: Long = 0L
    ) : ContentScreen
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
    is ContentScreen.Player -> NavSection.Home
}
