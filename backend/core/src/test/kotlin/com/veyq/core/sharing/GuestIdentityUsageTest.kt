package com.veyq.core.sharing

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The drop decision `SharingStore.dropDeadIdentities` asks before deleting a guest identity, boot-free. The case that
 * bought it is the empty fan-in: combined with a bare `any`, no implementation meant "nobody references it", so
 * every candidate was deleted even while a trashed-but-restorable medium still named it — and that identity's
 * wrapped key is the only way to open an uploader guest's photo. It must fail closed instead.
 */
class GuestIdentityUsageTest {
    private fun usage(referenced: Boolean) = object : GuestIdentityUsage {
        override fun stillReferenced(identityId: String) = referenced
    }

    @Test
    fun noImplementationKeepsTheIdentity() {
        assertFalse(
            GuestIdentityUsage.unreferenced(emptyList(), "g1"),
            "with no usage to ask, nothing vouches that no row names the identity: it is kept",
        )
    }

    @Test
    fun oneUsageStillReferencingKeepsIt() {
        assertFalse(GuestIdentityUsage.unreferenced(listOf(usage(false), usage(true)), "g1"), "any reference keeps it")
        assertFalse(GuestIdentityUsage.unreferenced(listOf(usage(true), usage(false)), "g1"), "in either order")
    }

    @Test
    fun everyUsageAskedAndNoneReferencingDropsIt() {
        assertTrue(GuestIdentityUsage.unreferenced(listOf(usage(false)), "g1"), "one usage, unreferenced: dropped")
        assertTrue(GuestIdentityUsage.unreferenced(listOf(usage(false), usage(false)), "g1"), "two, both unreferenced: dropped")
    }
}
