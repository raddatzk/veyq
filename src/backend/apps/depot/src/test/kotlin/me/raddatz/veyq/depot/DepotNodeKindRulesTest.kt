package me.raddatz.veyq.depot

import me.raddatz.veyq.core.node.FolderNodeRules
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.sharing.FolderGuestNodeRules
import me.raddatz.veyq.core.sharing.GuestCreatableKinds
import me.raddatz.veyq.core.sharing.GuestNodeOp
import me.raddatz.veyq.core.sharing.WriteOnlyGuestKinds
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The placement matrix of Depot's kind ([DepotNodeKindRules], ADR 0079): a `FILE` lives at the root or in a `FOLDER`,
 * nowhere else, and no rule of core's or Depot's admits a `FILE` as a parent — so a file has no children. The kinds of
 * Memories appear as the wire spells them: Memories is not on this module's classpath (an application never depends
 * on another), so the same question across core and BOTH applications is `ModuleCompositionTest`'s, in the root.
 */
class DepotNodeKindRulesTest {

    private val rules = DepotNodeKindRules()
    private val file = DepotNodeKinds.FILE

    @Test
    fun aFileLivesAtTheRootOrInAFolder() {
        assertTrue(rules.parentAllows(file, ParentSlot.Root), "a file at the root")
        assertTrue(rules.parentAllows(file, ParentSlot.Node(NodeEntity.FOLDER)), "a file in a folder")
    }

    @Test
    fun aFileLivesNowhereElse() {
        for (parent in listOf(file, "MEMORY", "CHAPTER", "NOT_A_KIND")) {
            assertFalse(rules.parentAllows(file, ParentSlot.Node(parent)), "a file under a $parent")
        }
        assertFalse(rules.parentAllows(file, ParentSlot.Missing), "a file under a missing parent")
    }

    @Test
    fun aDropBoxLivesWhereAFileDoesAndNothingIsPlacedInIt() {
        val dropBox = DepotNodeKinds.DROPBOX
        assertTrue(rules.parentAllows(dropBox, ParentSlot.Root), "a drop box at the root")
        assertTrue(rules.parentAllows(dropBox, ParentSlot.Node(NodeEntity.FOLDER)), "a drop box in a folder")
        assertFalse(rules.parentAllows(dropBox, ParentSlot.Node(dropBox)), "a drop box in a drop box")
        // ADR 0080 decision 11: no rule of core's or Depot's places anything in a drop box — its identity folders are
        // core's own placement (IdentityFolders), so a member can only delete or move out.
        val composed = listOf(FolderNodeRules(), rules)
        for (child in listOf(NodeEntity.FOLDER, file, dropBox)) {
            assertFalse(NodeKindRules.parentAllows(composed, child, ParentSlot.Node(dropBox)), "a $child placed in a drop box")
        }
        // and it is shared WRITE_ONLY only
        assertEquals(setOf("WRITE_ONLY"), DepotShareRoots().roles(dropBox))
        // …while a file is shared READ or READ_WRITE (a single-file link), never WRITE_ONLY
        assertEquals(setOf("READ", "READ_WRITE"), DepotShareRoots().roles(file))
        assertEquals(null, DepotShareRoots().roles(NodeEntity.FOLDER), "a folder is core's, not Depot's")
    }

    @Test
    fun aDropBoxGuestCreatesFilesOnly() {
        // inbox #377 (c), owner decision 2026-10-08: inside its identity folder a WRITE_ONLY guest creates FILEs only
        val kinds = listOf(DepotWriteOnlyGuestKinds())
        assertEquals(setOf(file), DepotWriteOnlyGuestKinds().creatable(DepotNodeKinds.DROPBOX))
        assertTrue(WriteOnlyGuestKinds.allows(kinds, DepotNodeKinds.DROPBOX, file))
        for (kind in listOf(NodeEntity.FOLDER, DepotNodeKinds.DROPBOX, "MEMORY", "CHAPTER", "NOT_A_KIND")) {
            assertFalse(WriteOnlyGuestKinds.allows(kinds, DepotNodeKinds.DROPBOX, kind), "a drop-box guest creates a $kind")
        }
        // Depot answers for its drop box only: no other root kind is its question
        for (root in listOf(NodeEntity.FOLDER, file, "MEMORY")) assertEquals(null, DepotWriteOnlyGuestKinds().creatable(root), root)
    }

