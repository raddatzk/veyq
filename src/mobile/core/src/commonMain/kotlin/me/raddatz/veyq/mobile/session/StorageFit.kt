package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.models.TenantStorageFitRequest
import me.raddatz.veyq.mobile.export.NodeExport

/**
 * Whether more bytes fit the tenant's storage (the web's `features/tenant/tenantStorage.ts` `tenantStorageFits`): asked
 * before a folder upload and a copy, with the plaintext bytes about to go up. Advisory — the server answers yes or no,
 * rate-limited, and the upload's own `quota_exceeded` stays the decision — so a check that does not answer (null) never
 * blocks.
 */
object StorageFit {
    @Throws(Exception::class)
    suspend fun fits(tenant: TenantSession, bytes: Long): Boolean? {
        if (bytes <= 0) return true
        return runCatching {
            tenant.account.call { TenantsApi(tenant.base, tenant.account.http).checkTenantStorageFit(tenant.tenant.tenantId, TenantStorageFitRequest(bytes)) }.fits
        }.getOrNull()
    }

    /**
     * The bytes a copy of node [nodeId] of [scopeId] stores again (the web's `prepareCopy` `planned.bytes`): its walk's
     * payloads, as [kinds] export them; null where the node is not in the tree.
     */
    @Throws(Exception::class)
    suspend fun bytesOfCopy(tenant: TenantSession, scopeId: String, nodeId: String, kinds: NodeExport.Kinds): Long? {
        val walked = NodeExport.walk(tenant, scopeId, nodeId, kinds) ?: return null
        fun sum(e: NodeExport.Entry): Long = (e.content?.payloads?.sumOf { it.size } ?: 0L) + e.children.sumOf { sum(it) }
        return sum(walked.root)
    }

    /** The bytes a copy of [nodeId] would store when the tenant has no room for them; null where it fits or is not known. */
    @Throws(Exception::class)
    suspend fun noRoomForCopy(tenant: TenantSession, scopeId: String, nodeId: String, kinds: NodeExport.Kinds): Long? {
        val bytes = runCatching { bytesOfCopy(tenant, scopeId, nodeId, kinds) }.getOrNull() ?: return null
        return bytes.takeIf { fits(tenant, it) == false }
    }
}
