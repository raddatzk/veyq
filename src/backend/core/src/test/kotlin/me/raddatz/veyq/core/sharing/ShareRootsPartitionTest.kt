package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.node.NodeEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ShareRoots] fails closed as `NodeKindRules` does: the one implementation that handles a kind decides; a kind none
 * handles, or several do, is the root of no share — never the first CDI happens to list, and never a default. The
 * kinds here are data: a forged implementation claims them. The registered sets are pinned beside the beans
 * (`CoreAloneSliceTest`, `ModuleCompositionTest`); `SharingHandler` asks exactly [ShareRoots.allows].
 */
class ShareRootsPartitionTest {

    private class Forged(private val kind: String, private val roles: Set<String>) : ShareRoots {
        override fun roles(kind: String): Set<String>? = if (kind == this.kind) roles else null
    }

    @Test
    fun anEmptySetSharesNothingNotEvenAFolder() {
        assertEquals(emptySet<String>(), ShareRoots.roles(emptyList(), NodeEntity.FOLDER))
        // control: core's own answer
        assertTrue(ShareRoots.allows(listOf(FolderShareRoots()), NodeEntity.FOLDER, ShareEntity.READ))
    }

    @Test
    fun aKindTwoImplementationsClaimIsSharedByNeither() {
        val wide = Forged("MEMORY", setOf(ShareEntity.READ, ShareEntity.READ_WRITE, ShareEntity.WRITE_ONLY))
        val narrow = Forged("MEMORY", setOf(ShareEntity.READ))
        for (order in listOf(listOf(wide, narrow), listOf(narrow, wide))) {
            val roots = listOf<ShareRoots>(FolderShareRoots()) + order
            assertEquals(emptySet<String>(), ShareRoots.roles(roots, "MEMORY"), "an ambiguous kind takes no role")
            assertTrue(ShareRoots.allows(roots, NodeEntity.FOLDER, ShareEntity.READ), "the unambiguous kind beside it is unaffected")
        }
        // control: either alone decides
        assertTrue(ShareRoots.allows(listOf(wide), "MEMORY", ShareEntity.WRITE_ONLY))
        assertFalse(ShareRoots.allows(listOf(narrow), "MEMORY", ShareEntity.WRITE_ONLY))
    }

    @Test
    fun coreSharesAFolderToReadOrContributeButNeverAsADropBox() {
        val core = listOf<ShareRoots>(FolderShareRoots())
        assertEquals(setOf(ShareEntity.READ, ShareEntity.READ_WRITE), ShareRoots.roles(core, NodeEntity.FOLDER))
        for (kind in listOf("MEMORY", "CHAPTER", "NOT_A_KIND")) {
            assertEquals(emptySet<String>(), ShareRoots.roles(core, kind), "core does not answer for $kind")
        }
    }
}
