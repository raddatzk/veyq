package com.veyq.memories.media

import com.veyq.core.blob.UploadDeclare
import com.veyq.core.quota.StorageUsage
import com.veyq.core.tenant.TenantEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager

/**
 * The one unit that answers "does this upload fit" (ADR 0073 §3–§5). The stored total itself is summed in
 * `core.quota.StorageUsage` — the only place it is — and this class asks it; what stays here is what reads a media
 * table or takes an upload declaration ([linkUsage], [linkFits], [tenantFits]).
 *
 * A check is only a reservation inside the transaction that also inserts the sessions, under [lockTenant] — without
 * the lock, N parallel starts each see the same remainder and together admit N times it. The caller owns that
 * transaction (`MediaHandler.reserveAndOpen`, which every start — member, cover, link — goes through); presigning
 * happens after it commits, so the lock is never held across signatures.
 *
 * Two checks, each independent of the other (ADR 0073 §1, §4): the Server Owner's tenant quota ([tenantFits]) on
 * every start, and the tenant owner's per-link limit ([linkFits]) on a link start.
 */
@ApplicationScoped
class StorageQuota(private val em: EntityManager, private val usage: StorageUsage) {

    /** [StorageUsage.lockTenant], kept here so the upload path has one quota entry point. */
    fun lockTenant(tenantId: String): TenantEntity? = usage.lockTenant(tenantId)

    /**
     * Whether [items] fit the link [shareId] **together** under [tenant]'s per-link limit: their declared sizes plus
     * what the link already holds. Call under [lockTenant], in the transaction that inserts their sessions.
     */
    fun linkFits(tenant: TenantEntity, shareId: String, items: List<UploadDeclare>): Boolean =
        linkUsage(shareId) + items.sumOf { it.byteSize } <= tenant.shareLinkLimitBytes

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
     * What link [shareId] holds (ADR 0073 §4): the blobs of every medium its identities contributed — the medium's
     * own blob and each derivative's — plus every open session one of its identities opened. A medium a member
     * uploaded into the same Memory is not the link's, and neither is another link's.
     */
    fun linkUsage(shareId: String): Long = sum(
        """
        SELECT COALESCE((SELECT SUM(b.byte_size) FROM veyq_blob b WHERE b.id IN (
                   SELECT m.blob_id FROM veyq_media m
                     JOIN veyq_share_identity i ON i.id = m.share_identity_id
                    WHERE i.share_id = :key
                   UNION
                   SELECT d.blob_id FROM veyq_media_derivative d
                     JOIN veyq_media m ON m.id = d.media_id
                     JOIN veyq_share_identity i ON i.id = m.share_identity_id
                    WHERE i.share_id = :key)), 0)
             + COALESCE((SELECT SUM(s.byte_size) FROM veyq_upload_session s
                   JOIN veyq_share_identity i ON i.id = s.opener_share_identity_id
                  WHERE i.share_id = :key), 0)
        """,
        shareId,
    )

    private fun sum(sql: String, key: String): Long =
        (em.createNativeQuery(sql.trimIndent()).setParameter("key", key).singleResult as Number).toLong()
}
