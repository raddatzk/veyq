package me.raddatz.veyq.core.identity

/**
 * The one email normalization: trimmed, lower-cased. It is the client's auth-salt rule (`src/packages/crypto/src/v1.ts`,
 * `email.trim().toLowerCase()`, ADR 0003), and it has to be, because the address an account was born under is the
 * address its login salt was derived from — a server that folded an address differently from the client would find
 * a different account, or none, for the same person.
 *
 * Every place that stores, looks up, or keys anything by an address goes through this: [AccountStore] (which
 * applies it itself, so a caller cannot forget), the mint paths, the login challenge, and the mail rate limiters.
 * The JSON layer already trims request emails; normalizing again here is deliberate, so the rule does not depend
 * on how a value arrived.
 */
fun normalizeEmail(email: String): String = email.trim().lowercase()
