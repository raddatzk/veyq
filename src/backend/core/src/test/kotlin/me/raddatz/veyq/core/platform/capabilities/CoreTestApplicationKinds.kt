package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import jakarta.enterprise.context.ApplicationScoped

/**
 * An application stand-in of `:core`'s test application: one kind per application of the register, each answering
 * [applicationOf] with that application and placed by no rule ([parentAllows] is false, so only a seeded row has one).
 * An erasure row exists only where the tenant holds a node of the application (ADR 0088 decision 5,
 * [ApplicationErasureStore.holds]), and with no application in `:core` no kind names one — so the tests of what the
 * switches, the tick and the API do with the row (`ApplicationErasureSliceTest`, `ApplicationErasureRaceSliceTest`) need
 * a node of some application's kind, and these are they, by the test sources only. The real kinds are the root's
 * `ApplicationErasureSweepTest`'s.
 *
 * `CoreAloneSliceTest` excludes it (`CoreAloneProfile`, `quarkus.arc.exclude-types`), so the core-alone pin still boots
 * core with only its own `FOLDER` rules.
 */
@ApplicationScoped
class CoreTestApplicationKinds : NodeKindRules {
    override fun handles(kind: String): Boolean = kind in KINDS

    override fun parentAllows(kind: String, parent: ParentSlot): Boolean = false

    override fun applicationOf(kind: String): String? = KINDS[kind]

    companion object {
        /** A stand-in kind per application, as the column holds it (16 characters at most). */
        val KINDS: Map<String, String> = mapOf("TEST_MEMORY" to "memories", "TEST_FILE" to "depot", "TEST_POT" to "expenses")

        /** The stand-in kind of [application]. */
        fun of(application: String): String = KINDS.entries.single { it.value == application }.key
    }
}
