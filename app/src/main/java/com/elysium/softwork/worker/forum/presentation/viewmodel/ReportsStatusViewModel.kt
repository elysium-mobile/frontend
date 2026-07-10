package com.elysium.softwork.worker.forum.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.elysium.softwork.SoftWorkApplication
import com.elysium.softwork.shared.data.local.SharedPrefsManager
import com.elysium.softwork.worker.forum.application.usecase.GetForumReportsUseCase
import com.elysium.softwork.worker.forum.domain.model.Report
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state holder for the reports-status screen.
 *
 * Triggers [GetForumReportsUseCase] on construction and exposes the result through a
 * single [UiState] stream so the screen renders loading / error / list states without
 * juggling multiple flags. No business logic lives here.
 *
 * @param getForumReports fetches the worker's submitted reports.
 */
class ReportsStatusViewModel(
    private val getForumReports: GetForumReportsUseCase,
) : ViewModel() {

    /** Coarse UI state for the reports list. */
    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val reports: List<Report>) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state: MutableStateFlow<UiState> = MutableStateFlow(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-fetches the reports list. Safe to call from a pull-to-refresh affordance. */
    fun refresh() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = getForumReports().fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { UiState.Error(it.message ?: GENERIC_ERROR) },
            )
        }
    }

    companion object {
        private const val GENERIC_ERROR: String = "Could not load reports"

        /** Factory that assembles the use case from the application service locator. */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SoftWorkApplication
                val locator = app.serviceLocator
                return ReportsStatusViewModel(
                    getForumReports = GetForumReportsUseCase(
                        store = locator.forumReportStore,
                        accountIdProvider = {
                            locator.sharedPrefsManager
                                .getLong(SharedPrefsManager.KEY_USER_ACCOUNT_ID)
                                .takeIf { it != SharedPrefsManager.DEFAULT_LONG }
                        },
                    ),
                ) as T
            }
        }
    }
}
