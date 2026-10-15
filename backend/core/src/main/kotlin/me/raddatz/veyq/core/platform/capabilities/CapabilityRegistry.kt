package me.raddatz.veyq.core.platform.capabilities

import jakarta.enterprise.context.ApplicationScoped

/**
 * Computes the instance's **active** capability set (ADR 0032, ADR 0088). It is read in two places that together
 * make switching off honest:
 *  - the **setup manifest** (a pre-auth UX hint the client soft-gates on), and
 *  - each feature's **gate** (the hard `422 capability_disabled` — the server is the authority, so a stale or
 *    ignoring client still cannot use a switched-off capability).
 *
 * **The AND along the path** (ADR 0088 decision 2): an id is active only if it and every ancestor entry are. An
 * ancestor is each dotted prefix that is itself an entry this build knows, so `core` (no entry) is never one, and
 * switching off `core.public-shares` switches off `.contribute` and `.drop-box` with it. The server computes this;
 * a client reads leaf ids and never ANDs them itself (decision 3).
 *
 * [switches] is asked on every call and the registry keeps nothing, so a switch the Server Owner flips through the
 * Admin API (`PUT /api/instance/capabilities/{capabilityId}`) acts without a restart; what is cached, and how a write
 * reaches it, is [StoredCapabilitySwitches]'. The entries are [CapabilityIds], generated from the catalog. It holds
 * **no per-user state**: the active set is instance-wide and identical for every caller (operator-blind-safe — the
 * manifest adds no signal beyond "what this build/instance supports").
 */
@ApplicationScoped
class CapabilityRegistry(
    private val switches: CapabilitySwitches,
) {
    /** The active capability ids, in [CapabilityIds.ADVERTISED] order. */
    val active: List<String>
        get() = switches.switchedOff().let { off -> CapabilityIds.ADVERTISED.filter { isActive(it, off) } }

    /** True when [id] is advertised by this build and neither it nor any ancestor entry is switched off — the gate. */
    fun isActive(id: String): Boolean = isActive(id, switches.switchedOff())

    /**
     * Every entry in [CapabilityIds.ADVERTISED] order with its own switch and its effective state, both from ONE read of
     * [switches] — the register as the Server Owner reads it. A child of a switched-off parent is `off = false`,
     * `effective = false`: its own switch survives the parent's.
     */
    fun register(): List<CapabilityState> =
        switches.switchedOff().let { off -> CapabilityIds.ADVERTISED.map { CapabilityState(it, it in off, isActive(it, off)) } }

    private fun isActive(id: String, off: Set<String>): Boolean =
        id in CapabilityIds.ADVERTISED && selfAndAncestors(id).none { it in off }

    /** [id] and each of its dotted prefixes that is an entry: `a.b.c` → `a.b.c`, `a.b` (and `a` if it were one). */
    private fun selfAndAncestors(id: String): List<String> {
        val segments = id.split('.')
        return (segments.size downTo 1).map { segments.take(it).joinToString(".") }
            .filter { it in CapabilityIds.ADVERTISED }
    }
}

/** One register entry's state: [off] is its own switch, [effective] the AND along its path (what the gates follow). */
data class CapabilityState(val id: String, val off: Boolean, val effective: Boolean)
