package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.http.FailureStatus
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

/** A register entry as the Server Owner reads it: its words from the catalog, its own switch and its effective state. */
data class CapabilityView(val state: CapabilityState, val texts: CapabilityEntryTexts)

sealed interface CapabilitySetOutcome {
    data class Stored(val view: CapabilityView) : CapabilitySetOutcome
    /** No register entry has the id; nothing is stored. */
    data object NotFound : CapabilitySetOutcome, HttpFailure by httpFailure("capability_not_found", FailureStatus.NOT_FOUND)
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
) {
    fun list(): List<CapabilityView> = registry.register().map { it.view() }

    fun set(id: String, off: Boolean): CapabilitySetOutcome {
        if (id !in CapabilityIds.ADVERTISED) return CapabilitySetOutcome.NotFound
        QuarkusTransaction.requiringNew().run { store.set(id, off) }
        switches.invalidate()
        return CapabilitySetOutcome.Stored(registry.register().single { it.id == id }.view())
    }

    private fun CapabilityState.view() = CapabilityView(this, CapabilityTexts.BY_ID.getValue(id))
}
