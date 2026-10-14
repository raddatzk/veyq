package me.raddatz.veyq.core.sharing

import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [GuestCreatableKinds] (owner decision 2026-10-14): one declaration of a kind makes it guest-creatable in a folder;
 * none does not, and two do not either — and two fail startup, through [FolderGuestNodeRules]' `StartupEvent` observer.
 *
 * Plain JUnit, not `@QuarkusTest`: a failed boot cannot be asserted from inside the application it failed to be
 * (`AdminListenerBindTest`'s reason). So the observer is called as ArC calls it, beside the control of a disjoint set.
 * Files' own kind against a second claimant is `FilesNodeKindRulesTest`'s.
 */
class GuestCreatableKindsTest {

    private class Declaring(private vararg val declared: String) : GuestCreatableKinds {
        override fun kinds() = declared.toSet()
    }

    private class Other(private vararg val declared: String) : GuestCreatableKinds {
        override fun kinds() = declared.toSet()
    }

    @Test
    fun exactlyOneDeclarationMakesAKindCreatable() {
        assertFalse(GuestCreatableKinds.allows(emptyList(), "X"), "nothing declared")
        assertTrue(GuestCreatableKinds.allows(listOf(Declaring("X"), Other("Y")), "X"), "one declares X")
        assertFalse(GuestCreatableKinds.allows(listOf(Declaring("X"), Other("Y")), "Z"), "nobody declares Z")
        assertFalse(GuestCreatableKinds.allows(listOf(Declaring("X"), Other("X")), "X"), "two declare X")
    }

    @Test
    fun twoDeclarationsOfOneKindFailStartupNamingTheKindAndBoth() {
        val rules = FolderGuestNodeRules(mutableListOf(Declaring("X", "Y"), Other("X")))
        val e = assertThrows(IllegalStateException::class.java) { rules.onStart(StartupEvent()) }
        assertTrue(e.message!!.contains("X by ${Declaring::class.java.name}, ${Other::class.java.name}"), e.message)
        assertFalse(e.message!!.contains("Y by"), e.message)
        assertEquals(mapOf("X" to listOf("a", "b")), GuestCreatableKinds.overlaps(mapOf("b" to setOf("X"), "a" to setOf("X", "Y"))))
    }

    @Test
    fun disjointDeclarationsStart() {
        assertDoesNotThrow { FolderGuestNodeRules(mutableListOf(Declaring("X"), Other("Y"))).onStart(StartupEvent()) }
        assertDoesNotThrow { FolderGuestNodeRules(mutableListOf()).onStart(StartupEvent()) }
    }
}
