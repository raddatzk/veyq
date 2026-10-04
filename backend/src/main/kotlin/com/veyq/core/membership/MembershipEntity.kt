package com.veyq.core.membership

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * Membership — the (user, scope, role, grant) relation (ADR 0007). A **shared, query-only primitive** (cr-0160):
 * it lives in `shared/membership/` because half a dozen feature slices read it, so it must not belong to any one
 * of them. It references a `scopeId` (cr-0026); the TENANT root scope is keyed `scopeId == tenantId`, a
 * PERSONAL/GROUP scope gets its own id. `wrappedGrant` is the scope key sealed to the member's identity public
 * key — opaque ciphertext the server stores but never opens. The first row is the owner's, born at tenant
 * activation (cr-0010); members extend the relation later via the invite-wrap, and each member births their own
 * PERSONAL scope (en.spaces.personal-scope).
 */
@Entity
@Table(name = "veyq_membership")
@DynamicUpdate
class MembershipEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "user_id", nullable = false, length = 36)
    lateinit var userId: String

    @Column(name = "tenant_id", nullable = false, length = 36)
    lateinit var tenantId: String

    /** The scope this membership grants (cr-0026). For the TENANT root scope this equals `tenantId`. */
    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "role", nullable = false, length = 16)
    lateinit var role: String

    /** The scope key sealed to this member's identity public key — opaque ciphertext the server never opens.
     *  **Nullable**: a *pending* member (cr-0076) holds a role but no grant yet until a key-holder delivers one
     *  (grant-delivery job); a grant-less membership is the "hollow" state ADR 0007 blesses. */
    @Column(name = "wrapped_grant")
    var wrappedGrant: String? = null

    /** The scope epoch this membership's `wrappedGrant` wraps (ADR 0024, cr-0088). `< scope.currentEpoch` means the
     *  grant is **stale** (a rotation hasn't re-granted this member yet) — grant-delivery re-grants them. */
    @Column(name = "granted_epoch", nullable = false)
    var grantedEpoch: Int = 0

    /** A **soft consent proposal** the target has not yet accepted (uc.spaces.consent-group-add, cr-0094, ADR 0014
     *  §5). While true this is a grant-less membership grant-delivery **skips** — `K_SC` is gated **before** the
     *  wrap — until the invitee accepts (clears the flag) or declines (deletes the row). Default false = an
     *  ordinary membership (the pre-consent behaviour). */
    @Column(name = "pending_consent", nullable = false)
    var pendingConsent: Boolean = false

    /** For a `pendingConsent` proposal (cr-0096, ADR 0027): the group's **derived name-key `K_name`** sealed to
     *  the invitee's identity public key (`asymWrap(inviteePub, K_name, "group-name-key")`) — opaque ciphertext the
     *  server stores verbatim. Handed to the invitee so they open the scope's **canonical** `encrypted_name` (not
     *  a private name copy) and see the group-consistent name. Null for an ordinary membership; cleared on accept. */
    @Column(name = "sealed_name_key")
    var sealedNameKey: String? = null

    /** For a `pendingConsent` proposal (cr-0094): the userId of the GroupOwner who proposed the add ("who added
     *  you"), resolved to their per-tenant username in the invitee's invitations view. Null for an ordinary
     *  membership; cleared on accept. */
    @Column(name = "added_by", length = 36)
    var addedBy: String? = null

    /** The admission (ADR 0020 move 6): a client-minted id, the epoch whose key computed [admissionTag], and the tag —
     *  an HMAC only a holder of that epoch key can compute. Opaque to the server, which stores, relays and never
     *  verifies it; every client verifies it before wrapping a scope key to this member. Null = never admitted by a
     *  key-holder (or dropped by a rotation whose rotator could not verify it) — no honest client delivers to it. */
    @Column(name = "admission_id", length = 36)
    var admissionId: String? = null

    @Column(name = "admission_epoch")
    var admissionEpoch: Int? = null

    @Column(name = "admission_tag")
    var admissionTag: String? = null

    /** Who posted [wrappedGrant], and their Ed25519 identity signature over it (ADR 0028 Decision 6). Written with
     *  every grant — the granter is always the authenticated caller of the write — and never verified here: the
     *  recipient checks it before it uses the key. Null for a grant-less membership. */
    @Column(name = "granter_user_id", length = 36)
    var granterUserId: String? = null

    @Column(name = "grant_signature")
    var grantSignature: String? = null

    /** The granter's identity public bundle **as it was when this grant was written** — the key [grantSignature] was
     *  made with — copied from their account row in the write's own transaction, and what the reads serve. Never
     *  the granter's CURRENT key: an account rotation (ADR 0060) replaces that, and a grant served beside it fails
     *  verification before the recipient's pin is read (ADR 0028 § What is still open). The pin, not this value,
     *  decides whether the key is trusted. Non-null exactly when [granterUserId] is; every writer sets both, which
     *  `scripts/validate_grant_writer_arity.py` holds. */
    @Column(name = "granter_identity_public_key")
    var granterIdentityPublicKey: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    /** The role vocabulary — `OWNER` / `MEMBER` at **every** level, no third role (ADR 0007, ADR 0014). These
     *  constants are held equal to the contract's `SetMemberRoleRequest.role` enum by
     *  `scripts/validate_scope_model_vocabulary.py`, which reads every `const val` here — so this companion
     *  carries role constants and nothing else (cr-0166). */
    companion object : PanacheCompanionBase<MembershipEntity, String> {
        const val OWNER = "OWNER"
        const val MEMBER = "MEMBER"
    }
}
