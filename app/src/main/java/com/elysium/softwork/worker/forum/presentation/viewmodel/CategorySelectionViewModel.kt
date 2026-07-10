package com.elysium.softwork.worker.forum.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.elysium.softwork.SoftWorkApplication
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.worker.forum.application.usecase.CreateCategoryUseCase
import com.elysium.softwork.worker.forum.application.usecase.GetCompanyForumUseCase
import com.elysium.softwork.worker.forum.domain.model.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state holder for the intermediate category-selection step of the new-post flow.
 *
 * Loads the worker's company forum (org-scoped in the store) and surfaces its categories, plus a
 * create-category action that hits `POST /api/v1/categories` under the resolved `forum_id` and
 * reloads. The screen forwards the chosen `category_id` into the composer.
 *
 * @param getCompanyForum fetches the company forum + its nested categories.
 * @param createCategory creates a new category under the forum.
 */
class CategorySelectionViewModel(
    private val getCompanyForum: GetCompanyForumUseCase,
    private val createCategoryUseCase: CreateCategoryUseCase,
) : ViewModel() {

    /** Coarse UI state for the category list. */
    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val forumId: Long?, val categories: List<Category>) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state: MutableStateFlow<UiState> = MutableStateFlow(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _isCreating: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** `true` while a create-category round-trip is in flight; gates re-entrant creates. */
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    init {
        load()
    }

    /** Loads (or reloads) the company forum's categories. */
    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = getCompanyForum().fold(
                onSuccess = { forum ->
                    UiState.Ready(forum?.forum_id, forum?.categories.orEmpty())
                },
                onFailure = { UiState.Error(resolveError(it)) },
            )
        }
    }

    /**
     * Creates a new category under the current forum, then reloads the list. No-ops on blank
     * input, while another create is in flight, or when the forum id is unresolved.
     */
    fun createCategory(title: String) {
        if (title.isBlank() || _isCreating.value) return
        val forumId: Long = (_state.value as? UiState.Ready)?.forumId ?: return
        _isCreating.value = true
        viewModelScope.launch {
            createCategoryUseCase(title = title, forumId = forumId)
                .onSuccess { load() }
                .onFailure { _state.value = UiState.Error(resolveError(it)) }
            _isCreating.value = false
        }
    }

    private fun resolveError(throwable: Throwable): String = when (throwable) {
        is BadRequestException -> throwable.response.primaryFieldError() ?: GENERIC_ERROR
        else -> throwable.message ?: GENERIC_ERROR
    }

    companion object {
        private const val GENERIC_ERROR: String = "Could not load categories"

        /** Factory that assembles the use cases from the application service locator. */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SoftWorkApplication
                val store = app.serviceLocator.forumStore
                return CategorySelectionViewModel(
                    getCompanyForum = GetCompanyForumUseCase(store),
                    createCategoryUseCase = CreateCategoryUseCase(store),
                ) as T
            }
        }
    }
}
