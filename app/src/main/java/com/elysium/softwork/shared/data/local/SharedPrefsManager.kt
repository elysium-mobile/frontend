package com.elysium.softwork.shared.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Thin wrapper around the legacy [SharedPreferences] API for cross-cutting key/value storage.
 *
 * Intentionally minimal — Stores depend on it for small key/value primitives such as
 * credentials, session tokens, and feature flags. DataStore is not part of the locked
 * tech stack and is not introduced for the small surface area covered here.
 *
 * NOTE: this stores tokens in plain shared preferences. If the security team requires
 * encryption later, swap the backing store to `EncryptedSharedPreferences` here without
 * touching call sites.
 */
class SharedPrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the value for [key] or `null` when not set. */
    fun getString(key: String): String? = prefs.getString(key, null)

    /** Persists [value] under [key]. Pass `null` to clear the entry. */
    fun putString(key: String, value: String?) {
        prefs.edit {
            if (value == null) remove(key) else putString(key, value)
        }
    }

    /** Removes the entry for [key]. Idempotent. */
    fun remove(key: String) {
        prefs.edit { remove(key) }
    }

    /**
     * Atomically purges every IAM session key — the JWT, the account/profile ids, and the
     * cached credentials. Used on logout and on a cold-launch token-validation failure to drop
     * a stale/malformed session in a single commit, so the app returns cleanly to the
     * `LoginScreen` with no half-cleared state. Membership/anonymity feature flags are left
     * untouched (they are not part of the IAM session).
     */
    fun clearSession() {
        prefs.edit {
            remove(KEY_AUTH_TOKEN)
            remove(KEY_USER_ACCOUNT_ID)
            remove(KEY_EMPLOYEE_PROFILE_ID)
            remove(KEY_USER_EMAIL)
            remove(KEY_USER_PASSWORD)
            remove(KEY_GOOGLE_SESSION)
            remove(KEY_MEMBERSHIP_ID)
        }
    }

    /** Returns the boolean stored under [key] or [default] when not set. */
    fun getBoolean(key: String, default: Boolean = false): Boolean = prefs.getBoolean(key, default)

    /** Persists [value] under [key]. */
    fun putBoolean(key: String, value: Boolean) {
        prefs.edit { putBoolean(key, value) }
    }

    /**
     * Returns the [Long] stored under [key] or [default] when not set. Used for the backend
     * identity primary keys ([KEY_USER_ACCOUNT_ID], [KEY_EMPLOYEE_PROFILE_ID]) which the
     * Spring Boot API issues as numeric ids.
     */
    fun getLong(key: String, default: Long = DEFAULT_LONG): Long = prefs.getLong(key, default)

    /** Persists [value] under [key]. */
    fun putLong(key: String, value: Long) {
        prefs.edit { putLong(key, value) }
    }

    companion object {
        private const val PREFS_NAME = "softwork_prefs"

        /** Sentinel returned by [getLong] when no numeric id has been persisted yet. */
        const val DEFAULT_LONG: Long = -1L

        /** Storage key for the active session JWT (`Authorization: Bearer <token>`). */
        const val KEY_AUTH_TOKEN: String = "auth_token"

        /**
         * Storage key for the backend `user_account_id` (the `id` field of the sign-in
         * response). Persisted at login so the sequential employee-profile lookup can match
         * the worker's profile row, and so order/payment calls can reference the account.
         */
        const val KEY_USER_ACCOUNT_ID: String = "user_account_id"

        /**
         * Storage key for the backend `employee_profile_id`. Resolved by the post-login
         * sequential call to `GET /api/v1/employee-profile` and persisted for downstream
         * feedback / survey-response calls that key off the employee profile.
         */
        const val KEY_EMPLOYEE_PROFILE_ID: String = "employee_profile_id"

        /**
         * Storage key for the backend `membership_id` — the foreign key exposed on the worker's
         * `user_accounts` record. Resolved by the post-login sequential call to
         * `GET /api/v1/user_accounts` (matched by `user_account_id`) and used by the
         * session-authorization gate to query `GET /api/v1/memberships/{id}` for the live
         * subscription status + validity window. Part of the IAM session — cleared by
         * [clearSession].
         */
        const val KEY_MEMBERSHIP_ID: String = "membership_id"

        /** Storage key for the worker's email — retained for programmatic re-authentication. */
        const val KEY_USER_EMAIL: String = "user_email"

        /**
         * Storage key for the worker's plain-text password.
         *
         * Required because the backend exposes **no** token-refresh endpoint: after a
         * successful membership payment the app must re-invoke `sign-in` with the original
         * credentials to obtain a fresh active token. Stored in plain SharedPreferences like
         * the token; swap the backing store to `EncryptedSharedPreferences` here if the
         * security team mandates encryption (see the class note above).
         */
        const val KEY_USER_PASSWORD: String = "user_password"

        /**
         * Storage key flagging whether the active session was established through the Google
         * (Gmail) handshake rather than the credentials form. Persisted at login so the
         * `HTTP 401` trap can pick the correct recovery route: a Google-linked session stores
         * no local password and must re-authenticate through Google, not the `LoginScreen`.
         * Part of the IAM session — cleared by [clearSession].
         */
        const val KEY_GOOGLE_SESSION: String = "google_session"

        /** Storage key for the global anonymity master switch. */
        const val KEY_GLOBAL_ANONYMITY: String = "global_anonymity"

        /** Storage key for the per-context anonymity flag in the workers' forum. */
        const val KEY_FORUM_ANONYMITY: String = "forum_anonymity"

        /** Storage key for the per-context anonymity flag in surveys. */
        const val KEY_SURVEYS_ANONYMITY: String = "surveys_anonymity"

        /** Storage key for the per-context anonymity flag in incident reports. */
        const val KEY_REPORTS_ANONYMITY: String = "reports_anonymity"

        /**
         * Storage key for the membership gate flag. When `false`, an authenticated worker
         * must be routed exclusively into the payment graph; the main app shell stays
         * unreachable until a successful subscription flips this to `true`. Cleared by
         * `MembershipStore.cancelSubscription()`.
         */
        const val KEY_HAS_MEMBERSHIP: String = "has_membership"

        /**
         * Storage key for the active plan's stable [String] identifier (matches
         * `MembershipPlan.key`). Read by the methods screen to recap the next charge and by
         * any consumer that needs to gate features by tier.
         */
        const val KEY_CURRENT_PLAN: String = "current_plan"
    }
}
