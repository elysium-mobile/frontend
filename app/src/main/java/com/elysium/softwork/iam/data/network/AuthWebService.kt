package com.elysium.softwork.iam.data.network

import com.elysium.softwork.iam.domain.model.User
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit contract for the live IAM endpoints of the FlowWork Spring Boot API.
 *
 * The same [User] bean carries both request bodies and response payloads (the bean
 * shortcut) — different endpoints fill different subsets of its nullable fields. Only the
 * employee paths are declared here: the HR/RRHH sign-up endpoint is intentionally absent,
 * since this client is exclusively the employee experience.
 *
 * All paths are **relative** — the host + `/` base lives in `BuildConfig.BACKEND_BASE_URL`
 * (resolved by `ApiClient`). The `Authorization` header is attached automatically by
 * `AuthInterceptor`; the two public auth paths below are skipped by it.
 */
interface AuthWebService {

    /**
     * Authenticates an existing worker. Send [User.email] + [User.password]; the response
     * fills [User.id] (the user-account id), [User.email], and [User.token].
     */
    @POST("api/v1/authentication/sign-in")
    suspend fun signIn(@Body credentials: User): Response<User>

    /**
     * Registers a new employee account. Send the employee sign-up subset of [User]
     * ([User.name], [User.last_name], [User.email], [User.password], [User.dni],
     * [User.anonymous_name], [User.date_start], [User.position], [User.salary]); the response
     * fills [User.id], [User.email], and [User.token].
     */
    @POST("api/v1/authentication/sign-up/employee")
    suspend fun signUpEmployee(@Body request: User): Response<User>

    /**
     * **Google Phase 1** — validates the Google `id_token` server-side and reports whether an
     * account already exists. Send [User.id_token] only. The response fills [User.registered]
     * (+ [User.id]/[User.email]/[User.token] when `registered == true`); it persists nothing for
     * new users.
     */
    @POST("api/v1/authentication/google")
    suspend fun googleSignIn(@Body request: User): Response<User>

    /**
     * **Google Phase 2 (employee)** — completes registration for a Google-authenticated worker.
     * Send [User.id_token] (re-validated; the trusted email is derived from it) plus the real
     * profile data ([User.name], [User.last_name], [User.phone_number], [User.dni],
     * [User.date_start], [User.position], [User.salary]). **No** `email` / `password` /
     * `anonymous_name` — the backend derives/auto-generates those. The response fills
     * [User.id]/[User.email]/[User.token].
     */
    @POST("api/v1/authentication/sign-up/employee/google")
    suspend fun googleSignUpEmployee(@Body request: User): Response<User>

    /**
     * Lists every employee profile. Used by the post-login sequential sync to locate the
     * worker's own row by matching [User.user_account_id] against the persisted account id,
     * then extracting [User.employee_profile_id].
     */
    @GET("api/v1/employee-profile")
    suspend fun getEmployeeProfiles(): Response<List<User>>

    /**
     * Lists every user account. Used by the post-login sequential sync to locate the worker's
     * own account by matching [User.user_account_id] against the persisted account id, then
     * extracting the [User.membership_id] foreign key that anchors the membership check.
     */
    @GET("api/v1/user_accounts")
    suspend fun getUserAccounts(): Response<List<User>>
}
