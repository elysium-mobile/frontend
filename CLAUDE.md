# SoftWork — Employee Client (Elysium)

Native Android client for the **Employee/Worker** side of Elysium's SoftWork product. This
repository is **exclusively** the Employee experience; the HR/Admin client lives elsewhere.

---

## Tech Stack (Locked)

| Concern        | Choice                                            |
|----------------|---------------------------------------------------|
| Language       | Kotlin (2.3.21) — `JavaVersion.VERSION_11`        |
| UI             | Jetpack Compose + Material 3 (BOM 2026.05.00)     |
| Navigation     | androidx.navigation:navigation-compose 2.9.8      |
| Networking     | Retrofit 3 + Converter-Gson                       |
| Persistence    | Room 2.8.4 (KSP)                                  |
| Images         | Coil 3                                            |
| Fonts          | Compose Google Fonts (Exo)                        |
| State (UI)     | androidx.lifecycle 2.10.0 — `viewmodel-compose` + `runtime-compose` |
| i18n           | AppCompat 1.7.x (`AppCompatDelegate.setApplicationLocales`) |

**Not in stack:** Hilt, Material Extended Icons, third-party design systems, DataStore.
Do not introduce them without an explicit decision recorded in this file.

SDKs: `minSdk = 29`, `targetSdk = 36`, `compileSdk = 36.1`. AGP 9.2.1, Gradle 9.4.1.

---

## Build Commands

```bash
./gradlew assembleDebug          # Compile + package the debug APK
./gradlew installDebug           # Install on the connected device/emulator
./gradlew build                  # Full build (assemble + lint + tests)
./gradlew lint                   # Static analysis only
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # Instrumented tests (requires device)
./gradlew clean                  # Wipe build outputs
```

On Windows (PowerShell): swap `./gradlew` for `.\gradlew.bat`.

---

## Architecture

**Package-by-feature, DDD-style bounded contexts** under `com.elysium.softwork`:

| Package              | Responsibility                                                                    |
|----------------------|-----------------------------------------------------------------------------------|
| `shared`             | Cross-cutting code (theme, components, i18n, network/local infra, core utilities) |
| `iam`                | Identity & Access — Employee login, session, profile                              |
| `worker.forum`       | Workers Forum — posts, comments, reactions                                        |
| `feedback`           | Check-ins & Reports — periodic feedback to HR                                     |
| `payment.membership` | Payment processing — transactions, invoices                                       |

**Each context owns four layers:**

```
<context>/
├── domain/                  # Pure entities + contracts. No framework imports, no annotations.
├── data/store/              # FooStore implementations (Retrofit + Room)
├── application/usecase/     # Use cases — the only home of business operations
└── presentation/
    ├── viewmodel/           # UI state holders. Delegate every operation to a use case.
    ├── views/               # Composables
    └── navigation/          # Route catalogs + NavGraphBuilder wiring
```

**Layer rules (enforced in review):**
- **Domain is 100% framework-agnostic.** No Gson/Room/Retrofit/Compose imports in
  `domain/model/` — with one documented exception (see "Domain purity" below).
- **ViewModels live in `presentation/viewmodel/`, never in `application/`.** They hold UI
  state via read-only `StateFlow`s and delegate every business operation to a use case.
- **`application/usecase/` houses the use cases** — small, stateless classes with an
  `operator fun invoke(...)` that wrap exactly one business operation (input
  normalization, entity assembly, dispatch to a Store). Factories on the ViewModel
  companions assemble them from the `ServiceLocator` stores.

### Domain purity

Domain entities carry **zero serializer annotations**. Property names match the backend
wire keys exactly, so Gson resolves fields by reflection without `@SerializedName`. If a
wire contract ever diverges from the entity's property names, introduce a DTO under
`data/network/dto/` and map it into the domain entity — never re-annotate the domain.

Two consequences to keep in mind:
- **R8/minify**: reflection-based Gson requires keep rules for the model packages if
  `isMinifyEnabled` is ever turned on (it is currently `false`). Add
  `-keep class com.elysium.softwork.**.domain.model.** { *; }` before enabling shrinking.
- **`Post` is the one documented exception**: it keeps Room's `@Entity`/`@PrimaryKey`
  because it doubles as the offline-first cache row. Splitting it would force a mapper
  on every feed emission — doubled allocations on the hot scroll path of the lowest-end
  target devices. No other domain entity may carry Room annotations.

### Store Pattern

The data layer uses **Stores**, not Repositories — a frontend-friendly term that avoids the
`*RepositoryImpl` suffix.

```kotlin
// domain/PostStore.kt
interface PostStore {
    suspend fun list(): List<Post>
    suspend fun publish(content: String): Post
}

// data/store/PostStoreImpl.kt
class PostStoreImpl(
    private val api: ForumApi,
    private val dao: PostDao,
) : PostStore { /* ... */ }
```

### Bean / Pragmatic Shortcut

A deliberate shortcut shared by every context: **a single annotation-free Kotlin
`data class` flows through the Retrofit WebService for both request bodies and response
payloads** — no DTOs, no assemblers, no mappers. Gson matches fields by name via
reflection, which is why domain property names mirror the wire keys 1:1. Different
endpoints fill different subsets of the model (login fills `email`/`password`, the
response fills `id`/`token`/…), so all fields are nullable.

```kotlin
// iam/domain/model/User.kt — no framework imports
data class User(
    val id: String? = null,
    val email: String? = null,
    val password: String? = null,
    val token: String? = null,
    /* ... */
)

// iam/data/network/AuthWebService.kt
interface AuthWebService {
    @POST("auth/login") suspend fun login(@Body credentials: User): Response<User>
}
```

Trade-off accepted: simpler code, faster iteration; cost: a single class describes
multiple wire shapes, and renaming a property is a silent wire break (covered by review +
the integration suite once the backend lands). Re-introduce DTOs under
`data/network/dto/` the moment the wire contract diverges meaningfully from the domain
model.

### Use Case Pattern

Every business operation is a dedicated class in `application/usecase/` with an
`operator fun invoke(...)`. Use cases are stateless, hold a single Store (or
`SharedPrefsManager`) reference, and own the rules that must not drift between callers —
input trimming, entity assembly, identity blanking for anonymous content, mock delays.

```kotlin
// iam/application/usecase/LoginUseCase.kt
class LoginUseCase(private val store: AuthStore) {
    suspend operator fun invoke(email: String, password: String): Result<User> =
        store.login(email.trim(), password)
}

// iam/presentation/viewmodel/AuthViewModel.kt — pure UI state holder
class AuthViewModel(
    private val loginUseCase: LoginUseCase,
    /* ... */
) : ViewModel() {
    fun submitLogin() { /* gate, then */ runRequest { loginUseCase(email, password) } }
}
```

ViewModel `Factory` companions assemble the use cases from `ServiceLocator` stores;
unit tests assemble them from fakes — same wiring, no locator.

### Dependency Wiring (no Hilt)

A manual [`ServiceLocator`](app/src/main/java/com/elysium/softwork/shared/core/ServiceLocator.kt)
owned by `SoftWorkApplication` exposes process-wide singletons (SharedPreferences, Retrofit,
each context's WebService, each context's Store). ViewModels receive their **use cases**
via a `ViewModelProvider.Factory` exposed on the ViewModel companion — the factory pulls
the Store from the locator and wraps it in the context's use cases (see
`AuthViewModel.Factory`). Composables resolve the ViewModel with
`viewModel(factory = AuthViewModel.Factory)`. Stores never reach a ViewModel directly.

### Shared Utilities Layout (`shared/utils/`) and Route Catalogs

To keep cross-cutting primitives in one predictable place — and to avoid every bounded
context shipping its own enum scattered across `application/` and `domain/model/` — **all
enums live in [`shared/utils/`](app/src/main/java/com/elysium/softwork/shared/utils)**,
split into two subpackages by purpose:

- **[`shared/utils/values/`](app/src/main/java/com/elysium/softwork/shared/utils/values)** —
  **value-bearing enums.** Each entry pairs the constant with a stable wire `key` (and
  often a `@StringRes labelRes` for localized labels). These are the enums you reach for
  when you need a closed set of options serialized to/from the backend and rendered to
  the user.
  - `AppLocale` (`tag`), `ForumCategory` / `ReportType` / `ReportArea` (`key` + `labelRes`),
    `ReportStatus` (`key`).
  - Pattern:
    ```kotlin
    enum class ForumCategory(val key: String, @param:StringRes val labelRes: Int) {
        SUGGESTIONS("suggestions", R.string.forum_category_suggestions),
        /* ... */ ;
        companion object {
            fun fromKey(key: String?): ForumCategory? = entries.firstOrNull { it.key == key }
        }
    }
    ```

- **[`shared/utils/discriminators/`](app/src/main/java/com/elysium/softwork/shared/utils/discriminators)** —
  **discriminator enums.** Pure sum types with no payload; they exist only to drive a
  `when` branch in a composable or nav graph.
  - `ButtonVariant { EMPLOYEE, HR }`, `SuccessKind { LOGIN, REGISTER }`.

- **[`shared/utils/constants/`](app/src/main/java/com/elysium/softwork/shared/utils/constants)** —
  **process-wide value catalogs that aren't enums.** One `object` per category, exposing
  named `val`s. Examples:
  - `Regexes.EMAIL` — the loose RFC 5322 surrogate used by every email validator.
  - `Domains.PERSONAL` — personal-provider domains that fail the "corporate email" check.
  - Pattern:
    ```kotlin
    object Regexes {
        val EMAIL: Regex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
    ```

**Adding a new enum or constant?** Decide first:
- Carries a value (key/label)? → enum in `values/`.
- Pure marker? → enum in `discriminators/`.
- Reusable literal (regex, set, lookup table)? → `object` in `constants/`.

Don't create context-local enums in `application/` or `domain/model/`, and don't inline
regex/domain literals inside a validator — promote them here so the catalog stays canonical.

**Route catalogs live next to their `NavGraphBuilder`, but in their own file.** Each
navigation folder has both `XNavigation.kt` (the `NavHost` / `NavGraphBuilder` wiring)
and `XRoutes.kt` (the `object` of `const val` strings + route builders):

- [`iam/presentation/navigation/AuthRoutes.kt`](app/src/main/java/com/elysium/softwork/iam/presentation/navigation/AuthRoutes.kt) + `AuthNavigation.kt`
- [`worker/forum/presentation/navigation/ForumRoutes.kt`](app/src/main/java/com/elysium/softwork/worker/forum/presentation/navigation/ForumRoutes.kt) + `ForumNavigation.kt`
- [`shared/presentation/navigation/MainRoutes.kt`](app/src/main/java/com/elysium/softwork/shared/presentation/navigation/MainRoutes.kt) + `MainNavigation.kt`

This split lets any composable or other navigation host import the route catalog without
pulling in the `NavGraphBuilder` extension. Cross-context destinations (e.g. the Foro
bottom tab pointing at `ForumRoutes.FEED`) become single-import references with no
incidental coupling to the foreign graph's wiring.

---

## UI Conventions

- **Always wrap UI in `SoftWorkTheme`.** Defined at `shared/presentation/theme/Theme.kt`.
- **Use the brand components first** — `SoftWorkButton`, `SoftWorkTextField`, `SoftWorkCard` in
  `shared/presentation/components/`. Reach for raw Material 3 widgets only when the brand
  component genuinely cannot express the requirement; document the reason in a code comment.
- **Buttons declare a variant** — `ButtonVariant.EMPLOYEE` (gradient, default) or
  `ButtonVariant.HR` (solid navy). Never hard-code a colored `Button`.
- **No raw colors in feature code.** Pull from `MaterialTheme.colorScheme` or
  `SoftWorkTheme.colors` — never `Color(0xFF…)` outside `shared/presentation/theme/Color.kt`.
- **Icons:** `painterResource(R.drawable.ic_*)` only. New icons land as XML vector drawables
  under `app/src/main/res/drawable/`. Do **not** add `material-icons-extended`.

---

## i18n Workflow

User-facing text lives **only** in Android string resources:

- `app/src/main/res/values/strings.xml` — English (default).
- `app/src/main/res/values-es/strings.xml` — Spanish.

**Every new key must land in both files in the same change.** Never hard-code a literal in a
Composable; always `stringResource(R.string.key)`.

**Switching language at runtime** uses the modern AppCompat API:

```kotlin
LocaleHelper.apply(AppLocale.ES)
```

AppCompat persists the choice (via the `AppLocalesMetadataHolderService` declared in the
manifest with `autoStoreLocales=true`) and re-applies the configuration automatically. **Do
not** call `Activity.recreate()`, override `attachBaseContext`, or wrap `Configuration` —
those approaches are obsolete and incompatible with App Bundles + per-app language settings.

---

## KDoc Standards

- **English only** — across code, comments, log messages, and identifiers.
- **KDoc required** on every public class, interface, function, and enum. Document the
  *why*, not just the signature. Mention defaults, units, and side effects.
