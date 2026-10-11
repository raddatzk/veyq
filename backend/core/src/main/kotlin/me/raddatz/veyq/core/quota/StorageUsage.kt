package me.raddatz.veyq.core.quota

import me.raddatz.veyq.core.tenant.TenantEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType

/**
 * What a tenant holds in the store (ADR 0073 §2) and the lock that serializes reservations against it — the core
 * half of the storage quota, and the only place a stored total is summed. Usage is **derived** at the check, never
 * counted: six paths delete blobs or sessions, and a counter each of them must decrement correctly is one that
 * drifts. Sizes are the declared `byte_size`, which `complete` holds to the stored size chunk by chunk (ADR 0013
 * §3). Nothing here filters on `deleted_at`: trash is still in the store, so it counts until it is purged
 * (ADR 0073 §2). It reads only blobs, sessions, scopes and tenants — no application table — so any application
 * that stores blobs is counted without this class knowing it exists. The checks against a declaration (the tenant
 * quota and the per-link limit) are [StorageQuota]'s, which delegates here.
 *
 * The tenant total is one SQL text ([tenantUsageSql]) asked for one tenant under [lockTenant], or grouped for every
 * tenant by the Server Owner's list — never one query per tenant, and never a second SUM elsewhere.
 */
@ApplicationScoped
class StorageUsage(private val em: EntityManager) {

    /** The tenant row, locked for the rest of the caller's transaction (`SELECT … FOR UPDATE`), or null if absent. */
    fun lockTenant(tenantId: String): TenantEntity? = TenantEntity.findById(tenantId, LockModeType.PESSIMISTIC_WRITE)

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
     * The id is cast to text: a native `SELECT` of a `UUID` column hands back `byte[]` on H2 (measured), not the
     * `String` the callers read, and the cast reads the same on both engines (inbox #387).
     *
     * The one tenant total, per tenant row [where] admits: its blobs' sizes plus its open sessions', through
     * `veyq_scope.tenant_id`. The tenant filter sits on the outer row, and each term is a correlated subquery, so
     * the locked single-tenant check reads only that tenant's scopes (`ix_scope_tenant`, `ix_blob_scope`).
     */
    private fun tenantUsageSql(where: String): String =
        """
        SELECT CAST(t.id AS CHAR(36)),
               COALESCE((SELECT SUM(b.byte_size) FROM veyq_blob b
                   JOIN veyq_scope sc ON sc.id = b.scope_id
                  WHERE sc.tenant_id = t.id), 0)
             + COALESCE((SELECT SUM(s.byte_size) FROM veyq_upload_session s
                   JOIN veyq_scope sc ON sc.id = s.scope_id
                  WHERE sc.tenant_id = t.id), 0)
          FROM veyq_tenant t $where
        """.trimIndent()
}
