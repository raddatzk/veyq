package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.config.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped

/**
 * Computes the instance's **active** capability set (ADR 0032): the built-and-advertised catalog ids
 * ([CapabilityIds.ADVERTISED]) minus the operator's `veyq.capabilities.disabled` list. It is read in two places
 * that together make disabling honest:
 *  - the **setup manifest** (a pre-auth UX hint the client soft-gates on), and
 *  - each feature's **create/enable gate** (the hard `422 capability_disabled` — the server is the authority,
 *    so a stale/ignoring client still cannot use a disabled capability).
 *
 * It holds **no per-user state**: the active set is instance-wide and identical for every caller
 * (operator-blind-safe — the manifest adds no signal beyond "what this build/instance supports").
 */
@ApplicationScoped
class CapabilityRegistry(
    config: VeyqConfig,
) {
    private val disabledSet: Set<String> =
        config.capabilities().disabled().orElse(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** The active capability ids, in [CapabilityIds.ADVERTISED] order, with the operator's disabled ones removed. */
    val active: List<String> = CapabilityIds.ADVERTISED.filterNot { it in disabledSet }

    /** True when [id] is advertised by this build **and** the operator has not disabled it — the endpoint gate. */
    fun isActive(id: String): Boolean = id in active
}
