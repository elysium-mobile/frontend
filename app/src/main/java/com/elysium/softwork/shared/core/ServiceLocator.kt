package com.elysium.softwork.shared.core

import android.content.Context
import com.google.gson.Gson
import com.elysium.softwork.worker.forum.data.local.ForumDatabase
import com.elysium.softwork.worker.forum.data.network.ForumWebService
import com.elysium.softwork.worker.forum.data.store.ForumStore
import com.elysium.softwork.worker.forum.data.store.ForumStoreImpl
import com.elysium.softwork.iam.data.network.AuthWebService
import com.elysium.softwork.iam.data.store.AuthStore
import com.elysium.softwork.iam.data.store.AuthStoreImpl
import com.elysium.softwork.shared.data.local.SharedPrefsManager
import com.elysium.softwork.shared.data.network.ApiClient
import com.elysium.softwork.worker.forum.data.store.ForumReportStoreImpl
import com.elysium.softwork.worker.forum.domain.ForumReportStore
import com.elysium.softwork.feedback.data.store.FeedbackStore
import com.elysium.softwork.feedback.data.store.FeedbackStoreImpl
import com.elysium.softwork.feedback.data.network.FeedbackAssistantWebService
import com.elysium.softwork.feedback.data.network.SurveyWebService
import com.elysium.softwork.feedback.data.store.SurveyStore
import com.elysium.softwork.feedback.data.store.SurveyStoreImpl
import com.elysium.softwork.notifications.data.network.NotificationWebService
import com.elysium.softwork.notifications.data.store.NotificationStore
import com.elysium.softwork.notifications.data.store.NotificationStoreImpl
import com.elysium.softwork.payment.membership.data.network.MembershipWebService
import com.elysium.softwork.payment.membership.application.usecase.ValidateMembershipUseCase
import com.elysium.softwork.payment.membership.data.store.MembershipStore
import com.elysium.softwork.payment.membership.data.store.MembershipStoreImpl

/**
 * Manual service locator that owns process-wide singletons (shared preferences, the
 * Retrofit instance, per-context WebServices and Stores).
 *
 * Every singleton is exposed through `by lazy` so its construction cost — Retrofit proxy
 * generation, the Room database open call, SharedPreferences disk read — is deferred to
 * first access rather than paid on `Application.onCreate()`. Cold-start critical paths
 * (authentication, the payment gate) therefore avoid touching the forum database, the
 * forum WebServices, and any unrelated stores until the user actually navigates to them.
 *
 * Stores are exposed as their interface type so call sites depend on the contract rather
 * than the implementation, and the captured [Context] is normalized to the application
 * context up front to guarantee the locator can never retain an Activity reference.
 */
class ServiceLocator(context: Context) {

    private val appContext: Context = context.applicationContext

    val sharedPrefsManager: SharedPrefsManager by lazy { SharedPrefsManager(appContext) }

    /**
     * Installs the session-token supplier on [ApiClient] so [AuthInterceptor] can attach the
     * `Authorization: Bearer <token>` header to every authenticated request. Performed in the
     * constructor — before any store can trigger a network call — and reads the token live on
     * each request, so a fresh login or a logout is reflected immediately.
     */
    init {
        ApiClient.installTokenProvider { sharedPrefsManager.getString(SharedPrefsManager.KEY_AUTH_TOKEN) }
        // Route a mid-session 401 into the IAM session-invalidation trap. Resolved lazily inside
        // the closure so it only touches `authStore` when a 401 actually fires — cold start stays
        // free of the IAM store construction.
        ApiClient.installUnauthorizedHandler { authStore.invalidateSession() }
    }

    /**
     * Process-wide Gson used to deserialize structured error payloads (e.g. [BadRequestResponse]).
     * Reuses [ApiClient.gson] so the error-parsing path shares the exact same date policy
     * (ISO 8601 UTC instant with trailing `Z`) as the Retrofit converter — one configuration,
     * no drift.
     */
    private val gson: Gson by lazy { ApiClient.gson }

    private val authWebService: AuthWebService by lazy {
        ApiClient.retrofit.create(AuthWebService::class.java)
    }

    val authStore: AuthStore by lazy { AuthStoreImpl(authWebService, sharedPrefsManager, gson) }

    private val forumDatabase: ForumDatabase by lazy { ForumDatabase.create(appContext) }

    private val forumWebService: ForumWebService by lazy {
        ApiClient.retrofit.create(ForumWebService::class.java)
    }

    val forumStore: ForumStore by lazy {
        ForumStoreImpl(
            threadDao = forumDatabase.threadDao(),
            messageDao = forumDatabase.messageDao(),
            webService = forumWebService,
            gson = gson,
        )
    }

    val forumReportStore: ForumReportStore by lazy {
        ForumReportStoreImpl(webService = forumWebService, gson = gson)
    }

    private val surveyWebService: SurveyWebService by lazy {
        ApiClient.retrofit.create(SurveyWebService::class.java)
    }

    val surveyStore: SurveyStore by lazy { SurveyStoreImpl(surveyWebService, gson) }

    private val feedbackAssistantWebService: FeedbackAssistantWebService by lazy {
        ApiClient.retrofit.create(FeedbackAssistantWebService::class.java)
    }

    val feedbackStore: FeedbackStore by lazy {
        FeedbackStoreImpl(
            webService = feedbackAssistantWebService,
            gson = gson,
            companyIdProvider = {
                sharedPrefsManager
                    .getLong(SharedPrefsManager.KEY_COMPANY_ID)
                    .takeIf { it != SharedPrefsManager.DEFAULT_LONG }
            },
        )
    }

    private val notificationWebService: NotificationWebService by lazy {
        ApiClient.retrofit.create(NotificationWebService::class.java)
    }

    val notificationStore: NotificationStore by lazy {
        NotificationStoreImpl(notificationWebService, gson)
    }

    private val membershipWebService: MembershipWebService by lazy {
        ApiClient.retrofit.create(MembershipWebService::class.java)
    }

    val membershipStore: MembershipStore by lazy {
        MembershipStoreImpl(sharedPrefsManager, membershipWebService, gson)
    }

    /**
     * Session-authorization membership check. Reads the cached `membership_id` (resolved during
     * the post-login `user_accounts` sync) and validates the live subscription against
     * `GET /api/v1/memberships/{id}`, syncing the reactive gate. Invoked by `MainActivity` on
     * cold-start and after a successful login to decide between the main shell and payment
     * onboarding. Exposed as the use case itself (not a `StateFlow`) because it is an action.
     */
    val validateMembershipUseCase: ValidateMembershipUseCase by lazy {
        ValidateMembershipUseCase(membershipStore) {
            sharedPrefsManager
                .getLong(SharedPrefsManager.KEY_MEMBERSHIP_ID)
                .takeIf { it != SharedPrefsManager.DEFAULT_LONG }
        }
    }
}
