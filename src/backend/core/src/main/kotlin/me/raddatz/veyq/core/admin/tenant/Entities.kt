package me.raddatz.veyq.core.admin.tenant

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * One acceptance of one terms **pair** by one member (cr-0114, ADR 0035 §4 and decision 6): the space's
 * [acceptedRevision] and the operator section's [operatorRevision]. Keyed logically by (user_id, tenant_id,
 * accepted_revision, operator_revision) — a surrogate id + a UNIQUE — so the table is the member's **history** of
 * acceptances, one row per pair, never overwritten (owner decision 2026-10-10, inbox #402):
 * acceptance is evidence should a violation or dispute arise, and a dispute about an older text needs that text's row.
 * **Operational metadata** (like membership), never content. The gate asks whether a row for the current pair
 * exists (uc.account.accept-terms); accepting it adds its row. A row written before the operator section existed has
 * [operatorRevision] 0, and matches no current pair.
 *
 * [acceptedAt] is the server-observed time the member accepted [acceptedRevision] — audit evidence of *when* which
 * terms revision was accepted. No code path reads it and no API serves it: operators and support read it from the
 * database (owner decision 2026-10-09, after inbox #318 had dropped it as unread; `EveryStoredTimeHasAReaderTest`
 * names that reader). Written once per revision: a re-acceptance of a revision already recorded keeps its first
 * time. The client never supplies it.
 *
 * No foreign key to the account or the tenant: the account's erasure (`AccountPurge`) and the tenant's (`TenantPurge`)
 * each delete the rows no moderation case keeps, and a kept row outlives both, under the opaque ids, until the sweep of
 * kept rows (`AccountPurge.sweepKeptTerms`) finds no case naming its account (ADR 0101 decision 6, `TermsEvidence`).
 */
@Entity
@Table(name = "veyq_tenant_terms_acceptance")
@DynamicUpdate
class TenantTermsAcceptanceEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    lateinit var userId: String

    @Column(name = "tenant_id", nullable = false, columnDefinition = "uuid")
    lateinit var tenantId: String

    @Column(name = "accepted_revision", nullable = false)
    var acceptedRevision: Int = 0

    @Column(name = "operator_revision", nullable = false)
    var operatorRevision: Int = 0

    @Column(name = "accepted_at", nullable = false)
    lateinit var acceptedAt: Instant

    companion object : PanacheCompanionBase<TenantTermsAcceptanceEntity, String>
}

/**
 * A tenant's own SMTP + invite-template configuration (cr-email-smtp-transport, ADR 0048/0049/0050). One row per
 * tenant (PK == tenant_id). Nullable transport fields = "not configured" ⇒ this tenant cannot send, so its member
 * invites and password recovery are refused with `503` (ADR 0055 §2/§4); only the Server-Owner's tenant-bootstrap
 * invite still falls back to a copy-link (§3), where a PENDING tenant has no settings by construction. The SMTP
 * **password is never stored in plaintext**: [passwordCiphertext] + [passwordNonce] hold the `ConfigSecretSeal`
 * output (AES-256-GCM under the config-secret KEK, ADR 0050); null ciphertext = no password. [subjectTemplate] /
 * [bodyTemplate] are the owner-authored invite templates (null = veyq's bilingual EN+DE default), rendered over
 * the closed variable set `{{link}}`/`{{domain}}`/`{{expiry}}` (ADR 0049) — operator-visible config, not content.
 */
@Entity
@Table(name = "veyq_tenant_mail_settings")
@DynamicUpdate
class TenantMailSettingsEntity : PanacheEntityBase {
    @Id
    @Column(name = "tenant_id", columnDefinition = "uuid")
    lateinit var tenantId: String

    @Column(name = "host", length = 253)
    var host: String? = null

    @Column(name = "port")
    var port: Int? = null

    @Column(name = "from_address", length = 254)
    var fromAddress: String? = null

    @Column(name = "username", length = 254)
    var username: String? = null

    /** The SMTP password sealed at rest (ADR 0050): base64 AES-256-GCM ciphertext. Null = no password stored. */
    @Column(name = "password_ciphertext")
    var passwordCiphertext: String? = null

    /** The random nonce for [passwordCiphertext] (base64). Non-null iff the ciphertext is. */
    @Column(name = "password_nonce")
    var passwordNonce: String? = null

    /** The fingerprint of the config-secret KEK that sealed [passwordCiphertext] (ADR 0089 decision 4) — a hash,
     *  never the key. Non-null iff the ciphertext is; compared per value, so a re-sealed store secret cannot hide
     *  that this password is still sealed under a lost key. */
    @Column(name = "password_fingerprint", length = 64)
    var passwordFingerprint: String? = null

    @Column(name = "tls", nullable = false)
    var tls: Boolean = true

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false

    @Column(name = "subject_template", length = 200)
    var subjectTemplate: String? = null

    @Column(name = "body_template")
    var bodyTemplate: String? = null

    companion object : PanacheCompanionBase<TenantMailSettingsEntity, String>
}

