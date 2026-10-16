package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.generated.model.Capability

/**
 * One entry of an effective set as both deliveries carry it, `{ id, …values }` (ADR 0032 §2, ADR 0088 decision 8): the
 * requirements of THIS entry that are in effect, each as its own property of the wire's `Capability`. A requirement of
 * another entry is never put on this one. A new requirement in the catalog needs its property in the contract
 * (`scripts/validate_capabilities.py` holds that) and its line here (`CapabilityWireTest` is red on a requirement this
 * function drops).
 */
fun capabilityWire(id: String, requirements: Map<String, Int>, services: Map<String, ServiceAddress> = emptyMap()): Capability {
    // An external service's address (ADR 0088 decision 7) rides only on its own entry, which is in a set only while active.
    val service = services[id]?.takeIf { id in CapabilityIds.EXTERNAL_SERVICES }
    val mine = CapabilityRequirements.ALL.filter { it.capabilityId == id }.mapNotNull { def -> requirements[def.name]?.let { def.name to it } }.toMap()
    return Capability(
        id = id,
        linkPassword = mine[CapabilityRequirements.LINK_PASSWORD]?.let { true },
        linkMaxDays = mine[CapabilityRequirements.LINK_MAX_DAYS],
        addConsent = mine[CapabilityRequirements.ADD_CONSENT]?.let { true },
        passwordMaxDays = mine[CapabilityRequirements.PASSWORD_MAX_DAYS],
        serviceUrl = service?.url,
        serviceAttribution = service?.attribution,
    )
}
