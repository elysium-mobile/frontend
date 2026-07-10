package com.elysium.softwork.shared.data.network

import com.elysium.softwork.BuildConfig
import com.elysium.softwork.shared.utils.constants.DateTimeFormats
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Process-wide Retrofit instance. Bounded contexts retrieve their typed WebService through
 * [retrofit] and call `create(MyWebService::class.java)` from their store implementation.
 *
 * Configuration sources (all compile-time, never embedded as Kotlin literals):
 *
 * - `BuildConfig.BACKEND_BASE_URL` — fed by the Secrets Gradle Plugin from
 *   `secrets.properties` (gitignored) with a fallback to `secrets.defaults.properties`
 *   (committed). The base URL is the **single source of truth**; WebService interfaces
 *   must only declare relative paths.
 * - `BuildConfig.API_KEY_GEMINI` / `API_KEY_EXTERNAL_SERVICE` — consumed by
 *   [ApiKeyInterceptor] to attach third-party credentials on a per-host basis.
 *
 * The session JWT is attached by [AuthInterceptor], which resolves the token through
 * [tokenProvider] **on every single request** — never a value captured in a field at build
 * time. `ServiceLocator` wires the provider to read `SharedPrefsManager.KEY_AUTH_TOKEN` live,
 * so each out-of-bounds call (the sequential `employee-profile` lookup, then the membership
 * endpoints, …) re-reads the current session state from disk. A token saved a moment earlier
 * by `sign-in` / `sign-up` is therefore visible to the very next call, and a logout stops
 * authorizing immediately — with no stale per-client cache to leak.
 *
 * There is intentionally **no** cached `authToken` field: caching the token reference is what
 * lets a later async call (e.g. `membership-plans`) ship without the header while an earlier
 * one (`employee-profile`) carried it. The single shared [okHttpClient] is reused by every
 * WebService, so this one live-reading interceptor governs the whole app.
 *
 * This object keeps its no-`Context` `object` shape; the provider closure is the only state.
 *
 * Debug builds get an [HttpLoggingInterceptor] at `BODY` level for inspecting requests in
 * Logcat; release builds omit it entirely (the dependency is `debugImplementation` only).
 */
object ApiClient {

    /**
     * Live JWT supplier consulted by [AuthInterceptor] on **every** authenticated request.
     * Wired by `ServiceLocator` to read `SharedPrefsManager.KEY_AUTH_TOKEN` so the value is
     * never stale. Defaults to a no-session provider so a build that forgets to wire it sends
     * no `Authorization` header rather than crashing. `@Volatile` so the installation performed on
     * the main thread during `Application.onCreate` is visible to OkHttp's dispatcher threads.
     */
    @Volatile
    private var tokenProvider: () -> String? = { null }

    /**
     * Handler invoked by [AuthInterceptor] when an authenticated request returns `401`.
     * Wired by `ServiceLocator` to `AuthStore.invalidateSession()`. Defaults to a no-op so an
     * un-wired build simply skips the graceful-degradation routing. `@Volatile` so the installation
     * on the main thread during `Application.onCreate` is visible to OkHttp's dispatcher threads.
     */
    @Volatile
    private var unauthorizedHandler: () -> Unit = {}

    /**
     * Registers the [provider] used to resolve the current session token for outgoing
     * requests. Call it once from `ServiceLocator` before any store triggers a network call.
     */
    fun installTokenProvider(provider: () -> String?) {
        tokenProvider = provider
    }

    /**
     * Registers the [handler] invoked when an authenticated call comes back `401 Unauthorized`
     * (the mid-session token-rejection trap). Call it once from `ServiceLocator`.
     */
    fun installUnauthorizedHandler(handler: () -> Unit) {
        unauthorizedHandler = handler
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(
                    tokenProvider = { tokenProvider() },
                    onUnauthorized = { unauthorizedHandler() },
                ),
            )
            .addInterceptor(
                ApiKeyInterceptor(
                    geminiApiKey = BuildConfig.API_KEY_GEMINI,
                    externalApiKey = BuildConfig.API_KEY_EXTERNAL_SERVICE,
                ),
            )
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BODY
                        },
                    )
                }
            }
            .build()
    }

    /**
     * Process-wide [Gson] carrying the uniform date policy. `setDateFormat` pins every
     * `java.util.Date` to the backend's [DateTimeFormats.ISO_LOCAL_DATE_TIME] pattern on both
     * serialization and deserialization, so any current or future `Date`-typed wire field maps
     * to `yyyy-MM-dd'T'HH:mm:ss` automatically. Exposed so callers that parse error payloads
     * (e.g. `ServiceLocator`'s `BadRequestResponse` reader) share the exact same configuration
     * — one Gson, no drift.
     *
     * Note: the timestamp fields currently on the domain beans are `String` (already ISO), which
     * Gson passes through untouched; those are formatted at the source via [DateTimeFormats].
     * This policy governs any `Date`/`Calendar` field the beans may adopt later.
     */
    val gson: Gson by lazy {
        GsonBuilder()
            .setDateFormat(DateTimeFormats.ISO_LOCAL_DATE_TIME)
            .create()
    }

    val retrofit: Retrofit by lazy {
        val baseUrl = BuildConfig.BACKEND_BASE_URL
        check(baseUrl.isNotBlank()) {
            "BACKEND_BASE_URL is blank. Copy secrets.defaults.properties to " +
                "secrets.properties and set BACKEND_BASE_URL=https://<your-backend>/ " +
                "before building."
        }
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
}