/**
 * An owner invite (one tenant has a history of them). Only the SHA-256 token hash is stored — never the
 * token (ADR 0004 model). status: ACTIVE → SUPERSEDED on resend/reassign, → CONSUMED on acceptance
 * (Slice B), → EXPIRED past its TTL. Soft transitions; a scheduled purge removes terminal rows later.
 *
 * Several writers transition `status` (accept, revoke, reissue, the expiry sweep), each by a guarded bulk update
 * whose row count decides (developer-styleguide §12); a new writer of `status` joins them rather than saving the
 * entity. Like every entity this one is `@DynamicUpdate`, so a managed write of other columns cannot write back the
 * `status` it read — before that, a rotation's re-tag brought a revoked invite back to `ACTIVE`. A rotation's re-grant
 * is a column-scoped bulk update all the same (`TenantStore.carryInvites`).
 *
 * A MEMBER invite is a **placeholder member** (ADR 0111 decisions 1-2): it holds the invite key the owner signed and a
 * grant wrapped to it, the columns a membership's grant has. Every statement that moves an invite out of `ACTIVE` nulls
 * them in the same statement ([INVITE_KEY_DROPPED]), so an accepted, revoked, superseded or expired invite holds no key,
 * no grant and no anchor.
 */
@Entity
@Table(name = "veyq_tenant_invite")
@DynamicUpdate
class TenantInviteEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "tenant_id", nullable = false, columnDefinition = "uuid")
    lateinit var tenantId: String

    @Column(name = "owner_email", nullable = false, length = 254)
    lateinit var ownerEmail: String

    @Column(name = "token_hash", nullable = false, length = 64)
    lateinit var tokenHash: String

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    /** The role this invite grants on acceptance (cr-0077): OWNER cold-births the tenant, MEMBER joins it through the
     *  invite key below. Defaults OWNER so the owner-invite flows are unchanged. */
    @Column(name = "role", nullable = false, length = 16)
    var role: String = OWNER

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    /** A live MEMBER invite's key (ADR 0111 decision 1): its public bundle, the inviting owner's `invite-key` signature
     *  over it, and that owner with their identity key **as it signed** — so an owner who rotates later does not void
     *  the invite. Null on an owner invite and on every invite that is no longer ACTIVE. Never verified here, except
     *  the invite key's proof at accept (`TenantAcceptHandler`). */
    @Column(name = "invite_public_key")
    var invitePublicKey: String? = null

    @Column(name = "invite_key_signature")
    var inviteKeySignature: String? = null

    @Column(name = "signer_user_id", columnDefinition = "uuid")
    var signerUserId: String? = null

    @Column(name = "signer_identity_public_key")
    var signerIdentityPublicKey: String? = null

    /** The invite's grant (ADR 0111 decision 2): the tenant scope's epoch key at [grantedEpoch], wrapped to the invite
     *  key, and its granter — the inviting owner, then whoever's rotation re-wrapped it — with the granter's key as it
     *  signed (`scripts/validate_grant_writer_arity.py` holds every writer to set all three). */
    @Column(name = "wrapped_grant")
    var wrappedGrant: String? = null

    @Column(name = "granted_epoch")
    var grantedEpoch: Int? = null

    @Column(name = "grant_signature")
    var grantSignature: String? = null

    @Column(name = "granter_user_id", columnDefinition = "uuid")
    var granterUserId: String? = null

    @Column(name = "granter_identity_public_key")
    var granterIdentityPublicKey: String? = null

    /** The invite's anchor (ADR 0111 decision 19): the inviting owner's own grant of the tenant
     *  key at [anchorEpoch], wrapped to the invite key, with the owner's `grant` signature over it — what the invitee
     *  checks every carried grant against. Written at mint (equal to the grant), moved only by a tenant rotation the
     *  signer commits (`TenantStore.carryInvites`), re-signed by the signer's securing, and dropped with the key. */
    @Column(name = "anchor_wrapped_grant")
    var anchorWrappedGrant: String? = null

    @Column(name = "anchor_epoch")
    var anchorEpoch: Int? = null

    @Column(name = "anchor_grant_signature")
    var anchorGrantSignature: String? = null

    companion object : PanacheCompanionBase<TenantInviteEntity, String> {
        const val ACTIVE = "ACTIVE"
        const val SUPERSEDED = "SUPERSEDED"
        const val CONSUMED = "CONSUMED"
        const val EXPIRED = "EXPIRED"

        /** Invite roles (mirror `core/membership` MembershipEntity roles): the role the invite confers on acceptance. */
        const val OWNER = "OWNER"
        const val MEMBER = "MEMBER"

        /** The terminal states a scheduled purge may physically delete. */
        val TERMINAL = listOf(SUPERSEDED, CONSUMED, EXPIRED)

        /** The assignments every status transition out of `ACTIVE` carries in its own bulk update: the invite's key,
         *  grant and anchor go with its last chance to be redeemed. */
        const val INVITE_KEY_DROPPED =
            "invitePublicKey = null, inviteKeySignature = null, signerUserId = null, signerIdentityPublicKey = null, " +
                "wrappedGrant = null, grantedEpoch = null, grantSignature = null, granterUserId = null, " +
                "granterIdentityPublicKey = null, anchorWrappedGrant = null, anchorEpoch = null, anchorGrantSignature = null"
    }
}