- Document parameters with `@param`, return values with `@return`, and example usage in a
  fenced ` ``` ` block when the API has more than one obvious shape.

---

## Testing Architecture

The test suite is split structurally across two source sets matching the JVM vs. device
execution boundary. **No file crosses the boundary** — test rules, fakes, and helpers
that touch coroutines or stores live under `src/test/`; anything that constructs a
Compose semantics tree lives under `src/androidTest/`. Production source is exclusively
under `src/main/` and never imports any test type.

### Dependencies

Added to `gradle/libs.versions.toml` and `app/build.gradle.kts`:

| Artifact | Configuration | Purpose |
|---|---|---|
| `kotlinx-coroutines-test` | `testImplementation`, `androidTestImplementation` | `runTest`, `TestDispatcher`, `StandardTestDispatcher`, virtual-time helpers. |
| `kotlinx-coroutines-android` | `implementation` (main) | **Pinned at the same version as `kotlinx-coroutines-test`** so the runtime ABI on the device matches what the test machinery was compiled against. Without this pin, `BuildersKt.runBlockingK$default(...)` is absent at runtime and Compose UI tests crash with `NoSuchMethodError`. |
| `androidx.lifecycle:lifecycle-runtime-testing` | `androidTestImplementation` | `TestLifecycleOwner` for the lifecycle-aware collection proof. |

### `src/test/java/` — JVM unit tests

| Path | Role |
|---|---|
| `testsupport/MainDispatcherRule.kt` | JUnit4 `TestRule` swapping `Dispatchers.Main` with a `TestDispatcher`. Default is `UnconfinedTestDispatcher` (eager dispatch — `StateFlow.value` reads immediately after a `submit*` call see the post-launch snapshot). Pass `StandardTestDispatcher()` when a test must observe transient states (e.g. `Loading` between `Idle` and `Success`). |
| `testsupport/FakeAuthStore.kt` | In-memory `AuthStore` double. Programmable `nextLoginResult` / `nextRegisterResult`, invocation counters, captured-argument tuples (`lastLoginArgs`, `lastRegisterArgs`, …). |
| `testsupport/FakeMembershipStore.kt` | In-memory `MembershipStore` double backed by `MutableStateFlow`s. Public `seedMembership(active, planKey)` and `seedPaymentMethods(...)` mutators bypass the public path for pre-state tests. |
| `testsupport/FakeFeedbackStore.kt` | In-memory `FeedbackStore` double. `send(...)` appends the user message synchronously, suspends `delay(mockReplyDelayMillis)` on virtual time, then appends a programmable AI reply. |
| `testsupport/FakePostStore.kt` | In-memory `PostStore` double with a `MutableStateFlow` backing `observe()` and an `emit(list)` test-only mutator. Records `refresh` / `publish` invocations. |
| `testsupport/FakeNotificationStore.kt` | In-memory `NotificationStore` double exposing the feed through a `MutableStateFlow` with a test-only `emit(...)` helper. |
| `iam/application/AuthValidationTest.kt` | Pure JVM coverage of `AuthValidation` — email regex, corporate-domain classification across every entry in `Domains.PERSONAL`, password length boundary, confirmation match, username trim. |
| `iam/presentation/viewmodel/AuthViewModelTest.kt` | Form-state mutators, `submitLogin` no-op for blank input, `Idle → Success / Error` transitions, trimmed-field forwarding (owned by the use cases), `consumeState` reset. |
| `payment/membership/presentation/viewmodel/MembershipViewModelTest.kt` | `CardFormState` input filtering and `isValid` boundaries, `PaymentState` state-machine (`Idle → Processing → Succeeded`) with `StandardTestDispatcher`, re-entrancy guard, activation/cancellation forwarding. |
| `feedback/presentation/viewmodel/AiChatViewModelTest.kt` | Drives the chat flow against virtual time: instant user-message append, `isSending = true` mid-round-trip, AI reply append, `isSending = false` in `finally`. Covers blank-input rejection and the re-entrancy guard. |
| `worker/forum/presentation/viewmodel/ForumViewModelTest.kt` | Launches a `backgroundScope.launch { vm.posts.collect {} }` to satisfy `stateIn(WhileSubscribed)`; verifies init-time refresh, category filter, null-category reset, stable item identity across emissions. |
| `notifications/presentation/viewmodel/NotificationsViewModelTest.kt` | Asserts init-time collector lands the first snapshot, subsequent emissions update the flow, list items keep stable identity (`assertSame`), `NotificationType` discriminators survive the round trip. |

### `src/androidTest/java/` — instrumentation tests

The supported entry point is **`androidx.compose.ui.test.junit4.v2.createComposeRule`** —
the JUnit4 v2 rule. The legacy `androidx.compose.ui.test.junit4.createComposeRule` is
deprecated, and the top-level `androidx.compose.ui.test.runComposeUiTest` builder is also
deprecated in favour of `androidx.compose.ui.test.v2.runComposeUiTest`. The v2 JUnit4
rule keeps the class-scoped `composeRule` property the project uses everywhere while
routing through the same v2 internals as the builder — no test-body migration required.

| Path | Role |
|---|---|
| `testsupport/ComposeTestingGuide.kt` | Standalone reference (no `@Test` methods) documenting the v2 JUnit4 rule template, semantics-tree query order (`onNodeWithText` → `onNodeWithContentDescription` → `onNodeWithTag`), the per-test 500 ms budget, and the runtime-coupling note on the `kotlinx-coroutines-android` pin. |
| `testsupport/LifecycleAwareCollectionTest.kt` | Drives a `TestLifecycleOwner` across `STARTED ↔ CREATED` to prove `collectAsStateWithLifecycle()` pauses collection (and therefore CPU) on backgrounding. |
| `shared/presentation/components/SoftWorkButtonTest.kt` | Label rendering, click action exposure, enabled/disabled gating, HR variant smoke. |
| `shared/presentation/components/SoftWorkTextFieldTest.kt` | Placeholder rendering, hoisted `onValueChange` emission via `performTextInput`, full clearance via `performTextClearance`. |
| `shared/presentation/components/SoftWorkCardTest.kt` | Slot content surfaces in the semantics tree; nested layouts propagate intact. |
| `shared/presentation/components/InitialsAvatarTest.kt` | Initials derivation: two-word names, single-word names, lowercase input uppercased, multi-whitespace collapse. |

### Conventions

- **Real use cases over fake stores** — tests build the ViewModel with the production
  use cases wrapping a fake store: `AuthViewModel(LoginUseCase(FakeAuthStore()), …)`.
  The use cases are stateless pass-throughs (plus normalization rules), so the fake
  remains the single observation point while the test still exercises the production
  wiring. The `Factory` companions are intentionally bypassed because their only role is
  to pull dependencies out of `SoftWorkApplication.serviceLocator` — that glue belongs to
  instrumentation, not host-machine unit tests.
- **`runTest(mainDispatcherRule.testDispatcher) { ... }`** — pass the rule's dispatcher
  into `runTest` so both the rule's `Dispatchers.Main` swap and the test body share the
  same virtual-time scheduler.
- **`backgroundScope` for hot subscriptions** — when a `stateIn(WhileSubscribed)` flow is
  under test, subscribe via `backgroundScope.launch { vm.x.collect {} }`. `backgroundScope`
  cancels automatically when the test returns, so no coroutine job leaks.
- **Lifecycle-safe collection contract** — every screen-level flow consumer uses
  `collectAsStateWithLifecycle()`; the `LifecycleAwareCollectionTest` is the canonical
  proof that the contract holds. Adding a new consumer that uses `collectAsState()` is a
  regression and must be caught in review.
- **Stable identity on lists** — every `LazyColumn`/`LazyRow` declares
  `key = { item.id }`. Tests assert that re-emissions of unchanged entries keep their
  reference (`assertSame`) so the diffing layer short-circuits.

### How to run

```bash
./gradlew test                  # JVM unit tests, no device required (~seconds)
./gradlew connectedAndroidTest  # Instrumentation tests (requires device or emulator)
```

---

## Current Progress

### ✅ Phase 1 — Scaffolding & Design System (complete)

- DDD package skeleton for `shared`, `iam`, `forum`, `feedback` (each with
  `domain/`, `data/store/`, `application/`, `presentation/`).
- Compose design system: `Color.kt`, `Type.kt` (bundled Exo variable font + downloadable
  upgrade — see "Brand typography" below), `Shape.kt`, `Theme.kt`.
- Brand components: `SoftWorkButton` (Employee + HR variants), `SoftWorkTextField`,
  `SoftWorkCard`.
- Native i18n: `AppLocale`, `LocaleHelper` (AppCompat back-port), English + Spanish strings.
- `SoftWorkApplication` wired in the manifest. Manifest theme renamed to `Theme.SoftWork`.

#### Brand typography — bundled Exo + downloadable upgrade

Earlier scaffolding declared Exo as a pure downloadable Google Font. That caused three
visible failure modes: (1) async download race produced a first-frame FOUT, (2) emulators
without Google Play Services Fonts silently fell back to Roboto, (3) Compose `@Preview`
never downloads at all. [Type.kt](app/src/main/java/com/elysium/softwork/shared/presentation/theme/Type.kt)
now declares a **layered `ExoFontFamily`**:

1. **Bundled variable font** — `res/font/exo_variable.ttf` (175 KB, official Google Fonts
   release under SIL OFL 1.1). Resource fonts default to `FontLoadingStrategy.Blocking`,
   so the very first composition renders in Exo with no FOUT and no network/GMS
   dependency. The TrueType GX `wght` axis is pinned per weight via
   `FontVariation.Settings(FontVariation.weight(N))`.
2. **Downloadable Google Fonts entry** — the original `Font(googleFont = ExoGoogleFont, …)`
   declarations remain as a secondary layer. When the GMS provider serves an optimized
   weight, Compose swaps it in transparently; until then the bundled font keeps the UI
   correct.

`FontVariation.Settings` on `Font(resId, …)` is annotated `@ExperimentalTextApi`. Per the
"granular opt-in" rule, the experimental call is wrapped in a single private helper
`exoVariableFont(weight)` carrying `@OptIn(ExperimentalTextApi::class)` — the file itself
does **not** use `@file:OptIn`. Future Compose churn around this API can only break that
one helper. Apply the same pattern when introducing any `ExperimentalMaterial3Api`
composable (e.g., `TopAppBar`, `SearchBar`): annotate the smallest declaration that
touches the experimental surface, and document why in a KDoc line above the annotation.

License: ship the SIL OFL text alongside the font when releasing to production (typical
placement: `app/src/main/assets/fonts/OFL.txt`). Not yet wired up — flag before the next
public build.

### ✅ Phase 2 — IAM bounded context (complete)

- **Domain**: `User` and `AuthToken` data classes annotated with `@SerializedName`. Shared
  between request/response per the bean/pragmatic-shortcut pattern.
- **Data**: `AuthWebService` (Retrofit), `AuthStore` (interface) + `AuthStoreImpl` orchestrating
  the WebService and persisting the JWT in `SharedPrefsManager`. Process-wide Retrofit
  instance lives in `shared/data/network/ApiClient.kt`.
- **Application**: `AuthViewModel` exposing `state: StateFlow<AuthState>` and
  `form: StateFlow<FormState>`. Validation in `AuthValidation` (pure, JVM-testable).
- **Presentation**: `LoginScreen`, `RegisterScreen`, `RegisterGoogleScreen`,
  `AuthSuccessScreen` plus shared IAM components (`PasswordVisibilityToggle`,
  `RoleSelectorCard`, `GoogleOutlineButton`, `BackTopBar`, `VerifiedDomainChip`).
- **Navigation**: `AuthNavHost` in `iam/presentation/navigation/AuthNavigation.kt`. Routes:
  `auth/login`, `auth/register`, `auth/register-google`, `auth/success/{kind}`.
- **Wiring**: `SoftWorkApplication.serviceLocator` exposes `authStore`. `AuthViewModel.Factory`
  pulls it from the application via `CreationExtras`. `MainActivity` mounts `AuthNavHost`.
- **Icons added** as XML vector drawables: `ic_visibility`, `ic_visibility_off`, `ic_check`,
  `ic_check_circle`, `ic_arrow_back`, `ic_user`, `ic_logo`, `ic_google`.

#### New dependencies (with rationale)

- `androidx.appcompat:appcompat:1.7.x` — required for `AppCompatDelegate.setApplicationLocales`
  (Phase 1).
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0` — Compose-aware `viewModel(...)`
  resolver, needed to instantiate `AuthViewModel` with a `CreationExtras`-aware factory.
- `androidx.lifecycle:lifecycle-runtime-compose:2.10.0` — `collectAsStateWithLifecycle`, the
  lifecycle-safe replacement for `collectAsState` on Compose surfaces.

### ✅ Phase 3 — Authenticated shell (complete)

- **Bottom navigation**: `MainNavHost` (Scaffold + Material 3 `NavigationBar`, 72.dp height)
  in `shared/presentation/navigation/MainNavigation.kt`. Tabs: Menú (Home), Perfil
  (Profile), Foro (placeholder), Notificaciones (placeholder). Active color `PrimarySky`,
  inactive `AccentMint`, container white, no indicator pill.