    /** #440 gap 2: a new drop box needs the drop-box links; a file needs no capability (`DropBoxCapabilitySliceTest`
     *  is the gate through the node API). */
    @Test
    fun aDropBoxNeedsTheDropBoxCapabilityToBeCreatedAndAFileNone() {
        assertEquals(CapabilityIds.PUBLIC_SHARES_DROP_BOX, rules.capabilityToCreate(DepotNodeKinds.DROPBOX))
        assertEquals(null, rules.capabilityToCreate(file))
        val composed = listOf(FolderNodeRules(), rules)
        assertEquals(CapabilityIds.PUBLIC_SHARES_DROP_BOX, NodeKindRules.capabilityToCreate(composed, DepotNodeKinds.DROPBOX))
        assertEquals(null, NodeKindRules.capabilityToCreate(composed, NodeEntity.FOLDER), "core's folder needs none")
    }

    @Test
    fun itAnswersForItsOwnKindsOnly() {
        assertTrue(rules.handles(file))
        assertTrue(rules.handles(DepotNodeKinds.DROPBOX))
        for (kind in listOf(NodeEntity.FOLDER, "MEMORY", "CHAPTER", "NOT_A_KIND", "file")) {
            assertFalse(rules.handles(kind), "Depot answers for $kind")
        }
        // the kind is checked inside too, not only by the caller's `handles` filter
        assertFalse(rules.parentAllows(NodeEntity.FOLDER, ParentSlot.Root), "Depot places a folder")
    }

    @Test
    fun noRuleOfCoreOrDepotAdmitsAFileAsAParent() {
        val composed = listOf(FolderNodeRules(), rules)
        for (child in listOf(NodeEntity.FOLDER, file)) {
            assertFalse(NodeKindRules.parentAllows(composed, child, ParentSlot.Node(file)), "a $child inside a file")
        }
        // control: the same composed set places both kinds in a folder
        for (child in listOf(NodeEntity.FOLDER, file)) {
            assertTrue(NodeKindRules.parentAllows(composed, child, ParentSlot.Node(NodeEntity.FOLDER)), "a $child in a folder")
        }
    }

    /** Owner decision 2026-10-14: a folder's contributor creates a `FILE` and no drop box — Depot's declaration, read by
     *  core's folder rule (`GuestCreatableKinds`). */
    @Test
    fun aFolderGuestCreatesFilesAndNoDropBox() {
        val folder = FolderGuestNodeRules(mutableListOf(DepotGuestCreatableKinds()))
        assertTrue(folder.readWriteAllows(NodeEntity.FOLDER, GuestNodeOp.CreateChild(file)), "a file")
        for (kind in listOf(DepotNodeKinds.DROPBOX, "MEMORY", "CHAPTER", "NOT_A_KIND")) {
            assertFalse(folder.readWriteAllows(NodeEntity.FOLDER, GuestNodeOp.CreateChild(kind)), "a guest creates a $kind")
        }
    }

    /**
     * A second application claiming `FILE` fails startup (the declarations are a partition): core's folder rule throws
     * from its `StartupEvent` observer, naming the kind and both declarers. Plain JUnit, as a failed boot cannot be
     * asserted from inside the application it failed to be; Depot alone is the control.
     */
    @Test
    fun aSecondClaimOfFileFailsStartup() {
        class SecondClaim : GuestCreatableKinds {
            override fun kinds() = setOf(DepotNodeKinds.FILE)
        }
        val twice = FolderGuestNodeRules(mutableListOf(DepotGuestCreatableKinds(), SecondClaim()))
        val e = assertThrows(IllegalStateException::class.java) { twice.onStart(StartupEvent()) }
        assertTrue(e.message!!.contains("FILE by "), e.message)
        assertTrue(e.message!!.contains(DepotGuestCreatableKinds::class.java.name) && e.message!!.contains(SecondClaim::class.java.name), e.message)
        assertFalse(twice.readWriteAllows(NodeEntity.FOLDER, GuestNodeOp.CreateChild(file)), "a kind claimed twice is created by nobody")
        assertDoesNotThrow { FolderGuestNodeRules(mutableListOf(DepotGuestCreatableKinds())).onStart(StartupEvent()) }
    }
}
