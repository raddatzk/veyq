package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.node.NodeEntity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [WriteOnlyGuestKinds] partitions root kinds as [ShareRoots] and [GuestNodeRules] do: the one implementation that
 * handles the link's root kind decides which kinds a `WRITE_ONLY` guest creates; none or several let it create nothing —
 * never the first by CDI bean order, never open by default (core declares none, so core alone allows nothing).
 */
class WriteOnlyGuestKindsPartitionTest {

    private fun claiming(root: String, vararg kinds: String) = object : WriteOnlyGuestKinds {
        override fun creatable(rootKind: String) = if (rootKind == root) kinds.toSet() else null
    }

    @Test
    fun aRootKindNobodyHandlesAllowsNothing() {
        assertFalse(WriteOnlyGuestKinds.allows(emptyList(), "BOX", "ITEM"), "no implementation at all")
        assertFalse(WriteOnlyGuestKinds.allows(listOf(claiming("BOX", "ITEM")), NodeEntity.FOLDER, "ITEM"), "an unclaimed root")
    }

    @Test
    fun aRootKindTwoImplementationsClaimAllowsNothing() {
        val rules = listOf(claiming("BOX", "ITEM"), claiming("BOX", "ITEM", NodeEntity.FOLDER))
        assertFalse(WriteOnlyGuestKinds.allows(rules, "BOX", "ITEM"), "two claim BOX")
    }

    @Test
    fun theOneHandlerDecidesKindByKind() {
        val rules = listOf(claiming("BOX", "ITEM"), claiming("OTHER", NodeEntity.FOLDER))
        assertTrue(WriteOnlyGuestKinds.allows(rules, "BOX", "ITEM"))
        assertFalse(WriteOnlyGuestKinds.allows(rules, "BOX", NodeEntity.FOLDER), "a kind the handler does not list")
    }
}
