package me.raddatz.veyq.core.platform.capabilities

import jakarta.enterprise.context.ApplicationScoped

/**
 * Which capability entries are switched off on this instance (ADR 0088). [CapabilityRegistry] asks it on every call
 * and caches nothing, so a switch takes effect without a restart once something can flip one.
 *
 * **Nothing can, yet.** The boot-time `veyq.capabilities.*` config list is gone: no deployment recipe ever passed it
 * through, and ADR 0088 decision 12 moves the Server Owner's switches to database state written through the Admin
 * API (inbox #443, which must land before V1 and replaces [NoCapabilitySwitchedOff]). Until then every built id is
 * active, and only a test can switch one off, through an `@Alternative` its `QuarkusTestProfile` enables.
 */
fun interface CapabilitySwitches {
    /** The catalog ids switched off right now. An id here also switches off every entry below it (the AND along the path). */
    fun switchedOff(): Set<String>
}

/** The one production [CapabilitySwitches]: nothing is switched off (see [CapabilitySwitches] for why, and until when). */
@ApplicationScoped
class NoCapabilitySwitchedOff : CapabilitySwitches {
    override fun switchedOff(): Set<String> = emptySet()
}
