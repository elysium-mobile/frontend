package com.elysium.softwork.shared.utils.discriminators

/**
 * Recovery route selected by the `HTTP 401` unified trap when a live session is rejected
 * mid-flight.
 *
 * **Category — discriminator enum.** A pure sum type with no payload; it exists only to tell
 * the top-level router *how* to re-authenticate the worker after
 * [com.elysium.softwork.iam.data.store.AuthStore.invalidateSession] wipes a dead session. The
 * distinction matters because a Google-linked account stores no local password (the Google
 * identity resolves the email server-side), so it cannot be renewed through the credentials
 * form — it must go back through the Gmail handshake.
 *
 * - [NONE] — no invalidation pending; the current session is valid (the default/idle state).
 * - [CREDENTIALS] — a standard credentials session was invalidated; route to the `LoginScreen`.
 * - [GOOGLE] — a Google-linked session was invalidated; prompt a fresh Google sign-in handshake.
 */
enum class SessionRecovery { NONE, CREDENTIALS, GOOGLE }
