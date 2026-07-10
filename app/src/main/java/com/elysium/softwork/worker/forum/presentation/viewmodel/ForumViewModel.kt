package com.elysium.softwork.worker.forum.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.elysium.softwork.SoftWorkApplication
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.worker.forum.application.usecase.ObserveThreadsUseCase
import com.elysium.softwork.worker.forum.application.usecase.RefreshThreadsUseCase
import com.elysium.softwork.worker.forum.domain.model.Thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state holder for the forum feed screen.
 *
 * Exposes the cached thread feed from [ObserveThreadsUseCase] and triggers a one-shot
 * [RefreshThreadsUseCase] on construction so the cache updates as soon as the screen mounts.
 * A backend `400 Bad Request` from a refresh is caught, its [BadRequestException] payload
 * parsed, and the `field_errors` message routed into [errorMessage]. No business logic lives
 * here.
 *
 * The legacy in-memory category filter is gone: the backend keys threads by a numeric
 * `category_id`, which the client-side `ForumCategory` enum does not map to.
 *
 * @param observeThreads streams the cached thread feed.
 * @param refreshThreads pulls the latest threads into the cache.
 */
class ForumViewModel(
    observeThreads: ObserveThreadsUseCase,
    private val refreshThreads: RefreshThreadsUseCase,
) : ViewModel() {

    /** Cached thread feed. Re-emits whenever the cache writes new rows. */
    val threads: StateFlow<List<Thread>> = observeThreads().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        initialValue = emptyList(),
    )

    private val _errorMessage: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Latest backend validation / load error, or `null` when none. */
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * `true` while a feed refresh is in flight. Bound to `ForumScreen`'s `PullToRefreshBox`
     * indicator; flipped back to `false` in a `finally` so the wheel always retracts, even on a
     * transport failure. Re-entrancy-guarded so overlapping pulls collapse into one round-trip.
     */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refresh()
    }

    /**
     * Pull-to-refresh hook for the feed. Re-fetches `GET /api/v1/forums` and re-applies the
     * company filter (`company_id == KEY_COMPANY_ID`) inside the store before the org-scoped
     * cache re-emits. Also invoked once on construction for the initial load.
     */
    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                refreshThreads().onFailure { throwable -> _errorMessage.value = resolveError(throwable) }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Clears the surfaced error after the UI has shown it. */
    fun consumeError() {
        _errorMessage.value = null
    }

    private fun resolveError(throwable: Throwable): String = when (throwable) {
        is BadRequestException -> throwable.response.primaryFieldError() ?: GENERIC_ERROR
        else -> throwable.message ?: GENERIC_ERROR
    }

    companion object {
        private const val GENERIC_ERROR: String = "Could not load the forum"

        /** Factory that assembles the forum use cases from the application service locator. */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SoftWorkApplication
                val store = app.serviceLocator.forumStore
                return ForumViewModel(
                    observeThreads = ObserveThreadsUseCase(store),
                    refreshThreads = RefreshThreadsUseCase(store),
                ) as T
            }
        }
    }
}
