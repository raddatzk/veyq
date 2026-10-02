package com.veyq.app.features.admin.tenant

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * A member's acceptance of a tenant's terms (cr-0114, ADR 0035). Keyed logically by (user_id, tenant_id) — a
 * surrogate id + a UNIQUE — it records the highest terms version the member accepted. **Coarse operational
 * metadata** (like membership), never content. When `acceptedVersion < tenant.termsVersion` the member accept
 * gate re-fires (uc.account.accept-terms); accepting upserts this row to the current version.
 */
@Entity
@Table(name = "veyq_tenant_terms_acceptance")
@DynamicUpdate
class TenantTermsAcceptanceEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "user_id", nullable = false, length = 36)
    lateinit var userId: String

    @Column(name = "tenant_id", nullable = false, length = 36)
    lateinit var tenantId: String

    @Column(name = "accepted_version", nullable = false)
    var acceptedVersion: Int = 0

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
    @Column(name = "tenant_id", length = 36)
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
 * `status` it read — before that, a rotation's re-tag brought a revoked invite back to `ACTIVE`. The re-tag is a
 * column-scoped bulk update all the same (`TenantStore.carryInvites`).
 */
@Entity
@Table(name = "veyq_tenant_invite")
@DynamicUpdate
class TenantInviteEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "tenant_id", nullable = false, length = 36)
    lateinit var tenantId: String

    @Column(name = "owner_email", nullable = false, length = 254)
    lateinit var ownerEmail: String

    @Column(name = "token_hash", nullable = false, length = 64)
    lateinit var tokenHash: String

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    /** The role this invite grants on acceptance (cr-0077): OWNER cold-births the tenant, MEMBER joins it
     *  grant-less. Defaults OWNER so pre-cr-0077 rows + the owner-invite flows are unchanged. */
    @Column(name = "role", nullable = false, length = 16)
    var role: String = OWNER

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    /** A MEMBER invite's admission (ADR 0020 move 6) — tagged by the owner's client under the tenant scope's epoch
     *  key, kept by a reissue, and copied onto the membership its acceptance creates. Null on an owner invite (the
     *  tenant has no key yet). Never verified here. */
    @Column(name = "admission_id", length = 36)
    var admissionId: String? = null

    @Column(name = "admission_epoch")
    var admissionEpoch: Int? = null

    @Column(name = "admission_tag")
    var admissionTag: String? = null

    companion object : PanacheCompanionBase<TenantInviteEntity, String> {
        const val ACTIVE = "ACTIVE"
        const val SUPERSEDED = "SUPERSEDED"
        const val CONSUMED = "CONSUMED"
        const val EXPIRED = "EXPIRED"

        /** Invite roles (mirror `shared/membership` MembershipEntity roles): the role the invite confers on acceptance. */
        const val OWNER = "OWNER"
        const val MEMBER = "MEMBER"

        /** The terminal states a scheduled purge may physically delete. */
        val TERMINAL = listOf(SUPERSEDED, CONSUMED, EXPIRED)
    }
}
