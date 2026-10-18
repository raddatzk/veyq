package me.raddatz.veyq.core.tenant

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * A tenant: a domain-scoped container resolved by Host. Operational metadata only — name, domain and the
 * intended owner email are cleartext; there is no content key here (the cold TENANT-scope birth that
 * activates a tenant is Slice B / cr-0010). PENDING until its owner accepts, then ACTIVE — and reversibly
 * SUSPENDED while a Server Owner has it offline (ADR 0066): an availability-only state that no key, grant,
 * content row or session takes part in. The scope-access gate refuses it on every operation acting on the tenant,
 * from any domain (uc.spaces.scope-access); `TenantSuspensionFilter` additionally refuses the tenant's own domain.
 *
 * A shared, **query-only** entity (ADR 0007 — the `MembershipEntity` precedent): it lives in the kernel so the gate
 * and the `Host` machinery beside it can read a tenant's status and domain without a kernel → slice import.
 * Every mutation stays in the admin feature's `TenantStore`.
 */
@Entity
@Table(name = "veyq_tenant")
@DynamicUpdate
class TenantEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "name", nullable = false, length = 100)
    lateinit var name: String

    @Column(name = "domain", nullable = false, length = 253)
    lateinit var domain: String

    @Column(name = "owner_email", nullable = false, length = 254)
    lateinit var ownerEmail: String

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    /** cr-0114 (ADR 0035): the space owner's per-locale terms markdown, serialized as a locale→markdown JSON map
     *  (opaque TEXT). Null until the owner first publishes. Public operational content, never a key. */
    @Column(name = "terms_markdown_by_locale")
    var termsMarkdownByLocale: String? = null

    /** cr-0114: the terms revision, bumped only on a **normalized** change (idempotent re-saves don't). 0 = no
     *  terms set (the member accept-gate never fires); a member whose accepted revision is below this re-accepts. */
    @Column(name = "terms_revision", nullable = false)
    var termsRevision: Int = 0

    /** The most any one READ_WRITE or WRITE_ONLY link of this tenant stores (ADR 0073 §4), set by the tenant owner.
     *  Read under the tenant-row lock a link start takes, so a start and a change of the limit serialize. */
    @Column(name = "share_link_limit_bytes", nullable = false)
    var shareLinkLimitBytes: Long = DEFAULT_SHARE_LINK_LIMIT_BYTES

    /** The most this tenant may hold in the store (ADR 0073 §1), set only by the Server Owner; null = no quota, the
     *  default. Read under the same tenant-row lock every upload start takes, so a start and a change serialize. */
    @Column(name = "storage_quota_bytes")
    var storageQuotaBytes: Long? = null

    /** When the purge erases a tenant the Server Owner deleted (ADR 0101 decision 10): set with `PENDING_DELETION`,
     *  cleared by a stop. Read by `TenantPurge`'s work list and the Server Owner's tenant list. */
    @Column(name = "deletion_due_at")
    var deletionDueAt: Instant? = null

    companion object : PanacheCompanionBase<TenantEntity, String> {
        /** 10 GiB — enough for an event's uploads, too little for a leaked link to eat a disk (ADR 0073 §4). The
         *  `share_link_limit_bytes` column default is the same number. */
        const val DEFAULT_SHARE_LINK_LIMIT_BYTES: Long = 10L * 1024 * 1024 * 1024

        const val PENDING = "PENDING"
        const val ACTIVE = "ACTIVE"

        /** Reversibly offline (ADR 0066). `status` is the *only* column either transition touches. */
        const val SUSPENDED = "SUSPENDED"

        /** Deleted by the Server Owner and offline exactly as [SUSPENDED] until `TenantPurge` erases it (ADR 0101
         *  decision 10); a stop returns it to [SUSPENDED]. */
        const val PENDING_DELETION = "PENDING_DELETION"

        /** The statuses every gate refuses as a suspension: offline, its own domain included (ADR 0066, ADR 0101). */
        val OFFLINE: List<String> = listOf(SUSPENDED, PENDING_DELETION)

        fun isOffline(status: String?): Boolean = status in OFFLINE
    }
}
