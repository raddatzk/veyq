package com.veyq.app.shared.http

import com.veyq.app.shared.http.FailureStatus.BAD_REQUEST
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.FailureStatus.FORBIDDEN
import com.veyq.app.shared.http.FailureStatus.NOT_FOUND
import com.veyq.app.shared.http.FailureStatus.RATE_LIMITED
import com.veyq.app.shared.http.FailureStatus.SERVICE_UNAVAILABLE
import com.veyq.app.shared.http.FailureStatus.UNPROCESSABLE

/**
 * Canonical [HttpFailure]s for semantic failures that recur across **more than one feature slice** — the single
 * source of truth for their `(code, status)` (ADR 0042). A failure variant in any slice delegates to one of these
 * with Kotlin interface delegation (`object … : SomeOutcome, HttpFailure by Failures.scopeNotFound`), so the
 * projection can never drift between the independent slices that raise it. Failures whose `code` is local to a
 * single slice (however often they recur *within* it) use the inline [httpFailure] factory at the variant instead
 * of an entry here — a global catalog is for cross-cutting failures, not one slice's private vocabulary.
 */
object Failures {
    /**
     * No such scope — **or** the caller holds no membership for it. The two are deliberately indistinguishable
     * (ADR 0042): a non-member must not learn a scope exists. Every scope-addressed operation raises this from
     * its **first** guard, before any existence/kind/state probe, so a stranger and a bogus id get one answer.
     */
    val scopeNotFound: HttpFailure = httpFailure("scope_not_found", NOT_FOUND)

    /** Target node absent (or membership-gated away) → 404. */
    val nodeNotFound: HttpFailure = httpFailure("node_not_found", NOT_FOUND)

    /** No such tenant — or the caller is not a member of it, indistinguishable for the same reason as
     *  [scopeNotFound] → 404. (The Server-Owner-gated tenant admin ops raise it for plain absence.) */
    val tenantNotFound: HttpFailure = httpFailure("tenant_not_found", NOT_FOUND)

    /** Target membership/member absent → 404. */
    val memberNotFound: HttpFailure = httpFailure("member_not_found", NOT_FOUND)

    /**
     * The caller **is** a member of the scope but holds no grant there — not a key-holder (ADR 0020: a grant is
     * a capability *within* the membership boundary, e.g. a member whose consent is still pending). A legitimate
     * 403: the membership guard already ran, so this caller knows the scope exists and no existence is disclosed
     * (ADR 0042). Do not raise it for a non-member — that is [scopeNotFound].
     */
    val notKeyHolder: HttpFailure = httpFailure("not_key_holder", FORBIDDEN)

    /** Scope is pending deletion; mutating it conflicts with that terminal transition → 409. */
    val scopeDeleting: HttpFailure = httpFailure("scope_deleting", CONFLICT)

    /** Slug already taken under the parent (uniqueness) → 409. */
    val slugTaken: HttpFailure = httpFailure("slug_taken", CONFLICT)

    /** The subject is already a member of the target scope/tenant → 409. */
    val alreadyMember: HttpFailure = httpFailure("already_member", CONFLICT)

    /** The change would remove the last owner of the scope/tenant → 409 (ADR 0007). */
    val lastOwner: HttpFailure = httpFailure("last_owner", CONFLICT)

    /** A required capability is disabled for the tenant → 422. */
    val capabilityDisabled: HttpFailure = httpFailure("capability_disabled", UNPROCESSABLE)

    /** Request violates the Memory-tree structural rules → 422. */
    val illegalStructure: HttpFailure = httpFailure("illegal_structure", UNPROCESSABLE)

    /** The subject's username collides with an existing one → 409. */
    val usernameTaken: HttpFailure = httpFailure("username_taken", CONFLICT)

    /** A structurally-valid request whose cross-field/state preconditions fail → 400. */
    val invalidRequest: HttpFailure = httpFailure("invalid_request", BAD_REQUEST)

    /** Too many requests against a rate-limited endpoint → 429. */
    val rateLimited: HttpFailure = httpFailure("rate_limited", RATE_LIMITED)

    /**
     * The addressed tenant has no working mail transport, so an action that can only travel by mail cannot
     * happen → 503 (ADR 0055). Raised by two independent slices — the member invite and password recovery —
     * which is why it lives here rather than in either of them.
     *
     * It is deliberately **not** an oracle: it answers a question about the deployment ("can this tenant send
     * mail"), never about an address. On the recovery path that is only true because the transport check runs
     * *before* the per-address rate limiter — see `RecoveryHandler.request`.
     */
    val mailUnavailable: HttpFailure = httpFailure("mail_unavailable", SERVICE_UNAVAILABLE)
}
