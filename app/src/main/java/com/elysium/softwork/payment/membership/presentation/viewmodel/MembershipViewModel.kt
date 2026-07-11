package com.elysium.softwork.payment.membership.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.elysium.softwork.SoftWorkApplication
import com.elysium.softwork.payment.membership.application.usecase.ActivateMembershipUseCase
import com.elysium.softwork.payment.membership.application.usecase.AddPaymentMethodUseCase
import com.elysium.softwork.payment.membership.application.usecase.BypassMembershipUseCase
import com.elysium.softwork.payment.membership.application.usecase.CancelSubscriptionUseCase
import com.elysium.softwork.payment.membership.application.usecase.GetMembershipPlansUseCase
import com.elysium.softwork.payment.membership.application.usecase.ObserveCurrentPlanUseCase
import com.elysium.softwork.payment.membership.application.usecase.ObservePaymentMethodsUseCase
import com.elysium.softwork.payment.membership.application.usecase.PayMembershipUseCase
import com.elysium.softwork.payment.membership.application.usecase.StartStripeCheckoutUseCase
import com.elysium.softwork.payment.membership.application.usecase.ValidateMembershipUseCase
import com.elysium.softwork.payment.membership.domain.model.MembershipPlan
import com.elysium.softwork.payment.membership.domain.model.PaymentMethod
import com.elysium.softwork.shared.data.local.SharedPrefsManager
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.shared.data.network.UnauthorizedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state holder for the payment and membership flow.
 *
 * One instance backs the four screens of the payment graph. Business logic is delegated to
 * application-layer use cases; what remains here is UI state: the async plan catalogue, the
 * saved-cards stream, the card composer buffer, the payment state machine, and the surfaced
 * [errorMessage] (a backend `400`/business-rule failure parsed via [BadRequestException]).
 *
 * @param getPlans fetches the public plan catalogue (browse-only, never gates).
 * @param validateMembership validates the worker's subscription via the `membership_id` FK and
 *   syncs the gate (drives the "membership required" prompt).
 * @param observePaymentMethods streams the saved-cards list.
 * @param observeCurrentPlan streams the active plan key.
 * @param addPaymentMethod assembles and persists a card from raw composer input.
 * @param payMembership creates the order + payment and re-authenticates after success.
 * @param activateMembership flips the persisted membership gate after payment.
 * @param cancelSubscription clears the persisted membership gate.
 */
