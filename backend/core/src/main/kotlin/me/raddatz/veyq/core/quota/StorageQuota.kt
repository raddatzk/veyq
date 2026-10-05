package com.veyq.core.quota

import com.veyq.core.blob.UploadDeclare
import com.veyq.core.tenant.TenantEntity
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager

/**
 * The one unit that answers "does this upload fit" (ADR 0073 §3–§5). The stored total itself is summed in
 * [StorageUsage] — the only place it is — and this class asks it; what stays here is what takes an upload
 * declaration ([tenantFits], [linkFits]) and the per-link usage ([linkUsage]).
 *
 * A check is only a reservation inside the transaction that also inserts the sessions, under [lockTenant] — without
 * the lock, N parallel starts each see the same remainder and together admit N times it. The caller owns that
 * transaction (`BlobUploadHandler.reserveAndOpen`, which every start — member, cover, link — goes through); presigning
 * happens after it commits, so the lock is never held across signatures.
 *
 * Two checks, each independent of the other (ADR 0073 §1, §4): the Server Owner's tenant quota ([tenantFits]) on
 * every start, and the tenant owner's per-link limit ([linkFits]) on a link start. A link's usage has two terms: the
 * sessions its identities hold open — core's tables, summed here — and the blobs they contributed, which only the
 * application that owns the link's root kind can find ([UploadAdmission]).
 */
@ApplicationScoped
class StorageQuota(
    private val em: EntityManager,
    private val usage: StorageUsage,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @All private val admissions: MutableList<UploadAdmission>,
) {

    /** [StorageUsage.lockTenant], kept here so the upload path has one quota entry point. */
    fun lockTenant(tenantId: String): TenantEntity? = usage.lockTenant(tenantId)

    /**
     * The one [UploadAdmission] that measures a link rooted at a node of [rootKind], or null — none, or more than
     * one, which would leave the choice to CDI bean order. Null **refuses** the link start (fail closed, like
     * `SharingHandler.writesFor`): a link whose usage nobody can measure is never admitted. Asked by the guest gate
     * before the start and again by [linkUsage] inside the reservation; `UploadAdmissionPartitionSliceTest` pins it.
     */
    fun admissionFor(rootKind: String): UploadAdmission? = admissions.filter { it.supports(rootKind) }.singleOrNull()

    /**
     * Whether [items] fit the link [shareId] **together** under [tenant]'s per-link limit: their declared sizes plus
     * what the link already holds. Call under [lockTenant], in the transaction that inserts their sessions. A link
     * whose usage no single [UploadAdmission] measures fits nothing.
     */
    fun linkFits(tenant: TenantEntity, shareId: String, items: List<UploadDeclare>): Boolean {
        val held = linkUsage(shareId) ?: return false
        return held + items.sumOf { it.byteSize } <= tenant.shareLinkLimitBytes
    }

    /**
     * Whether [items] fit [tenant]'s storage quota **together**: their declared sizes plus what the tenant already
     * holds. A tenant without a quota (the default, ADR 0073 §1) fits anything, and is not summed at all. Call under
     * [lockTenant], in the transaction that inserts their sessions.
     */
    fun tenantFits(tenant: TenantEntity, items: List<UploadDeclare>): Boolean {
        val quota = tenant.storageQuotaBytes ?: return true
        return usage.tenantUsage(tenant.id) + items.sumOf { it.byteSize } <= quota
    }

    /**
     * What link [shareId] holds (ADR 0073 §4): the blobs its identities contributed, as the root kind's
     * [UploadAdmission] measures them, plus every open session one of its identities opened. A blob a member
     * uploaded into the same root is not the link's, and neither is another link's. Null when no single admission
     * measures the link's root kind ([admissionFor]) — or the link has no root.
     */
    fun linkUsage(shareId: String): Long? {
        val admission = rootKind(shareId)?.let(::admissionFor) ?: return null
        // Two statements where there was one, so the sessions are read FIRST: a completion committing between the
        // two turns a session into a blob, and read in this order it is counted twice (refused a little early),
        // never zero times (admitted over the limit) — each statement reads what is committed when it runs.
        val open = sum(
            """
            SELECT COALESCE((SELECT SUM(s.byte_size) FROM veyq_upload_session s
                   JOIN veyq_share_identity i ON i.id = s.opener_share_identity_id
                  WHERE i.share_id = :key), 0)
            """,
            shareId,
        )
        return open + admission.linkBlobUsage(shareId)
    }

    /** The kind of link [shareId]'s root node — the key [admissionFor] dispatches on, as the guest gate resolves it. */
    private fun rootKind(shareId: String): String? =
        em.createNativeQuery("SELECT n.kind FROM veyq_share s JOIN veyq_node n ON n.id = s.node_id WHERE s.id = :key")
            .setParameter("key", shareId).resultList.singleOrNull() as String?

    private fun sum(sql: String, key: String): Long =
        (em.createNativeQuery(sql.trimIndent()).setParameter("key", key).singleResult as Number).toLong()
}
