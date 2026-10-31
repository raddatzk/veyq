package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare.create
import me.raddatz.veyq.core.sharing.GuestTreeShare.place
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.sharing.GuestNodeRules
import me.raddatz.veyq.core.sharing.GuestTreeShare

/**
 * A guest's tree operations with Memories present (ADR 0080 decisions 6 and 10): whoever owns the container decides.
 * Between folders core decides — a `READ_WRITE` guest moves a Memory from one subfolder to another, and creates none
 * (Memories declares no kind guest-creatable); inside a Memory
 * Memories decides, and it declares nothing ([GuestNodeRules]), so a guest creates, moves and renames nothing there
 * (uc.sharing.share-contributor:guest-tree-refusals: a write refused, the read served). A Memory-rooted link gains no
 * tree operation; its media operations are its own (`ShareMediaRouteSliceTest`, the share media suites).
 */
@QuarkusTest
class ShareNodesSliceTest {

    private data class Tree(val scopeId: String, val root: String, val left: String, val right: String, val memory: String, val chapter: String)

    /** A shared folder `root` holding the subfolders `left` and `right`; a Memory in `left` with one chapter. */
    private fun tree(): Pair<Tree, GuestTreeShare.Link> {
        val (userId, scopeId) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val left = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val right = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = left, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        return Tree(scopeId, root, left, right, memory, chapter) to GuestTreeShare.seed(userId, scopeId, root)
    }

    @Test
    fun aGuestMovesAndRenamesAMemoryBetweenTwoSubfoldersOfASharedFolder() {
        val (t, link) = tree()
        place(link, t.memory, t.right).statusCode(204)
        assertEquals(t.right, parentOf(t.memory))
        // a rename: the same parent, the next placement revision
        place(link, t.memory, t.right, placementRevision = 1).statusCode(204)
        assertEquals(2L, versionOf(t.memory))
    }

    /**
     * Owner decision 2026-10-14: a guest creates no Memory — not under a folder either, where core decides and Memories,
     * which declares no kind guest-creatable (core's `GuestCreatableKinds`), lets it create none. A chapter neither (no
     * rule would place one there anyway). `illegal_scope`, decided before the placement rule, and nothing stored; a
     * folder in the same place is the control. Moving and renaming an existing Memory stay (the test above).
     */
    @Test
    fun aGuestCreatesNoMemoryInAFolder() {
        val (t, link) = tree()
        for (kind in listOf(MemoriesNodeKinds.MEMORY, MemoriesNodeKinds.CHAPTER)) {
            create(link, t.right, kind).statusCode(422).body("code", equalTo("illegal_scope"))
        }
        assertEquals(0L, QuarkusTransaction.requiringNew().call { NodeEntity.count("parentId", t.right) }, "a refused create stores nothing")
        create(link, t.right, NodeEntity.FOLDER).statusCode(201) // control
    }

    @Test
    fun aGuestCannotMoveAChapterOutOfAMemory() {
        val (t, link) = tree()
        val other = seedNode(t.scopeId, MemoriesNodeKinds.MEMORY, parentId = t.right, wrappedContentKey = CONTENT_KEY)
        place(link, t.chapter, other).statusCode(422).body("code", equalTo("illegal_scope"))
        assertEquals(t.memory, parentOf(t.chapter))
    }

    /** uc.sharing.share-contributor:guest-tree-refusals: inside a kind no one declares guest operations for — a write
     *  refused, the read served. */
    @Test
    fun insideAMemoryAGuestWritesNothingAndReadsEverything() {
        val (t, link) = tree()
        create(link, t.memory, MemoriesNodeKinds.CHAPTER).statusCode(422).body("code", equalTo("illegal_scope"))
        place(link, t.chapter, t.memory).statusCode(422).body("code", equalTo("illegal_scope")) // a rename
        // a move INTO a Memory is the Memory's to allow too: a chapter of another Memory, moved in
        val other = seedNode(t.scopeId, MemoriesNodeKinds.MEMORY, parentId = t.right, wrappedContentKey = CONTENT_KEY)
        val stray = seedNode(t.scopeId, MemoriesNodeKinds.CHAPTER, parentId = other, wrappedContentKey = CONTENT_KEY)
        place(link, stray, t.memory).statusCode(422).body("code", equalTo("illegal_scope"))
        assertEquals(0L, versionOf(t.chapter))
        // the read: the guest is served the Memory and its chapter
        given().`when`().get("/api/shares/${link.shareId}").then().statusCode(200)
            .body("nodes.find { it.id == '${t.chapter}' }", notNullValue())
            .body("nodes.find { it.id == '${t.memory}' }.kind", equalTo(MemoriesNodeKinds.MEMORY))
    }

    @Test
    fun aMemoryRootedLinkGainsNoTreeOperation() {
        val (t, _) = tree()
        val (userId, _) = seedMemberScope()
        val link = GuestTreeShare.seed(userId, t.scopeId, t.memory)
        create(link, t.memory, MemoriesNodeKinds.CHAPTER).statusCode(422).body("code", equalTo("illegal_scope"))
        place(link, t.chapter, t.memory).statusCode(422).body("code", equalTo("illegal_scope"))
    }

    private fun parentOf(id: String): String = QuarkusTransaction.requiringNew().call { NodeEntity.findById(id)!!.parentId }
    private fun versionOf(id: String): Long = QuarkusTransaction.requiringNew().call { NodeEntity.findById(id)!!.placementRevision }
}
