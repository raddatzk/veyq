package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirementStore
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceAddresses
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceOrigins
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements
import me.raddatz.veyq.core.platform.capabilities.RequirementState
import me.raddatz.veyq.core.platform.capabilities.StoredCapabilityRequirementValues
import me.raddatz.veyq.core.platform.capabilities.accepts
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.platform.capabilities.CapabilityEntryTexts
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.platform.capabilities.CapabilityState
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchStore
import me.raddatz.veyq.core.platform.capabilities.CapabilityTexts
import me.raddatz.veyq.core.platform.capabilities.StoredCapabilitySwitches
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

/** A register entry as the Server Owner reads it: its words from the catalog, its own switch, its effective state, and
 *  its requirements with the instance's values. */
data class CapabilityView(
    val state: CapabilityState, val texts: CapabilityEntryTexts, val requirements: List<RequirementState>,
    /** For an external service, whether its address is configured; null for every other entry. */
    val serviceConfigured: Boolean?,
)

sealed interface CapabilitySetOutcome {
    data class Stored(val view: CapabilityView) : CapabilitySetOutcome
    /** No register entry has the id; nothing is stored. */
    data object NotFound : CapabilitySetOutcome, HttpFailure by httpFailure("capability_not_found", FailureStatus.NOT_FOUND)
    /** The entry has no requirement of the name; nothing is stored. */
    data object NoSuchRequirement : CapabilitySetOutcome, HttpFailure by Failures.requirementNotFound
    /** A flag's value is not `1`; nothing is stored. The days' range is the contract's, refused before the handler. */
    data object InvalidValue : CapabilitySetOutcome, HttpFailure by Failures.invalidRequest
}

/**
 * The Server Owner's capability switches (ADR 0088 decision 12): what `GET /api/instance/capabilities` and
 * `PUT /api/instance/capabilities/{capabilityId}` do. The persistence is [CapabilitySwitchStore], the cached set every
 * gate follows is [StoredCapabilitySwitches], the AND along the path is [CapabilityRegistry]'s — this class orders the
 * effects as `StoreHandler.set` does: commit, then forget the cached set, then read the entry back, so no gate can
 * follow a switch before it is committed or keep the old one after.
 */
@ApplicationScoped
class CapabilitySwitchHandler(
    private val store: CapabilitySwitchStore,
    private val switches: StoredCapabilitySwitches,
    private val registry: CapabilityRegistry,
    private val requirementStore: CapabilityRequirementStore,
    private val requirementValues: StoredCapabilityRequirementValues,
    private val origins: ExternalServiceOrigins,
    private val services: ExternalServiceAddresses,
) {
    fun list(): List<CapabilityView> = registry.register().map { it.view() }

    fun set(id: String, off: Boolean): CapabilitySetOutcome {
        if (id !in CapabilityIds.ADVERTISED) return CapabilitySetOutcome.NotFound
        QuarkusTransaction.requiringNew().run { store.set(id, off) }
        switches.invalidate()
        origins.refresh() // an external service's switch changes what the served policy may name
        return CapabilitySetOutcome.Stored(registry.register().single { it.id == id }.view())
    }

    /**
     * Set ([value]) or clear (null) the instance's value of one requirement (ADR 0088 decision 8), ordered as [set]:
     * commit, forget the cached values, read the entry back.
     */
    fun setRequirement(capabilityId: String, name: String, value: Int?): CapabilitySetOutcome {
        val def = CapabilityRequirements.BY_NAME[name]?.takeIf { it.capabilityId == capabilityId }
            ?: return CapabilitySetOutcome.NoSuchRequirement
        if (value != null && !def.accepts(value)) return CapabilitySetOutcome.InvalidValue
        QuarkusTransaction.requiringNew().run { requirementStore.setInstance(name, value) }
        requirementValues.invalidate()
        return CapabilitySetOutcome.Stored(registry.register().single { it.id == capabilityId }.view())
    }

    private fun CapabilityState.view() = CapabilityView(
        this, CapabilityTexts.BY_ID.getValue(id), registry.instanceRequirements(id),
        if (id in CapabilityIds.EXTERNAL_SERVICES) id in services.configured() else null,
    )
}
