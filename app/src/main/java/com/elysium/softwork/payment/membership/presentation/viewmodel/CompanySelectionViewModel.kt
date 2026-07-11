package com.elysium.softwork.payment.membership.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.elysium.softwork.SoftWorkApplication
import com.elysium.softwork.iam.application.usecase.AssociateCompanyUseCase
import com.elysium.softwork.iam.application.usecase.GetCompaniesUseCase
import com.elysium.softwork.iam.domain.model.Company
import com.elysium.softwork.payment.membership.application.usecase.ActivateMembershipUseCase
import com.elysium.softwork.payment.membership.application.usecase.BypassMembershipUseCase
import com.elysium.softwork.shared.data.network.BadRequestException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state holder for the onboarding **company-selection** step (reached after the demo membership
 * bypass creates a membership).
 *
 * Loads the corporate directory on construction, then — when the worker picks a company — associates
 * the account with the fresh `membership_id` + chosen `company_id` via
 * [AssociateCompanyUseCase] (`PUT /api/v1/user_accounts/{id}`). On a successful association it flips
 * the persisted membership gate through [ActivateMembershipUseCase]; the `MainActivity`-level
 * `hasMembership` collector reacts and hot-swaps the worker straight into the authenticated main
 * shell (Home dashboard + company-scoped forum), clearing the onboarding back stack entirely.
 *
 * Part of the development demo-bypass flow — delete with [BypassMembershipUseCase] before a
 * production build.
 *
 * @param getCompanies fetches the company directory.
 * @param associateCompany binds the membership + company onto the account and caches the context.
 * @param activateMembership flips the persisted gate once the account is associated.
 */
class CompanySelectionViewModel(
    private val getCompanies: GetCompaniesUseCase,
    private val associateCompany: AssociateCompanyUseCase,
    private val activateMembership: ActivateMembershipUseCase,
) : ViewModel() {

    private val _companies: MutableStateFlow<List<Company>> = MutableStateFlow(emptyList())

    /** The corporate directory rendered in the picker. */
    val companies: StateFlow<List<Company>> = _companies.asStateFlow()

    private val _isLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** `true` while the directory is loading. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAssociating: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** `true` while an account association (`PUT`) is in flight; gates re-entrant selections. */
    val isAssociating: StateFlow<Boolean> = _isAssociating.asStateFlow()

    private val _errorMessage: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Latest backend error, or `null` when none. */
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadCompanies()
    }

    /** (Re)loads the company directory. */
    fun loadCompanies() {
        viewModelScope.launch {
            _isLoading.value = true
            getCompanies().fold(
                onSuccess = { _companies.value = it },
                onFailure = { _errorMessage.value = resolveError(it) },
            )
            _isLoading.value = false
        }
    }

    /**
     * Associates the account with [membershipId] + [companyId] and, on success, opens the gate.
     * Re-entrant calls while a `PUT` is in flight are dropped.
     *
     * @param membershipId the membership created by the bypass (nav argument).
     * @param companyId the company the worker tapped.
     */
    fun selectCompany(membershipId: Long, companyId: Long) {
        if (_isAssociating.value) return
        _isAssociating.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            associateCompany(membershipId, companyId).fold(
                onSuccess = {
                    // Opening the gate hot-swaps the root into the main shell (dashboard + forum).
                    activateMembership.invoke(BypassMembershipUseCase.DEMO_PLAN_KEY)
                },
                onFailure = { _errorMessage.value = resolveError(it) },
            )
            _isAssociating.value = false
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
        private const val GENERIC_ERROR: String = "Could not associate the company"

        /** Assembles the use cases from the application service locator. */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SoftWorkApplication
                val locator = application.serviceLocator
                return CompanySelectionViewModel(
                    getCompanies = GetCompaniesUseCase(locator.authStore),
                    associateCompany = AssociateCompanyUseCase(locator.authStore),
                    activateMembership = ActivateMembershipUseCase(locator.membershipStore),
                ) as T
            }
        }
    }
}
