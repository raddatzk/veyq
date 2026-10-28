package me.raddatz.veyq.core.membership

/** A grant as it is written (ADR 0028 Decision 6): the ciphertext and its granter's identity signature over it. The
 *  granter is always the authenticated caller of the write, so it is never taken from a request body. */
data class SignedGrant(val wrappedGrant: String, val signature: String)
