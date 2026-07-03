package com.elysium.softwork.payment.membership.presentation.viewmodel

import com.elysium.softwork.payment.membership.application.usecase.ActivateMembershipUseCase
import com.elysium.softwork.payment.membership.application.usecase.AddPaymentMethodUseCase
import com.elysium.softwork.payment.membership.application.usecase.CancelSubscriptionUseCase
import com.elysium.softwork.payment.membership.application.usecase.GetMembershipPlansUseCase
import com.elysium.softwork.payment.membership.application.usecase.ValidateMembershipUseCase
import com.elysium.softwork.payment.membership.application.usecase.ObserveCurrentPlanUseCase
import com.elysium.softwork.payment.membership.application.usecase.ObservePaymentMethodsUseCase
import com.elysium.softwork.payment.membership.application.usecase.PayMembershipUseCase
import com.elysium.softwork.payment.membership.domain.model.MembershipPlan
import com.elysium.softwork.testsupport.FakeAuthStore
import com.elysium.softwork.testsupport.FakeMembershipStore
import com.elysium.softwork.testsupport.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [MembershipViewModel].
 *
 * Covers three independent surfaces of the ViewModel:
 *  - **Card form buffer** ([MembershipViewModel.CardFormState]): digit filtering, expiry
 *    auto-slash insertion, max-length caps, and the `isValid` boundary.
 *  - **Payment state machine** ([MembershipViewModel.PaymentState]): Idle → Processing
 *    → Succeeded transitions, re-entrance guard, consume-on-dispose.
 *  - **Membership lifecycle**: activation forwards the plan key, cancellation flips the
 *    store flags through the public mutator.
 *
 * The payment-state machine relies on a [StandardTestDispatcher] because the test must
 * observe the intermediate [MembershipViewModel.PaymentState.Processing] snapshot before
 * the mocked 1 s delay completes. An `UnconfinedTestDispatcher` would race past the
 * Processing assertion since the coroutine resumes synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MembershipViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    /**
     * Builds the ViewModel with the real application-layer use cases wrapping the fake
     * store. Card assembly (brand detection, last-4) and the mocked payment delay now
     * live inside the use cases, so wiring them keeps the original assertions valid.
     */
    private fun newViewModel(store: FakeMembershipStore = FakeMembershipStore()): MembershipViewModel =
        MembershipViewModel(
            getPlans = GetMembershipPlansUseCase(store),
            validateMembership = ValidateMembershipUseCase(store) { 1L },
            observePaymentMethods = ObservePaymentMethodsUseCase(store),
            observeCurrentPlan = ObserveCurrentPlanUseCase(store),
            addPaymentMethod = AddPaymentMethodUseCase(store),
            payMembership = PayMembershipUseCase(
                store = store,
                authStore = FakeAuthStore(),
                accountIdProvider = { 1L },
            ),
            activateMembership = ActivateMembershipUseCase(store),
            cancelSubscription = CancelSubscriptionUseCase(store),
        )

    private val samplePlan: MembershipPlan =
        MembershipPlan(plan_id = 2L, plan_name = "Plan Pro", price = 99, membership_id = 2L)

    // region Card form buffer
    @Test
    fun `onCardNumberChange strips non-digits`() {
        val vm = newViewModel()
        vm.onCardNumberChange("4242-4242-4242-4242")
        assertEquals("4242424242424242", vm.cardForm.value.cardNumber)
    }

    @Test
    fun `onCardNumberChange caps the PAN at 19 digits`() {
        val vm = newViewModel()
        vm.onCardNumberChange("1".repeat(40))
        assertEquals(19, vm.cardForm.value.cardNumber.length)
    }

    @Test
    fun `onExpiryChange auto-inserts the slash after the second digit`() {
        val vm = newViewModel()
        vm.onExpiryChange("12")
        assertEquals("12", vm.cardForm.value.expiry)

        vm.onExpiryChange("123")
        assertEquals("12/3", vm.cardForm.value.expiry)

        vm.onExpiryChange("1227")
        assertEquals("12/27", vm.cardForm.value.expiry)
    }

    @Test
    fun `onExpiryChange caps at four digits worth of input`() {
        val vm = newViewModel()
        vm.onExpiryChange("12278999")
        assertEquals("12/27", vm.cardForm.value.expiry)
    }

    @Test
    fun `onCvvChange caps at four digits and rejects non-digits`() {
        val vm = newViewModel()
        vm.onCvvChange("12345ab")
        assertEquals("1234", vm.cardForm.value.cvv)
    }

    @Test
    fun `isValid requires every minimum to be satisfied`() {
        val vm = newViewModel()
        assertFalse(vm.cardForm.value.isValid)

        vm.onHolderNameChange("Cesar Cardholder")
        vm.onCardNumberChange("4242424242424242")
        vm.onExpiryChange("1227")
        vm.onCvvChange("123")

        assertTrue(vm.cardForm.value.isValid)
    }

    @Test
    fun `isValid rejects a PAN below the 13-digit floor`() {
        val vm = newViewModel()
        vm.onHolderNameChange("Cesar")
        vm.onCardNumberChange("424242424242")
        vm.onExpiryChange("1227")
        vm.onCvvChange("123")
        assertFalse(vm.cardForm.value.isValid)
    }

    @Test
    fun `isValid rejects an incomplete expiry`() {
        val vm = newViewModel()
        vm.onHolderNameChange("Cesar")
        vm.onCardNumberChange("4242424242424242")
        vm.onExpiryChange("122")
        vm.onCvvChange("123")
        assertFalse(vm.cardForm.value.isValid)
    }

    @Test
    fun `isValid rejects a blank holder name`() {
        val vm = newViewModel()
        vm.onHolderNameChange("   ")
        vm.onCardNumberChange("4242424242424242")
        vm.onExpiryChange("1227")
        vm.onCvvChange("123")
        assertFalse(vm.cardForm.value.isValid)
    }
    // endregion

    // region addCard
    @Test
    fun `addCard is a no-op when the form is invalid`() = runTest {
        val store = FakeMembershipStore()
        val vm = newViewModel(store)
        var addedFired = false

        vm.addCard(onAdded = { addedFired = true })
        advanceUntilIdle()

        assertFalse(addedFired)
        assertNull(store.lastAddedPaymentMethod)
    }

    @Test
    fun `addCard persists the card and invokes onAdded when the form is valid`() = runTest {
        val store = FakeMembershipStore()
        val vm = newViewModel(store)
        vm.onHolderNameChange("Cesar Cardholder")
        vm.onCardNumberChange("4242424242424242")
        vm.onExpiryChange("1227")
        vm.onCvvChange("123")

        var addedFired = false
        vm.addCard(onAdded = { addedFired = true })
        advanceUntilIdle()

        assertTrue(addedFired)
        val saved = store.lastAddedPaymentMethod
        assertNotNull(saved)
        assertEquals("Visa", saved!!.brand)
        assertEquals("4242", saved.last4)
        assertEquals("12/27", saved.expiryMonthYear)
        assertEquals("Cesar Cardholder", saved.holderName)
    }

    @Test
    fun `addCard skips persistence when saveCard is off and still invokes onAdded`() = runTest {
        val store = FakeMembershipStore()
        val vm = newViewModel(store)
        vm.onHolderNameChange("Cesar")
        vm.onCardNumberChange("4242424242424242")
        vm.onExpiryChange("1227")
        vm.onCvvChange("123")
        vm.onSaveCardChange(false)

        var addedFired = false
        vm.addCard(onAdded = { addedFired = true })
        advanceUntilIdle()

        assertTrue(addedFired)
        assertNull(store.lastAddedPaymentMethod)
    }

    @Test
    fun `addCard clears the form buffer on success`() = runTest {
        val store = FakeMembershipStore()
        val vm = newViewModel(store)
        vm.onHolderNameChange("Cesar")
        vm.onCardNumberChange("4242424242424242")
        vm.onExpiryChange("1227")
        vm.onCvvChange("123")

        vm.addCard(onAdded = {})
        advanceUntilIdle()

        val cleared = vm.cardForm.value
        assertEquals("", cleared.holderName)
        assertEquals("", cleared.cardNumber)
        assertEquals("", cleared.expiry)
        assertEquals("", cleared.cvv)
    }
    // endregion

    // region payMembership state machine
    @Test
    fun `payMembership transitions Idle to Processing immediately, then Succeeded after the delay`() =
        runTest {
            val vm = newViewModel()

            assertEquals(MembershipViewModel.PaymentState.Idle, vm.paymentState.value)

            vm.payMembership(samplePlan)

            // Standard dispatcher leaves the spinner state observable before the call resolves.
            assertEquals(MembershipViewModel.PaymentState.Processing, vm.paymentState.value)

            advanceUntilIdle()

            assertEquals(MembershipViewModel.PaymentState.Succeeded, vm.paymentState.value)
        }

    @Test
    fun `payMembership ignores a second invocation while Processing is in flight`() = runTest {
        val vm = newViewModel()
        vm.payMembership(samplePlan)
        vm.payMembership(samplePlan)

        // Even after letting the dispatcher drain, the state must end at Succeeded — proving
        // the second call did NOT push the machine back to Processing.
        advanceUntilIdle()
        assertEquals(MembershipViewModel.PaymentState.Succeeded, vm.paymentState.value)
    }

    @Test
    fun `consumePaymentState resets the machine back to Idle`() = runTest {
        val vm = newViewModel()
        vm.payMembership(samplePlan)
        advanceUntilIdle()
        assertEquals(MembershipViewModel.PaymentState.Succeeded, vm.paymentState.value)

        vm.consumePaymentState()
        assertEquals(MembershipViewModel.PaymentState.Idle, vm.paymentState.value)
    }
    // endregion

    // region Membership lifecycle
    @Test
    fun `activateMembership forwards the plan key to the store and flips hasMembership`() = runTest {
        val store = FakeMembershipStore()
        val vm = newViewModel(store)

        vm.activateMembership("pro")
        advanceUntilIdle()

        assertEquals("pro", store.lastActivatedPlanKey)
        assertEquals(true, store.hasMembership.value)
        assertEquals("pro", store.currentPlanKey.value)
    }

    @Test
    fun `cancelSubscription clears the membership flags in the store`() = runTest {
        val store = FakeMembershipStore().apply { seedMembership(active = true, planKey = "pro") }
        val vm = newViewModel(store)

        vm.cancelSubscription()
        advanceUntilIdle()

        assertEquals(1, store.cancelInvocations)
        assertEquals(false, store.hasMembership.value)
        assertNull(store.currentPlanKey.value)
    }

    @Test
    fun `currentPlanKey StateFlow proxies the store value`() {
        val store = FakeMembershipStore().apply { seedMembership(active = true, planKey = "basic") }
        val vm = newViewModel(store)
        assertEquals("basic", vm.currentPlanKey.value)
    }

    @Test
    fun `availablePlans loads the catalogue from the store on construction`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(FakeMembershipStore.DEFAULT_PLANS, vm.availablePlans.value)
    }
    // endregion
}
