package me.raddatz.veyq.files

import me.raddatz.veyq.core.node.FolderNodeRules
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import org.junit.jupiter.api.Assertions.assertEquals
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
        assertEquals(null, FilesShareRoots().roles(file), "a file is no share root")
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
}
