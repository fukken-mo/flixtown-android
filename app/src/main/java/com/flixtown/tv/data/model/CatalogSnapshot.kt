package com.flixtown.tv.data.model

/** Everything the catalog screens need, fetched together and cached together. */
data class CatalogSnapshot(
    val vodCategories: List<Category>,
    val movies: List<Movie>,
    val seriesCategories: List<Category>,
    val series: List<Series>,
    val fetchedAtMillis: Long
)