- **HomeScreen** (`shared/presentation/home/`) — greeting + initials avatar, 5-emoji mood
  check-in card with anonymous badge, three action cards (Report incident, Internal forums,
  AI Assistant) using `SoftWorkCard` + `painterResource` icons.
- **ProfileScreen** (`shared/presentation/profile/`) — header with edit affordance, hero
  with `InitialsAvatar`, four section cards (Información laboral / Privacidad / Idioma /
  Pago), outline `Editar perfil` button, `Cerrar sesión` text button (Danger color) wired
  to a logout callback.
- **Live language toggle** in the Idioma card calls
  `LocaleHelper.apply(AppLocale.ES | EN)` → `AppCompatDelegate.setApplicationLocales(...)`.
  AppCompat persists via the manifest service and recreates the activity transparently.
- **Auth ↔ Main routing**: `MainActivity` now extends `AppCompatActivity` (so AppCompat's
  locale back-port can recreate the Activity on API 29-32) and swaps between `AuthNavHost`
  and `MainNavHost` based on `rememberSaveable<Boolean>` flipped by `onAuthComplete` /
  `onLogout`. Logout calls `AuthStore.clearSession()` and pops the user back to the IAM graph.
- **Shared component**: `InitialsAvatar` in `shared/presentation/components/` derives initials
  from a name and renders a circular brand-colored avatar.
- **Icons added**: `ic_home`, `ic_person`, `ic_forum`, `ic_notifications`, `ic_shield`,
  `ic_people`, `ic_sparkle`, `ic_chevron_right`, `ic_edit`.

#### Phase 3 extension — Identity Protection flow

- **Persistence**: `SharedPrefsManager` gained `getBoolean` / `putBoolean` plus four new
  keys: `KEY_GLOBAL_ANONYMITY`, `KEY_FORUM_ANONYMITY`, `KEY_SURVEYS_ANONYMITY`,
  `KEY_REPORTS_ANONYMITY`.
- **Domain**: `AnonymityPreferences` data class in `shared/domain/identity/` bundles the
  four flags as a single immutable snapshot.
- **Application**: `AnonymityViewModel` in `shared/application/` loads from prefs on
  construction, exposes a `StateFlow<AnonymityPreferences>` for the in-memory edit buffer,
  and flushes to prefs only on explicit `save()`. Wired through a `Factory` reading
  `SharedPrefsManager` from the service locator (same pattern as `AuthViewModel`).
- **Presentation**: `ProtectedIdentityScreen` in `shared/presentation/identity/` —
  back-arrow header, anonymous-user hero, global-toggle card, three-row granular toggle
  card with 0.5.dp dividers, AccentMint-tinted HR info banner with `ic_lock`, and a
  primary `Guardar preferencias` button.
- **Navigation**: new `MainRoutes.PROTECTED_IDENTITY` route. The Profile "Modo anónimo en
  foro" row now navigates to it; tapping Save pops back to Profile.
- **Icons added**: `ic_lock`.

**UX note** — toggles edit an in-memory buffer; persistence is explicit on Save. Backing
out of the screen discards unsaved changes. To switch to write-through behavior, route the
toggle handlers to call `viewModel.save()` after each `setX(...)`.

#### Architectural note — `AppCompatActivity` for locale back-port

