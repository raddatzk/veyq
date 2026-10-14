package me.raddatz.veyq.files

import me.raddatz.veyq.core.node.FolderNodeRules
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
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
 * The placement matrix of Files' kind ([FilesNodeKindRules], ADR 0079): a `FILE` lives at the root or in a `FOLDER`,
 * nowhere else, and no rule of core's or Files' admits a `FILE` as a parent — so a file has no children. The kinds of
 * Memories appear as the wire spells them: Memories is not on this module's classpath (an application never depends
 * on another), so the same question across core and BOTH applications is `ModuleCompositionTest`'s, in the root.
 */
class FilesNodeKindRulesTest {

    private val rules = FilesNodeKindRules()
    private val file = FilesNodeKinds.FILE

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
        val dropBox = FilesNodeKinds.DROPBOX
        assertTrue(rules.parentAllows(dropBox, ParentSlot.Root), "a drop box at the root")
        assertTrue(rules.parentAllows(dropBox, ParentSlot.Node(NodeEntity.FOLDER)), "a drop box in a folder")
        assertFalse(rules.parentAllows(dropBox, ParentSlot.Node(dropBox)), "a drop box in a drop box")
        // ADR 0080 decision 11: no rule of core's or Files' places anything in a drop box — its identity folders are
        // core's own placement (IdentityFolders), so a member can only delete or move out.
        val composed = listOf(FolderNodeRules(), rules)
        for (child in listOf(NodeEntity.FOLDER, file, dropBox)) {
            assertFalse(NodeKindRules.parentAllows(composed, child, ParentSlot.Node(dropBox)), "a $child placed in a drop box")
        }
        // and it is shared WRITE_ONLY only
        assertEquals(setOf("WRITE_ONLY"), FilesShareRoots().roles(dropBox))
        // …while a file is shared READ or READ_WRITE (a single-file link), never WRITE_ONLY
        assertEquals(setOf("READ", "READ_WRITE"), FilesShareRoots().roles(file))
        assertEquals(null, FilesShareRoots().roles(NodeEntity.FOLDER), "a folder is core's, not Files'")
    }

    @Test
    fun aDropBoxGuestCreatesFilesOnly() {
        // inbox #377 (c), owner decision 2026-10-08: inside its identity folder a WRITE_ONLY guest creates FILEs only
        val kinds = listOf(FilesWriteOnlyGuestKinds())
        assertEquals(setOf(file), FilesWriteOnlyGuestKinds().creatable(FilesNodeKinds.DROPBOX))
        assertTrue(WriteOnlyGuestKinds.allows(kinds, FilesNodeKinds.DROPBOX, file))
        for (kind in listOf(NodeEntity.FOLDER, FilesNodeKinds.DROPBOX, "MEMORY", "CHAPTER", "NOT_A_KIND")) {
            assertFalse(WriteOnlyGuestKinds.allows(kinds, FilesNodeKinds.DROPBOX, kind), "a drop-box guest creates a $kind")
        }
        // Files answers for its drop box only: no other root kind is its question
        for (root in listOf(NodeEntity.FOLDER, file, "MEMORY")) assertEquals(null, FilesWriteOnlyGuestKinds().creatable(root), root)
    }

    @Test
    fun itAnswersForItsOwnKindsOnly() {
        assertTrue(rules.handles(file))
        assertTrue(rules.handles(FilesNodeKinds.DROPBOX))
        for (kind in listOf(NodeEntity.FOLDER, "MEMORY", "CHAPTER", "NOT_A_KIND", "file")) {
            assertFalse(rules.handles(kind), "Files answers for $kind")
        }
        // the kind is checked inside too, not only by the caller's `handles` filter
        assertFalse(rules.parentAllows(NodeEntity.FOLDER, ParentSlot.Root), "Files places a folder")
    }

    @Test
    fun noRuleOfCoreOrFilesAdmitsAFileAsAParent() {
        val composed = listOf(FolderNodeRules(), rules)
        for (child in listOf(NodeEntity.FOLDER, file)) {
            assertFalse(NodeKindRules.parentAllows(composed, child, ParentSlot.Node(file)), "a $child inside a file")
        }
        // control: the same composed set places both kinds in a folder
        for (child in listOf(NodeEntity.FOLDER, file)) {
            assertTrue(NodeKindRules.parentAllows(composed, child, ParentSlot.Node(NodeEntity.FOLDER)), "a $child in a folder")
        }
    }

    /** Owner decision 2026-10-14: a folder's contributor creates a `FILE` and no drop box — Files' declaration, read by
     *  core's folder rule (`GuestCreatableKinds`). */
    @Test
    fun aFolderGuestCreatesFilesAndNoDropBox() {
        val folder = FolderGuestNodeRules(mutableListOf(FilesGuestCreatableKinds()))
        assertTrue(folder.readWriteAllows(NodeEntity.FOLDER, GuestNodeOp.CreateChild(file)), "a file")
        for (kind in listOf(FilesNodeKinds.DROPBOX, "MEMORY", "CHAPTER", "NOT_A_KIND")) {
            assertFalse(folder.readWriteAllows(NodeEntity.FOLDER, GuestNodeOp.CreateChild(kind)), "a guest creates a $kind")
        }
    }

    /**
     * A second application claiming `FILE` fails startup (the declarations are a partition): core's folder rule throws
     * from its `StartupEvent` observer, naming the kind and both declarers. Plain JUnit, as a failed boot cannot be
     * asserted from inside the application it failed to be; Files alone is the control.
     */
    @Test
    fun aSecondClaimOfFileFailsStartup() {
        class SecondClaim : GuestCreatableKinds {
            override fun kinds() = setOf(FilesNodeKinds.FILE)
        }
        val twice = FolderGuestNodeRules(mutableListOf(FilesGuestCreatableKinds(), SecondClaim()))
        val e = assertThrows(IllegalStateException::class.java) { twice.onStart(StartupEvent()) }
        assertTrue(e.message!!.contains("FILE by "), e.message)
        assertTrue(e.message!!.contains(FilesGuestCreatableKinds::class.java.name) && e.message!!.contains(SecondClaim::class.java.name), e.message)
        assertFalse(twice.readWriteAllows(NodeEntity.FOLDER, GuestNodeOp.CreateChild(file)), "a kind claimed twice is created by nobody")
        assertDoesNotThrow { FolderGuestNodeRules(mutableListOf(FilesGuestCreatableKinds())).onStart(StartupEvent()) }
    }
}
