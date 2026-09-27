package com.veyq.app.features.media

import com.veyq.app.shared.tenant.TenantEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType

/**
 * The one unit that answers "does this upload fit" (ADR 0073 §3–§5), and the only place a stored total is summed.
 * Usage is **derived** at the check, never counted: six paths delete blobs or sessions, and a counter each of them
 * must decrement correctly is one that drifts. Sizes are the declared `byte_size`, which `complete` holds to the
 * stored size chunk by chunk (ADR 0013 §3). Nothing here filters on `deleted_at`: trash is still in the store, so
 * it counts until it is purged (ADR 0073 §2).
 *
 * A check is only a reservation inside the transaction that also inserts the sessions, under [lockTenant] — without
 * the lock, N parallel starts each see the same remainder and together admit N times it. The caller owns that
 * transaction (`MediaHandler`'s link start); presigning happens after it commits, so the lock is never held across
 * signatures.
 *
 * Built so far: the per-link limit (uc.sharing.share-link-storage-limit) and the tenant total its owner reads. The
 * tenant quota (uc.admin.tenant-storage-quota) adds its check here, beside [linkFits], and routes the member start
 * through the same locked transaction — it must not write a second SUM.
 */
@ApplicationScoped
class StorageQuota(private val em: EntityManager) {

    /** The tenant row, locked for the rest of the caller's transaction (`SELECT … FOR UPDATE`), or null if absent. */
    fun lockTenant(tenantId: String): TenantEntity? = TenantEntity.findById(tenantId, LockModeType.PESSIMISTIC_WRITE)

    /**
     * Whether [items] fit the link [shareId] **together** under [tenant]'s per-link limit: their declared sizes plus
     * what the link already holds. Call under [lockTenant], in the transaction that inserts their sessions.
     */
    fun linkFits(tenant: TenantEntity, shareId: String, items: List<UploadDeclare>): Boolean =
        linkUsage(shareId) + items.sumOf { it.byteSize } <= tenant.shareLinkLimitBytes

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

    /**
     * What tenant [tenantId] holds in the store (ADR 0073 §2): every blob in its scopes — originals, derivatives,
     * covers — plus every open session there. Reclaim tombstones carry no scope, so they are not counted.
     */
    fun tenantUsage(tenantId: String): Long = sum(
        """
        SELECT COALESCE((SELECT SUM(b.byte_size) FROM veyq_blob b
                   JOIN veyq_scope sc ON sc.id = b.scope_id
                  WHERE sc.tenant_id = :key), 0)
             + COALESCE((SELECT SUM(s.byte_size) FROM veyq_upload_session s
                   JOIN veyq_scope sc ON sc.id = s.scope_id
                  WHERE sc.tenant_id = :key), 0)
        """,
        tenantId,
    )

    private fun sum(sql: String, key: String): Long =
        (em.createNativeQuery(sql.trimIndent()).setParameter("key", key).singleResult as Number).toLong()
}
