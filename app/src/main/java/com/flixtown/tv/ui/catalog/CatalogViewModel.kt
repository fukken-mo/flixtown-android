package com.flixtown.tv.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flixtown.tv.data.XtreamCatalogRepository
import com.flixtown.tv.data.model.CatalogSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface CatalogUiState {
    data object Loading : CatalogUiState
    data class Loaded(val snapshot: CatalogSnapshot) : CatalogUiState
    data class Error(val message: String) : CatalogUiState
}

/**
 * Loads the catalog once and shares it across Home, Movies, Series, and
 * Search — scoped to the Activity's ViewModelStore (created once inside
 * HomeShellScreen, alive for as long as Home is), so navigating between
 * catalog screens never re-fetches. Cache-first, background-refresh when
 * stale: consistent with [com.flixtown.tv.ui.startup.StartupViewModel].
 */
class CatalogViewModel(private val repository: XtreamCatalogRepository) : ViewModel() {

    private val _state = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading)
    val state: StateFlow<CatalogUiState> = _state

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            val cached = repository.getCachedSnapshot()
            if (cached != null) {
                _state.value = CatalogUiState.Loaded(cached)
                if (repository.isCacheStale(cached)) refreshInBackground()
            } else {
                _state.value = CatalogUiState.Loading
                val result = repository.refresh()
                _state.value = result.fold(
                    onSuccess = { CatalogUiState.Loaded(it) },
                    onFailure = { CatalogUiState.Error(it.message ?: "Could not load the catalog") }
                )
            }
        }
    }

    private fun refreshInBackground() {
        viewModelScope.launch {
            repository.refresh().onSuccess { _state.value = CatalogUiState.Loaded(it) }
        }
    }

    class Factory(private val repository: XtreamCatalogRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CatalogViewModel(repository) as T
    }
}
