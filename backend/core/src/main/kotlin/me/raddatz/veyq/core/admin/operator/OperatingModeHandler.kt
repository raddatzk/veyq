package me.raddatz.veyq.core.admin.operator

import me.raddatz.veyq.core.platform.capabilities.CapabilityEntryTexts
import me.raddatz.veyq.core.platform.capabilities.CapabilityPreset
import me.raddatz.veyq.core.platform.capabilities.CapabilityPresets
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchStore
import me.raddatz.veyq.core.platform.capabilities.CapabilityTexts
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceOrigins
import me.raddatz.veyq.core.platform.capabilities.StoredCapabilitySwitches
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

/** One preset entry as the Server Owner reads it: its value in each mode and its own instance switch now. */
data class PresetEntryView(val id: String, val texts: CapabilityEntryTexts, val preset: CapabilityPreset, val off: Boolean)

/** The mode in effect, whether one was ever chosen, and the preset with each entry's switch now. */
data class OperatingModeView(val mode: OperatingMode, val chosen: Boolean, val preset: List<PresetEntryView>)

/** An entry's value in [mode]: true = on. */
fun CapabilityPreset.valueIn(mode: OperatingMode): Boolean = when (mode) {
    OperatingMode.PRIVATE -> onInPrivate
    OperatingMode.ORGANISATION -> onInOrganisation
}

/**
 * The operating mode (ADR 0093 § 14, ADR 0088 decision 14, uc.admin.operator-details): what
 * `/api/instance/operator/mode` does, and what first-run setup calls with the mode it was given.
 *
 * **A preset, not a level.** Choosing a mode stores it and, in the same transaction, sets the instance-level switch
 * ([CapabilitySwitchStore], the Server Owner's own rows) of every entry the catalog gives a value per mode
 * ([CapabilityPresets]) — and nothing more: a tenant's switches and every entry without a preset are never written,
 * and afterwards each preset switch is an ordinary one the Capabilities panel may change. Choosing a mode again sets
 * its preset again. The notice and the tick are the clients'; the server records neither (ADR 0093 § 14).
 *
 * The preset is a parameter with the generated one as its default, so a test applies a register of its own (today's
 * catalog carries no `modes` yet; `cr-guest-write-record` adds the first).
 *
 * Ordered as `CapabilitySwitchHandler.set` orders a switch: commit, then forget the cached switches, so no gate follows
 * a mode before it is committed or keeps the old switches after.
 */
@ApplicationScoped
class OperatingModeHandler(
    private val store: OperatorDetailsStore,
    private val switchStore: CapabilitySwitchStore,
    private val switches: StoredCapabilitySwitches,
    private val origins: ExternalServiceOrigins,
) {

    fun read(presets: Map<String, CapabilityPreset> = CapabilityPresets.BY_ID): OperatingModeView =
        QuarkusTransaction.joiningExisting().call {
            val chosen = store.find()?.mode
            val off = switchStore.switchedOff()
            OperatingModeView(
                mode = chosen ?: OperatingMode.ORGANISATION,
                chosen = chosen != null,
                preset = presets.map { (id, p) -> PresetEntryView(id, CapabilityTexts.BY_ID.getValue(id), p, id in off) },
            )
        }

    /** Set [mode] and its preset, in a transaction of its own; then the gates read the new switches. */
    fun set(mode: OperatingMode, presets: Map<String, CapabilityPreset> = CapabilityPresets.BY_ID): OperatingModeView {
        QuarkusTransaction.requiringNew().run { apply(mode, presets) }
        afterCommit()
        return read(presets)
    }

    /**
     * Store [mode] and set each preset entry's instance switch to its value in it, **in the caller's transaction** —
     * first-run setup calls this inside its own, so the mode commits with the Server Owner or not at all. The caller
     * runs [afterCommit] once it committed.
     */
    fun apply(mode: OperatingMode, presets: Map<String, CapabilityPreset> = CapabilityPresets.BY_ID) {
        store.lockedRow().operatingMode = mode.name
        presets.forEach { (id, preset) -> switchStore.set(id, off = !preset.valueIn(mode)) }
    }

    /** After the transaction that set a mode COMMITTED: forget the cached switches, and re-read what the served policy
     *  may name (a preset may name an external service). */
    fun afterCommit() {
        switches.invalidate()
        origins.refresh()
    }
}
