package com.veyq.core.tenant

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * A tenant: a domain-scoped container resolved by Host. Operational metadata only — name, domain and the
 * intended owner email are cleartext; there is no content key here (the cold TENANT-scope birth that
 * activates a tenant is Slice B / cr-0010). PENDING until its owner accepts, then ACTIVE — and reversibly
 * SUSPENDED while a Server Owner has it offline (ADR 0066): an availability-only state that no key, grant,
 * content row or session takes part in. The scope-access gate refuses it on every operation acting on the tenant,
 * from any domain (uc.spaces.scope-access); `TenantSuspensionFilter` additionally refuses the tenant's own domain.
 *
 * A shared, **query-only** entity (ADR 0007 — the `MembershipEntity` precedent): it lives in `shared/` so the gate
 * and the `Host` machinery beside it can read a tenant's status and domain without a `shared → features` import.
 * Every mutation stays in the admin feature's `TenantStore`.
 */
@Entity
@Table(name = "veyq_tenant")
@DynamicUpdate
class TenantEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "name", nullable = false, length = 100)
    lateinit var name: String

    @Column(name = "domain", nullable = false, length = 253)
    lateinit var domain: String

    @Column(name = "owner_email", nullable = false, length = 254)
    lateinit var ownerEmail: String

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    /** cr-0114 (ADR 0035): the operator's per-locale terms markdown, serialized as a locale→markdown JSON map
     *  (opaque TEXT). Null until the owner first publishes. Public operational content, never a key. */
    @Column(name = "terms_markdown_by_locale")
    var termsMarkdownByLocale: String? = null

    /** cr-0114: the terms version, bumped only on a **normalized** change (idempotent re-saves don't). 0 = no
     *  terms set (the member accept-gate never fires); a member whose accepted version is below this re-accepts. */
    @Column(name = "terms_version", nullable = false)
    var termsVersion: Int = 0

    /** The most any one CONTRIBUTOR or UPLOADER link of this tenant stores (ADR 0073 §4), set by the tenant owner.
     *  Read under the tenant-row lock a link start takes, so a start and a change of the limit serialize. */
    @Column(name = "share_link_limit_bytes", nullable = false)
    var shareLinkLimitBytes: Long = DEFAULT_SHARE_LINK_LIMIT_BYTES

    /** The most this tenant may hold in the store (ADR 0073 §1), set only by the Server Owner; null = no quota, the
     *  default. Read under the same tenant-row lock every upload start takes, so a start and a change serialize. */
    @Column(name = "storage_quota_bytes")
    var storageQuotaBytes: Long? = null

    companion object : PanacheCompanionBase<TenantEntity, String> {
        /** 10 GiB — enough for an event's photos, too little for a leaked link to eat a disk (ADR 0073 §4). The
         *  `share_link_limit_bytes` column default is the same number. */
        const val DEFAULT_SHARE_LINK_LIMIT_BYTES: Long = 10L * 1024 * 1024 * 1024

        const val PENDING = "PENDING"
        const val ACTIVE = "ACTIVE"

        /** Reversibly offline (ADR 0066). `status` is the *only* column either transition touches. */
        const val SUSPENDED = "SUSPENDED"
    }
}
