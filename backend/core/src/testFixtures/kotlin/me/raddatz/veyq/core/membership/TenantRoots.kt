package me.raddatz.veyq.core.membership

import me.raddatz.veyq.core.scope.ScopeEntity
import java.time.Instant
import java.util.UUID

/**
 * The tenant half of a seeded member. A real member of a personal space or a group always belongs to its tenant —
 * an accepted, granted membership on the tenant's root scope (`scopeId == tenantId`) — and the scope-access gate
 * requires exactly that of a writer (uc.spaces.scope-access step 3). A fixture that seeds only the space's row
 * models a member removed from the tenant, which answers every write with the gate's 404.
 */
object TenantRoots {

    /** Make [userId] an accepted root member of [tenantId], creating the root scope if missing. Call inside the
     *  fixture's own transaction; idempotent. */
    fun join(userId: String, tenantId: String, role: String = MembershipEntity.MEMBER) {
        if (ScopeEntity.findById(tenantId) == null) {
            ScopeEntity().apply {
                id = tenantId; this.tenantId = tenantId; kind = ScopeEntity.TENANT; createdAt = Instant.now()
            }.persist()
        }
        if (MembershipEntity.count("userId = ?1 and scopeId = ?2", userId, tenantId) == 0L) {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                scopeId = tenantId; this.role = role; wrappedGrant = "AQ=="; createdAt = Instant.now()
            }.persist()
        }
    }
}
