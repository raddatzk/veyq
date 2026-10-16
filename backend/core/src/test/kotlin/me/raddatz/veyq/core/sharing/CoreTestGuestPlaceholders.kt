package me.raddatz.veyq.core.sharing

import jakarta.enterprise.context.ApplicationScoped

/**
 * An application stand-in of `:core`'s test application: one kind every node of which its owner calls empty
 * ([GuestPlaceholders]). With no application in `:core` no node is ever a placeholder, so without it the checks core
 * makes itself before an abandon — the reach, no child, no upload of anybody else open — could not be reached with core
 * alone; `ShareNodeAbandonCoreSliceTest` seeds nodes of [KIND] as rows to hold them.
 *
 * `CoreAloneSliceTest` excludes it (`CoreAloneProfile`, `quarkus.arc.exclude-types`), so the core-alone pin still boots
 * core with nothing declared.
 */
@ApplicationScoped
class CoreTestGuestPlaceholders : GuestPlaceholders {
    override fun handles(kind: String): Boolean = kind == KIND

    override fun isEmpty(scopeId: String, nodeId: String): Boolean = true

    companion object {
        /** Every node of it is empty to its (stand-in) owner. */
        const val KIND = "PLACEHOLDER_TEST"
    }
}
