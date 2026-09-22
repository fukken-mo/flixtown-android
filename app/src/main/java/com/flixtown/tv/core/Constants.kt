package com.flixtown.tv.core

/**
 * The Flix Town control/activation backend is a fixed, first-party host, not
 * customer-configurable data, so it is the one URL allowed to live in source.
 * Everything content-related (the Xtream server URL, pricing, intro video,
 * update policy, ...) is fetched from this host at runtime instead.
 */
object BackendConstants {
    const val CONTROL_HOST = "https://myflixtown.com"
    const val ACTIVATION_URL = "$CONTROL_HOST/pair"
    const val ACTIVATION_HOST_LABEL = "myflixtown.com/pair"

    const val CONFIG_ENDPOINT = "$CONTROL_HOST/api/v1/config.php"
    const val PAIR_START_ENDPOINT = "$CONTROL_HOST/api/v1/pair/start.php"
    const val PAIR_STATUS_ENDPOINT = "$CONTROL_HOST/api/v1/pair/status.php"
    const val PAIR_ACK_ENDPOINT = "$CONTROL_HOST/api/v1/pair/ack.php"
    const val AUTH_REGISTER_ENDPOINT = "$CONTROL_HOST/api/v1/auth/register.php"

    // Both proxy through the backend, which holds the TMDB API key server-side
    // (see backend/includes/tmdb_client.php) — the key itself is never sent
    // to, or stored on, the app.
    const val TMDB_CREDITS_ENDPOINT = "$CONTROL_HOST/api/v1/tmdb/credits.php"
    const val TMDB_RESOLVE_ENDPOINT = "$CONTROL_HOST/api/v1/tmdb/resolve.php"
}
