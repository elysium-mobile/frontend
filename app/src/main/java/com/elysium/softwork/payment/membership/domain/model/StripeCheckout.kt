package com.elysium.softwork.payment.membership.domain.model

/**
 * Hosted Stripe Checkout Session bean for `POST /api/v1/payments/stripe/checkout` — the
 * annotation-free bean spanning request + response (the *Bean / Pragmatic Shortcut*).
 *
 * The backend serializes **uniform snake_case**, so every property is snake_case and Gson
 * resolves each by reflection without `@SerializedName`. Every field is nullable because the two
 * wire shapes differ:
 *  - **Request** (`CreateStripeCheckoutRequest`) fills [order_id] (required) and optional
 *    [currency] (ISO 4217, lowercased server-side; defaults to `usd`).
 *  - **Response** fills [checkout_url] — the hosted Checkout Session URL the app opens in the
 *    external browser to complete payment.
 *
 * ⚠️ Contract note: `ELYSIUM-API_DOCUMENTATION.md` §11.2 currently documents this endpoint as
 * returning `client_secret` (a PaymentIntent for Stripe.js/Elements), **not** `checkout_url`. This
 * bean follows the hosted-redirect contract mandated by the REFI; if the deployed backend still
 * returns `client_secret`, [checkout_url] will be null and the redirect is skipped.
 *
 * @property order_id the order to charge (request).
 * @property currency ISO 4217 currency code (request, optional).
 * @property checkout_url the hosted Stripe Checkout URL to open externally (response).
 */
data class StripeCheckout(
    val order_id: Long? = null,
    val currency: String? = null,
    val checkout_url: String? = null,
)
