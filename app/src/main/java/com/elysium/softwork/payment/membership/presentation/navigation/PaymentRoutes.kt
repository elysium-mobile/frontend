package com.elysium.softwork.payment.membership.presentation.navigation

/**
 * Route catalog for the payment and membership bounded context.
 *
 * The graph is mounted in two host contexts:
 *  - As a standalone onboarding host at the application root when an authenticated worker
 *    has no active membership. Entry point is [SELECTION].
 *  - Nested inside the authenticated main shell when the worker enters through the
 *    profile settings ("Payment methods"). Entry point is [methods] with
 *    `fromSettings = true`, which surfaces the "Cancel subscription" action.
 *
 * Route templates use embedded path arguments. The graph declares the matching
 * `navArgument` types in `PaymentNavigation.kt`, so callers only need to build route
 * strings through the helper functions exposed here ([methods], [success]).
 */
object PaymentRoutes {

    /** Plan-selection screen. Start destination of the onboarding graph. */
    const val SELECTION: String = "payment/selection"

    /** Argument name carrying the stable plan key on the methods route. */
    const val METHODS_ARG_PLAN_KEY: String = "planKey"

    /** Argument name carrying the entry-context flag on the methods route. */
    const val METHODS_ARG_FROM_SETTINGS: String = "fromSettings"

    /**
     * Route template: `payment/methods/{planKey}/{fromSettings}`.
     *
     * The `fromSettings` segment is decoded with `NavType.BoolType`; the `planKey` segment
     * is decoded with `NavType.StringType`.
     */
    const val METHODS: String =
        "payment/methods/{$METHODS_ARG_PLAN_KEY}/{$METHODS_ARG_FROM_SETTINGS}"

    /**
     * Sentinel passed as `planKey` when the methods screen must resolve the active plan
     * from the membership store instead of from the navigation argument. Used by the
     * settings entry where the worker is already subscribed and the screen recaps the
     * existing subscription rather than a freshly picked plan.
     */
    const val CURRENT_PLAN_SENTINEL: String = "current"

    /**
     * Builds a concrete methods route.
     *
     * @param planKey stable plan identifier the worker just picked, or
     *   [CURRENT_PLAN_SENTINEL] when entering from settings.
     * @param fromSettings `true` when reached from the profile settings entry; controls
     *   the visibility of the "Cancel subscription" action on the methods screen.
     */
    fun methods(planKey: String, fromSettings: Boolean): String =
        "payment/methods/$planKey/$fromSettings"

    /** Add-card composer. The screen itself does not need to know the entry context. */
    const val NEW_CARD: String = "payment/new-card"

    /** Argument name carrying the freshly created `membership_id` on the company-selection route. */
    const val COMPANY_SELECTION_ARG_MEMBERSHIP_ID: String = "membershipId"

    /**
     * Route template: `payment/company-selection/{membershipId}`. The `membershipId` segment is
     * decoded with `NavType.StringType` (the demo-bypass flow passes the id as a string) and parsed
     * to a `Long` by the destination.
     */
    const val COMPANY_SELECTION: String =
        "payment/company-selection/{$COMPANY_SELECTION_ARG_MEMBERSHIP_ID}"

    /**
     * Builds a concrete company-selection route.
     *
     * @param membershipId the membership just created by the demo bypass, associated onto the
     *   worker's account once a company is picked.
     */
    fun companySelection(membershipId: Long): String = "payment/company-selection/$membershipId"

    /** Argument name carrying the plan key on the success route. */
    const val SUCCESS_ARG_PLAN_KEY: String = "planKey"

    /** Route template: `payment/success/{planKey}`. */
    const val SUCCESS: String = "payment/success/{$SUCCESS_ARG_PLAN_KEY}"

    /**
     * Builds a concrete success route.
     *
     * @param planKey stable plan identifier of the tier just paid for; persisted as the
     *   active plan when the worker taps the success screen's primary action.
     */
    fun success(planKey: String): String = "payment/success/$planKey"
}
