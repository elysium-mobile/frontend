package com.elysium.softwork.shared.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.elysium.softwork.SoftWorkApplication
import com.elysium.softwork.shared.data.local.SharedPrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI state holder for the authenticated home dashboard.
 *
 * Sources the worker's display name from the persisted session snapshot (`KEY_FIRST_NAME` +
 * `KEY_LAST_NAME`, falling back to the anonymous pseudonym, then the email) so the greeting and
 * avatar render the worker's real credentials. The snapshot is read on construction — the dashboard
 * mounts only after the onboarding association has committed these keys, so the first composition
 * already shows real values with no logout cycle. [refresh] re-reads them for any later mutation
 * (e.g. a future profile edit).
 *
 * @param prefs persistent session storage holding the identity snapshot.
 */
class HomeViewModel(private val prefs: SharedPrefsManager) : ViewModel() {

    private val _displayName: MutableStateFlow<String> = MutableStateFlow(resolveDisplayName())

    /** The worker's resolved display name (empty when nothing is cached yet). */
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    /** Re-reads the persisted identity so the header reflects the latest committed values. */
    fun refresh() {
        _displayName.value = resolveDisplayName()
    }

    private fun resolveDisplayName(): String {
        val first = prefs.getString(SharedPrefsManager.KEY_FIRST_NAME).orEmpty().trim()
        val last = prefs.getString(SharedPrefsManager.KEY_LAST_NAME).orEmpty().trim()
        val fullName = listOf(first, last).filter { it.isNotBlank() }.joinToString(separator = " ")
        if (fullName.isNotBlank()) return fullName

        val anonymous = prefs.getString(SharedPrefsManager.KEY_ANONYMOUS_NAME).orEmpty().trim()
        if (anonymous.isNotBlank()) return anonymous

        return prefs.getString(SharedPrefsManager.KEY_USER_EMAIL).orEmpty().trim()
    }

    companion object {
        /** Assembles the ViewModel from the application service locator. */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SoftWorkApplication
                return HomeViewModel(application.serviceLocator.sharedPrefsManager) as T
            }
        }
    }
}
