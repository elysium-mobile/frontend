# SoftWork Platform — API Documentation

> Exhaustive documentation of the Spring Boot backend. Contains exact request/response JSON
> extracted directly from the DTOs (`interfaces/rest/resources`) of each bounded context,
> including integrations with external services (Cloudinary, Stripe, Google Gemini).

---

## UPDATE (2026-07-03): Uniform snake_case Naming

**The naming inconsistency described in previous versions of this document has been removed.**

Every DTO under `interfaces/rest/resources` (all `*Request` and `*Response` records, across **all**
bounded contexts) now carries a class-level annotation:

```java
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateUserRequest( ... ) { }
```

- **All per-field `@JsonProperty("...")` annotations were removed.** Naming is now governed
  exclusively by the class-level `SnakeCaseStrategy`. This applies to both **serialization**
  (responses) and **deserialization** (requests).
- **Result: every JSON key is snake_case, uniformly, for both requests and responses.** The former
  mix of camelCase/snake_case is gone.
- The global `spring.jackson.property-naming-strategy=SNAKE_CASE` in `application.properties` still
  applies, but the DTOs no longer depend on it — each record declares the strategy explicitly, which
  is the source of truth.

### Pre-existing bugs that this standardization fixed

| DTO | Old JSON key | New JSON key | Was |
|---|---|---|---|
| `CompanyResponse` | `comapany_id` | `company_id` | typo in an `@JsonProperty` |
| `CreateSurveyRequest` / `UpdateSurveyRequest` / `SurveyResponse` | `expirationType` | `expiration_time` | request/response now match (field is `expirationTime`) |
| `InitiateRefundRequest` | `refoundAmountCents` | `refund_amount_cents` | typo in an `@JsonProperty` |
| Many DTOs (e.g. `lastName`, `userId`, `companyId`, …) | camelCase | snake_case | `@JsonProperty` overrode the global strategy |

### Residual quirks (NOT naming — these come from misnamed/typo'd Java fields, left intact)

The strategy converts the **Java field name** to snake_case. A few fields are themselves misnamed in
the domain records, so their canonical snake_case still looks odd. These field names were **not**
altered (out of scope for the naming change):

| DTO | Java field | JSON key | Note |
|---|---|---|---|
| `EmployeeProfileResponse` | `dateStart` | `date_start` | field is a typo of "dateStart"; response key differs from the request's `date_start` |
| `RRHHProfileSignUpRequest`, `CreateRRHHProfileRequest`, `UpdateRRHHProfileRequest`, `RRHHProfileResponse` | `RRHHDepartment` | `rrhhdepartment` | leading consecutive capitals → Jackson emits **no** underscore |

> **Note on Jackson's algorithm**: `SnakeCaseStrategy` does not insert an underscore between
> consecutive uppercase letters. So `RUC` → `ruc` and `RRHHDepartment` → `rrhhdepartment`
> (not `rrhh_department`). Standard camelCase like `userAccountId` → `user_account_id` behaves as
> expected.

---

## Table of Contents