`MainActivity` was migrated from `ComponentActivity` to `AppCompatActivity` so that
`AppCompatDelegate.setApplicationLocales` triggers automatic activity recreation on API
29-32. This is the official path documented at
[developer.android.com/guide/topics/resources/app-languages](https://developer.android.com/guide/topics/resources/app-languages).
On API 33+ the platform `LocaleManager` handles recreation regardless of the activity base
class. AppCompatActivity remains fully Compose-compatible — `enableEdgeToEdge()` and
`setContent` work as before.

### ✅ Phase 4 — Forum bounded context (complete, offline-first)

> **⚠️ Superseded by Phase 12 (Forum backend integration).** The flat `Post` model is gone:
> the context now mirrors the backend `Forum → Category → Thread → Message` hierarchy
> (+ `Asset`, `Report`), the Room cache holds `threads`/`messages` tables, `ForumStoreImpl`
> is Retrofit-backed (no `SeedPosts`), and the screens render Threads/Messages. See the
> Phase 12 section below. The historical Phase 4 notes are retained for context.

- **Domain**: [`Post`](app/src/main/java/com/elysium/softwork/forum/domain/model/Post.kt) data
  class doubles as a Room `@Entity(tableName = "posts")` and a Gson `@SerializedName` bean —
  the same instance flows through `PostWebService` requests/responses and into the local
  cache. All non-key fields default so partially populated drafts can be passed end-to-end.
- **Data — local**: `PostDao` exposes `getAllPosts(): Flow<List<Post>>`, a `count()` for the
  seed branch, and `upsertAll` / `upsert` (REPLACE on conflict). `ForumDatabase` (Room
  v1, `forum.db`) is built via a `companion object create(context)`.
- **Data — network**: `PostWebService` declares `@GET("posts")` and `@POST("posts")`. Both
  endpoints carry `Post` directly per the bean shortcut.
- **Data — store**: `PostStore` interface + `PostStoreImpl` implementing the
  **offline-first** contract:
  - `observe()` returns the Room `Flow` directly — UI always renders cached state.
  - `refresh()` tries the network and upserts on success; **fallback**: when the cache is
    empty after a failed refresh it seeds three bundled sample posts so the demo is alive
    without a backend. Remove the seed when the API is live.
  - `publish(...)` always inserts locally (using a UUID when the server is unreachable) so
    the user sees their post immediately; the next refresh reconciles.
- **Application**: `ForumCategory` enum (`SUGGESTIONS`, `QUESTIONS`, `EVENTS`, `CONFLICTS`)
  carries the wire `key` + the localized `labelRes`. ViewModels in
  `forum/application/viewmodel/`:
  - `ForumViewModel` — combines the cached posts flow with an in-memory category filter,
    triggers `store.refresh()` on init, exposes `posts: StateFlow<List<Post>>`.
  - `NewPostViewModel` — reads `forum_anonymity` from `SharedPrefsManager` on construction
    and surfaces it via `isAnonymous`. The user does **not** toggle anonymity here; it must
    be set on the protected-identity screen. Form has a 500-char limit, exposes
    `PublishState` (Idle / Publishing / Published / Error).
  - `ThreadViewModel` — loads a single post by id, surfaces `isAnonymous` for the sticky
    input. Comments are static samples in Phase 4 (Comment store lands in Phase 5).
- **Presentation**: screens under `forum/presentation/views/{feed,newpost,thread}/`,
  components (`AnonymousBadge`, `CategoryChips`, `Chip`) under
  `forum/presentation/components/`. Navigation in `forum/presentation/navigation/`:
  `ForumRoutes` (`forum/feed`, `forum/new-post`, `forum/thread/{postId}`) +
  `forumGraph(navController, userName)` extension that `MainNavHost` invokes.
- **MainNavigation update**: the Foro bottom tab now routes to `ForumRoutes.FEED`. The
  in-progress placeholder screen for the Foro tab was removed; the home action card
  ("Internal forums") also navigates to `ForumRoutes.FEED`.
- **Wiring**: `ServiceLocator` now owns a process-wide `ForumDatabase` and exposes
  `postStore: PostStore`. ViewModel factories pull `postStore` (and `sharedPrefsManager`)
  from the locator just like `AuthViewModel` and `AnonymityViewModel`.
- **Icons added**: `ic_close`, `ic_send`, `ic_paperclip`, `ic_image`, `ic_add` (FAB glyph).

#### Phase 4 caveats

- The 3 seeded posts are hard-coded in `PostStoreImpl.SeedPosts` — **delete that companion
  block** when the live `/posts` endpoint is ready.
- Comments are stubbed in `ThreadScreen.SAMPLE_COMMENTS`. A real Comment domain + store +
  WebService land in Phase 5.
- `forum_anonymity` is read **once** on ViewModel construction. Re-enter the new-post or
  thread screen to pick up a privacy preference change. Switch to a `Flow` if real-time
  updates are needed.
- Image / attachment toolbar buttons are no-ops (Phase 5 will wire the picker).

### 🧪 Mock Testing Harness (temporary — for UI walkthroughs before the backend lands)

> **⚠️ Superseded for IAM (Phase 9).** Items **#1 and #2 below no longer apply** — the IAM
> stack now talks to the live Spring Boot API (see "Phase 9 — Backend integration (IAM +
> shared)" below). `AuthStoreImpl.login` is real, the `MOCK_*` companion is deleted, and the
> only remaining shortcut is **#3 (forum seed)**, which is out of Phase 9's scope and still
> active. The #1/#2 text is retained for historical context only.

To let the team exercise the full login → home → forum → profile → logout journey without
a reachable backend, three deliberate shortcuts are layered on top of the real stack.
**All three are reversible single-file edits** and must be undone before the first
backend-integrated build.

#### 1. Relaxed login validation

[AuthViewModel.submitLogin](app/src/main/java/com/elysium/softwork/iam/application/viewmodel/AuthViewModel.kt)
no longer enforces corporate-email regex / 8-char password. The guard is now
`email.isBlank() || password.isBlank()`. [LoginScreen](app/src/main/java/com/elysium/softwork/iam/presentation/views/login/LoginScreen.kt)
mirrors the same condition on `SoftWorkButton.enabled`. The pure validators in
`AuthValidation` are intentionally preserved for the switch-back.

**Revert**: restore the original guard
(`!current.isEmailFormatValid || current.email.isEmpty()` + `!current.isPasswordValid`)
in `submitLogin`, and put the strict `isEmailFormatValid` / `isPasswordValid` checks back
on the `enabled` expression in `LoginScreen`.

#### 2. Mocked `AuthStoreImpl.login`

[AuthStoreImpl.login](app/src/main/java/com/elysium/softwork/iam/data/store/AuthStoreImpl.kt)
bypasses the Retrofit `AuthWebService` entirely. It simulates a 1 s round-trip via
`delay(MOCK_DELAY_MS)`, returns `Result.success` with `User(id="1", username="Cesar",
email=<input or cesar@gmail.com>, role="EMPLOYEE", token="MOCK_TOKEN_123")`, and persists
`MOCK_TOKEN_123` + the user id in `SharedPrefsManager`. All mock literals live in a
private `companion object` near the bottom of the class so the cleanup is one delete +
one swap.

Registration (`register`, `registerWithGoogle`) is **still wired to the real WebService** —
only `login` is mocked.

**Revert**: replace the mock body with
`callAndPersist { webService.login(User(email = email, password = password)) }` and
delete the `MOCK_*` companion. Drop the `kotlinx.coroutines.delay` import.

#### 3. Mocked `PostStoreImpl.refresh`

[PostStoreImpl.refresh](app/src/main/java/com/elysium/softwork/forum/data/store/PostStoreImpl.kt)
skips the Retrofit `webService.list()` call and, when the Room table is empty, seeds it
with **four** sample posts (`SeedPosts`). One post (`seed-2`) has `isAnonymous = true` so
the `AnonymousBadge` UI is exercised. The original network branch is preserved as an
inline comment showing the exact two-line restore.

`publish(...)` is unchanged — it still attempts the network and falls back to a local
insert with a generated UUID.

**Revert**: uncomment the `webService.list()` / `dao.upsertAll(...)` lines and delete the
`SeedPosts` companion entries that are no longer wanted (keep the seed if you still want
the no-backend demo path, but trim it to the contract the real API will return).

#### What still works without a backend

- Cold launch → LoginScreen, fields empty, button disabled.
- Type any email + any password → button enables.
- Tap **Iniciar sesión** → 1 s spinner → AuthSuccessScreen ("Sesión iniciada").
- Tap **Menú inicial** → `MainNavHost` mounts on Home.
- Foro tab → 4 seeded posts, anonymous badge on `seed-2`.
- Perfil tab → **Cerrar sesión** → `authStore.clearSession()` + state flip → LoginScreen.

#### Recovery on the next session

Token state survives process death (`SharedPrefsManager` is real). To force the IAM graph
again without logging out, uninstall + reinstall, or call `clearSession()` from a debug
hook.

### ✅ Phase 5 (in progress) — Feedback bounded context

> **⚠️ Superseded by Phase 10 (Feedback backend integration).** The survey stack now talks
> to the live Spring Boot API — `SurveyStoreImpl` is Retrofit-backed (no more string-resource
> mock), the `Survey` bean carries the real (annotation-free) wire keys, and `QuestionSurvey`
> / `SurveyResponse` beans + a submission use case were added. See the Phase 10 section below.
> The historical Phase 5 notes are retained for context.

- **Domain**: [`Survey`](app/src/main/java/com/elysium/softwork/feedback/domain/model/Survey.kt)
  data class with `id` / `title` / `description`, annotated with `@SerializedName` per the
  bean shortcut so it doubles as the future wire bean.
- **Data**: `SurveyStore` interface in `feedback/data/store/` + mocked `SurveyStoreImpl`
  that emits a static two-entry catalogue (Clima laboral, Productividad) resolved through
  Android string resources. Returns `Flow<List<Survey>>` so the contract survives the
  swap to Retrofit. Replace this mock with a real `SurveyWebService` once `/surveys`
  exists.
- **Application**: `PendingSurveysViewModel` exposes `surveys: StateFlow<List<Survey>>`,
  subscribes to the store on `init`, and follows the standard `Factory` companion pattern
  that pulls `surveyStore` from `ServiceLocator` via `CreationExtras`.
- **Presentation**: `PendingSurveysScreen` under `feedback/presentation/views/surveys/` —
  AccentWhite background, header (back arrow + "Encuestas pendientes" title in PrimaryNavy
  bold 20.sp), LazyColumn of `SoftWorkCard` items. Each card shows title (PrimaryNavy
  SemiBold 15.sp), description (AccentDark 14.sp), a 0.5.dp `HorizontalDivider`, and a
  full-width `SoftWorkButton` (EMPLOYEE variant — the PrimarySky→PrimaryTeal gradient,
  closest to the requested "PrimarySky" call-to-action).
- **Navigation**: `FeedbackRoutes.PENDING_SURVEYS = "feedback/pending_surveys"` and
  `NavGraphBuilder.feedbackGraph(navController)`. Wired into `MainNavHost` (the placeholder
  block previously bound to `MainRoutes.SURVEYS` was replaced by `feedbackGraph(...)`); the
  Home action card's `onOpenSurveys` now routes to `FeedbackRoutes.PENDING_SURVEYS`. The
  legacy `MainRoutes.SURVEYS` constant + its `placeholder_surveys_*` strings are now dead
  and can be pruned in a follow-up cleanup.
- **Wiring**: `ServiceLocator` gains `val surveyStore: SurveyStore` initialized with
  `SurveyStoreImpl(context.applicationContext)`.
- **i18n**: new keys `surveys_title`, `survey_start_button`, `survey_climate_title`,
  `survey_climate_desc`, `survey_productivity_title`, `survey_productivity_desc` added to
  both `values/strings.xml` and `values-es/strings.xml`.

### ✅ Phase 6 (in progress) — Notifications bounded context

> **⚠️ Superseded by Phase 11 (Notifications backend integration).** The notification stack
> now talks to the live Spring Boot API — `NotificationStoreImpl` is Retrofit-backed (no more
> string-resource mock), the `Notification` bean carries the real wire keys (split from the
> human-readable `NotificationDetail`), and the feed is a `NotificationFeedItem` aggregate
> assembled by the use case. See the Phase 11 section below. The historical Phase 6 notes are
> retained for context.

- **Domain**: [`Notification`](app/src/main/java/com/elysium/softwork/notifications/domain/model/Notification.kt)
  data class (`id` / `type` / `title` / `description` / `isRead`) annotated with
  `@SerializedName` per the bean shortcut. The category discriminator lives in
  [`NotificationType`](app/src/main/java/com/elysium/softwork/shared/utils/values/NotificationType.kt)
  under `shared/utils/values/` (carries a stable wire `key`, no `labelRes` because the
  list renders the per-item `title` instead of the category name).
- **Data**: `NotificationStore` interface in `notifications/data/store/` + mocked
  `NotificationStoreImpl` emitting a static four-entry catalogue — one notification per
  `NotificationType` — resolved through Android string resources. Returns
  `Flow<List<Notification>>` so the contract survives the swap to Retrofit.
- **Application**: `NotificationsViewModel` exposes
  `notifications: StateFlow<List<Notification>>`, subscribes to the store on `init`, and
  follows the standard `Factory` companion pattern that pulls `notificationStore` from
  `ServiceLocator` via `CreationExtras`.
- **Presentation**: `NotificationsScreen` under `notifications/presentation/views/feed/`.
  **Vibrant deviation from the plain mockup**: each card is themed by `NotificationType`
  via a private `NotificationTheme` value object (per-type background tint + foreground
  color + leading icon). Mapping:
  - `SURVEY` → `AccentMint` / `PrimaryTeal` / `ic_check_circle`
  - `PAYMENT` → soft warning surface `#FFF9F0` / `Warning` / `ic_flag`
  - `FORUM` → soft sky surface `#F0F8FF` / `PrimarySky` / `ic_forum`
  - `MESSAGE` → soft navy surface `#F0F4F8` / `PrimaryNavy` / `ic_send`

  Layout: leading 40.dp circular icon, center column (title 15.sp SemiBold in the
  category foreground + description 14.sp AccentDark), trailing `ic_chevron_right`
  tinted `PrimarySky`. The three "soft surface" tints are intentionally declared inline
  in the screen file — they only exist for this screen, so promoting them to `Color.kt`
  would falsely suggest a broader semantic contract.
- **Navigation**: `NotificationRoutes.NOTIFICATIONS_FEED = "notifications/feed"` +
  `NavGraphBuilder.notificationGraph(navController)`. `MainNavHost` now points the
  fourth bottom tab (`BottomDestinations`) at `NotificationRoutes.NOTIFICATIONS_FEED`
  and hosts `notificationGraph(...)`. The old `MainRoutes.NOTIFICATIONS` placeholder
  block + its `placeholder_notifications_*` strings are now dead and can be pruned in
  a follow-up cleanup (the legacy `MainRoutes.NOTIFICATIONS` constant is still defined
  but no longer wired).
- **Wiring**: `ServiceLocator` gains `val notificationStore: NotificationStore`
  initialized with `NotificationStoreImpl(context.applicationContext)`.
- **i18n**: new keys `notifications_title`, `notif_survey_title`, `notif_survey_desc`,
  `notif_payment_title`, `notif_payment_desc`, `notif_forum_title`, `notif_forum_desc`,
  `notif_message_title`, `notif_message_desc` added to both locales.
- **Icons**: no new drawables added — the screen reuses the existing
  `ic_check_circle`, `ic_flag`, `ic_forum`, `ic_send`, `ic_chevron_right` set. Swap in
  dedicated glyphs (e.g. `ic_money`, `ic_mail`) when the brand library expands.

### ✅ Phase 7 (in progress) — Payment / Membership bounded context

> **⚠️ Superseded by Phase 13 (Payment backend integration).** The hardcoded `PlanCatalogue`,
> the mock payment `delay`, and the fake in-memory plan list are gone: the context now talks
> to the live payment-service API (`memberships` / `orders` / `payments` / `membership-plans`
> / `benefits`), `MembershipPlan` carries the real wire keys, and checkout creates an order +
> payment then re-authenticates. The local membership **gate** (`hasMembership` /
> `currentPlanKey`) and the client-side saved cards are retained. See Phase 13 below.

- **Domain**: [`MembershipPlan`](app/src/main/java/com/elysium/softwork/payment/membership/domain/model/MembershipPlan.kt)
  (`key` / `name` / `monthlyPrice` / `features` / `isRecommended`) and
  [`PaymentMethod`](app/src/main/java/com/elysium/softwork/payment/membership/domain/model/PaymentMethod.kt)
  (`id` / `brand` / `holderName` / `last4` / `expiryMonthYear`). Both annotated with
  `@SerializedName` per the bean shortcut. `monthlyPrice` is intentionally a pre-formatted
  string so the backend (not Compose) owns locale and currency choices.
- **Data**: `MembershipStore` interface in `payment/membership/data/store/` + mocked
  `MembershipStoreImpl`. Membership flags (`KEY_HAS_MEMBERSHIP`, `KEY_CURRENT_PLAN`)
  persist through `SharedPrefsManager`; the saved-cards list lives in an in-memory
  `MutableStateFlow`. The catalogue (Basic S/. 59, Plan Pro S/. 99 — Plan Pro flagged as
  `isRecommended`) is hardcoded in a `PlanCatalogue` companion until `/plans` ships.
- **Reactive gate**: `hasMembership` and `currentPlanKey` are exposed as `StateFlow`s on
  the store. `MainActivity` collects `hasMembership` and uses it to decide whether to
  mount `MainNavHost` or the standalone `PaymentOnboardingHost`. "Cancel subscription"
  clears both prefs and emits the new value; the Activity recomposes, the main shell
  unmounts (back stack wiped automatically), and the worker lands back at
  `MembershipSelectionScreen` — no explicit `popUpTo` needed.
- **Application**: `MembershipViewModel` exposes `availablePlans`, `paymentMethods`,
  `currentPlanKey`, a `CardFormState` buffer for the new-card composer with input
  formatting (digit-only filtering, `MM/YY` auto-slash insertion), a `PaymentState`
  state machine (`Idle` → `Processing` → `Succeeded`), and orchestrator methods
  `addCard`, `payMembership`, `activateMembership`, `cancelSubscription`,
  `consumePaymentState`. Standard `Factory` companion pulls `membershipStore` from
  `ServiceLocator` via `CreationExtras`.
- **Presentation** (four screens under `payment/membership/presentation/views/`):
  - **`MembershipSelectionScreen`** — header + plan cards. Recommended tier (Plan Pro)
    uses PrimaryTeal accent + solid teal CTA; the basic tier uses an outline CTA.
  - **`PaymentMethodsScreen(planKey, fromSettings, ...)`** — gradient PrimaryTeal→PrimaryNavy
    "Next charge" hero card recapping the selection, list of saved cards (or a
    structural empty-state placeholder), outline "Add payment method" row, primary
    "Pay membership" CTA disabled until a card exists, and a Danger-tinted
    "Cancel subscription" text link rendered **only** when `fromSettings = true`.
    Routes through a `LaunchedEffect(paymentState)` so the screen navigates exactly
    once on `PaymentState.Succeeded` and then resets the VM stream.
  - **`NewCardScreen`** — high-fidelity gradient credit-card preview that updates as
    the worker types (`XXXX`-padded PAN grouping keeps layout stable), four
    `SoftWorkTextField` inputs with numeric IMEs, a Material 3 `Switch` for
    "Save this card", and a primary `SoftWorkButton` ("Add card"). Disabled until
    the minimal validator (`CardFormState.isValid`) is satisfied.
  - **`PaymentSuccessScreen(planKey, ...)`** — lockup + checkmark + "Membership activated"
    + `SoftWorkButton` "Main menu". Tap calls `viewModel.activateMembership(planKey)`
    which flips `hasMembership` to `true`; the Activity-level collector then swaps the
    user into `MainNavHost`.
- **Navigation**: `PaymentRoutes` defines `SELECTION`, parameterized `METHODS`
  (`payment/methods/{planKey}/{fromSettings}` — `BoolType` argument),
  `NEW_CARD`, and parameterized `SUCCESS` (`payment/success/{planKey}`). A
  `CURRENT_PLAN_SENTINEL = "current"` is passed by the settings entry so the methods
  screen resolves the active plan from `MembershipStore.currentPlanKey`.
  `NavGraphBuilder.paymentGraph(navController, onExitToMainShell)` is mounted **twice**:
  - From `MainActivity.PaymentOnboardingHost` (standalone `NavHost`) when the membership
    gate fires.
  - From `MainNavHost` so Profile → "Payment methods" lands inside the main back stack
    with `fromSettings = true`.
- **Wiring**: `ServiceLocator` gains `val membershipStore: MembershipStore =
  MembershipStoreImpl(sharedPrefsManager)`. `SharedPrefsManager` gains
  `KEY_HAS_MEMBERSHIP` (Boolean) and `KEY_CURRENT_PLAN` (String).
- **MainActivity** evolves from a two-state (auth/no-auth) router to a three-state
  router (`!authenticated` → `AuthNavHost`, `authenticated && !hasMembership` →
  `PaymentOnboardingHost`, `authenticated && hasMembership` → `MainNavHost`). The host
  swap on cancel/activation is driven by `collectAsState` on `membershipStore.hasMembership`.
- **ProfileScreen**: `onOpenPaymentMethods` now navigates to
  `PaymentRoutes.methods(planKey = CURRENT_PLAN_SENTINEL, fromSettings = true)` — that's
  what enables the reactive cancel button.
- **i18n**: 22 new `payment_*` keys added to both `values/strings.xml` and
  `values-es/strings.xml`. **Strict feature policy**: this bounded context ships in
  English only; the Spanish locale file holds the same English strings (not translations)
  so the i18n machinery stays consistent without violating the policy.
- **Icons**: no new drawables added — the screens reuse `ic_arrow_back`, `ic_check`,
  `ic_add`, and `ic_logo`. Add a dedicated `ic_credit_card.xml` if the brand library
  later wants a real card glyph (currently the brand badge is a teal pill with the
  brand name).

#### Phase 7 caveats

- **Cards live in memory.** Saved cards do not survive process death — the
  `MutableStateFlow<List<PaymentMethod>>` is rebuilt empty on every cold start.
  Persist through Room or a real `PaymentWebService` before shipping.
- **Card data is unencrypted.** Phase 7 stores PANs in memory only and `last4` is the
  only piece displayed back to the user, but the form itself buffers the full PAN
  in plaintext. **PCI rule**: replace with a tokenized reference from the processor
  before any production build.
- **`detectBrand` is a 1-digit BIN heuristic.** Good enough to surface the brand
  badge in the mock; swap with the processor's response in production.
- **Mock payment flow** — the 1 s `delay` lives in `MembershipViewModel.payMembership()`.
  No real network call, no idempotency key, no retry. Replace the body when
  `/subscriptions` is live.

### ✅ Phase 8 (in progress) — Feedback / FlowWork AI chat

- **Domain**: [`ChatMessage`](app/src/main/java/com/elysium/softwork/feedback/domain/model/ChatMessage.kt)
  data class (`id` / `content` / `isFromUser` / `timestamp`) annotated with
  `@SerializedName` per the bean shortcut. Drives bubble alignment and color in the
  chat feed.
- **Data**: `FeedbackStore` interface in `feedback/data/store/` + mocked
  `FeedbackStoreImpl`. Conversation lives in a `MutableStateFlow<List<ChatMessage>>`
  for the lifetime of the process (no persistence yet). `send(content)` appends the
  worker's message, suspends 1.2 s to simulate an AI round-trip, then appends a
  templated reply deterministically chosen from a five-entry `RESPONSE_TEMPLATES`
  list via a hash of the prompt. Replace the body of `send` with a real Retrofit
  call when the backend is live — the contract does not need to change.
- **Application**: `AiChatViewModel` exposes
  `messages: StateFlow<List<ChatMessage>>` (proxied straight from
  `FeedbackStore.conversation`) and a transient `isSending: StateFlow<Boolean>` flag
  that gates the send button and drives the "typing" row. Standard `Factory`
  companion pulls `feedbackStore` from `ServiceLocator` via `CreationExtras`.
- **Presentation**: `AiChatScreen` under `feedback/presentation/views/chat/`.
  - **Header** — `ChatTopBar` with a back arrow and the "FlowWork AI" title in
    PrimaryNavy bold 20.sp. Applies
    `Modifier.windowInsetsPadding(WindowInsets.statusBars)` so the title clears any
    notch / cutout. `MainNavHost` calls `Modifier.consumeWindowInsets(padding)` on
    its NavHost so this same modifier resolves to zero inside the bottom-bar
    Scaffold (no double padding) but still pads correctly if the screen is ever
    mounted standalone.
  - **Feed** — `LazyColumn` with `rememberLazyListState`, mandatory item key
    `key = { it.id }`, and a `LaunchedEffect(messages.size, isSending)` that
    `scrollToItem`-s to the last entry whenever the log grows. User bubbles align
    trailing with a solid `PrimarySky` fill and white text; AI bubbles align
    leading with a white fill, soft `shadowElevation = 1.dp`, and `AccentDark`
    text. Both cap at `widthIn(max = 280.dp)`. A typing indicator row appears
    while `isSending = true`. When the conversation is empty, an `EmptyState`
    composable centred under the launcher-foreground brand mark prompts the
    worker to start chatting.
  - **Composer** — rounded white field hosting a leading `Image` of
    `R.drawable.ic_launcher_foreground` (36.dp, `Image` not `Icon` so native
    gradients survive), a single-to-four-line `BasicTextField` with an IME
    `Send` action wired to the send handler, and a circular send button that
    flips between `PrimarySky` (enabled) and `AccentMint` (disabled). The Row
    chains `imePadding()` then `navigationBarsPadding()` so the composer floats
    above the keyboard when open and above the gesture pill / button bar when
    closed.
- **Navigation**: `FeedbackRoutes.AI_CHAT = "feedback/ai_chat"` and a `composable`
  registered inside `feedbackGraph` (push/pop transitions reuse the shared
  `PushEnter / PushExit / PushPopEnter / PushPopExit` set). `MainNavHost` now
  observes the current back-stack entry and suppresses `SoftWorkBottomBar` whenever
  the active route is in the `ImmersiveRoutes` set (currently
  `FeedbackRoutes.AI_CHAT`), so the chat surface owns the full vertical viewport
  and the composer's `navigationBarsPadding()` does not stack on the bottom bar's
  own inset consumption. `HomeScreen`'s "AI Assistant" action card
  (`onOpenAssistant`) now navigates to the chat route.
- **Wiring**: `ServiceLocator` exposes
  `val feedbackStore: FeedbackStore by lazy { FeedbackStoreImpl() }`. No
  constructor argument needed — the mock is fully self-contained.
- **i18n**: six new `ai_chat_*` keys in both `values/strings.xml` and
  `values-es/strings.xml`. `ai_chat_title` carries `translatable="false"` because
  "FlowWork AI" is a brand name.

#### Phase 8 caveats

- The conversation log is **in memory only**. Cold start wipes it. Persist through
  Room or a real `FeedbackWebService` before shipping.
- `generateAiReply` is a five-template hash cycle. Plenty for demos and
  screenshots; obviously not a real AI integration.
- The screen is immersive (no bottom navigation bar). When other surfaces need
  the same treatment, append their route to `MainNavigation.ImmersiveRoutes`.

### Top-level rendering and routing — `MainActivity` contract

`MainActivity` is structured around three guarantees that any future change to
the cold-start path must preserve:

1. **`enableEdgeToEdge()` runs as the very first statement of `onCreate`** —
   before `super.onCreate(savedInstanceState)`. This installs the transparent
   system-bar configuration on the window decor before the platform attaches the
   Activity's content view. Calling it later allows the platform to paint one
   frame with the manifest theme's opaque status / navigation bar over a
   yet-undrawn Compose tree, which is perceived as a black flash on launch.
2. **`Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background)`
   is the immediate child of `SoftWorkTheme`** — not nested behind state
   collectors. The brand background paints on the very first frame even if the
   downstream routing composable takes a frame to attach its `StateFlow`
   collectors.
3. **All routing logic lives inside a private `AppRoot()` composable that the
   `Surface` hosts** — not inline in `setContent`. This means:
   - The `ServiceLocator` lookup is held on a `private val locator: ServiceLocator
     by lazy { ... }` Activity field, not re-resolved on every recomposition.
   - The seeded `SharedPreferences` read used to derive the initial value of the
     `rememberSaveable` auth flag is not on the recomposition hot path.
   - The `collectAsStateWithLifecycle` collector on the membership flow attaches
     inside `AppRoot`'s first composition, after the outer `Surface` has already
     been measured and painted.
   - The three-way `when` routes between `AuthNavHost`,
     `PaymentOnboardingHost`, and `MainNavHost`. The routing flags are never
     mutated from inside a composable body — only from lambdas (`onAuthComplete`,
     `onLogout`) and from `LaunchedEffect` blocks scheduled by
     `PaymentSuccessScreen` after activation. **No recomposition loop is possible
     here.**

The manifest theme (`Theme.SoftWork` in `res/values/themes.xml`) pins
`android:windowBackground` and `android:colorBackground` to white. The SoftWork
brand uses a single light visual treatment regardless of system dark mode, so
the platform-painted background matches the Compose-painted background from the
very first frame onward. This pin is what prevents a "black until Compose
paints" window on devices in system dark mode.

### ✅ Phase 9 — Backend integration (IAM + shared) against the live Spring Boot API

The IAM and shared contexts now talk to the real FlowWork backend (contract in
`API_DOCUMENTATION.md`). All IAM mocks are deleted. **Employee-exclusive**: the HR/RRHH
sign-up route and the `RoleSelectorCard` are removed — the role picker is gone because
`sign-up/employee` already scopes every account to the worker experience. **Dual
registration is preserved**: both the standard `RegisterScreen` and the Gmail
`RegisterGoogleScreen` flow through the *same* `POST /api/v1/authentication/sign-up/employee`
endpoint (the backend has no dedicated Google route — the Google identity resolves the email
server-side, so the device sends only the display name).

- **Network (`shared/data/network/`)**:
  - `ApiClient` sources the base URL exclusively from `BuildConfig.BACKEND_BASE_URL` and now
    chains [`AuthInterceptor`](app/src/main/java/com/elysium/softwork/shared/data/network/AuthInterceptor.kt)
    **first** in the OkHttp chain. The interceptor reads the live JWT per request via a
    provider installed by `ServiceLocator` (`ApiClient.installTokenProvider { … KEY_AUTH_TOKEN }`),
    attaches `Authorization: Bearer <token>`, and **skips** the public auth paths
    (`/authentication/sign-in`, `/authentication/sign-up/employee`) by path-suffix match.
    `ApiClient` stays a `Context`-free `object`; the token supplier is the only new state.
  - [`BadRequestResponse`](app/src/main/java/com/elysium/softwork/shared/data/network/BadRequestResponse.kt)
    mirrors the backend 400 payload (`status` / `error` / `message` / `field_errors`), with
    `primaryFieldError()` preferring the `"argument"` key. A sibling `BadRequestException`
    carries the parsed payload through the `Result` failure channel.
- **Persistence (`shared/data/local/SharedPrefsManager`)**: added `getLong` / `putLong` and
  keys `KEY_USER_ACCOUNT_ID` (Long), `KEY_EMPLOYEE_PROFILE_ID` (Long), `KEY_USER_EMAIL`,
  `KEY_USER_PASSWORD`. The plaintext password is **required** for re-authentication (see
  below). `KEY_USER_ID` was removed (superseded by `KEY_USER_ACCOUNT_ID`).
- **Domain (`iam/domain/model/User`)**: one annotation-free bean spans `sign-in`,
  `sign-up/employee`, and `employee-profile`. **Uniform snake_case** (the backend serializes
  every DTO with `@JsonNaming(SnakeCaseStrategy)`), so every property is snake_case and Gson
  resolves by reflection — no `@SerializedName`. The one asymmetry is a backend field **typo**:
  the profile request sends `date_start` but the response returns `star_start` (Java field
  `starStart`), so both snake_case keys coexist as nullable fields. The sign-in/sign-up
  response (`AuthenticatedUserAccountResponse`) returns only `id`/`email`/`token` — the address
  echoes under the same `email` key, so the former phantom `gmail` field was **removed**. Other
  fields: `password`, `token`, `membership_status`, the employee sign-up payload (`name`,
  `last_name`, `phone_number`, `dni`, `anonymous_name`, `position`, `salary`), and the profile
  response ids (`employee_profile_id`, `user_account_id`, `work_of_team_id`). `id` is `Long?`.
  `isMembershipActive()` reads `membership_status` and treats only `"ACTIVE"` as active (null ⇒
  not active). **(Superseded the earlier camelCase keys `gmail`/`dateStart`/`membershipStatus`/
  `lastName`/… after the backend's 2026-07-03 snake_case migration — see Phase 14.)**
- **Network service (`iam/data/network/AuthWebService`)**: exact live routes —
  `POST api/v1/authentication/sign-in`, `POST api/v1/authentication/sign-up/employee`,
  `GET api/v1/employee-profile` (returns `List<User>`). Relative paths only.
- **Store (`iam/data/store/AuthStoreImpl`)**: mocks + `MOCK_*` companion + `delay` deleted.
  `login`/`register`/`registerWithGoogle` funnel through `persistSessionAndSyncProfile`,
  which (1) persists token, account id, and credentials, then (2) runs the **sequential**
  `employee-profile` lookup, matching the worker's row by `user_account_id` and persisting
  `employee_profile_id` (best-effort — a profile hiccup never voids a valid session).
  `registerWithGoogle(name)` reuses `signUpEmployee` and stores a blank password (Google
  re-auth, not `reauthenticate`). `unwrap` parses a 400 into `BadRequestException`;
  `reauthenticate()` re-runs `sign-in` from the stored credentials (no refresh endpoint
  exists — used after a membership payment).
- **Application / presentation**:
  - `RegisterUseCase` drops `role` (`name`/`email`/`password`). `RegisterWithGoogleUseCase`
    drops `role` and takes the display `name` only (routed to `sign-up/employee`).
  - `AuthState` gains `MembershipRequired(user)`. `AuthViewModel` removes `role`, keeps the
    `submitRegisterWithGoogle` action, adds `FormState.fieldError`, lifts a 400 `field_errors`
    message (the DNI rule) onto both the error state and `fieldError`, and on login branches
    `Success` vs `MembershipRequired` by membership status.
  - `LoginScreen` routes `MembershipRequired → onMembershipRequired` (wired to the host's
    `onAuthComplete`), so an inactive membership lands the worker in `PaymentOnboardingHost`
    via the existing Phase 7 gate. The `GoogleOutlineButton` entry point is retained.
  - `RegisterScreen` and `RegisterGoogleScreen` drop `RoleSelectorCard` and render
    `fieldError` under the identity input. The `register-google` destination is retained in
    `AuthRoutes`/`AuthNavHost`.
- **Tests**: `FakeAuthStore` + `AuthViewModelTest` updated to the new signatures (including
  `registerWithGoogle(name)`), plus new coverage for the `MembershipRequired` branch and the
  400 → `fieldError` path.

#### Phase 9 caveats / follow-ups

- **Membership source is still local.** The reactive gate reads `KEY_HAS_MEMBERSHIP`
  (Phase 7). The backend currently returns no `membershipStatus` on `sign-in`, so every
  fresh login is treated as not-active and routed to payment onboarding (the intended demo
  flow). When the backend begins sending `membershipStatus`, route an `ACTIVE` login through
  `MembershipStore.activateMembership(...)` so the gate's `StateFlow` opens reactively.
- **`reauthenticate()` is implemented but not yet wired** into the payment-success flow
  (that wiring lives in `payment.membership`, outside this change's scope). After a
  successful payment, call `authStore.reauthenticate()` to refresh the token.
- **Registration is intentionally minimal.** The form collects name/email/password only; the
  backend `sign-up/employee` also requires `dni`/`lastName`/`phoneNumber`/`dateStart`/
  `position`/`salary`. Those land via a fuller form later — until then the 400 handler
  surfaces the backend's field errors (e.g. the DNI length rule) inline.

#### Phase 9 hardening (production host cut-over)

- **Production HTTPS host.** `BACKEND_BASE_URL=https://api.elysium-mobile.online/`. TLS is
  validated natively; the old `network_security_config.xml` cleartext exception (legacy EC2
  IP/host) and its `android:networkSecurityConfig` manifest attribute were **deleted** — no
  cleartext policy or security-config XML remains in the tree.
- **400 field-errors wire key.** Per the 2026-07-03 backend migration, the
  `GlobalExceptionHandler` records are `@JsonNaming(SnakeCaseStrategy)`, so the envelope key is
  **`field_errors`** and the dynamic keys inside mirror the snake_case DTO field names (see
  `ELYSIUM-API_DOCUMENTATION.md`). `BadRequestResponse` carries the single snake_case
  `field_errors` map (no `@SerializedName`); `primaryFieldError()` reads it (`argument` key →
  first value → top-level `message`). (An interim build briefly carried both a camelCase
  `fieldErrors` and `field_errors`; the camelCase half was removed in Phase 14.)
- **HTTP 401 unified trap (graceful degradation, auth-method-aware).** A mid-session `401` on
  any authenticated call (e.g. `GET /api/v1/membership-plans`) — a `sub`-claim mismatch,
  unlinked account, or missing role authorization even with a fresh bearer — is caught at a
  single choke-point and routes the worker cleanly back to the **matching** auth surface instead
  of crashing/looping. Wiring:
  - [`AuthInterceptor`](app/src/main/java/com/elysium/softwork/shared/data/network/AuthInterceptor.kt)
    observes the response of every authenticated request; on `401` (skipping public auth paths,
    and exempting the best-effort post-login `/employee-profile` sync via `TRAP_EXEMPT_SUFFIXES`
    so the trap never fights the login handshake) it fires `onUnauthorized`.
  - [`ApiClient`](app/src/main/java/com/elysium/softwork/shared/data/network/ApiClient.kt) gains
    an `installUnauthorizedHandler` seam (mirrors `installTokenProvider`); `ServiceLocator` wires
    it to `AuthStore.invalidateSession()`.
  - [`AuthStore`](app/src/main/java/com/elysium/softwork/iam/data/store/AuthStore.kt) exposes a
    reactive `sessionRecovery: StateFlow<SessionRecovery>` (discriminator in
    [`shared/utils/discriminators/SessionRecovery.kt`](app/src/main/java/com/elysium/softwork/shared/utils/discriminators/SessionRecovery.kt):
    `NONE`/`CREDENTIALS`/`GOOGLE`) plus `invalidateSession()` (reads the dying session's auth
    method, wipes the session, emits the recovery route) and `consumeSessionInvalidation()`. A
    fresh successful login re-arms the flag (resets to `NONE`).
  - **Auth-method persistence.** `SharedPrefsManager.KEY_GOOGLE_SESSION` (Boolean, part of the
    IAM session, cleared by `clearSession()`) is written at login — `true` from
    `registerWithGoogle`, `false` from credentials `login`/`register`. `invalidateSession()`
    reads it *before* the wipe to pick `GOOGLE` vs `CREDENTIALS` (a Google-linked account stores
    no local password, so it can only recover through the Gmail handshake).
  - [`MainActivity`](app/src/main/java/com/elysium/softwork/MainActivity.kt) collects the signal
    with `collectAsStateWithLifecycle` and, in a `LaunchedEffect`, latches a `rememberSaveable`
    `googleReauth` bit, flips `isAuthenticated = false`, then consumes the signal — the router
    hot-swaps to `AuthNavHost` (no `popUpTo`) with `startDestination = REGISTER_GOOGLE` for a
    Google session, else `LOGIN`. A deliberate logout resets `googleReauth`.
  - [`AuthNavHost`](app/src/main/java/com/elysium/softwork/iam/presentation/navigation/AuthNavigation.kt)
    gained a `startDestination` parameter (defaults to `AuthRoutes.LOGIN`).
  - `FakeAuthStore` (with a programmable `nextInvalidationRecovery`) + the `AuthStore` contract
    updated in lockstep.
- **Membership validation via the `user_accounts` FK — catalog vs. enrolment contract split.**
  The session-authorization gate is anchored on the `membership_id` foreign key exposed on the
  worker's `user_accounts` record, **not** on the plan catalogue:
  - **Post-login / cold-start sync.** `AuthStoreImpl.persistSessionAndSyncProfile` now runs
    `syncUserAccount(accountId)` alongside `syncEmployeeProfile`: it fetches
    `GET /api/v1/user_accounts` (new `AuthWebService.getUserAccounts()`), matches the worker's row
    by `user_account_id`, and caches `membership_id` in `SharedPrefsManager.KEY_MEMBERSHIP_ID`
    (Long; part of the IAM session, cleared by `clearSession()`). `User` gained the
    `user_accounts` payload keys (`user_id`, `membership_id`, `company_id`).
  - **Session-authorization check.** `ValidateMembershipUseCase(store, membershipIdProvider)`
    reads the cached `membership_id`, queries `GET /api/v1/memberships/{id}`
    (`MembershipStore.getMembership(id)`), and evaluates `Membership.isActiveNow()` — status
    `ACTIVE` **and** today within `[membership_start, membership_over]`. It syncs the reactive
    gate (`activateMembership` when valid, `cancelSubscription` otherwise). A missing id / `401` /
    `404` / fetch failure ⇒ not active ⇒ gate closed. `MainActivity` invokes it in a
    `LaunchedEffect(isAuthenticated)` on cold-start and after login, so a non-active subscription
    reactively routes the worker to payment onboarding (`ServiceLocator.validateMembershipUseCase`).
  - **Catalogue stays browse-only.** `/api/v1/membership-plans` (`getPlans` → `loadPlans`) never
    flips the gate — a failure lands on `errorMessage` only, so an expired worker always sees the
    upgrade tiers. `MembershipViewModel.validateMembershipAccess()` reuses the same
    `ValidateMembershipUseCase` to drive the `MembershipRequiredBanner` (strings
    `payment_membership_required` / `_detail`); an `/orders` order-push business-rule failure also
    raises it via the VM's private `isMembershipGate()`.
  - **Trap exemption unchanged:** `AuthInterceptor.RETRY_GATE_SUFFIXES` (`/membership-plans`,
    `/memberships`, `/orders`) keeps all three out of the `401` session-invalidation logout trap;
    `MembershipStoreImpl.throwTyped` still maps `401` → `UnauthorizedException`.
  - Replaced the interim list-based `getMemberships()` / `GetMembershipsUseCase` with the FK
    `getMembership(id)` / `ValidateMembershipUseCase`. `FakeMembershipStore` exposes
    `nextMembershipResult`.
- **Native Google Sign-In via Credential Manager.** "Continue with Google" on `LoginScreen` now
  triggers the system account-picker tray directly (previously it just navigated to a name-only
  form and never showed the modal). Flow:
  - `AuthStore.signInWithGoogle(context)` / `AuthStoreImpl` build a `GetSignInWithGoogleOption`
    (the **exclusive** sign-in option — forces the account-picker drawer on every tap, unlike
    `GetGoogleIdOption`'s passive authorized-account lookup;
    `serverClientId = BuildConfig.GOOGLE_OAUTH_CLIENT`, injected by the Secrets plugin — no literal),
    call `CredentialManager.create(context).getCredential(context, request)`, and parse the
    `GoogleIdTokenCredential` (email `.id`, `.displayName`, `.idToken`). The **Activity** context
    is passed per-call (never retained — the store is a singleton).
  - `SignInWithGoogleUseCase` → `AuthViewModel.submitSignInWithGoogle(context)` (branches
    `Success` vs `MembershipRequired` like login); `LoginScreen` unwraps `LocalContext.current`
    to its host `Activity` via a `findActivity()` chain walk (the tray is a window and must be
    anchored to the real Activity, not a themed `ContextWrapper`) before invoking the button. A
    cancellation / `GetCredentialException` is caught by the store's `runCatching` and surfaced on
    `AuthState.Error`.
  - **Backend gap (flagged):** the API exposes **no** Google/id-token endpoint (auth is only
    `sign-in` + `sign-up/employee`; the only backend "Google" is Gemini). So the resolved identity
    is routed through the existing `sign-up/employee` dual-auth path, sending the real `email` +
    `name` + a new snake_case `User.id_token` (forward-compat; the backend ignores unknown keys).
    Until a real Google endpoint exists, this 400s on the other `@NotBlank` sign-up fields
    (`dni`/…), surfaced inline — same limitation as the existing minimal registration.
  - **New dependencies** (rationale): `androidx.credentials:credentials` +
    `:credentials-play-services-auth` and `com.google.android.libraries.identity.googleid:googleid`
    — the native Gmail identity handshake (the sole external identity path the client may touch).
    `LoginScreen` dropped its now-unused `onNavigateToRegisterWithGoogle` param; `FakeAuthStore` /
    `AuthViewModelTest` updated for the new use case.

### ✅ Phase 10 — Feedback backend integration against the live Spring Boot API

The `feedback` survey stack now talks to the real FlowWork backend. All survey mocks are
deleted; the AI-chat sub-context (`FeedbackStore`/`AiChatViewModel`) is untouched.

- **Domain (`feedback/domain/model/`)** — three annotation-free beans in **uniform snake_case**
  (no `@SerializedName`). The old request/response asymmetries were resolved backend-side (the
  `expirationType` copy-paste bug is fixed to `expiration_time`), so each concept is now a
  **single** field — the camelCase halves were removed in Phase 14:
  - [`Survey`](app/src/main/java/com/elysium/softwork/feedback/domain/model/Survey.kt):
    `survey_id`, `title`, `description`, `target_type`, `expiration_time`.
  - [`QuestionSurvey`](app/src/main/java/com/elysium/softwork/feedback/domain/model/QuestionSurvey.kt):
    `question_survey_id`, `text_question`, `question_type`, `survey_id`.
  - [`SurveyResponse`](app/src/main/java/com/elysium/softwork/feedback/domain/model/SurveyResponse.kt):
    `survey_response_id`, `survey_id`, `employee_profile_id`, `submitted_at`, `commentary`, `cause`.
- **Network (`feedback/data/network/SurveyWebService`)** — relative paths only:
  `GET api/v1/surveys`, `GET api/v1/surveys/{id}`, `GET api/v1/question-surveys`,
  `GET api/v1/question-surveys/{id}`, `POST api/v1/question-surveys`,
  `POST api/v1/survey-responses`, `GET api/v1/survey-responses`,
  `GET api/v1/survey-responses/survey/{surveyId}`.
- **Store (`feedback/data/store/SurveyStoreImpl`)** — mock + string-resource catalogue
  deleted; now `SurveyStoreImpl(webService, gson)`. `getPendingSurveys()` stays a `Flow`
  (single-shot, off `GET /surveys`); `getSurveyQuestions(surveyId)` fetches `GET /question-surveys`
  and filters by `survey_id` client-side (no server-side filter exists); `getSurveyResponses(surveyId)`
  hits the filtered `survey/{surveyId}` route; `submitSurveyResponse(...)` POSTs. A `400`
  parses into `BadRequestException` via the shared `unwrap`/`throwTyped` pattern.
- **Application (`SubmitSurveyResponseUseCase`)** — resolves `employee_profile_id` dynamically
  from `SharedPrefsManager.KEY_EMPLOYEE_PROFILE_ID` (cached during the Phase 9 post-login
  sync), trims the text fields, defaults `submittedAt` to `LocalDate.now()`, and binds the
  camelCase request keys.
- **Presentation (`PendingSurveysViewModel`)** — gains `submitResponse(...)`, `isSubmitting`,
  and an `errorMessage: StateFlow<String?>`; a `400` is caught, its `field_errors` extracted
  via `primaryFieldError()`, and routed into `errorMessage`. `PendingSurveysScreen` reads the
  nullable bean (`title.orEmpty()`, `key = survey_id`), forwards `survey_id: Long?` to
  `onStartSurvey`, and renders `errorMessage` as a `Danger` banner.
- **Wiring**: `ServiceLocator` now owns `surveyWebService` and builds
  `SurveyStoreImpl(surveyWebService, gson)`.

#### Phase 10 caveats

- **No answer-flow screen yet.** `onStartSurvey` is still an unwired no-op; `getSurveyQuestions`
  / `getSurveyResponses` / `submitResponse` are implemented and ready for a survey-detail
  screen that collects answers and calls `PendingSurveysViewModel.submitResponse(...)`.
- The old `survey_*` string resources (climate/productivity seed copy) are now dead and can
  be pruned in a follow-up cleanup.

### ✅ Phase 11 — Notifications backend integration against the live Spring Boot API

The `notifications` context now talks to the real FlowWork backend. All notification mocks
are deleted. The backend splits a notification across two resources, so the feed is a
**join**: category/ownership on `Notification`, headline/body on `NotificationDetail`.

- **Domain (`notifications/domain/model/`)** — two annotation-free wire beans + one render
  aggregate (no `@SerializedName`):
  - [`Notification`](app/src/main/java/com/elysium/softwork/notifications/domain/model/Notification.kt):
    `notification_id`, `seen`, `notification_type`, `user_account_id`. (Replaces the old
    `id`/`type`/`title`/`description`/`isRead`.)
  - [`NotificationDetail`](app/src/main/java/com/elysium/softwork/notifications/domain/model/NotificationDetail.kt):
    `notification_detail_id`, `title`, `content`, `notification_id`. (Phase 14 removed the
    camelCase `notificationId` half — request and response both use `notification_id`.)
  - [`NotificationFeedItem`](app/src/main/java/com/elysium/softwork/notifications/domain/model/NotificationFeedItem.kt):
    render aggregate (`id`, resolved `NotificationType`, `title`, `content`, `seen`) — never
    travels the wire, assembled by the use case.
  - [`NotificationType.fromKey`](app/src/main/java/com/elysium/softwork/shared/utils/values/NotificationType.kt)
    is now **case-insensitive** so any wire casing of the category resolves precisely.
- **Network (`notifications/data/network/NotificationWebService`)** — relative paths only:
  `GET/POST api/v1/notifications`, `GET api/v1/notifications/{id}`,
  `GET/POST api/v1/notification-details`, `GET api/v1/notification-details/{id}`.
- **Store (`notifications/data/store/NotificationStoreImpl`)** — mock + string-resource
  catalogue deleted; now `NotificationStoreImpl(webService, gson)` exposing suspend
  `getNotifications()` / `getNotificationDetails()` returning `Result`. A `400` parses into
  `BadRequestException` via the shared `unwrapList`/`throwTyped` pattern.
- **Application (`GetNotificationsUseCase`)** — now `(store, accountIdProvider: () -> Long?)`:
  reads the signed-in `user_account_id` **per call** (the factory wires the provider to
  `SharedPrefsManager.KEY_USER_ACCOUNT_ID`), filters the records to that account, joins each
  with its detail by `notification_id`, and resolves the category (unknown ⇒ `MESSAGE`). The
  provider seam keeps the use case unit-testable without a Robolectric `Context`.
- **Presentation (`NotificationsViewModel`)** — loads on `init`, exposes
  `notifications: StateFlow<List<NotificationFeedItem>>` + `errorMessage: StateFlow<String?>`;
  a `400` is caught, its `field_errors` extracted via `primaryFieldError()`, and routed into
  `errorMessage`. `NotificationsScreen` collects both via `collectAsStateWithLifecycle()`,
  keeps the per-category card theming (`SURVEY`/`PAYMENT`/`FORUM`/`MESSAGE`) driven by the
  network category, keys by `Long` id, forwards `onNotificationClick(Long)`, and renders
  `errorMessage` as a `Danger` banner.
- **Wiring**: `ServiceLocator` now owns `notificationWebService` and builds
  `NotificationStoreImpl(notificationWebService, gson)`.
- **Tests**: `FakeNotificationStore` + `NotificationsViewModelTest` rewritten for the join,
  category fallback, account-id filtering, and the `400 → errorMessage` path.

#### Phase 11 caveats

- **Client-side join + filter.** `GET /notifications` and `GET /notification-details` are both
  unfiltered lists; the worker's records and their details are matched/filtered in the use
  case (no server-side `by-account` or `by-notification` route exists yet).
- **No deep-link routing.** `onNotificationClick` is still an unwired no-op.
- The old `notif_*` string resources are now dead and prunable.

### ✅ Phase 12 — Forum backend integration against the live Spring Boot API

The `worker.forum` context transitioned off the flat `Post` model onto the backend's
hierarchical `Forum → Category → Thread → Message` structure (+ `Asset`, `Report`). All forum
mocks (`SeedPosts`, `SampleReports`, the report `delay`) are deleted.

- **Domain (`worker/forum/domain/model/`)** — six annotation-free beans in **uniform
  snake_case** (Phase 14 collapsed every request/response pair to a single field — the backend
  serializes both directions identically; no `@SerializedName`):
  - [`Forum`](app/src/main/java/com/elysium/softwork/worker/forum/domain/model/Forum.kt)
    (`forum_id`, `title`, `description`, `company_id`),
    [`Category`](app/src/main/java/com/elysium/softwork/worker/forum/domain/model/Category.kt)
    (`category_id`, `title`, `description`, `forum_id`),
    [`Report`](app/src/main/java/com/elysium/softwork/worker/forum/domain/model/Report.kt)
    (`report_id`, `reason`, `description`, `user_account_id`, `report_date`, `area_company_id`) —
    pure wire beans.
  - [`Asset`](app/src/main/java/com/elysium/softwork/worker/forum/domain/model/Asset.kt) models
    the **JSON `AssetResponse`** in snake_case (`asset_id`, `message_id`, `name`, `url`,
    `file_size`, `file_type`, …). Its *creation* request is **not** JSON — see the multipart
    exception below.
  - [`Thread`](app/src/main/java/com/elysium/softwork/worker/forum/domain/model/Thread.kt)
    (`thread_id`, `title`, `area_company_id`, `last_message`, `category_id`, `message_count`) and
    [`Message`](app/src/main/java/com/elysium/softwork/worker/forum/domain/model/Message.kt)
    (`message_id`, `user_account_id`, `content_message`, `thread_id`, `@Ignore`-d `attachments`)
    double as Room `@Entity`s (`threads` / `messages`) per the documented offline-first cache
    exception (mirrors the former `Post`).
    - **`Message.message_id` is a fully nullable `@PrimaryKey(autoGenerate = true) Long? = null`.**
      The former non-null `= 0L` fallback was serialized as `"message_id": 0` on
      `POST /api/v1/messages`, which the backend rejects (`"Message ID must be a positive
      number."`). A `null` field is dropped by Gson, so the create request omits the key;
      `autoGenerate` is what lets Room accept the *nullable* PK (a plain nullable `@PrimaryKey`
      is rejected), and it stays dormant because every cache write carries the real server id.
    - **`Thread.thread_id` still uses the non-null `Long = 0L` fallback** — its create request
      (`POST /api/v1/threads`) has the identical latent `thread_id: 0` risk; apply the same
      nullable-autogenerate fix if/when the backend enforces a positive `thread_id`.
    - All other columns are nullable to match the wire; Gson overwrites the ids on parse.
  - The former `Post` and `ForumReport` models are deleted.
- **Multipart exception (`POST /api/v1/assets`).** Asset creation is `multipart/form-data`
  (the backend streams the binary to Cloudinary), so its form-field names bypass Jackson's
  snake_case JSON strategy and stay camelCase — `ForumWebService.createAsset` is now a
  `@Multipart` call with `@Part("messageId")`, `@Part("name")`, `@Part("fileType")`, and a
  `@Part file: MultipartBody.Part` (was previously a mis-declared `@Body Asset` JSON POST). The
  JSON `Asset` response is still snake_case.
- **Room (`worker/forum/data/local/`)**: `PostDao` → `ThreadDao` + `MessageDao`;
  `ForumDatabase` bumped to **v3** (v2 → v3 drops the now-redundant camelCase columns from
  `threads` / `messages`; destructive migration — the cache regenerates from the backend).
- **Network (`worker/forum/data/network/ForumWebService`)** — replaces `PostWebService` +
  `ForumReportWebService`. Relative paths only: `GET api/v1/forums`, `GET api/v1/categories`,
  `GET/POST api/v1/threads`, `GET api/v1/threads/{id}`, `GET/POST api/v1/messages`,
  `GET api/v1/messages/{id}`, `POST api/v1/assets`, `GET/POST api/v1/reports`.
- **Stores (`worker/forum/data/store/`)**: `PostStore`/`PostStoreImpl` → `ForumStore`/
  `ForumStoreImpl` (offline-first: Room `Flow`s + network refresh/upsert; `createThread`,
  `postMessage`; messages filtered client-side by `thread_id`). `ForumReportStoreImpl` is now
  Retrofit-backed over `ForumWebService` (`Report`). Both parse a `400` into
  `BadRequestException` via the shared `unwrap`/`throwTyped` pattern.
- **Use cases (`worker/forum/application/usecase/`)**: `Observe/Refresh/Get` renamed to
  Threads; new `ObserveThreadMessages`/`RefreshThreadMessages`/`CreateThread`/`PostMessage`.
  Writes pull the worker identity from `SharedPrefsManager` where the wire model carries it —
  `PostMessageUseCase` and `SubmitForumReportUseCase` bind `user_account_id` (camelCase
  request keys). (The backend `thread` create contract has no worker slot, so `CreateThread`
  forwards only title/category/area.)
- **ViewModels (`worker/forum/presentation/viewmodel/`)**: `ForumViewModel` (thread feed +
  `errorMessage`; the unmappable client-side category filter is removed), `ThreadViewModel`
  (thread header + live message stream + `sendMessage`), `NewPostViewModel` (create thread,
  then seed first message). All three catch a `400` → `BadRequestException` →
  `primaryFieldError()`. Report VMs realigned to `Report`. Every forum screen collects via
  `collectAsStateWithLifecycle()`.
- **Wiring**: `ServiceLocator` exposes `forumStore = ForumStoreImpl(threadDao, messageDao,
  forumWebService, gson)` and `forumReportStore = ForumReportStoreImpl(forumWebService, gson)`.
- **Tests**: `FakePostStore` → `FakeForumStore`; `ForumViewModelTest` rewritten for the thread
  feed.

#### Phase 12 caveats

- **Client-side message filter.** `GET /messages` is unfiltered; the per-thread list is
  filtered by `thread_id` in the store (no server-side `by-thread` route).
- **Thread creation is partial.** The backend `thread` create requires `areaCompanyId` /
  `categoryId` the composer does not collect, so creation surfaces the backend's `field_errors`
  inline until a fuller composer lands. Message posting and reporting (which only need
  `user_account_id`) work end-to-end.
- **UI simplified to the wire.** Thread cards show title + reply count + last-activity date;
  message bubbles show `User #<id>` + content. The former author/anonymity/category-chip and
  the report status pills are gone (the backend provides no equivalent). `ForumCategory` /
  `ReportStatus` / the `CategoryChips` composable are now unused (prunable).

### ✅ Phase 13 — Payment / Membership backend integration against the live Spring Boot API

The `payment.membership` context now talks to the real payment-service API. The mock
`PlanCatalogue`, the `MOCK_PAYMENT_DELAY_MS`, and the fake in-memory plan list are deleted.
The local membership **gate** (`hasMembership` / `currentPlanKey`, backed by `SharedPrefsManager`)
and the **client-side saved cards** (`PaymentMethod`) are retained — the backend models
neither a gate nor a stored instrument (it models payment as a transaction).

- **Domain (`payment/membership/domain/model/`)** — five annotation-free beans (no
  `@SerializedName`), **uniform snake_case** (Phase 14 collapsed every request/response pair to
  a single field — the backend serializes both directions identically):
  - [`Membership`](app/src/main/java/com/elysium/softwork/payment/membership/domain/model/Membership.kt):
    `membership_id`, `membership_start`, `membership_over`, `membership_status`.
  - [`Order`](app/src/main/java/com/elysium/softwork/payment/membership/domain/model/Order.kt):
    `order_id`, `user_account_id`, `amount`, `membership_id`.
  - [`Payment`](app/src/main/java/com/elysium/softwork/payment/membership/domain/model/Payment.kt):
    `payment_id`, `order_id`, `transaction_id`, `payment_date`.
  - [`Benefit`](app/src/main/java/com/elysium/softwork/payment/membership/domain/model/Benefit.kt):
    `benefit_id`, `title`, `description`, `membership_plan_id`.
  - [`MembershipPlan`](app/src/main/java/com/elysium/softwork/payment/membership/domain/model/MembershipPlan.kt):
    `plan_id`, `plan_name`, integer `price`, `membership_id`, `benefit_response_list`.
  - `PaymentMethod` is the **client-side** saved card (no backend resource), so its camelCase
    `holderName`/`expiryMonthYear` are local field names, not wire keys — left as-is.
- **Network (`payment/membership/data/network/MembershipWebService`)** — relative paths:
  `POST/GET api/v1/memberships`, `GET api/v1/memberships/{id}`, `POST/GET api/v1/orders`,
  `GET api/v1/orders/userAccount/{userAccountId}`, `POST api/v1/payments`,
  `GET api/v1/payments/{id}`, `GET api/v1/membership-plans`,
  `POST api/v1/membership-plans/{id}/benefits`.
- **Store (`MembershipStoreImpl`)** — `PlanCatalogue`/`delay`/`availablePlans()`/`findPlan()`
  removed; now `MembershipStoreImpl(prefs, webService, gson)` with suspend `getPlans()` /
  `createOrder()` / `createPayment()` (`400` → `BadRequestException`). Gate flags + card
  mutators unchanged.
- **Use cases (`PayMembershipUseCase`)** — rewritten as the real checkout: creates the order
  (binding `user_account_id` resourced from `SharedPrefsManager` via a provider, the plan
  `price`, and `membership_id`), registers the payment (generated `transactionId`, today's
  date), then **re-authenticates** via `AuthStore.reauthenticate()` (the Phase 9 hook —
  no refresh endpoint exists). `GetMembershipPlansUseCase` is now suspend/`Result`.
- **Presentation (`MembershipViewModel`)** — `availablePlans` is now an async
  `StateFlow<List<MembershipPlan>>` loaded on init; `payMembership(plan)` drives the checkout
  and a `400`/business-rule failure lands on `errorMessage`. `MembershipSelectionScreen` and
  `PaymentMethodsScreen` collect the catalogue via `collectAsStateWithLifecycle()`, format the
  integer price, render benefits as features, key by `plan_id`, and surface `errorMessage`.
  The membership gate flips on the success-screen CTA (so the confirmation renders before the
  root hot-swaps into the main shell).
- **Wiring**: `ServiceLocator` exposes `membershipWebService` and builds
  `MembershipStoreImpl(sharedPrefsManager, membershipWebService, gson)`. The `PayMembershipUseCase`
  factory pulls `authStore` + the prefs-backed account-id provider.
- **Tests**: `FakeMembershipStore` realigned to the new contract (`getPlans`/`createOrder`/
  `createPayment`, new `MembershipPlan` shape); `MembershipViewModelTest` updated for the
  plan-driven `payMembership` and the async catalogue.

#### Phase 13 caveats

- **Re-auth wired here.** The Phase 9 follow-up ("wire `reauthenticate()` into the payment
  flow") is now done inside `PayMembershipUseCase`. Re-auth is best-effort: a refresh hiccup
  does not fail an already-successful payment.
- **Saved cards stay client-side.** The backend has no payment-method resource, so the card
  composer / saved list remain in memory (lost on cold start); the actual charge is the
  `Payment` transaction. A generated `transactionId` stands in for a real processor token.
- **One new string** `payment_price_format` (`S/. %1$d`) formats the integer price in both
  locales.

### 🚧 Phase 14 — Uniform snake_case wire migration

The backend completed a **uniform snake_case** migration (documented in
`ELYSIUM-API_DOCUMENTATION.md`, dated 2026-07-03): every DTO now serializes with
`@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy)`, and the dynamic keys inside the
400 `field_errors` map are snake_cased too. This supersedes the earlier "asymmetric camelCase
vs snake_case coexistence" understanding across the Phase 9–13 beans. The migration policy:

- **Every domain property is snake_case** to match the wire by reflection (still no
  `@SerializedName`, still one bean per context). Where request/response use *different*
  snake_case keys for one concept, both coexist as nullable fields (that is still snake_case).
- **Multipart exception:** `POST /api/v1/assets` form-field names bypass Jackson JSON naming and
  stay camelCase (`messageId`, `name`, `fileType`, `file`).

**✅ IAM (done first):**
- `User.kt` fully snake_cased. Removed the phantom `gmail` field (the sign-in/sign-up response
  echoes the address under `email`, not `gmail`). Renamed `membershipStatus → membership_status`,
  `lastName → last_name`, `phoneNumber → phone_number`, `anonymousName → anonymous_name`. Fixed
  the profile date keys to the real wire: request `date_start` (was `start_date`) and response
  `star_start` (was `dateStart`, the backend typo). `isMembershipActive()` now reads
  `membership_status`.
- `AuthStoreImpl.registerWithGoogle` reads `user.email` (the `gmail` fallback is gone);
  `AuthWebService` KDoc realigned. `FakeAuthStore` + `AuthViewModelTest` updated in lockstep.

**✅ Feedback (done):**
- `Survey` / `QuestionSurvey` / `SurveyResponse` collapsed to single snake_case fields
  (removed `targetType`, `expirationType`, `textQuestion`, `questionType`, `surveyId`,
  `employeeProfileId`, `submittedAt` — the backend resolved every request/response mismatch).
- `SubmitSurveyResponseUseCase` binds `survey_id`/`employee_profile_id`/`submitted_at`;
  `SurveyStoreImpl` question filter reads `survey_id` only. `ChatMessage` (AI-chat mock, not a
  wire bean and outside the surveys endpoints) intentionally left as-is.

**✅ Notifications (done):**
- `NotificationDetail` collapsed to a single `notification_id` (removed the camelCase
  `notificationId`; request + response share the key). `Notification` was already fully
  snake_case; `NotificationFeedItem` is a render aggregate (not a wire bean), untouched. Join in
  `GetNotificationsUseCase` and the `NotificationsViewModelTest` fixtures already used
  `notification_id` — no call-site or test changes required.

**✅ Payment / Membership (done):**
- `Membership`, `Order`, `Payment`, `Benefit`, `MembershipPlan` collapsed to single snake_case
  fields (removed `membershipStart/Over/Status`, `userAccountId`, `membershipId`, `orderId`,
  `transactionId`, `paymentDate`, `planName`, `membershipPlanId`). `PayMembershipUseCase` binds
  `user_account_id`/`membership_id`/`order_id`/`transaction_id`/`payment_date`; the two plan
  screens read `plan_name` directly. `MembershipWebService` route literals
  (`orders/userAccount/{userAccountId}`, `{id}/benefits` `@Path` names) are URLs, not JSON keys —
  untouched. `PaymentMethod` is client-side only — left as-is. Tests already used snake_case.

**✅ Worker Forum (done):**
- `Forum`, `Category`, `Asset`, `Report`, `Thread`, `Message` collapsed to single snake_case
  fields (removed `companyId`, `forumId`, `areaCompanyId`, `lastMessage`, `categoryId`,
  `messageCount`, `userAccountId`, `contentMessage`, `threadId`, `reportDate`, `messageId`,
  `fileSize`, `fileType`). `ForumStoreImpl` message filter reads `thread_id` only; the three
  write use cases (`PostMessage`, `CreateThread`, `SubmitForumReport`) bind snake_case keys.
- **Room v2 → v3** (`ForumDatabase`) drops the redundant camelCase columns via destructive
  migration; DAOs already queried only `thread_id` / `message_id`, so no query changed.
- **Multipart fix:** `ForumWebService.createAsset` converted from a mis-declared `@Body Asset`
  JSON POST to a proper `@Multipart` upload with camelCase `@Part` form fields (`messageId`,
  `name`, `fileType`, `file`) — the documented multipart exception. Tests already used snake_case.
- **Interceptor header guard:** `AuthInterceptor` now injects `Content-Type: application/json`
  on every authenticated JSON write, but **skips it for `multipart/*` bodies** (detected via
  `request.body?.contentType()?.type`), so the `/assets` upload keeps OkHttp's body-derived
  `multipart/form-data; boundary=…`. Previously Content-Type was set unconditionally, which
  would have corrupted the multipart upload. `Accept` + `Authorization` are unchanged.

**✅ Shared error response (done):** `BadRequestResponse` trimmed to the single snake_case
`field_errors` map (the interim camelCase `fieldErrors` half removed); `primaryFieldError()`
reads it directly. `AuthToken.expiresAt` → `expires_at` (an unused speculative bean, aligned for
consistency — candidate for deletion since no stand-alone token endpoint exists).

**Migration complete.** All wire beans across IAM, Feedback, Notifications, Payment, Worker Forum
**and** the shared `BadRequestResponse` error envelope are uniform snake_case. Documented
non-wire exclusions (unchanged): `ChatMessage.isFromUser` (AI-chat mock, in-memory), `PaymentMethod`
`holderName`/`expiryMonthYear` (client-side saved card, no backend resource), and the `/assets`
multipart `@Part` form-field names `messageId`/`name`/`fileType`/`file` (bypass Jackson JSON naming).

### 🔜 Next — Phase (IMPLEMENTATION WITH REAL BACKEND API)

- Comment domain + store + WebService backing `ThreadScreen`.
- Image / attachment picker for the new-post composer.
- Real user/profile data sourced from a `ProfileStore` (replace placeholder strings).
- Forgot-password flow.
- Wire `AuthStore.reauthenticate()` into `PaymentSuccessScreen`; route `ACTIVE`-membership
  logins through `MembershipStore` once the backend reports `membershipStatus`.

---

## Security & Environment Variables

The app is built with **zero secrets in source**. Backend URLs and third-party API keys are
injected at compile time via the [Secrets Gradle Plugin for Android](https://developers.google.com/maps/documentation/android-sdk/secrets-gradle-plugin)
and exposed to Kotlin through `BuildConfig` fields.

### Local setup (new developer checklist)

1. Copy the committed template to a personal, gitignored file:
   ```bash
   cp secrets.defaults.properties secrets.properties
   ```
2. Fill in real values in `secrets.properties` (see the keys below). **Never commit this
   file** — it is listed in `.gitignore`.
3. Build normally: `./gradlew assembleDebug` (or `.\gradlew.bat assembleDebug` on Windows).
   The plugin reads `secrets.properties` first, then falls back to
   `secrets.defaults.properties` for any missing key, so an unconfigured environment still
   builds and boots.

### Available keys

| Properties key | Generated constant | Consumer |
|---|---|---|
| `BACKEND_BASE_URL` | `BuildConfig.BACKEND_BASE_URL` | `ApiClient.retrofit` base URL — fail-fast if blank |
| `API_KEY_GEMINI` | `BuildConfig.API_KEY_GEMINI` | `ApiKeyInterceptor` → `x-goog-api-key` header on `generativelanguage.googleapis.com` |
| `GOOGLE_OAUTH_CLIENT` | `BuildConfig.GOOGLE_OAUTH_CLIENT` | Google Cloud Web Application **OAuth client id** — `serverClientId` for the Credential Manager `GetSignInWithGoogleOption` (native Google Sign-In). Renamed from the legacy `API_KEY_GMAIL` alias. |
| `API_KEY_EXTERNAL_SERVICE` | `BuildConfig.API_KEY_EXTERNAL_SERVICE` | `ApiKeyInterceptor` → `X-Api-Key` header on the configured external host |

### CI / pipelines

CI must materialize `secrets.properties` from the team secret manager **before** invoking
`./gradlew assembleRelease`. The committed defaults file alone produces a build that boots
but with no functional third-party integrations — adequate for unit tests and static
analysis only.

### Network layer rule

The base URL has exactly **one** source of truth: `BuildConfig.BACKEND_BASE_URL`. Retrofit
`WebService` interfaces (`AuthWebService`, `PostWebService`, …) must only declare
**relative paths** in their `@GET`/`@POST` annotations. Reintroducing a full URL — or any
new `"https://..."` literal anywhere under `app/src/main/` — is a regression and must be
caught in review.

Third-party API keys must never appear in `WebService` annotations either. Route them
through `ApiKeyInterceptor` on a per-host basis (see `ApiKeyInterceptor.hostKeyMap`).

### The `unset` sentinel

The Secrets Gradle Plugin refuses to emit empty-string values as `BuildConfig` fields
(generates illegal Java). The defaults file therefore uses the literal string `unset` as
the placeholder for unconfigured API keys. `ApiKeyInterceptor` recognizes this sentinel
and **skips header injection** for it, so a default build sends no third-party credentials
rather than leaking the string `"unset"` over the wire. Replace `unset` with a real key in
`secrets.properties` to enable the corresponding integration.

### Adding a new key

1. Append the new key to `secrets.defaults.properties` with a non-blank placeholder (use
   `unset` — see above).
2. Declare the matching `buildConfigField("String", "MY_NEW_KEY", "\"\"")` inside
   `android { defaultConfig { ... } }` in `app/build.gradle.kts`. This is the fallback the
   secrets plugin overwrites when the same name is present in `secrets.properties`.
3. Consume it from Kotlin via `BuildConfig.MY_NEW_KEY`. For host-scoped third-party keys,
   add an entry to `ApiKeyInterceptor.hostKeyMap` rather than handling it in a store.

### New dependencies (with rationale)

- `com.google.android.libraries.mapsplatform.secrets-gradle-plugin:2.0.1` — generates
  `BuildConfig` fields from `secrets.properties` with a committed defaults fallback.
- `com.squareup.okhttp3:okhttp:4.12.0` — pinned explicitly (Retrofit 3 brings it
  transitively) so the version is locked and an `OkHttpClient.Builder` can register the
  interceptor chain.
- `com.squareup.okhttp3:logging-interceptor:4.12.0` (debug only) — `BODY`-level request
  logging in Logcat. Never reaches release builds.

### AGP 9 compatibility note

The Secrets Gradle Plugin (2.0.1, Apr 2024) predates AGP 9. If a future AGP bump breaks it,
the plugin can be replaced by ~15 lines of inline `Properties().load(...)` +
`buildConfigField(...)` in `app/build.gradle.kts`; the rest of the stack (`BuildConfig`
consumers, interceptor, defaults file) is unaffected by that swap.

---

# Final Consideration
- When implementing important changes or adding new dependencies, please update this document with the rationale and the impact on the architecture. This will help maintain a clear understanding of the design decisions and ensure consistency across the codebase.