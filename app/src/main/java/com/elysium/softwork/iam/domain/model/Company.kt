package com.elysium.softwork.iam.domain.model

/**
 * A company/corporation — the annotation-free bean for the `companies` endpoints
 * (the *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson
 * resolves each by reflection without `@SerializedName`. Only the subset the onboarding
 * company-selection step renders is modelled; the response also carries `employees` /
 * `area_company_responses` lists the client ignores.
 *
 * @property company_id primary key; the identifier associated onto the worker's account.
 * @property name company name (the primary label in the selection list).
 * @property ruc tax id (rendered as a secondary line).
 * @property contact_email company contact email.
 * @property contact_phone company contact phone.
 */
data class Company(
    val company_id: Long? = null,
    val name: String? = null,
    val ruc: String? = null,
    val contact_email: String? = null,
    val contact_phone: String? = null,
)
