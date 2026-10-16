package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.scope.ScopeEntity
import jakarta.enterprise.context.ApplicationScoped

/**
 * Computes the **active** capability set (ADR 0032, ADR 0088), at the instance level and for a tenant. It is read in
 * places that together make switching off honest:
 *  - the **setup manifest** (pre-auth, the instance level alone), and the **tenant's set** a member reads after sign-in
 *    (`GET /api/tenants/{tenantId}/capabilities`) — UX hints the client soft-gates on;
 *  - each feature's **gate** (the hard `422 capability_disabled` — the server is the authority, so a stale or
 *    ignoring client still cannot use a switched-off capability).
 *
 * **The AND along the path, at both levels** (ADR 0088 decision 2): an id is active only if it and every ancestor
 * entry are on at the instance level and, for a tenant, at that tenant's level too. An ancestor is each dotted prefix
 * that is itself an entry this build knows, so `core` (no entry) is never one, and switching off
 * `core.public-shares` switches off `.contribute` and `.drop-box` with it. A tenant can therefore only narrow what
 * the Server Owner allows. The server computes this; a client reads leaf ids and never ANDs them itself (decision 3).
 *
 * **Every gate names its tenant** ([isActive]'s `tenantId` has no default): the tenant of the scope the request acts
 * in, so a switch reaches that tenant's content and no other's. `null` asks the instance level alone, which is the
 * whole answer for the pre-auth manifest and for an entry outside [CapabilityIds.TENANT_SWITCHABLE] (a tenant row for
 * one is never stored, and would be ignored here).
 *
 * [switches] and [tenantSwitches] are asked on every call and the registry keeps nothing, so a switch flipped through
 * the Admin API or by a tenant owner acts without a restart; what is cached, and how a write reaches it, is
 * [StoredCapabilitySwitches]' and [StoredTenantCapabilitySwitches]'. It holds **no per-user state**: the instance set is
 * identical for every caller, a tenant's for every member of that tenant (operator-blind-safe).
 */
@ApplicationScoped
class CapabilityRegistry(
    private val switches: CapabilitySwitches,
    private val tenantSwitches: TenantCapabilitySwitches,
) {
    /** The instance's active capability ids, in [CapabilityIds.ADVERTISED] order — the pre-auth manifest. */
    val active: List<String>
        get() = active(null)

    /** The ids active for [tenantId] (the instance level alone when null), in [CapabilityIds.ADVERTISED] order. */
    fun active(tenantId: String?): List<String> =
        offSets(tenantId).let { (off, tenantOff) -> CapabilityIds.ADVERTISED.filter { isActive(it, off, tenantOff) } }

    /** True when [id] is advertised by this build and neither it nor any ancestor entry is switched off, at the
     *  instance level and, when [tenantId] is given, at that tenant's level — the gate. */
    fun isActive(id: String, tenantId: String?): Boolean =
        offSets(tenantId).let { (off, tenantOff) -> isActive(id, off, tenantOff) }

    /** [isActive] for the tenant [scopeId] lives in — the tenant of the content a gate acts on (`veyq_scope.tenant_id`,
     *  never null for a scope that exists; a scope that does not exist asks the instance level alone, and its caller
     *  refuses it on its own terms). */
    fun isActiveInScope(id: String, scopeId: String): Boolean = isActive(id, ScopeEntity.findById(scopeId)?.tenantId)

    /**
     * Every entry in [CapabilityIds.ADVERTISED] order with its own switch and its effective state, both from ONE read of
     * [switches] — the register as the Server Owner reads it. A child of a switched-off parent is `off = false`,
     * `effective = false`: its own switch survives the parent's.
     */
    fun register(): List<CapabilityState> =
        switches.switchedOff().let { off -> CapabilityIds.ADVERTISED.map { CapabilityState(it, it in off, isActive(it, off, emptySet())) } }

    /**
     * The entries a tenant owner may switch ([CapabilityIds.TENANT_SWITCHABLE]), in catalog order, as [tenantId]'s owner
     * reads them, from ONE read of each level: the tenant's own switch, whether the instance level has the entry off
     * (the Server Owner switched it or an ancestor off), and the AND of both along the path.
     */
    fun tenantRegister(tenantId: String): List<TenantCapabilityState> {
        val (off, tenantOff) = offSets(tenantId)
        return CapabilityIds.ADVERTISED.filter { it in CapabilityIds.TENANT_SWITCHABLE }.map {
            TenantCapabilityState(
                id = it,
                off = it in tenantOff,
                serverOff = !isActive(it, off, emptySet()),
                effective = isActive(it, off, tenantOff),
            )
        }
    }

    private fun offSets(tenantId: String?): Pair<Set<String>, Set<String>> =
        switches.switchedOff() to (tenantId?.let { tenantSwitches.switchedOff(it) } ?: emptySet())

    private fun isActive(id: String, off: Set<String>, tenantOff: Set<String>): Boolean =
        id in CapabilityIds.ADVERTISED &&
            selfAndAncestors(id).none { it in off || (it in tenantOff && it in CapabilityIds.TENANT_SWITCHABLE) }

    /** [id] and each of its dotted prefixes that is an entry: `a.b.c` → `a.b.c`, `a.b` (and `a` if it were one). */
    private fun selfAndAncestors(id: String): List<String> {
        val segments = id.split('.')
        return (segments.size downTo 1).map { segments.take(it).joinToString(".") }
            .filter { it in CapabilityIds.ADVERTISED }
    }
}

/** One register entry's state: [off] is its own switch, [effective] the AND along its path (what the gates follow). */
data class CapabilityState(val id: String, val off: Boolean, val effective: Boolean)

/** One tenant-switchable entry as a tenant owner reads it: [off] the tenant's own switch, [serverOff] the instance level's
 *  AND along the path, [effective] the AND of both levels (what the tenant's gates follow). */
data class TenantCapabilityState(val id: String, val off: Boolean, val serverOff: Boolean, val effective: Boolean)
