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
 * transaction (`MediaHandler.reserveAndOpen`, which every start — member, cover, link — goes through); presigning
 * happens after it commits, so the lock is never held across signatures.
 *
 * Two checks, each independent of the other (ADR 0073 §1, §4): the Server Owner's tenant quota ([tenantFits]) on
 * every start, and the tenant owner's per-link limit ([linkFits]) on a link start. The tenant total is one SQL text
 * ([tenantUsageSql]) asked for one tenant under the lock, or grouped for every tenant by the Server Owner's list —
 * never one query per tenant, and never a second SUM elsewhere.
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
     * Whether [items] fit [tenant]'s storage quota **together**: their declared sizes plus what the tenant already
     * holds. A tenant without a quota (the default, ADR 0073 §1) fits anything, and is not summed at all. Call under
     * [lockTenant], in the transaction that inserts their sessions.
     */
    fun tenantFits(tenant: TenantEntity, items: List<UploadDeclare>): Boolean {
        val quota = tenant.storageQuotaBytes ?: return true
        return tenantUsage(tenant.id) + items.sumOf { it.byteSize } <= quota
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

    /**
     * What tenant [tenantId] holds in the store (ADR 0073 §2): every blob in its scopes — originals, derivatives,
     * covers — plus every open session there. Reclaim tombstones carry no scope, so they are not counted.
     */
    fun tenantUsage(tenantId: String): Long =
        (em.createNativeQuery(tenantUsageSql("WHERE t.id = :key")).setParameter("key", tenantId).singleResult as Array<*>)
            .let { (it[1] as Number).toLong() }

    /** [tenantUsage] for every tenant at once, keyed by tenant id — one grouped query, for the Server Owner's list. */
    fun usageByTenant(): Map<String, Long> =
        em.createNativeQuery(tenantUsageSql("")).resultList.associate { row ->
            (row as Array<*>).let { it[0] as String to (it[1] as Number).toLong() }
        }

    /**
     * The one tenant total, per tenant row [where] admits: its blobs' sizes plus its open sessions', through
     * `veyq_scope.tenant_id`. The tenant filter sits on the outer row, and each term is a correlated subquery, so
     * the locked single-tenant check reads only that tenant's scopes (`ix_scope_tenant`, `ix_blob_scope`).
     */
    private fun tenantUsageSql(where: String): String =
        """
        SELECT t.id,
               COALESCE((SELECT SUM(b.byte_size) FROM veyq_blob b
                   JOIN veyq_scope sc ON sc.id = b.scope_id
                  WHERE sc.tenant_id = t.id), 0)
             + COALESCE((SELECT SUM(s.byte_size) FROM veyq_upload_session s
                   JOIN veyq_scope sc ON sc.id = s.scope_id
                  WHERE sc.tenant_id = t.id), 0)
          FROM veyq_tenant t $where
        """.trimIndent()

    private fun sum(sql: String, key: String): Long =
        (em.createNativeQuery(sql.trimIndent()).setParameter("key", key).singleResult as Number).toLong()
}
