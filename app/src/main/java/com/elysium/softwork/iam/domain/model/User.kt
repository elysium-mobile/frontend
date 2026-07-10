package com.elysium.softwork.iam.domain.model

/**
 * IAM user/profile entity — the single annotation-free bean that flows through every IAM
 * endpoint (the *Bean / Pragmatic Shortcut*).
 *
 * One immutable data class carries the request **and** the response of `sign-in`,
 * `sign-up/employee`, and `employee-profile`. Because those contracts overlap only
 * partially, every field is nullable. The backend serializes **uniform snake_case**
 * (`@JsonNaming(SnakeCaseStrategy)`), so every property name here is snake_case and Gson
 * resolves each by reflection from the property name — **no `@SerializedName` is present or
 * permitted**. The cost is that one class describes several wire shapes; the win is zero
 * DTO/mapper boilerplate.
 *
 * Wire asymmetry this bean absorbs (see ELYSIUM-API_DOCUMENTATION.md):
 * - employee-profile **request** sends [date_start]; the profile **response** returns the
 *   same date under [star_start] — a backend field typo (`starStart`) left intact, so the two
 *   snake_case keys coexist as separate nullable fields.
 *
 * The sign-in / sign-up response (`AuthenticatedUserAccountResponse`) returns only [id],
 * [email] and [token]; the address comes back under the *same* [email] key it was sent with,
 * so there is no separate response-email field.
 *
 * @property id user-account id returned by `sign-in` / `sign-up` (response key `id`). This is
 *   the `user_account_id` for downstream calls.
 * @property email email sent on the sign-in / sign-up **request** and echoed back on the
 *   **response** (wire key `email`, both directions).
 * @property password plain-text password sent on login/registration. Never returned populated.
 * @property token JWT issued on a successful `sign-in` / `sign-up`.
 * @property membership_status subscription tier reported alongside the session (`ACTIVE`,
 *   `PENDING`, `INACTIVE`). Drives the membership gate; `null` when the backend omits it.
 * @property name worker first name — employee sign-up request field.
 * @property last_name worker last name — employee sign-up request field.
 * @property phone_number contact phone — employee sign-up request field.
 * @property dni national id — employee sign-up request field (8 chars; see the 400 rule).
 * @property anonymous_name forum/survey pseudonym — employee sign-up request field.
 * @property position job title — employee sign-up / profile field.
 * @property salary monthly salary — employee sign-up / profile field.
 * @property date_start profile start date on the **creation request** (wire key `date_start`).
 * @property star_start profile start date on the **profile response** (wire key `star_start`,
 *   a backend typo of "dateStart").
 * @property employee_profile_id profile primary key returned by `employee-profile` responses.
 * @property user_account_id owning account id on `employee-profile` / `user_accounts` responses;
 *   matched against the persisted account id to find this worker's row in the list endpoints.
 * @property work_of_team_id team association on `employee-profile` responses.
 * @property user_id the domain user id on the `user_accounts` response.
 * @property membership_id foreign key to the worker's subscription on the `user_accounts`
 *   response; drives the session-authorization membership check (`GET /memberships/{id}`).
 * @property company_id owning company on the `user_accounts` response.
 */
data class User(
    // --- Authentication (sign-in / sign-up) ---
    val id: Long? = null,
    val email: String? = null,
    val password: String? = null,
    val token: String? = null,
    val membership_status: String? = null,

    // --- Employee sign-up request payload ---
    val name: String? = null,
    val last_name: String? = null,
    val phone_number: String? = null,
    val dni: String? = null,
    val anonymous_name: String? = null,
    val position: String? = null,
    val salary: Int? = null,
    val date_start: String? = null,

    // --- Employee profile response payload ---
    val star_start: String? = null,
    val employee_profile_id: Long? = null,
    val user_account_id: Long? = null,
    val work_of_team_id: Long? = null,

    // --- User-account response payload (`GET /api/v1/user_accounts`) ---
    val user_id: Long? = null,
    val membership_id: Long? = null,
    val company_id: Long? = null,

    // --- Native Google (Credential Manager) identity handshake ---
    val id_token: String? = null,

    // --- Google Phase-1 handshake response (`POST /authentication/google`) ---
    val registered: Boolean? = null,
) {

    /**
     * `true` only when the backend explicitly reports an `ACTIVE` membership. A `null`
     * status (the backend currently omits the field on sign-in) is treated as **not active**,
     * so the worker is routed through the payment onboarding gate until a successful
     * subscription is recorded.
     */
    fun isMembershipActive(): Boolean = membership_status.equals(MEMBERSHIP_ACTIVE, ignoreCase = true)

    companion object {
        /** Wire value of the active membership tier. */
        const val MEMBERSHIP_ACTIVE: String = "ACTIVE"
    }
}
