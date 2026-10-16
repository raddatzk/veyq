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
    private val requirementValues: CapabilityRequirementValues,
    private val services: ExternalServiceAddresses,
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
     * Each switch that keeps [id] off for [tenantId] (the instance level alone when null): every entry on its path that
     * is off, once per level that has it off, the root first and the instance level before the tenant's — empty exactly
     * when an advertised [id] is active. [isActive] is this list being empty, so the reason a member's share list
     * names (inbox #457) and the gate cannot disagree.
     */
    fun offSwitches(id: String, tenantId: String?): List<OffSwitch> =
        offSets(tenantId).let { (off, tenantOff) -> offSwitches(id, off, tenantOff) }

    /** [offSwitches] for the tenant [scopeId] lives in, as [isActiveInScope] asks it. */
    fun offSwitchesInScope(id: String, scopeId: String): List<OffSwitch> = offSwitches(id, ScopeEntity.findById(scopeId)?.tenantId)

    /**
     * Every entry in [CapabilityIds.ADVERTISED] order with its own switch and its effective state, both from ONE read of
     * [switches] — the register as the Server Owner reads it. A child of a switched-off parent is `off = false`,
     * `effective = false`: its own switch survives the parent's.
     */
    fun register(): List<CapabilityState> {
        val own = switches.switchedOff()
        val off = offSets(null).first
        return CapabilityIds.ADVERTISED.map { CapabilityState(it, it in own, isActive(it, off, emptySet())) }
    }

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

    /**
     * The requirements in effect (ADR 0088 decision 8), by name: for [tenantId] the strictest of the instance's value and
     * the tenant's — a flag (`1`) set at either level, the smaller number of days — and for null the instance's alone.
     * A tenant value of a requirement whose entry is not tenant-switchable is never stored, and would be ignored here.
     */
    fun requirements(tenantId: String?): Map<String, Int> {
        val instance = requirementValues.instance()
        val tenant = tenantId?.let { requirementValues.tenant(it) } ?: emptyMap()
        return CapabilityRequirements.ALL.mapNotNull { def ->
            strictest(def, instance[def.name], tenant[def.name].takeIf { def.capabilityId in CapabilityIds.TENANT_SWITCHABLE })
                ?.let { def.name to it }
        }.toMap()
    }

    /** [requirements] for the tenant [scopeId] lives in, as [isActiveInScope] asks it. */
    fun requirementsInScope(scopeId: String): Map<String, Int> = requirements(ScopeEntity.findById(scopeId)?.tenantId)

    /** [capabilityId]'s requirements as the Server Owner reads them: each with the instance's value. */
    fun instanceRequirements(capabilityId: String): List<RequirementState> {
        val instance = requirementValues.instance()
        return CapabilityRequirements.ALL.filter { it.capabilityId == capabilityId }.map { RequirementState(it, instance[it.name]) }
    }

    /** [capabilityId]'s requirements as [tenantId]'s owner reads them: the tenant's value, the instance's, and the one
     *  in effect. */
    fun tenantRequirements(tenantId: String, capabilityId: String): List<TenantRequirementState> {
        val instance = requirementValues.instance()
        val tenant = requirementValues.tenant(tenantId)
        return CapabilityRequirements.ALL.filter { it.capabilityId == capabilityId }.map {
            TenantRequirementState(it, tenant[it.name], instance[it.name], strictest(it, instance[it.name], tenant[it.name]))
        }
    }

    private fun strictest(def: CapabilityRequirement, a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        def.kind == RequirementKind.FLAG -> 1
        else -> minOf(a, b)
    }

    /** The switched-off sets of both levels; an external service with no configured address counts as switched off at
     *  the instance level (ADR 0088 decision 7): it is not served, and nothing a switch does brings it back. */
    private fun offSets(tenantId: String?): Pair<Set<String>, Set<String>> {
        val configured = services.configured().keys
        val unconfigured = CapabilityIds.EXTERNAL_SERVICES - configured
        return (switches.switchedOff() + unconfigured) to (tenantId?.let { tenantSwitches.switchedOff(it) } ?: emptySet())
    }

    private fun isActive(id: String, off: Set<String>, tenantOff: Set<String>): Boolean =
        id in CapabilityIds.ADVERTISED && offSwitches(id, off, tenantOff).isEmpty()

    /** The one rule behind [isActive] and [offSwitches]: a tenant's switch counts only on an entry it may switch. */
    private fun offSwitches(id: String, off: Set<String>, tenantOff: Set<String>): List<OffSwitch> =
        selfAndAncestors(id).reversed().flatMap { entry ->
            listOfNotNull(
                OffSwitch(SwitchLevel.INSTANCE, entry).takeIf { entry in off },
                OffSwitch(SwitchLevel.TENANT, entry).takeIf { entry in tenantOff && entry in CapabilityIds.TENANT_SWITCHABLE },
            )
        }

    /** [id] and each of its dotted prefixes that is an entry: `a.b.c` → `a.b.c`, `a.b` (and `a` if it were one). */
    private fun selfAndAncestors(id: String): List<String> {
        val segments = id.split('.')
        return (segments.size downTo 1).map { segments.take(it).joinToString(".") }
            .filter { it in CapabilityIds.ADVERTISED }
    }
}

/** Who switched an entry off: the Server Owner for the instance, or a tenant owner for their tenant (ADR 0088 decision 12). */
enum class SwitchLevel { INSTANCE, TENANT }

/** One entry [capabilityId] switched off at [level] — a reason an id below or at it is not active. */
data class OffSwitch(val level: SwitchLevel, val capabilityId: String)

/** One register entry's state: [off] is its own switch, [effective] the AND along its path (what the gates follow). */
data class CapabilityState(val id: String, val off: Boolean, val effective: Boolean)

/** One requirement at the instance level: [value] is the instance's, null while it sets none. */
data class RequirementState(val def: CapabilityRequirement, val value: Int?)

/** One requirement as a tenant owner reads it: their own [value], the instance's [serverValue], and the [effective] one. */
data class TenantRequirementState(val def: CapabilityRequirement, val value: Int?, val serverValue: Int?, val effective: Int?)

/** One tenant-switchable entry as a tenant owner reads it: [off] the tenant's own switch, [serverOff] the instance level's
 *  AND along the path, [effective] the AND of both levels (what the tenant's gates follow). */
data class TenantCapabilityState(val id: String, val off: Boolean, val serverOff: Boolean, val effective: Boolean)
