package com.veyq.core.membership

/**
 * An admission as the server holds it (ADR 0020 move 6): the client-minted id, the scope epoch whose key computed
 * the tag, and the tag. **Opaque**: the server has no epoch key, so it cannot compute or check a tag — it stores
 * one, copies it from an accepted invite onto the membership, serves it to the clients that verify it, and makes a
 * rotation carry every open one forward. What it does check is only whether one is *present* where it must be.
 */
data class Admission(val id: String, val epoch: Int, val tag: String)

/** A grant as it is written (ADR 0028 Decision 6): the ciphertext and its granter's identity signature over it. The
 *  granter is always the authenticated caller of the write, so it is never taken from a request body. */
data class SignedGrant(val wrappedGrant: String, val signature: String)

/** Write [a] onto this membership, or clear it (null) — the one place a membership's three admission columns move. */
fun MembershipEntity.admit(a: Admission?) {
    admissionId = a?.id
    admissionEpoch = a?.epoch
    admissionTag = a?.tag
}

/** This membership's admission, or null when it carries none (all three columns are written together). */
fun MembershipEntity.admission(): Admission? {
    val id = admissionId ?: return null
    return Admission(id, admissionEpoch ?: return null, admissionTag ?: return null)
}
