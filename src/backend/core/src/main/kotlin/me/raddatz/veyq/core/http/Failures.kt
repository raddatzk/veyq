package me.raddatz.veyq.core.http

import me.raddatz.veyq.core.http.FailureStatus.BAD_REQUEST
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.FailureStatus.RATE_LIMITED
import me.raddatz.veyq.core.http.FailureStatus.SERVICE_UNAVAILABLE
import me.raddatz.veyq.core.http.FailureStatus.UNAUTHORIZED
import me.raddatz.veyq.core.http.FailureStatus.UNAVAILABLE_FOR_LEGAL_REASONS
import me.raddatz.veyq.core.http.FailureStatus.UNPROCESSABLE

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
     * No such scope — **or** the caller holds no accepted membership for it. The two are deliberately
     * indistinguishable (ADR 0042): a non-member must not learn a scope exists. Every scope-addressed operation
     * raises this from its **first** guard, the scope-access gate (`ScopeAccess`), before any existence/kind/state
     * probe, so a stranger, an unaccepted proposal and a bogus id get one answer.
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
     * The tenant an operation acts on is SUSPENDED → 403 (ADR 0066). Raised by the scope-access gate on every
     * operation acting on the tenant, from whichever domain it arrived (uc.spaces.scope-access), and by
     * `TenantSuspensionFilter` on the tenant's own domain, before authentication — so it is the answer *every*
     * route can give, which is why it belongs in this catalog rather than in one slice's vocabulary.
     *
     * Deliberately **not** indistinguishable from [tenantNotFound], and safe for that: the gate tells it only to an
     * accepted member — a stranger gets the 404 first — and on the tenant's own domain the domain is public by
     * construction (it is how the request got here). The caller needs to tell "this space is temporarily offline"
     * from "wrong address"; telling a member their own space still exists is the honest answer, not a leak.
     */
    val tenantSuspended: HttpFailure = httpFailure("tenant_suspended", FORBIDDEN)

    /**
     * The caller **is** an accepted member of the scope but holds no grant there — not a key-holder (ADR 0020: a
     * grant is a capability *within* the membership boundary; every membership is born with one, ADR 0111). Never an unaccepted
     * proposal: the gate answers that one like a stranger. A legitimate 403: the scope-access gate already ran, so this caller knows the scope exists and no existence is disclosed
     * (ADR 0042). Do not raise it for a non-member — that is [scopeNotFound].
     */
    val notKeyHolder: HttpFailure = httpFailure("not_key_holder", FORBIDDEN)

    /** The scope is being deleted — read-only for its grace window, for every write (uc.spaces.delete-group A4) → 409.
     *  The one code for that state; raised by the scope-access gate, so only an accepted member who still belongs to
     *  the tenant ever sees it. */
    val scopeDeleting: HttpFailure = httpFailure("scope_deleting", CONFLICT)

    /**
     * The client-minted id of a create is already taken → 409 (ADR 0072 Decision 3). The client mints the id of
     * every sealed object it creates because each ciphertext of the create is sealed bound to it, so the server may
     * neither substitute its own id nor overwrite the object that holds this one. **The same answer wherever the
     * taken id lives** — this scope, another scope, a trashed row — and only ever after the scope-access gate, so
     * it tells a member nothing about a scope they cannot see. Not idempotent: a retry whose first response was
     * lost answers this too, and the client re-reads.
     */
    val idTaken: HttpFailure = httpFailure("id_taken", CONFLICT)

    /** A write stated an epoch that is no longer the scope's current one — a concurrent rotation moved it → 409; the
     *  client re-reads and rebuilds against the current epoch (ADR 0024). */
    val staleEpoch: HttpFailure = httpFailure("stale_epoch", CONFLICT)

    /** A grant names the key it is wrapped to (`recipientIdentityDigest`), and that is not the key the server stores for
     *  its recipient — an account's current identity, an invite's key → 409; nothing written. The client re-reads the
     *  recipient's key and rewraps (ADR 0111: a grant names its recipient key from birth). */
    val recipientKeyNotCurrent: HttpFailure = httpFailure("recipient_key_not_current", CONFLICT)

    /** Securing the account (ADR 0111 decision 6): the stage or the account's set of grant-carrying
     *  memberships is not what the request names → 409; nothing written. The client re-reads the plan, re-stages the
     *  delta (or opens a new stage) and resubmits. */
    val rotationPlanChanged: HttpFailure = httpFailure("rotation_plan_changed", CONFLICT)

    /** A write sealed under the key of a node the scope's last rotation has not re-keyed yet → 409; nothing was
     *  written, and the writer retries once the re-key reached the node (ADR 0109 point 6). Only that node waits. */
    val nodeRekeyPending: HttpFailure = httpFailure("node_rekey_pending", CONFLICT)

    /** A moderator blocked this content (uc.moderation.take-a-measure; RFC 7725) → 451 for every byte read, a restore
     *  and a content replace, member and guest alike: the client draws "Blocked by the operator", not "missing". */
    val blockedByOperator: HttpFailure = httpFailure("blocked_by_operator", UNAVAILABLE_FOR_LEGAL_REASONS)

    /** A link a moderator blocked (or a guest uploader who came through it) is not re-activated or re-issued → 409. */
    val shareBlockedByOperator: HttpFailure = httpFailure("share_blocked_by_operator", CONFLICT)

    /** A revision-checked write stated a revision the stored one has moved past → 409; nothing was written. The
     *  sealed-meta writes answer it with the blob's current value ([httpStaleSealedMeta], ADR 0077). */
    val staleRevision: HttpFailure = httpFailure("stale_revision", CONFLICT)

    /** A placement write (a move or a rename) stated a `placementRevision` the node has moved past → 409; nothing was
     *  written, and the client re-reads the node and rebuilds the placement against it (ADR 0080 Decision 3). */
    val stalePlacement: HttpFailure = httpFailure("stale_placement", CONFLICT)

    /** Slug already taken under the parent (uniqueness) → 409. */
    val slugTaken: HttpFailure = httpFailure("slug_taken", CONFLICT)

    /** The subject is already a member of the target scope/tenant → 409. */
    val alreadyMember: HttpFailure = httpFailure("already_member", CONFLICT)

    /** The change would remove the last owner of the scope/tenant → 409 (ADR 0007). */
    val lastOwner: HttpFailure = httpFailure("last_owner", CONFLICT)

    /** A required capability is disabled for the tenant → 422. */
    val capabilityDisabled: HttpFailure = httpFailure("capability_disabled", UNPROCESSABLE)

    /** No register entry has a requirement of this name, at the level asked (ADR 0088 decision 8). */
    val requirementNotFound: HttpFailure = httpFailure("requirement_not_found", NOT_FOUND)

    /** A link without a password while every link of its tenant needs one (requirement `linkPassword`). */
    val linkPasswordRequired: HttpFailure = httpFailure("link_password_required", UNPROCESSABLE)

    /** A link with no end date, or one beyond its tenant's `linkMaxDays` after it was made (requirement `linkMaxDays`). */
    val linkExpiryRequired: HttpFailure = httpFailure("link_expiry_required", UNPROCESSABLE)

    /** Request violates the node tree's structural rules → 422. */
    val illegalStructure: HttpFailure = httpFailure("illegal_structure", UNPROCESSABLE)

    /** The subject's username collides with an existing one → 409. */
    val usernameTaken: HttpFailure = httpFailure("username_taken", CONFLICT)

    /** A date-time of the right shape that no time column holds (`StrictJsonTypes.STORABLE`, inbox #509) → 422: a bound
     *  no date-time schema can state, so a semantic refusal of its own, not the shape's 400 (ADR 0091 decision 9). */
    val dateTimeOutOfRange: HttpFailure = httpFailure("date_time_out_of_range", UNPROCESSABLE)

    /** A structurally-valid request whose cross-field/state preconditions fail → 400. */
    val invalidRequest: HttpFailure = httpFailure("invalid_request", BAD_REQUEST)

    /** The caller's session ended while the request waited — a reset, a password change or a key rotation revoked
     *  it (inbox #297). The same answer the authentication layer gives a revoked token, because it is one. */
    val authenticationFailed: HttpFailure = httpFailure("authentication_failed", UNAUTHORIZED)

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