class MembershipViewModel(
    private val getPlans: GetMembershipPlansUseCase,
    private val validateMembership: ValidateMembershipUseCase,
    observePaymentMethods: ObservePaymentMethodsUseCase,
    observeCurrentPlan: ObserveCurrentPlanUseCase,
    private val addPaymentMethod: AddPaymentMethodUseCase,
    private val payMembership: PayMembershipUseCase,
    private val startStripeCheckout: StartStripeCheckoutUseCase,
    private val bypassMembership: BypassMembershipUseCase,
    private val activateMembership: ActivateMembershipUseCase,
    private val cancelSubscription: CancelSubscriptionUseCase,
) : ViewModel() {

    /** Snapshot of the credit-card composer. Updated via the `on*Change` handlers. */
    data class CardFormState(
        val holderName: String = "",
        val cardNumber: String = "",
        val expiry: String = "",
        val cvv: String = "",
        val saveCard: Boolean = true,
    ) {
        /** Minimal validation gate for the "Add card" button. */
        val isValid: Boolean
            get() = holderName.isNotBlank() &&
                cardNumber.filter { it.isDigit() }.length in MIN_PAN_LENGTH..MAX_PAN_LENGTH &&
                expiry.length == EXPIRY_LENGTH &&
                cvv.length in MIN_CVV_LENGTH..MAX_CVV_LENGTH
    }

    /** Lifecycle of the "Pay membership" action. */
    sealed interface PaymentState {
        data object Idle : PaymentState
        data object Processing : PaymentState
        data object Succeeded : PaymentState
    }

    private val _availablePlans: MutableStateFlow<List<MembershipPlan>> = MutableStateFlow(emptyList())

    /** Catalogue of plans the user can choose from, loaded from the backend on construction. */
    val availablePlans: StateFlow<List<MembershipPlan>> = _availablePlans.asStateFlow()

    /** Live list of saved cards. */
    val paymentMethods: StateFlow<List<PaymentMethod>> = observePaymentMethods()

    /** Stable identifier of the currently active plan, or `null` when no membership is active. */
    val currentPlanKey: StateFlow<String?> = observeCurrentPlan()

    private val _cardForm: MutableStateFlow<CardFormState> = MutableStateFlow(CardFormState())
    val cardForm: StateFlow<CardFormState> = _cardForm.asStateFlow()

    private val _paymentState: MutableStateFlow<PaymentState> = MutableStateFlow(PaymentState.Idle)
    val paymentState: StateFlow<PaymentState> = _paymentState.asStateFlow()

    private val _errorMessage: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Latest backend validation / business-rule error, or `null` when none. */
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _checkoutUrl: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * One-shot hosted Stripe Checkout URL. Emitted by [startCheckout] on a successful session
     * creation; the selection screen observes it, launches the external browser via
     * `Intent.ACTION_VIEW`, then calls [consumeCheckoutUrl]. `null` when idle.
     */
    val checkoutUrl: StateFlow<String?> = _checkoutUrl.asStateFlow()

    private val _isMembershipExpired: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * `true` when the worker's **enrolment status** (`/api/v1/memberships`) or an **order push**
     * (`/api/v1/orders`) reports a business-rule failure (`401` / expired-state
     * `IllegalStateException`) — the session is valid but there is no active membership. Drives
     * the payment-onboarding gate. **Never** set by the plan catalogue (`/api/v1/membership-plans`),
     * which stays browse-only so an expired worker can still view and select upgrade tiers.
     */
    val isMembershipExpired: StateFlow<Boolean> = _isMembershipExpired.asStateFlow()

    init {
        loadPlans()
        validateMembershipAccess()
    }

    /**
     * (Re)loads the **public plan catalogue** (`GET /api/v1/membership-plans`). Browse-only: a
     * failure lands on [errorMessage] and **never** flips [isMembershipExpired] or tears the
     * session down — the catalogue must always flow into the UI so an expired worker can pick an
     * upgrade tier.
     */
    fun loadPlans() {
        viewModelScope.launch {
            getPlans().fold(
                onSuccess = { _availablePlans.value = it },
                onFailure = { _errorMessage.value = resolveError(it) },
            )
        }
    }

    /**
     * Validates the worker's **subscription** via the `membership_id` FK
     * (`GET /api/v1/memberships/{id}`) and syncs the reactive gate. When the subscription is not
     * active/in-window (or cannot be resolved), [isMembershipExpired] is raised so the selection
     * screen surfaces the "membership required" prompt. The plan catalogue is unaffected either
     * way, so an expired worker still sees the upgrade tiers below the prompt.
     */
    fun validateMembershipAccess() {
        viewModelScope.launch {
            _isMembershipExpired.value = !validateMembership()
        }
    }

    // region Card form handlers
    fun onHolderNameChange(value: String) {
        _cardForm.value = _cardForm.value.copy(holderName = value)
    }

    fun onCardNumberChange(value: String) {
        val digits: String = value.filter { it.isDigit() }.take(MAX_PAN_LENGTH)
        _cardForm.value = _cardForm.value.copy(cardNumber = digits)
    }

    fun onExpiryChange(value: String) {
        val digits: String = value.filter { it.isDigit() }.take(EXPIRY_DIGITS)
        val formatted: String = if (digits.length >= 3) {
            "${digits.substring(0, 2)}/${digits.substring(2)}"
        } else {
            digits
        }
        _cardForm.value = _cardForm.value.copy(expiry = formatted)
    }

    fun onCvvChange(value: String) {
        val digits: String = value.filter { it.isDigit() }.take(MAX_CVV_LENGTH)
        _cardForm.value = _cardForm.value.copy(cvv = digits)
    }

    fun onSaveCardChange(value: Boolean) {
        _cardForm.value = _cardForm.value.copy(saveCard = value)
    }
    // endregion

    // region Actions
    /** Validates the form and delegates card assembly + persistence to the use case. */
    fun addCard(onAdded: () -> Unit) {
        val current: CardFormState = _cardForm.value
        if (!current.isValid) return
        viewModelScope.launch {
            if (current.saveCard) {
                addPaymentMethod(
                    holderName = current.holderName,
                    pan = current.cardNumber,
                    expiryMonthYear = current.expiry,
                )
            }
            _cardForm.value = CardFormState()
            onAdded()
        }
    }

    /**
     * Executes the checkout for [plan]: creates the order (binding `user_account_id`),
     * registers the payment, and re-authenticates. [paymentState] flips to [PaymentState.Processing]
     * immediately and to [PaymentState.Succeeded] on success; a `400`/business-rule failure
     * resets to [PaymentState.Idle] and lands its message on [errorMessage]. Re-entrant calls
     * while processing are dropped.
     */
    fun payMembership(plan: MembershipPlan) {
        if (_paymentState.value is PaymentState.Processing) return
        _paymentState.value = PaymentState.Processing
        _errorMessage.value = null
        viewModelScope.launch {
            _paymentState.value = payMembership.invoke(plan).fold(
                onSuccess = { PaymentState.Succeeded },
                onFailure = { throwable ->
                    // Order push (/orders) is a gated route: a business-rule failure (401 /
                    // expired-state IllegalStateException) blocks routing to onboarding, in
                    // addition to surfacing the message.
                    if (throwable.isMembershipGate()) _isMembershipExpired.value = true
                    _errorMessage.value = resolveError(throwable)
                    PaymentState.Idle
                },
            )
        }
    }

    /**
     * Starts the **hosted Stripe Checkout** for [plan] (the native-card path is deprecated):
     * runs the three-phase chain (create membership → create order → create Stripe Checkout
     * Session) and emits the resulting `checkout_url` on [checkoutUrl] for the screen to open in
     * the external browser. [paymentState] flips to
     * [PaymentState.Processing] during the round-trip and back to [PaymentState.Idle] once the URL
     * is ready (the browser completes the flow); a `400`/business-rule failure lands on
     * [errorMessage] (and raises [isMembershipExpired] for a gated `/orders` failure). Re-entrant
     * calls while processing are dropped.
     */
    fun startCheckout(plan: MembershipPlan) {
        if (_paymentState.value is PaymentState.Processing) return
        _paymentState.value = PaymentState.Processing
        _errorMessage.value = null
        viewModelScope.launch {
            startStripeCheckout.invoke(plan)
                .onSuccess { url -> _checkoutUrl.value = url }
                .onFailure { throwable ->
                    if (throwable.isMembershipGate()) _isMembershipExpired.value = true
                    _errorMessage.value = resolveError(throwable)
                }
            _paymentState.value = PaymentState.Idle
        }
    }

    /** Clears the [checkoutUrl] event after the screen has launched the browser. */
    fun consumeCheckoutUrl() {
        _checkoutUrl.value = null
    }

    private val _bypassMembershipId: MutableStateFlow<Long?> = MutableStateFlow(null)

    /**
     * One-shot `membership_id` created by the demo bypass. The selection screen observes it and
     * routes to the company-selection step (which associates the account and opens the gate), then
     * calls [consumeBypassMembershipId]. `null` when idle.
     */
    val bypassMembershipId: StateFlow<Long?> = _bypassMembershipId.asStateFlow()

    /**
     * **Development-only demo bypass.** Runs [BypassMembershipUseCase] (Phase 1 only —
     * `POST /api/v1/memberships` with a hardcoded long-lived `ACTIVE` window) and, on success,
     * emits the created `membership_id` on [bypassMembershipId] so the screen can route to the
     * company-selection step. It does **not** flip the gate directly — that happens after the
     * account is associated with a company.
     *
     * Behaves like [startCheckout] w.r.t. the shared [PaymentState] guard; a `400`/business-rule
     * failure (or a missing `membership_id`) lands on [errorMessage]. Delete with the use case
     * before a production build.
     */
    fun bypassMembership() {
        if (_paymentState.value is PaymentState.Processing) return
        _paymentState.value = PaymentState.Processing
        _errorMessage.value = null
        viewModelScope.launch {
            bypassMembership.invoke().fold(
                onSuccess = { membership ->
                    _paymentState.value = PaymentState.Idle
                    membership.membership_id
                        ?.let { _bypassMembershipId.value = it }
                        ?: run { _errorMessage.value = GENERIC_ERROR }
                },
                onFailure = { throwable ->
                    _errorMessage.value = resolveError(throwable)
                    _paymentState.value = PaymentState.Idle
                },
            )
        }
    }

    /** Clears the [bypassMembershipId] event after the screen has routed to company selection. */
    fun consumeBypassMembershipId() {
        _bypassMembershipId.value = null
    }

    /** Activates the worker's membership for [planKey], flipping the persisted gate. */
    fun activateMembership(planKey: String) {
        viewModelScope.launch { activateMembership.invoke(planKey) }
    }

    /** Resets [paymentState] back to [PaymentState.Idle]. */
    fun consumePaymentState() {
        _paymentState.value = PaymentState.Idle
    }

    /** Clears the surfaced error after the UI has shown it. */
    fun consumeError() {
        _errorMessage.value = null
    }

    /** Cancels the active membership; the persisted gate clears and the root swaps back. */
    fun cancelSubscription() {
        viewModelScope.launch { cancelSubscription.invoke() }
    }
    // endregion

    private fun resolveError(throwable: Throwable): String = when (throwable) {
        is BadRequestException -> throwable.response.primaryFieldError() ?: GENERIC_ERROR
        else -> throwable.message ?: GENERIC_ERROR
    }

    /**
     * `true` for the business-rule failures that mean "no active membership" — a `401`
     * ([UnauthorizedException]) on a gated route, or the backend's expired-state
     * `IllegalStateException`. A [BadRequestException] (field validation) is **not** a gate.
     */
    private fun Throwable.isMembershipGate(): Boolean =
        this is UnauthorizedException || this is IllegalStateException

    companion object {
        private const val MIN_PAN_LENGTH: Int = 13
        private const val MAX_PAN_LENGTH: Int = 19
        private const val EXPIRY_LENGTH: Int = 5
        private const val EXPIRY_DIGITS: Int = 4
        private const val MIN_CVV_LENGTH: Int = 3
        private const val MAX_CVV_LENGTH: Int = 4
        private const val GENERIC_ERROR: String = "Could not complete the payment"

        /** Factory that assembles the membership use cases from the application service locator. */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SoftWorkApplication
                val locator = application.serviceLocator
                val store = locator.membershipStore
                return MembershipViewModel(
                    getPlans = GetMembershipPlansUseCase(store),
                    validateMembership = ValidateMembershipUseCase(store) {
                        locator.sharedPrefsManager
                            .getLong(SharedPrefsManager.KEY_MEMBERSHIP_ID)
                            .takeIf { it != SharedPrefsManager.DEFAULT_LONG }
                    },
                    observePaymentMethods = ObservePaymentMethodsUseCase(store),
                    observeCurrentPlan = ObserveCurrentPlanUseCase(store),
                    addPaymentMethod = AddPaymentMethodUseCase(store),
                    payMembership = PayMembershipUseCase(
                        store = store,
                        authStore = locator.authStore,
                        accountIdProvider = {
                            locator.sharedPrefsManager
                                .getLong(SharedPrefsManager.KEY_USER_ACCOUNT_ID)
                                .takeIf { it != SharedPrefsManager.DEFAULT_LONG }
                        },
                    ),
                    startStripeCheckout = StartStripeCheckoutUseCase(
                        store = store,
                        accountIdProvider = {
                            locator.sharedPrefsManager
                                .getLong(SharedPrefsManager.KEY_USER_ACCOUNT_ID)
                                .takeIf { it != SharedPrefsManager.DEFAULT_LONG }
                        },
                    ),
                    bypassMembership = BypassMembershipUseCase(store),
                    activateMembership = ActivateMembershipUseCase(store),
                    cancelSubscription = CancelSubscriptionUseCase(store),
                ) as T
            }
        }
    }
}