1. [Connection and Configuration](#1-connection-and-configuration)
2. [Authentication (IAM)](#2-authentication-iam)
3. [Bounded Context: IAM](#3-bounded-context-iam)
4. [Bounded Context: Dashboard](#4-bounded-context-dashboard)
5. [Bounded Context: Feedback](#5-bounded-context-feedback)
6. [Bounded Context: Payment Service](#6-bounded-context-payment-service)
7. [Bounded Context: Notification](#7-bounded-context-notification)
8. [Bounded Context: Worker Forum](#8-bounded-context-worker-forum)
9. [Bounded Context: Profile Performance](#9-bounded-context-profile-performance)
10. [Error Handling](#10-error-handling)
11. [External Services: Cloudinary, Stripe, Google Gemini](#11-external-services-cloudinary-stripe-google-gemini)
12. [Seed Data](#12-seed-data)
13. [Naming Reference (Request vs Response)](#13-naming-reference-request-vs-response)
14. [Endpoint Routes — Quick Reference](#14-endpoint-routes--quick-reference)

---

## 1. Connection and Configuration

### PostgreSQL Database

**`dev` profile** (`application-dev.properties`):
```
URL:      jdbc:postgresql://localhost:5432/softwork
User:     postgres
Password: postgres
Port:     5432
```

**`prod` profile** (`application-prod.properties`) — environment variables:
```properties
spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://db:5432/softwork}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:postgres}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:postgres}
server.port=${PORT:8080}
authorization.jwt.secret=${JWT_SECRET}
authorization.jwt.expiration.days=${JWT_EXPIRATION_DAYS:7}
authorization.google.client-id=${GOOGLE_CLIENT_ID}
swagger.server.url=https://${API_HOST}
```

> **Note**: In production the default port changes to `8080` (via `${PORT:8080}`), different from
> the `8092` used in dev.

```sql
CREATE DATABASE softwork;
```

### Starting the Backend

```bash
# Development
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Production (requires environment variables)
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

- **Port (dev)**: `8092` — **Base URL**: `http://localhost:8092`
- **Port (prod)**: `${PORT:8080}` — configurable via environment variable
- **Swagger UI**: `http://localhost:8092/swagger-ui.html` (dev)
- Tables are created automatically (`ddl-auto=update`)
- `data.sql` seeds test data on startup (`spring.sql.init.mode=always` also enabled in prod)

### Required Headers

```
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>    (except public endpoints)
```

### Production Environment Variables (Summary)

| Variable | Service | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | PostgreSQL | JDBC URL of the DB |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL | DB user |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL | DB password |
| `JWT_SECRET` | Security | Key used to sign JWTs (mandatory, no default) |
| `JWT_EXPIRATION_DAYS` | Security | Token expiration in days (default 7) |
| `PORT` | Server | HTTP port (default 8080) |
| `API_HOST` | Swagger | Public host for the server URL shown in Swagger |
| `CLOUDINARY_URL` | Cloudinary | Connection URL including credentials |
| `STRIPE_SECRET_KEY` | Stripe | API secret key (`sk_...`) |
| `STRIPE_WEBHOOK_SECRET` | Stripe | Webhook signing secret (`whsec_...`) |
| `GOOGLE_GENAI_API_KEY` | Google Gemini | Google GenAI API key |
| `GOOGLE_CLIENT_ID` | Google Sign-In | OAuth 2.0 Client ID used as the expected `id_token` audience |

See section [11](#11-external-services-cloudinary-stripe-google-gemini) for the full detail of
these integrations.

---

## 2. Authentication (IAM)

Public endpoints — no token required.

### POST `/api/v1/authentication/sign-in`

**Request** (`SignInRequest`):
```json
{
  "email": "carlos.ramirez@techcorp.pe",
  "password": "password123"
}
```

**Response 200** (`AuthenticatedUserAccountResponse`):
```json
{
  "id": 1,
  "email": "carlos.ramirez@techcorp.pe",
  "token": "eyJhbGciOiJIUzI1NiIs..."
}
```

---

### POST `/api/v1/authentication/sign-up/employee`

**Request** (`EmployeeProfileSignUpRequest`):
```json
{
  "name": "Juan",
  "last_name": "Perez",
  "phone_number": "987654321",
  "dni": "12345678",
  "email": "juan@example.com",
  "password": "myPassword123",
  "anonymous_name": "AnonymousJuan",
  "date_start": "2026-01-15",
  "position": "Backend Developer",
  "salary": 5000
}
```

**Response 201** (`EmployeeProfileResponse`):
```json
{
  "employee_profile_id": 1,
  "date_start": "2026-01-15",
  "position": "Backend Developer",
  "salary": 5000,
  "work_of_team_id": 1,
  "user_account_id": 1
}
```

> **Note**: The sign-up endpoint returns the created `EmployeeProfileResponse`, not an
> `AuthenticatedUserAccountResponse`. The JWT-based session is established via sign-in
> afterwards.
> **Warning**: the response field `date_start` is a typo of `date_start` (see residual quirks).

---

### POST `/api/v1/authentication/sign-up/rrhh`

**Request** (`RRHHProfileSignUpRequest`):
```json
{
  "name": "Ana",
  "last_name": "Garcia",
  "phone_number": "999888777",
  "dni": "87654321",
  "email": "ana@example.com",
  "password": "myPassword123",
  "anonymous_name": "AnonymousAna",
  "rrhhdepartment": "Human Resources",
  "status_hierarchy": "Manager"
}
```

> **Note**: the key is `rrhhdepartment` (no underscore) because the Java field is `RRHHDepartment`
> and Jackson's `SnakeCaseStrategy` does not split consecutive capitals.

**Response 201** (`RRHHProfileResponse`):
```json
{
  "rrhh_profile_id": 1,
  "rrhhdepartment": "Human Resources",
  "status_hierarchy": "Manager",
  "user_account_id": 1
}
```

> **Note**: Same as employee sign-up — returns the profile, not an auth token.
> The JWT session is obtained via the sign-in endpoint.

---

### Sign in with Google (two-phase flow)

Google authentication is split into two phases so that **no user data is ever mocked or hardcoded**:

1. **`POST /google`** validates the Google `id_token` on the backend (`GoogleIdTokenVerifier`,
   audience = `GOOGLE_CLIENT_ID`). It **persists nothing** for new users — it only reports whether an
   account already exists for the verified email.
2. If the account does not exist yet, the frontend shows a role form and calls
   **`POST /sign-up/employee/google`** or **`POST /sign-up/rrhh/google`** with the **same** `id_token`
   plus the real profile data. The backend re-validates the token, derives the trusted email from it
   (never from the request body), creates `User` + `UserAccount` (Google-backed, random password) +
   the profile, and returns the application JWT.

> On the Google sign-up endpoints the client sends **no** `email`, `password` or `anonymous_name`:
> the email comes from the verified token, the password is a random BCrypt hash (password login is
> disabled for Google accounts), and the anonymous name is auto-generated. Every other field is real
> data entered by the user. See the integration detail in [section 11.5](#115-google-identity--sign-in-with-google-id_token-verification).

---

### POST `/api/v1/authentication/google`

**Phase 1** — validate the Google `id_token` and check whether the account already exists. Creates nothing.

**Request** (`GoogleSignInRequest`):
```json
{
  "id_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6..."
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `id_token` | `idToken` | String | Google Identity Services ID token; validated server-side |

**Response 200 — account already exists** (`GoogleAuthenticationResponse`):
```json
{
  "registered": true,
  "id": 1,
  "email": "carlos.ramirez@techcorp.pe",
  "token": "eyJhbGciOiJIUzI1NiIs..."
}
```

**Response 200 — registration required** (`GoogleAuthenticationResponse`):
```json
{
  "registered": false,
  "id": null,
  "email": null,
  "token": null
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `registered` | `registered` | boolean | `true` → session token issued; `false` → must complete sign-up |
| `id` | `id` | Long | user account id (`null` when not registered) |
| `email` | `email` | String | account email (`null` when not registered) |
| `token` | `token` | String | application JWT (`null` when not registered) |

> Returns `200` for any **valid** token (registered or not). An **invalid/expired** token, or an
> audience that does not match `GOOGLE_CLIENT_ID`, raises `IllegalArgumentException` → `400 Bad Request`.

---

### POST `/api/v1/authentication/sign-up/employee/google`

**Phase 2 (employee)** — complete registration for a Google-authenticated user and open a session.

**Request** (`GoogleEmployeeSignUpRequest`):
```json
{
  "id_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6...",
  "name": "Juan",
  "last_name": "Perez",
  "phone_number": "987654321",
  "dni": "12345678",
  "date_start": "2026-01-15",
  "position": "Backend Developer",
  "salary": 5000
}
```

| JSON Key | Java Field | Type | Validation | Notes |
|---|---|---|---|---|
| `id_token` | `idToken` | String | @NotNull @NotBlank | re-validated; the account email is derived from it |
| `name` | `name` | String | @NotNull @NotBlank | |
| `last_name` | `lastName` | String | @NotNull @NotBlank | |
| `phone_number` | `phoneNumber` | String | @NotNull @NotBlank | |
| `dni` | `dni` | String | @NotNull @NotBlank | 8 chars |
| `date_start` | `dateStart` | Date | @NotNull | |
| `position` | `position` | String | @NotNull @NotBlank | |
| `salary` | `salary` | Integer | @NotNull | |

> No `email`, `password` or `anonymous_name` in the request — see the note above.

**Response 201** (`AuthenticatedUserAccountResponse`):
```json
{
  "id": 9,
  "email": "juan@gmail.com",
  "token": "eyJhbGciOiJIUzI1NiIs..."
}
```

> `400 Bad Request` if the email already has an account (`"Email already exists"`) or the token is invalid.

---

### POST `/api/v1/authentication/sign-up/rrhh/google`

**Phase 2 (RRHH)** — same as above but creates an RRHH profile.

**Request** (`GoogleRRHHSignUpRequest`):
```json
{
  "id_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6...",
  "name": "Ana",
  "last_name": "Garcia",
  "phone_number": "999888777",
  "dni": "87654321",
  "rrhhdepartment": "Human Resources",
  "status_hierarchy": "Manager"
}
```

> **Note**: the key is `rrhhdepartment` (no underscore) because the Java field is `RRHHDepartment`
> and Jackson's `SnakeCaseStrategy` does not split consecutive capitals — same quirk as the classic
> `/sign-up/rrhh`.

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|
| `id_token` | `idToken` | String | @NotNull @NotBlank |
| `name` | `name` | String | @NotNull @NotBlank |
| `last_name` | `lastName` | String | @NotNull @NotBlank |
| `phone_number` | `phoneNumber` | String | @NotNull @NotBlank |
| `dni` | `dni` | String | @NotNull @NotBlank |
| `rrhhdepartment` | `RRHHDepartment` | String | @NotNull @NotBlank |
| `status_hierarchy` | `statusHierarchy` | String | @NotNull @NotBlank |

**Response 201** (`AuthenticatedUserAccountResponse`):
```json
{
  "id": 9,
  "email": "ana@gmail.com",
  "token": "eyJhbGciOiJIUzI1NiIs..."
}
```

---

## 3. Bounded Context: IAM

> Package: `pe.edu.upc.soft.work.platform.iam`
> All DTOs annotated with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`.

---

### 3.1 Users — `/api/v1/users`

#### POST `/api/v1/users` — Create user

**Request** (`CreateUserRequest`):
```json
{
  "name": "string",
  "last_name": "string",
  "phone_number": "string",
  "dni": "string"
}
```

| JSON Key | Java Field | Type | Validation | Notes |
|---|---|---|---|---|
| `name` | `name` | String | @NotNull @NotBlank | max 100 chars |
| `last_name` | `lastName` | String | @NotNull @NotBlank | max 100 chars |
| `phone_number` | `phoneNumber` | String | @NotNull @NotBlank | max 15 chars |
| `dni` | `dni` | String | @NotNull @NotBlank @Min(0) | max 8 chars, unique |

**Response 201** (`UserResponse`):
```json
{
  "user_id": 1,
  "name": "Carlos",
  "last_name": "Mendoza",
  "phone_number": "987654321",
  "dni": "12345678"
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `user_id` | `userId` | Long | Auto-generated ID |
| `name` | `name` | String | |
| `last_name` | `lastName` | String | |
| `phone_number` | `phoneNumber` | String | |
| `dni` | `dni` | String | |

#### PUT `/api/v1/users/{id}` — Update user

**Request** (`UpdateUserRequest`): Same structure as `CreateUserRequest`.

#### GET `/api/v1/users` — List all

**Response 200**: `UserResponse[]`

#### GET `/api/v1/users/{id}` — Get by ID

**Response 200**: `UserResponse` | **404** if not found.

#### DELETE `/api/v1/users/{userId}` — Delete

**Response 200/204**

**Errors**: Duplicate DNI → `500 RuntimeException`.

---

### 3.2 User Accounts — `/api/v1/user_accounts`

#### POST `/api/v1/user_accounts` — Create account

**Request** (`CreateUserAccountRequest`):
```json
{
  "user_id": 1,
  "email": "juan@example.com",
  "password": "myPassword123",
  "anonymous_name": "AnonymousJuan",
  "membership_id": 1,
  "company_id": 1
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|---|
| `user_id` | `userId` | Long | @NotNull |
| `email` | `email` | String | @NotNull @NotBlank |
| `password` | `password` | String | @NotNull @NotBlank |
| `anonymous_name` | `anonymousName` | String | @NotNull @NotBlank |
| `membership_id` | `membershipId` | Long | @NotNull |
| `company_id` | `companyId` | Long | @NotNull |

**Response 201** (`UserAccountResponse`):
```json
{
  "user_account_id": 1,
  "user_id": 1,
  "email": "juan@example.com",
  "password": "$2a$10$...",
  "anonymous_name": "AnonymousJuan",
  "membership_id": 1,
  "company_id": 1
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `user_account_id` | `userAccountId` | Long | |
| `user_id` | `userId` | Long | |
| `email` | `email` | String | |
| `password` | `password` | String | BCrypt hash is exposed |
| `anonymous_name` | `anonymousName` | String | |
| `membership_id` | `membershipId` | Long | |
| `company_id` | `companyId` | Long | |

#### PUT `/api/v1/user_accounts/{id}` — Update

**Request** (`UpdateUserAccountRequest`): Same structure as `CreateUserAccountRequest`.

#### GET `/api/v1/user_accounts` — List all

#### DELETE `/api/v1/user_accounts/{id}` — Delete

---

### 3.3 Employee Profiles — `/api/v1/employee-profile`

#### POST `/api/v1/employee-profile` — Create profile

**Request** (`CreateEmployeeProfileRequest`):
```json
{
  "date_start": "2026-01-15",
  "position": "Backend Developer",
  "salary": 5000,
  "work_of_team_id": 1,
  "user_account_id": 1
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|---|
| `date_start` | `dateStart` | Date | @NotNull |
| `position` | `position` | String | @NotNull @NotBlank |
| `salary` | `salary` | Integer | @NotNull |
| `work_of_team_id` | `workOfTeamId` | Long | @NotNull |
| `user_account_id` | `UserAccountId` | Long | @NotNull |

**Response 201** (`EmployeeProfileResponse`):
```json
{
  "employee_profile_id": 1,
  "date_start": "2026-01-15",
  "position": "Backend Developer",
  "salary": 5000,
  "work_of_team_id": 1,
  "user_account_id": 1
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `employee_profile_id` | `employeeProfileId` | Long | |
| `date_start` | `dateStart` | Date | **field is a typo of "dateStart"** → serializes as `date_start` |
| `position` | `position` | String | |
| `salary` | `salary` | Integer | |
| `work_of_team_id` | `workOfTeamId` | Long | |
| `user_account_id` | `UserAccountId` | Long | |

> **RESIDUAL INCONSISTENCY**: the request sends `date_start` but the response returns `date_start`,
> because the response record's field is literally named `dateStart` (a typo left intact — renaming
> domain fields was out of scope for the naming change).

#### PUT `/api/v1/employee-profile/{id}` — Update

**Request** (`UpdateEmployeeProfileRequest`): Same structure as Create.

#### GET `/api/v1/employee-profile` — List all

#### GET `/api/v1/employee-profile/{id}` — Get by ID

#### DELETE `/api/v1/employee-profile/{employeeProfileId}` — Delete

---

### 3.4 RRHH Profiles — `/api/v1/rrhh-profiles`

#### POST `/api/v1/rrhh-profiles` — Create RRHH profile

**Request** (`CreateRRHHProfileRequest`):
```json
{
  "rrhhdepartment": "Human Resources",
  "status_hierarchy": "Manager",
  "user_account_id": 1
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|---|
| `rrhhdepartment` | `RRHHDepartment` | String | @NotNull @NotBlank |
| `status_hierarchy` | `statusHierarchy` | String | @NotNull @NotBlank |
| `user_account_id` | `userAccountId` | Long | @NotNull |

> **Note**: `RRHHDepartment` → `rrhhdepartment` (no underscore). This is a change from the previous
> `rrhh_department`, which had been forced by an explicit `@JsonProperty` now removed.

**Response 201** (`RRHHProfileResponse`):
```json
{
  "rrhh_profile_id": 1,
  "rrhhdepartment": "Human Resources",
  "status_hierarchy": "Manager",
  "user_account_id": 1
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `rrhh_profile_id` | `rrhhProfileId` | Long | |
| `rrhhdepartment` | `RRHHDepartment` | String | no underscore (consecutive capitals) |
| `status_hierarchy` | `statusHierarchy` | String | |
| `user_account_id` | `userAccountId` | Long | |

#### PUT `/api/v1/rrhh-profiles/{id}` — Update

**Request** (`UpdateRRHHProfileRequest`): Same structure as Create.

#### GET `/api/v1/rrhh-profiles` — List all

#### GET `/api/v1/rrhh-profiles/{id}` — Get by ID

#### DELETE `/api/v1/rrhh-profiles/{id}` — Delete

---

## 4. Bounded Context: Dashboard

> Package: `pe.edu.upc.soft.work.platform.dashboard`
> All DTOs annotated with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`.

---

### 4.1 Companies — `/api/v1/companies`

#### POST `/api/v1/companies` — Create company

**Request** (`CreateCompanyRequest`):
```json
{
  "name": "TechCorp SAC",
  "ruc": "20123456789",
  "contact_email": "info@techcorp.pe",
  "contact_phone": "015551234"
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|
| `name` | `name` | String | @NotNull @NotBlank |
| `ruc` | `RUC` | String | @NotNull @NotBlank, unique |
| `contact_email` | `contactEmail` | String | @NotNull @NotBlank |
| `contact_phone` | `contactPhone` | String | @NotNull @NotBlank |

> **Note**: `RUC` → `ruc` (consecutive capitals collapse without underscores).

**Response 201** (`CompanyResponse`):
```json
{
  "company_id": 1,
  "name": "TechCorp SAC",
  "ruc": "20123456789",
  "contact_email": "info@techcorp.pe",
  "contact_phone": "015551234",
  "employees": [],
  "area_company_responses": []
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `company_id` | `companyId` | Long | **typo fixed**: was `comapany_id`, now correctly `company_id` |
| `name` | `name` | String | |
| `ruc` | `RUC` | String | request and response now match (`ruc`) |
| `contact_email` | `contactEmail` | String | request and response now match |
| `contact_phone` | `contactPhone` | String | request and response now match |
| `employees` | `employees` | UserAccountResponse[] | List of linked employees |
| `area_company_responses` | `areaCompanyResponses` | AreaCompanyResponse[] | List of linked areas |

#### PUT `/api/v1/companies/{id}` — Update

**Request** (`UpdateCompanyRequest`): Same structure as Create.

#### GET `/api/v1/companies` — List all

#### GET `/api/v1/companies/{id}` — Get by ID

#### DELETE `/api/v1/companies/{id}` — Delete (204 No Content)

#### GET `/api/v1/companies/search?name={name}` — Search by name

**Response 200**: `CompanyResponse[]` | **404** if no results.

#### POST `/api/v1/companies/{companyId}/employees` — Add employee

**Request** (`AddEmployeeToCompanyRequest`):
```json
{
  "employee_id": 1
}
```

**Response 200**: Updated `CompanyResponse`.

#### POST `/api/v1/companies/{companyId}/area-companies` — Add area

**Request** (`AddAreaCompanyToCompanyRequest`):
```json
{
  "area_company_id": 1
}
```

**Response 200**: Updated `CompanyResponse`.

---

### 4.2 Area Companies — `/api/v1/area-company`

> Note: the route is **singular** `/area-company`, not `/area-companies`.

#### POST `/api/v1/area-company` — Create area

**Request** (`CreateAreaCompanyRequest`):
```json
{
  "name": "Software Development",
  "annual_budget": 100000,
  "company_id": 1
}
```

**Response 201** (`AreaCompanyResponse`):
```json
{
  "area_company_id": 1,
  "name": "Software Development",
  "annual_budget": 100000,
  "company_id": 1,
  "unit_of_work_list": []
}
```

#### PUT `/api/v1/area-company/{id}` — Update

**Request** (`UpdateAreaCompanyRequest`): Same structure as Create.

#### GET `/api/v1/area-company` — List all

#### GET `/api/v1/area-company/{id}` — Get by ID

#### DELETE `/api/v1/area-company/{id}` — Delete (204 No Content)

#### POST `/api/v1/area-company/{areaCompanyId}/unitsOfWork` — Add UnitOfWork

**Request** (`AddUnitOfWorkToAreaCompanyRequest`):
```json
{
  "unit_of_work_id": 1
}
```

---

### 4.3 Dashboards — `/api/v1/dashboards`

#### POST `/api/v1/dashboards` — Create dashboard

**Request** (`CreateDashboardRequest`):
```json
{
  "title": "Main Dashboard",
  "description": "Overview of metrics",
  "ruc": "20123456789",
  "company_id": 1
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|
| `title` | `title` | String | @NotNull @NotBlank |
| `description` | `description` | String | @NotNull @NotBlank |
| `ruc` | `ruc` | String | @NotNull @NotBlank |
| `company_id` | `companyId` | Long | @NotNull |

> **Note**: Both Create and Update accept `company_id`.

**Response 201** (`DashboardResponse`):
```json
{
  "dashboard_id": 1,
  "title": "Main Dashboard",
  "description": "Overview of metrics",
  "ruc": "20123456789",
  "company_id": 1,
  "widgets": []
}
```

#### PUT `/api/v1/dashboards/{id}` — Update

**Request** (`UpdateDashboardRequest`): Same structure as Create.

#### GET `/api/v1/dashboards` — List all

#### GET `/api/v1/dashboards/{id}` — Get by ID

#### DELETE `/api/v1/dashboards/{id}` — Delete (204 No Content)

#### GET `/api/v1/dashboards/company/{companyId}` — By company

**Response 200**: `DashboardResponse[]` | **404** if none found.

#### POST `/api/v1/dashboards/{dashboardId}/widgets` — Add widget

**Request** (`AddWidgetToDashboardRequest`):
```json
{
  "widget_id": 1
}
```

---

### 4.4 Widgets — `/api/v1/widgets` `@Deprecated`

> **DEPRECATED**: This endpoint is scheduled for removal. Use Dashboard's widget
> sub-resource (`POST /api/v1/dashboards/{dashboardId}/widgets`) instead.

#### POST `/api/v1/widgets` — Create widget

**Request** (`CreateWidgetRequest`):
```json
{
  "title": "Monthly Productivity",
  "refresh_period": 60,
  "dashboard_id": 1
}
```

**Response 201** (`WidgetResponse`):
```json
{
  "widget_id": 1,
  "title": "Monthly Productivity",
  "refresh_period": 60,
  "dashboard_id": 1
}
```

#### PUT `/api/v1/widgets/{id}` — Update

#### GET `/api/v1/widgets` — List all

#### GET `/api/v1/widgets/{id}` — Get by ID

#### DELETE `/api/v1/widgets/{id}` — Delete (204 No Content)

---

### 4.5 Unit of Work — `/api/v1/unit-of-work`

> Note: the route is **singular** `/unit-of-work`, not `/unit-of-works`.

#### POST `/api/v1/unit-of-work` — Create

**Request** (`CreateUnitOfWorkRequest`):
```json
{
  "name": "Sprint 1"
}
```

**Response 201** (`UnitOfWorkResponse`):
```json
{
  "unit_of_work_id": 1,
  "name": "Sprint 1",
  "work_team_list": []
}
```

#### PUT `/api/v1/unit-of-work/{id}` — Update

#### GET `/api/v1/unit-of-work` — List all

#### GET `/api/v1/unit-of-work/{id}` — Get by ID

#### DELETE `/api/v1/unit-of-work/{id}` — Delete (204 No Content)

#### POST `/api/v1/unit-of-work/{uniOfWorkId}/work-teams` — Add team

**Request** (`AddWorkTeamToUnitOFWorkRequest`):
```json
{
  "work_team_id": 1
}
```

---

### 4.6 Work Teams — `/api/v1/work-teams`

#### POST `/api/v1/work-teams` — Create team

**Request** (`CreateWorkTeamRequest`):
```json
{
  "team_name": "Team Alpha",
  "leader_of_team": "Carlos Mendoza",
  "unit_of_work_id": 1
}
```

**Response 201** (`WorkTeamResponse`):
```json
{
  "work_team_id": 1,
  "team_name": "Team Alpha",
  "leader_of_team": "Carlos Mendoza",
  "unit_of_work_id": 1
}
```

#### PUT `/api/v1/work-teams/{id}` — Update

#### GET `/api/v1/work-teams` — List all

#### GET `/api/v1/work-teams/{id}` — Get by ID

#### DELETE `/api/v1/work-teams/{id}` — Delete (204 No Content)

---

### 4.7 Dashboard Assistant (AI) — `/api/v1/dashboard-assistant`

> Endpoint powered by Google Gemini (`gemini-2.5-flash`), reserved for **RRHH**. See the full
> technical details of this integration in
> [section 11.6](#116-google-gemini--spring-ai-dashboard-assistant).
>
> This is the **RRHH-facing** counterpart of the [Employee Assistant](#55-employee-assistant-ai--apiv1feedback-assistant)
> (`/api/v1/feedback-assistant`): the two endpoints were split so that each AI persona is scoped to
> the profile that consumes it — employees get help with policies/surveys/forum, RRHH gets a data
> diagnosis of the company's climate. Controller: `DashboardAssistantController`.

#### POST `/api/v1/dashboard-assistant` — Analyze a company's dashboard

**Auth**: requires `Authorization: Bearer <JWT>` (not in the public allowlist).
**CORS**: only `POST` is enabled.

**Request** (`AnalyzeDashboardRequest`):
```json
{
  "company_id": 1,
  "question": "Why did forum activity drop in the QA area last month?"
}
```

| JSON Key | Java Field | Type | Validation | Notes |
|---|---|---|---|---|
| `company_id` | `companyId` | Long | Must reference an existing `Company` | `NoSuchElementException` if not found → `500` |
| `question` | `question` | String | Optional (nullable) | If omitted/blank, the assistant runs a general climate diagnosis instead of answering a specific question |

**What happens server-side** (`DashboardAssistantServiceImpl`): the service gathers 4 metrics for the
company — average performance, positive-survey rate, report count, forum activity — computes a
**deterministic** status label from thresholds (not from the AI), then asks Gemini to explain that
status and give recommendations, injecting the raw metrics + the optional `question` into the prompt.

**Response 200** (`DashboardInsightResponse`):
```json
{
  "status": "REGULAR",
  "analysis": "El clima laboral se encuentra en un estado regular. El desempeño promedio (3.1/5) y la tasa de encuestas positivas (58%) están dentro de rangos aceptables, pero no óptimos...\n\nRecomendaciones:\n- Revisar la carga de trabajo del área de QA...\n- Fomentar espacios de feedback...",
  "metrics": {
    "averagePerformance": 3.1,
    "totalEvaluations": 5,
    "positiveSurveyRate": 58.0,
    "totalSurveyAnswers": 5,
    "totalReports": 3,
    "reportsByArea": [
      { "areaId": 1, "areaName": "Desarrollo de Software", "reportCount": 2 },
      { "areaId": 2, "areaName": "Control de Calidad", "reportCount": 1 }
    ],
    "totalForumMessages": 6,
    "forumActivityByArea": [
      { "areaId": 1, "areaName": "Desarrollo de Software", "threadCount": 1, "messageCount": 2 },
      { "areaId": 2, "areaName": "Control de Calidad", "threadCount": 1, "messageCount": 1 }
    ]
  }
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `status` | `status` | String | One of `"BUENO"`, `"REGULAR"`, `"CRITICO"` — computed in code from the metrics thresholds, not by the AI |
| `analysis` | `analysis` | String | AI-generated explanation + recommendations, always in Spanish |
| `metrics` | `metrics` | `Map<String, Object>` | Raw metrics used to build the analysis — **see naming warning below** |

> ⚠️ **`metrics` is a plain `Map<String, Object>` built in Java code, not a DTO.** Jackson's
> `@JsonNaming(SnakeCaseStrategy)` only rewrites **declared record/bean properties** — it does
> **not** touch runtime `Map` keys. So while the top-level response fields (`status`, `analysis`,
> `metrics`) are snake_case, **every key inside `metrics` — and inside the nested `reports_by_area`/
> `forum_activity_by_area` list items — stays camelCase exactly as written in
> `DashboardAssistantServiceImpl`**: `averagePerformance`, `totalEvaluations`, `positiveSurveyRate`,
> `totalSurveyAnswers`, `totalReports`, `reportsByArea` (list of `{areaId, areaName, reportCount}`),
> `totalForumMessages`, `forumActivityByArea` (list of `{areaId, areaName, threadCount, messageCount}`).
> Frontend clients must read `metrics.averagePerformance`, **not** `metrics.average_performance`.
> This mirrors the existing Stripe `metadata` quirk documented in
> [section 11.2](#112-stripe--online-payments).

**Errors**:
- `500 Internal Server Error`: `company_id` does not exist (`NoSuchElementException: Company not found: {id}`), or the AI model call fails (`"Error generating dashboard analysis: {detail}"`)

**Frontend integration example (curl)**:
```bash
curl -X POST http://localhost:8092/api/v1/dashboard-assistant \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"company_id":1,"question":"How is the team doing this quarter?"}'
```

---

## 5. Bounded Context: Feedback

> Package: `pe.edu.upc.soft.work.platform.feedback`
> All DTOs annotated with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`.
> **Auth**: all endpoints require `Authorization: Bearer <JWT>` (see section 2 for sign-in).
> **CORS**: all CRUD controllers allow `GET, POST, PUT, DELETE` from any origin.

---

### 5.1 Surveys — `/api/v1/surveys`

#### POST `/api/v1/surveys` — Create survey

**Request** (`CreateSurveyRequest`):
```json
{
  "title": "Workplace Climate Survey",
  "description": "Quarterly assessment",
  "target_type": "AREA_COMPANY",
  "expiration_time": "2026-12-31T23:59:59"
}
```

`target_type` valid values: `AREA_COMPANY`, `UNIT_OF_WORK`, `TEAM_OF_WORK`.

**Response 201** (`SurveyResponse` — note: this is the **Survey** response DTO, not to be confused with the SurveyResponse entity):
```json
{
  "survey_id": 1,
  "title": "Workplace Climate Survey",
  "description": "Quarterly assessment",
  "target_type": "AREA_COMPANY",
  "expiration_time": "2026-12-31T23:59:59"
}
```

#### PUT `/api/v1/surveys/{id}` — Update

**Request** (`UpdateSurveyRequest`): same structure as Create. **Response 200**: `SurveyResponse`.

#### GET `/api/v1/surveys` — List all

**Response 200**: `SurveyResponse[]`

#### GET `/api/v1/surveys/{id}` — Get by ID

**Response 200**: `SurveyResponse` | **404** if not found.

#### DELETE `/api/v1/surveys/{id}` — Delete

**Response 204**: no content.

---

### 5.2 Survey Responses — `/api/v1/survey-responses`

#### POST `/api/v1/survey-responses` — Create response

**Request** (`CreateSurveyResponseRequest`):
```json
{
  "survey_id": 1,
  "employee_profile_id": 3,
  "submitted_at": "2026-06-19T10:00:00",
  "commentary": "Good work environment",
  "cause": "Teamwork"
}
```

**Backend validations**: Survey must exist; Employee Profile must exist (checked via IAM ACL); Survey must not be expired; Employee must not have already responded to this survey.

**Response 201** (`SurveyResponseResponse` — the response DTO for the SurveyResponse aggregate):
```json
{
  "survey_response_id": 1,
  "survey_id": 1,
  "employee_profile_id": 3,
  "submitted_at": "2026-06-19T10:00:00",
  "commentary": "Good work environment",
  "cause": "Teamwork"
}
```

#### PUT `/api/v1/survey-responses/{id}` — Update

**Request** (`UpdateSurveyResponseRequest`): same structure as Create. **Response 200**: `SurveyResponseResponse`.

#### GET `/api/v1/survey-responses` — List

**Response 200**: `SurveyResponseResponse[]`

#### GET `/api/v1/survey-responses/{id}` — Get by ID

**Response 200**: `SurveyResponseResponse` | **404** if not found.

#### GET `/api/v1/survey-responses/survey/{surveyId}` — By survey

**Response 200**: `SurveyResponseResponse[]` (filtered client-side in the current implementation).

#### DELETE `/api/v1/survey-responses/{id}` — Delete

**Response 204**: no content.

---

### 5.3 Question Surveys — `/api/v1/question-surveys`

#### POST `/api/v1/question-surveys` — Create question

**Request** (`CreateQuestionSurveyRequest`):
```json
{
  "text_question": "How would you rate the work environment?",
  "question_type": "RATING",
  "survey_id": 1
}
```

`question_type` valid values: `OPEN_SURVEY`, `MULTIPLE_CHOICE`, `RATING`.

**Response 201** (`QuestionSurveyResponse`):
```json
{
  "question_survey_id": 1,
  "text_question": "How would you rate the work environment?",
  "question_type": "RATING",
  "survey_id": 1
}
```

#### PUT `/api/v1/question-surveys/{id}` — Update

**Request** (`UpdateQuestionSurveyRequest`): same structure as Create. **Response 200**: `QuestionSurveyResponse`.

#### GET `/api/v1/question-surveys` — List

**Response 200**: `QuestionSurveyResponse[]`

#### GET `/api/v1/question-surveys/{id}` — Get by ID

**Response 200**: `QuestionSurveyResponse` | **404** if not found.

#### DELETE `/api/v1/question-surveys/{id}` — Delete

**Response 204**: no content.

---

### 5.4 Answers — `/api/v1/answers` `@Deprecated`

> **DEPRECATED**: This endpoint is scheduled for removal. Use QuestionSurvey
> sub-resource operations instead.

#### POST `/api/v1/answers` — Create answer

**Request** (`CreateAnswerRequest`):
```json
{
  "value": 5,
  "score_answer": 10
}
```

**Response 201** (`AnswerResponse`):
```json
{
  "answer_id": 1,
  "value": 5,
  "score_answer": 10
}
```

#### PUT `/api/v1/answers/{id}` — Update

**Request** (`UpdateAnswerRequest`): same structure as Create. **Response 200**: `AnswerResponse`.

#### GET `/api/v1/answers` — List

**Response 200**: `AnswerResponse[]`

#### GET `/api/v1/answers/{id}` — Get by ID

**Response 200**: `AnswerResponse` | **404** if not found.

#### DELETE `/api/v1/answers/{id}` — Delete

**Response 204**: no content.

---

### 5.5 Employee Assistant (AI) — `/api/v1/feedback-assistant`

> Endpoint powered by Google Gemini (`gemini-2.5-flash`). See the full technical details
> of this integration in [section 11.3](#113-google-gemini--spring-ai-employee-assistant).
>
> **Naming note**: the AI assistant endpoints are split by profile — this one (implemented by
> `FeedbackAssistantController`, route unchanged at `/api/v1/feedback-assistant`) is the
> **employee-facing** assistant (survey/feedback help), while
> [`/api/v1/dashboard-assistant`](#47-dashboard-assistant-ai--apiv1dashboard-assistant) is the
> **RRHH-facing** one (dashboard/climate diagnosis). The Java class name and route were **not**
> renamed to `/api/v1/employee-assistant` — this document refers to it as "Employee Assistant" to
> reflect its actual usage, not its literal route.

#### POST `/api/v1/feedback-assistant` — Ask the assistant

**Auth**: requires `Authorization: Bearer <JWT>` (not in the public allowlist).
**CORS**: only `POST` is enabled (unlike other controllers which allow GET/POST/PUT/DELETE).

**Request** (`AskAssistantRequest`):
```json
{
  "survey_id": 1,
  "prompt": "Help me draft 3 questions about workplace climate"
}
```

| JSON Key | Java Field | Type | Validation | Notes |
|---|---|---|---|---|
| `survey_id` | `surveyId` | Long | Optional (nullable) | If provided, the assistant appends context from that survey (title + description) to the prompt |
| `prompt` | `prompt` | String | Must not be null/blank | Validated in the **record** constructor of `AskFeedbackAssistantCommand`, not via Jakarta annotations |

**Response 200** (`AssistantAnswerResponse`):
```json
{
  "content_answer": "Here are 3 suggested questions:\n1. ...\n2. ...\n3. ..."
}
```

| JSON Key | Java Field | Notes |
|---|---|---|
| `content_answer` | `contentAnswer` | text generated by Gemini, always in Spanish (per system prompt) |

**Errors**:
- `400 Bad Request` (`IllegalArgumentException` → `BadRequestResponse`): if `prompt` is null or blank → message `"Prompt must not be empty."`
- `500 Internal Server Error`: if the AI model call fails → message `"Error generating assistant response: {detail}"`

---

### 5.6 Connection Flow — Frontend Integration Guide

#### 5.6.1 Authentication Flow

Every Feedback endpoint requires a JWT token. The flow is:

```
1. POST /api/v1/authentication/sign-in  ──►  { "token": "eyJ..." }
         { "email": "...", "password": "..." }
         
2. Use the token in every subsequent request:
   Authorization: Bearer eyJ...
```

#### 5.6.2 Typical Business Flow

The Feedback context follows this logical order:

```
[RRHH / Admin]           [Employee]

1. POST /api/v1/surveys  ────────────────────── Creates a survey
2. POST /api/v1/question-surveys (x N) ──────── Adds questions to the survey
3.                                           │  Employee receives notification
4.                     POST /api/v1/survey-responses  ── Submits answers
5.                     POST /api/v1/answers (x N) ───── Individual answer scores
6. POST /api/v1/feedback-assistant  ─────────── AI analysis (optional)
```

#### 5.6.3 Example Connection (cURL)

```bash
BASE="http://localhost:8092"

# 1. Sign in (any valid user)
TOKEN=$(curl -s -X POST "$BASE/api/v1/authentication/sign-in" \
  -H "Content-Type: application/json" \
  -d '{"email":"carlos.ramirez@techcorp.pe","password":"password123"}' | jq -r '.token')

AUTH="Authorization: Bearer $TOKEN"

# 2. Create a survey
SURVEY_ID=$(curl -s -X POST "$BASE/api/v1/surveys" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{
    "title": "Q1 Climate Survey",
    "description": "Quarterly evaluation",
    "target_type": "AREA_COMPANY",
    "expiration_time": "2026-12-31T23:59:59"
  }' | jq -r '.survey_id')

# 3. Add a question to the survey
curl -s -X POST "$BASE/api/v1/question-surveys" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d "{
    \"text_question\": \"Rate your satisfaction\",
    \"question_type\": \"RATING\",
    \"survey_id\": $SURVEY_ID
  }"

# 4. List all surveys (employee side)
curl -s -X GET "$BASE/api/v1/surveys" -H "$AUTH"

# 5. Submit a survey response
curl -s -X POST "$BASE/api/v1/survey-responses" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d "{
    \"survey_id\": $SURVEY_ID,
    \"employee_profile_id\": 1,
    \"submitted_at\": \"2026-07-10T10:00:00\",
    \"commentary\": \"Great environment\",
    \"cause\": \"Teamwork\"
  }"
```

#### 5.6.4 Frontend Implementation Notes

| Aspect | Detail |
|---|---|
| **Base URL** | `http://localhost:8092` (dev) or `${API_HOST}` (prod) |
| **Naming** | Always use `snake_case` in JSON request bodies |
| **Dates** | Send ISO 8601: `"2026-07-10T10:00:00"` |
| **Error handling** | Parse the `field_errors` map for validation failures (see section 10) |
| **Survey creation** | Only RRHH/admin profiles should have access to create surveys |
| **Response submission** | One response per survey per employee (backend rejects duplicates) |
| **IDs** | Create endpoints return the new ID in the response body (e.g. `survey_id`, `answer_id`) |

---

## 6. Bounded Context: Payment Service

> Package: `pe.edu.upc.soft.work.platform.payment.service`
> All DTOs annotated with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`.

---

### 6.1 Memberships — `/api/v1/memberships`

#### POST `/api/v1/memberships` — Create membership

**Request** (`CreateMembershipRequest`):
```json
{
  "membership_plan_id": 1,
  "membership_start": "2026-01-01",
  "membership_over": "2026-12-31",
  "membership_status": "ACTIVE"
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|
| `membership_plan_id` | `membershipPlanId` | Long | @NotNull |
| `membership_start` | `membershipStart` | Date | @NotNull |
| `membership_over` | `membershipOver` | Date | @NotNull |
| `membership_status` | `membershipStatus` | String | @NotNull @NotBlank |

**Response 201** (`MembershipResponse`):
```json
{
  "membership_id": 1,
  "membership_plan_id": 1,
  "membership_start": "2026-01-01",
  "membership_over": "2026-12-31",
  "membership_status": "ACTIVE"
}
```

#### PUT `/api/v1/memberships/{id}` — Update

#### GET `/api/v1/memberships` — List

#### GET `/api/v1/memberships/{id}` — Get by ID

#### DELETE `/api/v1/memberships/{id}` — Delete

---

### 6.2 Orders — `/api/v1/orders`

#### POST `/api/v1/orders` — Create order

**Request** (`CreateOrderRequest`):
```json
{
  "user_account_id": 1,
  "amount": 99,
  "membership_id": 1
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|
| `user_account_id` | `userAccountId` | Long | @NotNull |
| `amount` | `amount` | Integer | — |
| `membership_id` | `membershipId` | Long | @NotNull |

> **Note**: `amount` has no `@NotNull` validation in the DTO.

**Business validations** (in `OrderCommandServiceImpl`):
1. `user_account_id` must exist → `404 NotFoundArgumentException`
2. `membership_id` must exist → `404 NotFoundArgumentException`
3. Membership status must be `ACTIVE` → `500 IllegalStateException`
4. Current date must be within the membership's date range → `500 IllegalStateException`

**Response 201** (`OrderResponse`):
```json
{
  "order_id": 1,
  "user_account_id": 1,
  "amount": 99,
  "membership_id": 1
}
```

#### PUT `/api/v1/orders/{id}` — Update

#### GET `/api/v1/orders` — List

#### GET `/api/v1/orders/{id}` — Get by ID

#### DELETE `/api/v1/orders/{id}` — Delete

#### GET `/api/v1/orders/userAccount/{userAccountId}` — By user account

**Response 200**: `OrderResponse[]`

---

### 6.3 Payments — `/api/v1/payments`

#### POST `/api/v1/payments` — Register payment (manual)

**Request** (`CreatePaymentRequest`):
```json
{
  "order_id": 1,
  "transaction_id": "TXN-2026-001",
  "payment_date": "2026-06-19",
  "payment_status": "PENDING",
  "payment_method": "STRIPE"
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|
| `order_id` | `orderId` | Long | @NotNull |
| `transaction_id` | `transactionId` | String | @NotNull @NotBlank |
| `payment_date` | `paymentDate` | Date | @NotNull |
| `payment_status` | `paymentStatus` | String | @NotNull @NotBlank |
| `payment_method` | `paymentMethod` | String | @NotNull @NotBlank |

**Response 201** (`PaymentResponse`):
```json
{
  "payment_id": 1,
  "order_id": 1,
  "transaction_id": "TXN-2026-001",
  "payment_status": "PENDING",
  "payment_date": "2026-06-19",
  "payment_method": "STRIPE"
}
```

#### PUT `/api/v1/payments/{id}` — Update

#### GET `/api/v1/payments` — List

#### GET `/api/v1/payments/{id}` — Get by ID

#### DELETE `/api/v1/payments/{id}` — Delete

> In addition to this manual CRUD, there are dedicated endpoints for the real Stripe
> payment flow under `/api/v1/payments/stripe/**`. See [section 11.2](#112-stripe--online-payments)
> for the full detail (checkout, retry, refund, webhook).

---

### 6.4 Membership Plans — `/api/v1/memberships-plans`

> **Note**: The route is `/api/v1/memberships-plans` (plural of both words), not `/api/v1/membership-plans`.

#### POST `/api/v1/membership-plans` — Create plan

**Request** (`CreateMembershipPlanRequest`):
```json
{
  "plan_name": "Professional",
  "price": 99
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|
| `plan_name` | `planName` | String | @NotBlank |
| `price` | `price` | Integer | — |

**Response 201** (`MembershipPlanResponse`):
```json
{
  "plan_id": 1,
  "plan_name": "Professional",
  "price": 99,
  "benefit_response_list": []
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `plan_id` | `planId` | Long | |
| `plan_name` | `planName` | String | |
| `price` | `price` | Integer | |
| `benefit_response_list` | `benefitResponseList` | BenefitResponse[] | Verbose field name (matches source code) |

#### PUT `/api/v1/membership-plans/{id}` — Update

#### GET `/api/v1/membership-plans` — List

#### GET `/api/v1/membership-plans/{id}` — Get by ID

#### DELETE `/api/v1/membership-plans/{id}` — Delete

#### POST `/api/v1/membership-plans/{membershipPlanId}/benefits` — Add benefit

**Request** (`AddBenefitToMembershipPlanRequest`):
```json
{
  "benefit_id": 1
}
```

---

### 6.5 Benefits — `/api/v1/benefits`

#### POST `/api/v1/benefits` — Create benefit

**Request** (`CreateBenefitRequest`):
```json
{
  "title": "Forum Access",
  "description": "Full access to the workers' forum",
  "membership_plan_id": 1
}
```

**Response 201** (`BenefitResponse`):
```json
{
  "benefit_id": 1,
  "title": "Forum Access",
  "description": "Full access to the workers' forum",
  "membership_plan_id": 1
}
```

#### PUT `/api/v1/benefits/{id}` — Update

#### GET `/api/v1/benefits` — List

#### GET `/api/v1/benefits/{id}` — Get by ID

#### DELETE `/api/v1/benefits/{id}` — Delete

---

## 7. Bounded Context: Notification

> Package: `pe.edu.upc.soft.work.platform.notification`
> All DTOs annotated with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`.

---

### 7.1 Notifications — `/api/v1/notifications`

#### POST `/api/v1/notifications` — Create notification

**Request** (`CreateNotificationRequest`):
```json
{
  "seen": false,
  "notification_type": "ALERT",
  "user_account_id": 1
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|
| `seen` | `seen` | boolean | — |
| `notification_type` | `notificationType` | String (Enum) | @NotNull @NotBlank |
| `user_account_id` | `userAccountId` | Long | @NotNull |

**Response 201** (`NotificationResponse`):
```json
{
  "notification_id": 1,
  "seen": false,
  "notification_type": "ALERT",
  "user_account_id": 1
}
```

#### PUT `/api/v1/notifications/{id}` — Update

**Request** (`UpdateNotificationRequest`): Same structure as Create.

#### GET `/api/v1/notifications` — List

#### GET `/api/v1/notifications/{id}` — Get by ID

#### DELETE `/api/v1/notifications/{id}` — Delete

---

### 7.2 Notification Details — `/api/v1/notification-details`

#### POST `/api/v1/notification-details` — Create detail

**Request** (`CreateNotificationDetailRequest`):
```json
{
  "title": "New message",
  "content": "You have a new message in the forum",
  "notification_id": 1
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|
| `title` | `title` | String | @NotNull @NotBlank |
| `content` | `content` | String | @NotNull @NotBlank |
| `notification_id` | `notificationId` | Long | @NotNull |

**Response 201** (`NotificationDetailResponse`):
```json
{
  "notification_detail_id": 1,
  "title": "New message",
  "content": "You have a new message in the forum",
  "notification_id": 1
}
```

#### PUT `/api/v1/notification-details/{id}` — Update

**Request** (`UpdateNotificationDetailRequest`): Same structure as Create.

#### GET `/api/v1/notification-details` — List

#### GET `/api/v1/notification-details/{id}` — Get by ID

#### DELETE `/api/v1/notification-details/{id}` — Delete

---

## 8. Bounded Context: Worker Forum

> Package: `pe.edu.upc.soft.work.platform.worker.forum`
> All DTOs annotated with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`.

---

### 8.1 Forums — `/api/v1/forums`

#### POST `/api/v1/forums` — Create forum

**Request** (`CreateForumRequest`):
```json
{
  "title": "General Forum",
  "description": "Open discussion space",
  "company_id": 1
}
```

**Response 201** (`ForumResponse`):
```json
{
  "forum_id": 1,
  "title": "General Forum",
  "description": "Open discussion space",
  "company_id": 1,
  "categories": []
}
```

#### PUT `/api/v1/forums/{id}` — Update

#### GET `/api/v1/forums` — List

#### GET `/api/v1/forums/{id}` — Get by ID

#### GET `/api/v1/forums/company/{companyId}` — By company

**Response 200**: `ForumResponse[]`

#### DELETE `/api/v1/forums/{id}` — Delete

#### POST `/api/v1/forums/{forumId}/categories` — Add category

**Request** (`AddCategoryToForumRequest`):
```json
{
  "category_id": 1
}
```

---

### 8.2 Categories — `/api/v1/categories`

#### POST `/api/v1/categories` — Create category

**Request** (`CreateCategoryRequest`):
```json
{
  "title": "Announcements",
  "description": "Category for official announcements",
  "forum_id": 1
}
```

**Response 201** (`CategoryResponse`):
```json
{
  "category_id": 1,
  "title": "Announcements",
  "description": "Category for official announcements",
  "forum_id": 1,
  "threads": []
}
```

#### PUT `/api/v1/categories/{id}` — Update

#### GET `/api/v1/categories` — List

#### GET `/api/v1/categories/{id}` — Get by ID

#### DELETE `/api/v1/categories/{id}` — Delete

#### POST `/api/v1/categories/{categoryId}/threads` — Add thread

**Request** (`AddThreadToCategoryRequest`):
```json
{
  "thread_id": 1
}
```

---

### 8.3 Threads — `/api/v1/threads`

#### POST `/api/v1/threads` — Create thread

**Request** (`CreateThreadRequest`):
```json
{
  "title": "Sprint discussion",
  "area_company_id": 1,
  "last_message": "2026-06-19",
  "category_id": 1,
  "message_count": 0
}
```

**Response 201** (`ThreadResponse`):
```json
{
  "thread_id": 1,
  "title": "Sprint discussion",
  "area_company_id": 1,
  "last_message": "2026-06-19",
  "category_id": 1,
  "message_count": 0,
  "message_responses": []
}
```

#### PUT `/api/v1/threads/{id}` — Update

#### GET `/api/v1/threads` — List

#### GET `/api/v1/threads/{id}` — Get by ID

#### DELETE `/api/v1/threads/{id}` — Delete

#### POST `/api/v1/threads/{threadId}/messages` — Add message

**Request** (`AddMessageToThreadRequest`):
```json
{
  "message_id": 1
}
```

---

### 8.4 Messages — `/api/v1/messages`

#### POST `/api/v1/messages` — Create message

**Request** (`CreateMessageRequest`):
```json
{
  "user_account_id": 1,
  "content_message": "Hi team, we have a meeting tomorrow",
  "thread_id": 1
}
```

**Response 201** (`MessageResponse`):
```json
{
  "message_id": 1,
  "user_account_id": 1,
  "content_message": "Hi team, we have a meeting tomorrow",
  "thread_id": 1,
  "attachments": []
}
```

#### PUT `/api/v1/messages/{id}` — Update

#### GET `/api/v1/messages` — List

#### GET `/api/v1/messages/{id}` — Get by ID

#### DELETE `/api/v1/messages/{id}` — Delete

#### POST `/api/v1/messages/{messageId}/assets` — Add asset

**Request** (`AddAssetToMessageRequest`):
```json
{
  "asset_id": 1
}
```

---

### 8.5 Assets — `/api/v1/assets`

> **Note**: Unlike the other CRUD endpoints in the backend, Asset creation does **not** use
> JSON — it uses `multipart/form-data` because the actual file is uploaded to **Cloudinary**.
> See the technical detail of the upload flow in [section 11.1](#111-cloudinary--file-storage).

#### POST `/api/v1/assets` — Upload file and create asset

**Content-Type**: `multipart/form-data` (`consumes = MULTIPART_FORM_DATA_VALUE`)

**Request** — Form fields (`@RequestParam`, NOT a JSON body):

| Parameter (form field) | Type | Required | Description |
|---|---|---|---|
| `messageId` | Long | Yes | ID of the message this asset is attached to |
| `name` | String | Yes | File name |
| `fileType` | Enum `FileType` | Yes | One of: `VIDEO`, `JPEG`, `PDF` |
| `file` | Binary (MultipartFile) | Yes | Binary content of the file |

> **Note**: form-field (`@RequestParam`) names are **not** affected by Jackson's `@JsonNaming`
> (that strategy only applies to JSON bodies). The multipart field names remain `messageId`,
> `name`, `fileType`, `file` exactly as declared in the controller.

**Example (curl)**:
```bash
curl -X POST http://localhost:8092/api/v1/assets \
  -H "Authorization: Bearer $TOKEN" \
  -F "messageId=123" \
  -F "name=document.pdf" \
  -F "fileType=PDF" \
  -F "file=@/local/path/document.pdf"
```

> **IMPORTANT**: `url` and `fileSize` are **not** sent in the request — the backend computes them
> automatically after uploading the file to Cloudinary (public URL returned by Cloudinary, file
> size detected from the binary).

**Response 201** (`AssetResponse`) — JSON body, so snake_case applies:
```json
{
  "asset_id": 1,
  "message_id": 123,
  "name": "document.pdf",
  "url": "https://res.cloudinary.com/<cloud_name>/image/upload/v.../workersforum/pdfs/<public_id>.pdf",
  "file_size": "2.5MB",
  "file_type": "PDF"
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `asset_id` | `assetId` | Long | |
| `message_id` | `messageId` | Long | |
| `name` | `name` | String | |
| `url` | `url` | String | Public Cloudinary URL, generated by the backend |
| `file_size` | `fileSize` | String | generated by the backend |
| `file_type` | `fileType` | String (Enum) | `VIDEO` \| `JPEG` \| `PDF` |

**Errors**:
- `400 Bad Request`: invalid `fileType` or missing file
- `404 Not Found`: `messageId` does not exist
- `500 Internal Server Error` (Cloudinary): `"[CloudinaryStorageServiceImpl] Error al subir el archivo a Cloudinary: {detail}"`. If Asset creation fails **after** a successful Cloudinary upload, the backend deletes the just-uploaded file to avoid orphaned files (`storageService.delete(url)`).

#### PUT `/api/v1/assets/{id}` — Update metadata (JSON, not multipart)

**Request** (`UpdateAssetRequest`):
```json
{
  "message_id": 124,
  "name": "renamed-document.pdf",
  "url": "https://res.cloudinary.com/.../document.pdf",
  "file_size": "2.5MB"
}
```

> Note: Update **is** a normal JSON request (so snake_case applies) and does **not** allow changing
> `file_type` (the file type is immutable after creation).

#### GET `/api/v1/assets` — List all

#### GET `/api/v1/assets/{id}` — Get by ID

#### DELETE `/api/v1/assets/{id}` — Delete

> When deleting the Asset record, note that the physical file in Cloudinary may require separate
> cleanup depending on the `AssetCommandService` implementation.

---

### 8.6 Reports — `/api/v1/reports`

#### POST `/api/v1/reports` — Create report

**Request** (`CreateReportRequest`):
```json
{
  "reason": "Inappropriate content",
  "description": "The message contains offensive language",
  "user_account_id": 1,
  "report_date": "2026-06-19",
  "area_company_id": 1
}
```

**Response 201** (`ReportResponse`):
```json
{
  "report_id": 1,
  "reason": "Inappropriate content",
  "description": "The message contains offensive language",
  "user_account_id": 1,
  "report_date": "2026-06-19",
  "area_company_id": 1
}
```

#### PUT `/api/v1/reports/{id}` — Update

#### GET `/api/v1/reports` — List

#### GET `/api/v1/reports/{id}` — Get by ID

#### DELETE `/api/v1/reports/{id}` — Delete

---

### 8.7 Employee Assistant (Worker Forum) — `/api/v1/employee-assistant`

> Endpoint powered by Google Gemini (`gemini-2.5-flash`) through the
> `@Qualifier("employeeAssistantChatClient")` bean with an employee-facing HR persona.
> This is a **separate** AI assistant from the Feedback Assistant (`/api/v1/feedback-assistant`);
> it answers general company questions (policies, benefits, forum, surveys, performance)
> rather than focusing on survey drafting.

**Auth**: requires `Authorization: Bearer <JWT>`.
**CORS**: `POST` only.

#### POST `/api/v1/employee-assistant` — Ask the assistant

**Request** (`AskEmployeeAssistantRequest`):
```json
{
  "company_id": 1,
  "prompt": "What are the company policies on remote work?"
}
```

| JSON Key | Java Field | Type | Notes |
|---|---|---|---|
| `company_id` | `companyId` | Long | Optional (nullable). If provided, the assistant prepends company context |
| `prompt` | `prompt` | String | The employee's question or concern |

**What happens server-side**: If `company_id` is present, the service fetches the company name
via the Dashboard ACL and prepends context. The full prompt is sent to the AI.

**Response 200** (`AssistantAnswerResponse`):
```json
{
  "content_answer": "Our company supports remote work on Mondays and Fridays..."
}
```

**Errors**:
- `500 Internal Server Error`: AI model call fails → `"Error generating assistant response: {detail}"`

---

## 9. Bounded Context: Profile Performance

> Package: `pe.edu.upc.soft.work.platform.profile.performance`
> All DTOs annotated with `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`.

---

### 9.1 Performances — `/api/v1/performances`

#### POST `/api/v1/performances` — Create performance evaluation

**Request** (`CreatePerformanceRequest`):
```json
{
  "employee_profile_id": 1,
  "date_time": "2026-06-19",
  "classification": 5
}
```

**Response 201** (`PerformanceResponse`):
```json
{
  "performance_id": 1,
  "employee_profile_id": 1,
  "date_time": "2026-06-19",
  "classification": 5,
  "comment_employees": []
}
```

#### PUT `/api/v1/performances/{id}` — Update

#### GET `/api/v1/performances` — List

#### GET `/api/v1/performances/{id}` — Get by ID

#### GET `/api/v1/performances/employee/{employeeId}` — By employee profile

**Response 200**: `PerformanceResponse[]`

#### DELETE `/api/v1/performances/{id}` — Delete

#### POST `/api/v1/performances/{performanceId}/comment-employee` — Add comment

**Request** (`AddCommentEmployeeToPerformanceRequest`):
```json
{
  "comment_id": 1
}
```

---

### 9.2 Comment Employees — `/api/v1/commentemployees`

> **Note**: The route is `/api/v1/commentemployees` (no hyphens between words), not `/api/v1/comment-employees`.

#### POST `/api/v1/comment-employees` — Create comment

**Request** (`CreateCommentEmployeeRequest`):
```json
{
  "title": "Good performance",
  "content": "The employee has exceeded expectations this quarter",
  "rrhh_profile_id": 1,
  "performance_id": 1
}
```

**Response 201** (`CommentEmployeeResponse`):
```json
{
  "comment_employee_id": 1,
  "title": "Good performance",
  "content": "The employee has exceeded expectations this quarter",
  "rrhh_profile_id": 1,
  "performance_id": 1
}
```

#### PUT `/api/v1/comment-employees/{id}` — Update

#### GET `/api/v1/comment-employees` — List

#### GET `/api/v1/comment-employees/{id}` — Get by ID

#### DELETE `/api/v1/comment-employees/{id}` — Delete

---

## 10. Error Handling

### Standardized Error Responses

The `GlobalExceptionHandler` (`@RestControllerAdvice`) catches exceptions and returns snake_case
JSON. The error response records (`BadRequestResponse`, `NotFoundResponse`,
`InternalServerErrorResponse`, `ServiceUnavailableResponse`) are annotated with
`@JsonNaming(SnakeCaseStrategy)`, so envelope fields serialize as snake_case (`field_errors`).

> **UPDATE (2026-07-03)**: The dynamic keys inside `field_errors` are also snake_cased now. The
> handler translates each `MethodArgumentNotValidException` field name via Jackson's actual
> `PropertyNamingStrategies.SNAKE_CASE` before putting it in the map, so error keys match the DTO
> field names exactly (e.g. a validation failure on `annualBudget` reports `annual_budget`).

#### 400 Bad Request — Field validation (`BadRequestResponse`)

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "JSON validation failed",
  "field_errors": {
    "name": "must not be blank",
    "dni": "must not be blank"
  }
}
```

> Triggered by `MethodArgumentNotValidException` (failure of `@NotNull`/`@NotBlank` on the DTO).
> The keys inside `field_errors` are the snake_case field names (e.g. `last_name`, `user_account_id`).

#### 400 Bad Request — Internal business validation (`BadRequestResponse`)

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Internal validation failed",
  "field_errors": {
    "argument": "Prompt must not be empty."
  }
}
```

> Triggered by `IllegalArgumentException` (e.g. empty prompt in the Feedback Assistant). The
> `argument` key is a fixed literal (already lowercase, unaffected by the strategy).

#### 404 Not Found — Resource does not exist (`NotFoundResponse`)

```json
{
  "status": 404,
  "error": "NOT_FOUND",
  "message": "User Account not found"
}
```

> Triggered by `NotFoundIdException` or `NotFoundArgumentException`.

#### 500 Internal Server Error (`InternalServerErrorResponse`)

```json
{
  "status": 500,
  "error": "Internal Server Error",
  "message": "User with DNI 12345678 already exists."
}
```

> Triggered by `NullPointerException` or an uncaught `RuntimeException` (duplicate DNI,
> Stripe/Cloudinary/Gemini errors wrapped as `RuntimeException`, etc).

#### 503 Service Unavailable — DB down (`ServiceUnavailableResponse`)

```json
{
  "status": 503,
  "error": "Service Unavailable",
  "message": "could not execute statement..."
}
```

> Triggered by `PersistenceException` (JPA/Hibernate).

### Exception Table

| Java Exception | HTTP Status | When it Occurs |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | @NotNull/@NotBlank fails on the DTO |
| `IllegalArgumentException` | 400 | Internal business validation (e.g. empty AI assistant prompt) |
| `NotFoundIdException` | 404 | `findById` does not find the entity |
| `NotFoundArgumentException` | 404 | An argument references a non-existent entity (e.g. Order → UserAccount) |
| `PersistenceException` | 503 | Database error |
| `NullPointerException` | 500 | Unexpected internal error |
| `RuntimeException` (generic) | 500 | Duplicate DNI, Cloudinary/Stripe/Gemini failures, uncaught errors |
| `IllegalStateException` | 500 | Inactive/out-of-range membership, Payment in invalid state for retry/refund |
| `StripeException` (wrapped) | 500 | Stripe API error (wrapped as `RuntimeException`) |
| `SignatureVerificationException` (Stripe) | 400 | Invalid webhook signature — **handled directly in the controller**, does not go through `GlobalExceptionHandler` |
| `IOException` (Cloudinary, wrapped) | 500 | Failure uploading/deleting a file in Cloudinary |

---

## 11. External Services: Cloudinary, Stripe, Google Gemini

> This section documents the external service integrations added to the backend (visible
> in `application-prod.properties`). These integrations **do not replace** the traditional CRUD
> documented in the previous sections — they complement it with real flows for file uploads,
> payment processing, and AI assistance.

### New Dependencies (pom.xml)

| Library | Artifact | Version | Purpose |
|---|---|---|---|
| Cloudinary | `com.cloudinary:cloudinary-http5` | `2.0.0` | HTTP client for uploading/deleting files |
| Cloudinary | `com.cloudinary:cloudinary-taglib` | `2.0.0` | Additional Cloudinary utilities |
| Stripe | `com.stripe:stripe-java` | `26.3.0` | Official Stripe SDK for Java |
| Spring AI | `org.springframework.ai:spring-ai-starter-model-google-genai` | BOM `2.0.0` | Integration with Gemini models via Spring AI |
| Google API Client | `com.google.api-client:google-api-client` | `2.9.0` | Verify Google Sign-In `id_token` (`GoogleIdTokenVerifier`) |

---

### 11.1 Cloudinary — File Storage

**Configuration** (`CloudinaryConfig.java`):
```java
@Configuration
public class CloudinaryConfig {
  @Value("${CLOUDINARY_URL}")
  private String cloudinaryUrl;

  @Bean
  public Cloudinary cloudinary() {
    return new Cloudinary(cloudinaryUrl);
  }
}
```

- **Environment variable**: `CLOUDINARY_URL` (format `cloudinary://<api_key>:<api_secret>@<cloud_name>`)
- **Upload limits** (`application-prod.properties`):
  ```properties
  spring.servlet.multipart.max-file-size=100MB
  spring.servlet.multipart.max-request-size=100MB
  ```
  Files exceeding these limits receive a `413 Payload Too Large` at the Spring level, before ever
  reaching the controller.

**Folder organization in Cloudinary** (based on `FileType`):

| `FileType` | Cloudinary Folder |
|---|---|
| `VIDEO` | `workersforum/videos` |
| `JPEG` | `workersforum/images` |
| `PDF` | `workersforum/pdfs` |

**Endpoint used**: `POST /api/v1/assets` — see full detail in [section 8.5](#85-assets--apiv1assets).

**Cloudinary-specific error handling**:
```java
// Upload failure
catch (IOException e) {
    throw new RuntimeException(
        "[CloudinaryStorageServiceImpl] Error al subir el archivo a Cloudinary: " + e.getMessage(), e);
}

// Delete failure
catch (IOException e) {
    throw new RuntimeException(
        "[CloudinaryStorageServiceImpl] Error al eliminar el archivo de Cloudinary: " + e.getMessage(), e);
}
```

If Asset creation in the database fails **after** a successful upload to Cloudinary, the backend
automatically runs `storageService.delete(url)` to avoid leaving orphaned files in Cloudinary.

---

### 11.2 Stripe — Online Payments

**Configuration** (`StripeProperties.java`):
```java
@Component
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {
    private String secretKey;
    private String webhookSecret;
}
```

- `stripe.secret-key` ← `STRIPE_SECRET_KEY` (`sk_...`)
- `stripe.webhook-secret` ← `STRIPE_WEBHOOK_SECRET` (`whsec_...`)
- The API key is initialized via `@PostConstruct` inside `StripePaymentGatewayAdapter`:
  ```java
  @PostConstruct
  public void initStripe() {
      Stripe.apiKey = stripeProperties.getSecretKey();
  }
  ```

**Controller**: `StripePaymentController`
**Base route**: `/api/v1/payments/stripe`
**CORS**: only `GET, POST` enabled (`@CrossOrigin(methods = {RequestMethod.POST, RequestMethod.GET})`)

---

#### POST `/api/v1/payments/stripe/checkout` — Create PaymentIntent

Creates a Stripe `PaymentIntent` for an existing `Order` and returns the `client_secret` that the
frontend uses with Stripe.js/Stripe Elements to confirm the payment.

**Request** (`CreateStripeCheckoutRequest`):
```json
{
  "order_id": 42,
  "currency": "usd"
}
```

| JSON Key | Java Field | Type | Validation | Notes |
|---|---|---|---|---|
| `order_id` | `orderId` | Long | @NotNull | The Order must exist |
| `currency` | `currency` | String | Optional | ISO 4217 code, lowercased before sending to Stripe; if null/blank, the adapter defaults to `"usd"` |

**Response 200** (`StripeCheckoutResponse`):
```json
{
  "client_secret": "pi_3ABC123_secret_XYZ789"
}
```

**Internal details of the Stripe PaymentIntent created**:
- `amount`: `order.getAmount() * 100` (Stripe uses the smallest currency unit, e.g. cents)
- `currency`: lowercased, defaults to `"usd"`
- `automaticPaymentMethods`: enabled
- `metadata`: `{ "orderId": <id>, "membershipId": <id> }` — Stripe metadata keys are set literally in
  Java code and are **not** transformed by `@JsonNaming` (they are not DTO fields)

**Errors**:
- `400 Bad Request`: Order not found or invalid request
- `500 Internal Server Error`: `"[StripePaymentGatewayAdapter] Failed to create Stripe PaymentIntent: {detail}"` (wraps `StripeException`)

---

#### POST `/api/v1/payments/stripe/{paymentId}/retry` — Retry failed payment

Creates a **new** `PaymentIntent` to retry a payment that previously failed.

**Path param**: `paymentId` (Long)

**Request** (`RetryPaymentRequest`):
```json
{
  "order_id": 42,
  "currency": "usd"
}
```

| JSON Key | Java Field | Type | Validation |
|---|---|---|---|
| `order_id` | `orderId` | Long | @NotNull |
| `currency` | `currency` | String | @NotNull (unlike checkout, here it is mandatory) |

**Business validation**: the `Payment` referenced by `paymentId` must exist and be in **`FAILED`**
status (`payment.isFailed()`). Otherwise it is rejected with:
```
Cannot retry Payment in status: {status}. Only FAILED payments can be retried.
```

**Response 200** (`PaymentRetryResponse`):
```json
{
  "payment_id": 5,
  "client_secret": "pi_3XYZ456_secret_ABC123",
  "new_transaction_id": "pi_3XYZ456"
}
```

| JSON Key | Java Field | Notes |
|---|---|---|
| `payment_id` | `paymentId` | The same original Payment (ID does not change) |
| `client_secret` | `clientSecret` | New Stripe secret to confirm the payment from the frontend |
| `new_transaction_id` | `newTransactionId` | New Stripe PaymentIntent ID — replaces the previous `transaction_id` on the Payment |

**Side effects**: the original `Payment` is updated with the new `transactionId` and its status is
reset to `PENDING`. A `PaymentRetryInitiatedEvent` is published.

**Errors**:
- `400 Bad Request`: invalid request, Payment not found, or Payment is not in FAILED state
- `500 Internal Server Error`: Stripe API error

---

#### POST `/api/v1/payments/stripe/{paymentId}/refund` — Initiate refund

**Path param**: `paymentId` (Long)

**Request** (`InitiateRefundRequest`):
```json
{
  "order_id": 42,
  "reason": "requested_by_customer",
  "refund_amount_cents": 5000
}
```

| JSON Key | Java Field | Type | Validation | Notes |
|---|---|---|---|---|
| `order_id` | `orderId` | Long | @NotNull | |
| `reason` | `reason` | String | @NotNull | Must map to a value of Stripe's `RefundCreateParams.Reason` enum (e.g. `requested_by_customer`, `duplicate`, `fraudulent`) |
| `refund_amount_cents` | `refundAmountCents` | Integer | @NotNull | **typo fixed**: the JSON key was previously `refoundAmountCents` (extra "o") via an `@JsonProperty` — now correctly `refund_amount_cents`. Amount in cents; if omitted, the refund logic falls back to a full refund |

> **FIXED**: previously the JSON key was literally `refoundAmountCents` (typo of "refund" → "refound").
> The offending `@JsonProperty` has been removed, so the field now serializes/deserializes as
> `refund_amount_cents`. **Clients that were sending `refoundAmountCents` must switch to
> `refund_amount_cents`.**

**Business validation**: the `Payment` must exist and be in **`SUCCEEDED`** status
(`payment.isSucceeded()`). Otherwise it is rejected with:
```
Cannot refund Payment in status: {status}
```

**Response 200** (`RefundResponse`):
```json
{
  "refund_id": "re_1ABC23DEfghijk",
  "payment_intent_id": "pi_3ABC123",
  "refunded_amount_cents": 5000,
  "status": "succeeded"
}
```

| JSON Key | Java Field |
|---|---|
| `refund_id` | `refundId` |
| `payment_intent_id` | `paymentIntentId` |
| `refunded_amount_cents` | `refundedAmountCents` |
| `status` | `status` |

**Internal details of the Stripe Refund created**:
- `paymentIntent`: taken from `payment.getTransactionId()` (the original PaymentIntent)
- `amount`: optional — if `refund_amount_cents` is provided, it's a partial refund
- `reason`: converted from the received String to the `RefundCreateParams.Reason` enum
- `metadata`: `{ "orderId": <id>, "paymentId": <id> }`

**Side effects**: a `RefundInitiatedEvent` is published for asynchronous processing.

**Errors**:
- `400 Bad Request`: invalid request or Payment not found
- `422 Unprocessable Entity`: Payment is not in SUCCEEDED status (cannot be refunded)
- `500 Internal Server Error`: Stripe API error

---

#### POST `/api/v1/payments/stripe/webhook` — Stripe webhook

**No JWT authentication** — this endpoint is invoked directly by Stripe's servers, not by the
frontend. Security is guaranteed by validating the **cryptographic signature** of the payload.

**Content-Type**: `application/json`

**Required headers**:

| Header | Description |
|---|---|
| `Stripe-Signature` | HMAC signature generated by Stripe using `STRIPE_WEBHOOK_SECRET` |

**Request Body**: raw JSON exactly as sent by Stripe (the controller receives it as a `String
payload`, not a typed DTO, so it can validate the signature against the exact string). Because it is
not a DTO, `@JsonNaming` does not apply — the payload keys are whatever Stripe sends.

**Signature verification**:
```java
try {
    event = Webhook.constructEvent(payload, stripeSignature, stripeProperties.getWebhookSecret());
} catch (SignatureVerificationException e) {
    return ResponseEntity.badRequest().body("Invalid Stripe signature");
}
```

**Event types handled**:

| Stripe Event | Backend Action |
|---|---|
| `payment_intent.succeeded` | Publishes `StripePaymentSucceededEvent(source, stripePaymentIntentId, orderId, amountReceived)` — `orderId` is read from the PaymentIntent's `metadata.orderId` |
| `payment_intent.payment_failed` | Publishes `StripePaymentFailedEvent(source, stripePaymentIntentId, orderId, failureReason)` — `failureReason` comes from `lastPaymentError.message`, or `"Unknown reason"` if not present |
| `charge.refunded` | Publishes `RefundCompletedEvent(source, refundId, paymentId, orderId, amountCents, status)` — only if `metadata.orderId` and `metadata.paymentId` are present on the Refund |

**Response 200 OK**:
```
Webhook processed
```

**Response 400 Bad Request** (invalid signature):
```
Invalid Stripe signature
```

**Response 422 Unprocessable Entity** (unsupported event type):
```
Unhandled event type: {event.type}
```

**Response 500 Internal Server Error**:
```
Error processing webhook: {detail}
```

> **Technical note**: this endpoint is annotated with `@Transactional`. To test it locally, it is
> recommended to use the `stripe CLI` (`stripe listen --forward-to
> localhost:8092/api/v1/payments/stripe/webhook`) to forward test events with a valid signature.

---

### 11.3 Google Gemini / Spring AI — Employee Assistant

**Configuration** (`ChatConfig.java`):
```java
@Configuration
public class ChatConfig {
  @Bean
  public ChatClient chatClient(ChatModel chatModel){
    return ChatClient.builder(chatModel)
      .defaultSystem("""
        Eres un asistente especializado en encuestas de clima laboral (Dentro del Feedback BC del proyecto).
        Ayudas a redactar preguntas, resumir resultados y sugerir mejoras de encuestas.
        Responde siempre en espanol y de forma breve.
      """)
      .build();
  }
}
```

**Environment variables / properties**:
```properties
spring.ai.google.genai.api-key=${GOOGLE_GENAI_API_KEY:***}
spring.ai.google.chat.model=gemini-2.5-flash
spring.ai.google.genai.chat.temperature=0.4
```

- **Model**: `gemini-2.5-flash`
- **Temperature**: `0.4` (more deterministic/focused answers, less creative)
- **Fixed system prompt**: in Spanish, specialized in workplace climate surveys (context of the
  Feedback bounded context). This means the AI assistant will always respond in Spanish regardless
  of the language of the incoming `prompt`.

**Exposed endpoint**: `POST /api/v1/feedback-assistant` — documented in detail in
[section 5.5](#55-employee-assistant-ai--apiv1feedback-assistant).

> **`employeeAssistantChatClient` is used by the Worker Forum's `EmployeeAssistantController`.**
> `ChatConfig.java` defines a second, more elaborate persona bean (`@Qualifier("employeeAssistantChatClient")`)
> with a broader system prompt (policies, benefits, forum, surveys, performance — not just survey drafting).
> Unlike `FeedbackAssistantController` (which uses the plain `chatClient`), the Worker Forum's
> `EmployeeAssistantController` routes through this bean. See [section 8.7](#87-employee-assistant-worker-forum--apiv1employee-assistant).

**Internal response generation flow** (`FeedbackAssistantServiceImpl`):
1. If `survey_id` is present in the request, the survey is looked up (`GetSurveyByIdQuery`) and a
   context string is prepended to the prompt with the format: `"Encuesta: {title} - {description}. "`
2. The context (if any) is concatenated with the user's `prompt`.
3. It is sent to the `ChatClient` (Gemini) via `chatClient.prompt().user(...).call().content()`.
4. The response is wrapped in `AssistantAnswer` and returned.

```java
public AssistantAnswer handle(AskFeedbackAssistantCommand command) {
    var contextBuilder = new StringBuilder();
    if (command.surveyId() != null){
        surveyQueryService.handle(new GetSurveyByIdQuery(command.surveyId()))
            .ifPresent(survey -> contextBuilder
                .append("Encuesta: ").append(survey.getTitle())
                .append(" - ").append(survey.getDescription()).append(". "));
    }
    try {
        var response = chatClient.prompt()
            .user(u -> u.text(contextBuilder + command.prompt()))
            .call()
            .content();
        return new AssistantAnswer(response);
    } catch (Exception e) {
        throw new RuntimeException("Error generating assistant response: " + e.getMessage(), e);
    }
}
```

> If the provided `survey_id` does not exist, the call does **not** fail: no context is added and
> the user's prompt is simply sent alone (silent behavior, no 404 error).

---

### 11.4 Controllers Added

| Controller | Base Route | Bounded Context | Purpose |
|---|---|---|---|---|
| `StripePaymentController` | `/api/v1/payments/stripe` | Payment Service | Stripe checkout, retry, refund, webhook |
| `EmployeeAssistantController` | `/api/v1/employee-assistant` | Worker Forum | AI assistant (Gemini) for **employees** — general company questions |
| `FeedbackAssistantController` | `/api/v1/feedback-assistant` | Feedback | AI assistant (Gemini) for **employees** — surveys/feedback |
| `DashboardAssistantController` | `/api/v1/dashboard-assistant` | Dashboard | AI assistant (Gemini) for **RRHH** — dashboard/climate diagnosis |

The `AssetController` (`/api/v1/assets`) already existed, but its `POST` endpoint changed from a
JSON DTO to `multipart/form-data` to integrate with Cloudinary. The `AuthenticationController`
(`/api/v1/authentication`) also gained three Google endpoints — see [section 11.5](#115-google-identity--sign-in-with-google-id_token-verification).

---

### 11.5 Google Identity — Sign in with Google (id_token verification)

Backend verification of Google Identity Services **ID tokens** for the IAM authentication flow.

**Dependency**: `com.google.api-client:google-api-client:2.9.0`.

**Configuration** (`application-*.properties`):
```properties
authorization.google.client-id=${GOOGLE_CLIENT_ID:WriteHereYourGoogleOAuthClientId}   # dev has a placeholder default
authorization.google.client-id=${GOOGLE_CLIENT_ID}                                     # prod (mandatory)
```

**Verification** (`GoogleTokenServiceImpl`, `iam.infrastructure.google.services`): builds a
`GoogleIdTokenVerifier` with `NetHttpTransport` + `GsonFactory`, restricted to the configured client
id as the expected audience:
```java
new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
    .setAudience(Collections.singletonList(clientId))
    .build();
```
`verifier.verify(idToken)` checks the token signature (against Google's public certificates),
expiration and audience; the adapter additionally requires `email_verified == true`. On failure it
throws `IllegalArgumentException` → `400 Bad Request`. The verified claims (`sub`, `email`,
`given_name`, `family_name`) are exposed to the application layer as a `GoogleUserInfo`.

**Exposed endpoints** (documented in [section 2](#2-authentication-iam)):

| Route | Phase | Persists |
|---|---|---|
| `POST /api/v1/authentication/google` | 1 — validate + check existence | **nothing** |
| `POST /api/v1/authentication/sign-up/employee/google` | 2 — employee completion | User + UserAccount + EmployeeProfile |
| `POST /api/v1/authentication/sign-up/rrhh/google` | 2 — RRHH completion | User + UserAccount + RRHHProfile |

> **No local account is created on `/google`.** The `User` + `UserAccount` are created only when the
> user completes one of the Google sign-up endpoints, with real data and a random encoded password
> (Google is the only login path for those accounts; `membership_id`/`company_id` default to `0`,
> matching the classic sign-up).

---

### 11.6 Google Gemini / Spring AI — Dashboard Assistant

**Configuration** (`ChatConfig.java`, `dashboardAssistantChatClient` bean):
```java
@Bean
@Qualifier("dashboardAssistantChatClient")
public ChatClient dashboardAssistantChatClient(ChatModel chatModel){
  return ChatClient.builder(chatModel).defaultSystem("""
        Eres un analista de datos experto en clima y ambiente laboral, que apoya a
        gerentes y encargados de Recursos Humanos (RRHH) a interpretar las métricas
        del dashboard de su empresa (desempeño promedio, porcentaje de encuestas
        positivas, cantidad de reportes/incidencias y actividad en el foro de trabajadores).
        A partir de los datos entregados debes:
        1) Explicar brevemente cómo está el ambiente laboral y por qué, citando las métricas relevantes.
        2) Señalar posibles riesgos o señales de alerta si los hay.
        3) Dar 2 o 3 recomendaciones prácticas y accionables para RRHH.
        No inventes datos que no se te hayan entregado.
        Responde siempre en español, en formato breve y estructurado (usa viñetas cuando ayude a la claridad).
      """).build();
}
```

Same `spring.ai.google.genai.*` properties as [section 11.3](#113-google-gemini--spring-ai-employee-assistant)
(shared `gemini-2.5-flash` model / `0.4` temperature) — only the system prompt persona differs, via a
separate, explicitly `@Qualifier`-ed `ChatClient` bean.

**Exposed endpoint**: `POST /api/v1/dashboard-assistant` — documented in detail in
[section 4.7](#47-dashboard-assistant-ai--apiv1dashboard-assistant).

**Internal response generation flow** (`DashboardAssistantServiceImpl`):
1. Gathers 4 metrics for `company_id` via `DashboardQueryService`: average performance
   (`GetAveragePerformanceByCompanyQuery`), positive-survey rate (`GetPositiveSurveyRateByCompanyQuery`,
   threshold `>= 3`), report count by area (`GetReportCountByCompanyQuery`), forum activity by area
   (`GetForumActivityByCompanyQuery`).
2. Computes a **deterministic** `status` in code (not by the AI):
   | Rule | Status |
   |---|---|
   | `averagePerformance >= 3.5` **and** `positiveRate >= 70%` | `"BUENO"` |
   | `averagePerformance < 2.5` **or** `positiveRate < 40%` | `"CRITICO"` |
   | otherwise | `"REGULAR"` |
3. Builds a Spanish-language prompt embedding the status + all metrics + the optional `question`,
   and sends it to the `dashboardAssistantChatClient`.
4. Returns a `DashboardInsight(status, analysis, metrics)` — `status` is the code-computed label,
   `analysis` is the AI's free-text response, `metrics` is the raw data map (see the camelCase-keys
   warning in [section 4.7](#47-dashboard-assistant-ai--apiv1dashboard-assistant)).

> If `company_id` does not reference an existing company, `dashboardQueryService` throws
> `NoSuchElementException` **before** the AI is ever called — unlike the Employee Assistant's
> `survey_id`, there is no silent-fallback behavior here.

---

## 12. Seed Data

### How the seed runs

`application-dev.properties` (and `-prod`) enable SQL init, so `classpath:data.sql` runs on **every**
startup:
```properties
spring.sql.init.mode=always
spring.sql.init.data-locations=classpath:data.sql
spring.jpa.defer-datasource-initialization=true
spring.sql.init.continue-on-error=true
```
- Rows are inserted with **explicit IDs** (`1..N`). After the inserts, the script runs
  `SELECT setval('<table>_id_seq', (SELECT MAX(id) FROM <table>))` for every table, so
  auto-generated IDs continue after the seeded ones.
- `continue-on-error=true` means a failing statement (e.g. rows already present from a previous run)
  is logged and skipped rather than aborting startup.

> **Passwords**: the 8 accounts are stored as **BCrypt hashes** (`$2b$10$…`) directly in `data.sql`.
> The sign-in examples in this document use `password123` as the conventional test password.

### Seeded User Accounts (`users` + `user_accounts`)

| id | email | Full name | Company | `membership_id` | `anonymous_name` | Profiles |
|---|---|---|---|---|---|---|
| 1 | `carlos.ramirez@techcorp.pe` | Carlos Ramirez | TechCorp SAC (1) | 1 · ACTIVE | `CRamz` | Employee #1 (Backend Developer) **+** RRHH #1 (Senior HR Manager) |
| 2 | `maria.lopez@techcorp.pe` | Maria Lopez | TechCorp SAC (1) | 1 · ACTIVE | `MariLop` | Employee #2 (Frontend Developer) |
| 3 | `jorge.quispe@techcorp.pe` | Jorge Quispe | TechCorp SAC (1) | 1 · ACTIVE | `JQuispe` | Employee #3 (QA Engineer) |
| 4 | `ana.torres@innovateperu.com` | Ana Torres | InnovatePeru SRL (2) | 2 · ACTIVE | `AnaTor` | Employee #4 (Product Manager) **+** RRHH #2 (HR Coordinator) |
| 5 | `luis.mamani@innovateperu.com` | Luis Mamani | InnovatePeru SRL (2) | 2 · ACTIVE | `LuisMam` | Employee #5 (Data Analyst) |
| 6 | `sofia.vargas@innovateperu.com` | Sofia Vargas | InnovatePeru SRL (2) | 2 · ACTIVE | `SofiVar` | Employee #6 (DevOps Engineer) |
| 7 | `diego.chavez@techcorp.pe` | Diego Chavez | TechCorp SAC (1) | 1 · ACTIVE | `DiegoC` | Employee #7 (Tech Lead) |
| 8 | `valeria.mendoza@techcorp.pe` | Valeria Mendoza | TechCorp SAC (1) | 3 · PENDING | `ValMen` | Employee #8 (Junior Developer) |

### Seeded Companies & Memberships

| Company id | Name | RUC | Employees (`user_account` ids) |
|---|---|---|---|
| 1 | TechCorp SAC | `20123456781` | 1, 2, 3, 7, 8 |
| 2 | InnovatePeru SRL | `20987654322` | 4, 5, 6 |

| Membership id | Start | Over | Status |
|---|---|---|---|
| 1 | 2024-01-01 | 2025-01-01 | `ACTIVE` |
| 2 | 2024-03-01 | 2025-03-01 | `ACTIVE` |
| 3 | 2024-06-01 | 2024-12-01 | `PENDING` |

### Payments (useful for Stripe retry/refund testing)

| Payment id | Order id | Status | Note |
|---|---|---|---|
| 1 | 1 | `SUCCEEDED` | eligible for **refund** (`/payments/stripe/{1}/refund`) |
| 2 | 2 | `PENDING` | — |
| 3 | 3 | `FAILED` | eligible for **retry** (`/payments/stripe/{3}/retry`) |

### Row counts per table (as inserted by `data.sql`)

| Bounded Context | Tables (rows) |
|---|---|
| IAM | `users` (8), `user_accounts` (8), `employee_profiles` (8), `rrhh_profiles` (2) |
| Dashboard | `companies` (2), `dashboards` (2), `widgets` (4), `area_companies` (4), `unit_of_works` (5), `work_teams` (5) |
| Payment | `memberships` (3), `membership_plans` (3), `benefits` (6), `orders` (3), `payments` (3) |
| Worker Forum | `forums` (2), `categories` (4), `threads` (4), `messages` (7), `assets` (4 → 3 PDF + 1 VIDEO), `reports` (8) |
| Notification | `notifications` (6), `notification_details` (6) |
| Feedback | `surveys` (3), `questions_surveys` (5), `answers` (5), `survey_responses` (5) |
| Profile Performance | `performances` (5), `comments_employees` (5) |

> **Asset URLs are placeholders** (`https://storage.softwork.pe/assets/...`) — the seed does not
> upload to Cloudinary, so those files do not physically exist; real assets are created through
> `POST /api/v1/assets`.

### Full Flow Example (including Stripe and AI)

```bash
BASE=http://localhost:8092

# 1. Authenticate
curl -X POST $BASE/api/v1/authentication/sign-in \
  -H "Content-Type: application/json" \
  -d '{"email":"carlos.ramirez@techcorp.pe","password":"password123"}'

TOKEN="eyJhbGciOiJIUzI1NiIs..."

# 2. Create an order (snake_case body)
curl -X POST $BASE/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"user_account_id":1,"amount":99,"membership_id":1}'

# 3. Create the Stripe PaymentIntent for that order
curl -X POST $BASE/api/v1/payments/stripe/checkout \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"order_id":1,"currency":"usd"}'
# → { "client_secret": "pi_..._secret_..." }  (use it in the frontend with Stripe.js)

# 4. Upload an attachment (multipart, not JSON — form-field names stay camelCase)
curl -X POST $BASE/api/v1/assets \
  -H "Authorization: Bearer $TOKEN" \
  -F "messageId=1" \
  -F "name=report.pdf" \
  -F "fileType=PDF" \
  -F "file=@./report.pdf"

# 5. Ask the AI assistant
curl -X POST $BASE/api/v1/feedback-assistant \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"survey_id":1,"prompt":"Suggest 3 questions about teamwork"}'
```

### Sign in with Google — two-phase example

```bash
BASE=http://localhost:8092
ID_TOKEN="<google_id_token_from_the_frontend>"

# Phase 1 — validate the token and check whether the account exists (creates nothing)
curl -X POST $BASE/api/v1/authentication/google \
  -H "Content-Type: application/json" \
  -d "{\"id_token\":\"$ID_TOKEN\"}"
# → { "registered": true,  "id": 1, "email": "...", "token": "..." }   → already registered, done
# → { "registered": false, "id": null, ... }                          → go to Phase 2

# Phase 2 (only if registered:false) — complete the profile with the SAME id_token
curl -X POST $BASE/api/v1/authentication/sign-up/employee/google \
  -H "Content-Type: application/json" \
  -d "{\"id_token\":\"$ID_TOKEN\",\"name\":\"Juan\",\"last_name\":\"Perez\",\"phone_number\":\"987654321\",\"dni\":\"12345678\",\"date_start\":\"2026-01-15\",\"position\":\"Backend Developer\",\"salary\":5000}"
# → 201 { "id": 9, "email": "<from the verified token>", "token": "eyJhbGci..." }
```

---

## 13. Naming Reference (Request vs Response)

> **As of 2026-07-03, all JSON keys are snake_case for both requests and responses** (class-level
> `@JsonNaming(SnakeCaseStrategy)` on every DTO). Request and response keys now **match** for the
> same logical field. This table lists the canonical snake_case key per field, plus the few
> residual quirks that come from misnamed Java fields (not from naming).

| Bounded Context | Logical Field | Java Field | JSON Key (request & response) |
|---|---|---|---|
| IAM / User | ID | `userId` | `user_id` |
| IAM / User | last name | `lastName` | `last_name` |
| IAM / User | phone | `phoneNumber` | `phone_number` |
| IAM / UserAccount | ID | `userAccountId` | `user_account_id` |
| IAM / UserAccount | anonymous name | `anonymousName` | `anonymous_name` |
| IAM / EmployeeProfile | start date (request) | `dateStart` | `date_start` |
| IAM / EmployeeProfile | start date (response) | `dateStart` ⚠️ | `date_start` (**field typo, left intact**) |
| IAM / RRHHProfile | department | `RRHHDepartment` ⚠️ | `rrhhdepartment` (**consecutive caps, no `_`**) |
| IAM / RRHHProfile | hierarchy | `statusHierarchy` | `status_hierarchy` |
| Dashboard / Company | ID | `companyId` | `company_id` (**typo `comapany_id` fixed**) |
| Dashboard / Company | RUC | `RUC` | `ruc` |
| Dashboard / Company | contact email | `contactEmail` | `contact_email` |
| Dashboard / Company | contact phone | `contactPhone` | `contact_phone` |
| Dashboard / AreaCompany | budget | `annualBudget` | `annual_budget` |
| Dashboard / Widget | period | `refreshPeriod` | `refresh_period` |
| Dashboard / WorkTeam | name | `teamName` | `team_name` |
| Dashboard / WorkTeam | leader | `leaderOfTeam` | `leader_of_team` |
| Feedback / Survey | target type | `targetType` | `target_type` |
| Feedback / Survey | expiration | `expirationTime` | `expiration_time` |
| Feedback / SurveyResponse | response ID | `surveyResponseId` | `survey_response_id` |
| Feedback / SurveyResponse | submitted at | `submittedAt` | `submitted_at` |
| Feedback / QuestionSurvey | question ID | `questionSurveyId` | `question_survey_id` |
| Feedback / QuestionSurvey | text | `textQuestion` | `text_question` |
| Feedback / Answer | answer ID | `answerId` | `answer_id` |
| Feedback / Answer | score | `scoreAnswer` | `score_answer` |
| Feedback / AssistantAnswer | AI answer | `contentAnswer` | `content_answer` |
| Dashboard / AnalyzeDashboardRequest | company | `companyId` | `company_id` |
| Dashboard / DashboardInsight | metrics | `metrics` ⚠️ | `metrics` (**inner keys stay camelCase — raw `Map`, not a DTO**) |
| Payment / Membership | start | `membershipStart` | `membership_start` |
| Payment / Membership | plan id | `membershipPlanId` | `membership_plan_id` |
| Payment / Order | account | `userAccountId` | `user_account_id` |
| Payment / Payment | transaction | `transactionId` | `transaction_id` |
| Payment / Payment | status | `paymentStatus` | `payment_status` |
| Payment / Payment | method | `paymentMethod` | `payment_method` |
| Payment / MembershipPlan | name | `planName` | `plan_name` |
| Payment / MembershipPlan | benefits | `benefitResponseList` ⚠️ | `benefit_response_list` (verbose Java name) |
| Payment / Stripe Checkout | client secret | `clientSecret` | `client_secret` |
| Payment / Stripe Refund | refund amount (request) | `refundAmountCents` | `refund_amount_cents` (**typo `refoundAmountCents` fixed**) |
| Payment / Stripe Refund | refunded amount (response) | `refundedAmountCents` | `refunded_amount_cents` |
| Notification / Detail | notification | `notificationId` | `notification_id` |
| Forum / Message | content | `contentMessage` | `content_message` |
| Forum / Thread | messages | `messageResponses` ⚠️ | `message_responses` (verbose Java name) |
| Forum / Asset | size | `fileSize` | `file_size` (response; not sent in the multipart request) |
| Forum / Asset | type | `fileType` | `file_type` (JSON) / `fileType` (multipart form field) |
| Forum / EmployeeAssistant | company | `companyId` | `company_id` |
| Forum / EmployeeAssistant | prompt | `prompt` | `prompt` |
| Forum / EmployeeAssistant | AI answer | `contentAnswer` | `content_answer` |
| Report / Report | reason | `reason` | `reason` |
| Report / Report | description | `description` | `description` |
| Report / Report | report date | `reportDate` | `report_date` |
| Report / Report | area | `areaCompanyId` | `area_company_id` |
| Performance | employee | `employeeProfileId` | `employee_profile_id` |
| Performance | date | `dateTime` | `date_time` |
| CommentEmployee | RRHH | `rrhhProfileId` | `rrhh_profile_id` |
| Feedback / SurveyResponse | commentary | `commentary` | `commentary` |
| Feedback / SurveyResponse | cause | `cause` | `cause` |
| Dashboard / Dashboard | ruc | `ruc` | `ruc` (lowercase — differs from Company's `RUC`) |

> ⚠️ = the JSON key looks odd because the **Java field name** itself is misnamed. Those field names
> were intentionally left untouched by the naming standardization.
>
> **Multipart note**: `POST /api/v1/assets` uses `@RequestParam` form fields, which are NOT governed
> by `@JsonNaming`. Its form fields remain `messageId`, `name`, `fileType`, `file`.
>
> **`metrics` note**: `DashboardInsightResponse.metrics` is a `Map<String, Object>` populated
> programmatically in `DashboardAssistantServiceImpl` (`averagePerformance`, `totalEvaluations`,
> `positiveSurveyRate`, `totalSurveyAnswers`, `totalReports`, `reportsByArea`, `totalForumMessages`,
> `forumActivityByArea`, and the nested `areaId`/`areaName`/`reportCount`/`threadCount`/
> `messageCount` list entries) — like the Stripe `metadata` map, its keys are **not** rewritten by
> `@JsonNaming` and stay camelCase. See [section 4.7](#47-dashboard-assistant-ai--apiv1dashboard-assistant)
> for the full example payload.

---

## 14. Endpoint Routes — Quick Reference

| Bounded Context | Base Route | Note |
|---|---|---|
| Auth | `/api/v1/authentication` | Public — sign-in, sign-up (employee/rrhh), Google sign-in + Google sign-up |
| Users | `/api/v1/users` | |
| User Accounts | `/api/v1/user_accounts` | underscore, no hyphen |
| Employee Profile | `/api/v1/employee-profile` | **singular** |
| RRHH Profiles | `/api/v1/rrhh-profiles` | plural |
| Companies | `/api/v1/companies` | |
| Area Company | `/api/v1/area-company` | **singular** |
| Dashboards | `/api/v1/dashboards` | |
| Dashboard Assistant (AI) | `/api/v1/dashboard-assistant` | POST only, requires auth, **RRHH** |
| Widgets | `/api/v1/widgets` | `@Deprecated` — use sub-resource `POST /dashboards/{id}/widgets` |
| Unit of Work | `/api/v1/unit-of-work` | **singular** |
| Work Teams | `/api/v1/work-teams` | |
| Surveys | `/api/v1/surveys` | |
| Survey Responses | `/api/v1/survey-responses` | |
| Question Surveys | `/api/v1/question-surveys` | |
| Answers | `/api/v1/answers` | `@Deprecated` — scheduled for removal |
| Employee Assistant (AI) | `/api/v1/feedback-assistant` | POST only, requires auth, **Employee** (route unchanged — see [naming note](#55-employee-assistant-ai--apiv1feedback-assistant) in section 5.5) |
| Orders | `/api/v1/orders` | |
| Memberships | `/api/v1/memberships` | |
| Payments (manual) | `/api/v1/payments` | Traditional CRUD |
| Payments (Stripe) | `/api/v1/payments/stripe` | checkout, retry, refund, webhook |
| Membership Plans | `/api/v1/memberships-plans` | plural of both words |
| Benefits | `/api/v1/benefits` | |
| Notifications | `/api/v1/notifications` | |
| Notification Details | `/api/v1/notification-details` | |
| Forums | `/api/v1/forums` | |
| Categories | `/api/v1/categories` | |
| Threads | `/api/v1/threads` | |
| Messages | `/api/v1/messages` | |
| Assets | `/api/v1/assets` | creation via multipart + Cloudinary |
| Reports | `/api/v1/reports` | |
| Employee Assistant (Forum) | `/api/v1/employee-assistant` | POST only, requires auth, **Employee** — general company questions |
| Performances | `/api/v1/performances` | also: `GET /employee/{employeeId}` |
| Comment Employees | `/api/v1/commentemployees` | route has **no hyphens** |

### Sub-resources (linking operations) — snake_case bodies

| Route | Method | Request Key |
|---|---|---|
| `/api/v1/companies/{id}/employees` | POST | `{"employee_id": 1}` |
| `/api/v1/companies/{id}/area-companies` | POST | `{"area_company_id": 1}` |
| `/api/v1/area-company/{id}/unitsOfWork` | POST | `{"unit_of_work_id": 1}` |
| `/api/v1/unit-of-work/{id}/work-teams` | POST | `{"work_team_id": 1}` |
| `/api/v1/dashboards/{id}/widgets` | POST | `{"widget_id": 1}` |
| `/api/v1/forums/{id}/categories` | POST | `{"category_id": 1}` |
| `/api/v1/forums/company/{companyId}` | GET | — |
| `/api/v1/performances/employee/{employeeId}` | GET | — |
| `/api/v1/categories/{id}/threads` | POST | `{"thread_id": 1}` |
| `/api/v1/threads/{id}/messages` | POST | `{"message_id": 1}` |
| `/api/v1/messages/{id}/assets` | POST | `{"asset_id": 1}` |
| `/api/v1/memberships-plans/{id}/benefits` | POST | `{"benefit_id": 1}` |
| `/api/v1/performances/{id}/comment-employee` | POST | `{"comment_id": 1}` |

### External Service Endpoints (quick reference)

| Route | Method | Auth | Content-Type | Purpose |
|---|---|---|---|---|
| `/api/v1/authentication/google` | POST | Public | `application/json` | Validate Google `id_token` (phase 1 — creates nothing) |
| `/api/v1/authentication/sign-up/employee/google` | POST | Public | `application/json` | Complete Google employee sign-up (phase 2) |
| `/api/v1/authentication/sign-up/rrhh/google` | POST | Public | `application/json` | Complete Google RRHH sign-up (phase 2) |
| `/api/v1/assets` | POST | JWT | `multipart/form-data` | Upload file to Cloudinary + create Asset |
| `/api/v1/payments/stripe/checkout` | POST | JWT | `application/json` | Create a Stripe PaymentIntent |
| `/api/v1/payments/stripe/{paymentId}/retry` | POST | JWT | `application/json` | Retry a failed payment |
| `/api/v1/payments/stripe/{paymentId}/refund` | POST | JWT | `application/json` | Initiate a refund |
| `/api/v1/payments/stripe/webhook` | POST | **Stripe signature** (no JWT) | `application/json` | Receive asynchronous Stripe events |
| `/api/v1/feedback-assistant` | POST | JWT | `application/json` | Query the Feedback Assistant (Gemini) — survey/feedback help |
| `/api/v1/employee-assistant` | POST | JWT | `application/json` | Query the Employee Assistant (Gemini) — general company questions |
| `/api/v1/dashboard-assistant` | POST | JWT | `application/json` | Query the Dashboard Assistant (Gemini) — RRHH climate diagnosis |
