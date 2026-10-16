package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.GuestTreeShare.Link
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The last editor of a node's name and meta (ADR 0093 § 8, en.platform.field-editor), with core alone: every writer path
 * records the authenticated caller — a member as `user:{id}`, a link's guest as `share:{identityId}` — beside the
 * ciphertext it wrote, and nothing else moves it. Each path runs on a node whose field the OTHER kind of principal wrote
 * last, so the assertion sees the editor change hands, never a value that was already there. The identity folder, a
 * `WRITE_ONLY` guest's path, needs a drop box and is `DropBoxSliceTest`'s.
 *
 * The tree: `root` at the scope root, shared `READ_WRITE` to one guest; `inner` inside it, a destination for moves.
 */
@QuarkusTest
class NodeFieldEditorCoreSliceTest {

    @Inject lateinit var purge: NodeSubtreePurge

    private data class Tree(val userId: String, val scopeId: String, val root: String, val inner: String, val link: Link) {
        val member get() = PrincipalKey.User(userId)
    }

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val inner = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, root, inner, GuestTreeShare.seed(userId, scopeId, root, ShareEntity.READ_WRITE))
    }

    // --- members ---------------------------------------------------------------------------------------------------

    @Test
    fun aMembersCreateNamesTheMemberAsTheNamesAndTheMetasEditor() {
        val t = tree()
        val bare = memberCreate(t, parent = t.root)
        assertEquals(t.member, row(bare).nameEditor)
        assertNull(row(bare).metaEditor, "no meta, no editor of it")
        val described = memberCreate(t, parent = t.root, meta = "bWV0YQ==")
        assertEquals(t.member, row(described).metaEditor)
        // Control, the other kind: the guest's create names the guest.
        val guests = guestCreate(t, t.inner)
        assertEquals(PrincipalKey.Share(identityOf(t.link)), row(guests).nameEditor)
    }

    @Test
    fun aMembersRenameAndMoveTakeTheNameOverFromTheGuest() {
        val t = tree()
        val node = guestCreate(t, t.inner)
        assertEquals(guest(t), row(node).nameEditor, "control: the guest named it")

        memberPlace(t, node, parent = t.inner, revision = 0) // rename in place
        assertEquals(t.member, row(node).nameEditor)

        val other = guestCreate(t, t.inner)
        memberPlace(t, other, parent = t.root, revision = 0) // a move re-seals the name, so it is a write of it
        assertEquals(t.member, row(other).nameEditor)
    }

    /** A meta write is the meta's: it takes the meta over and leaves the name's editor where it was, and the other way
     *  round. A guest writes no meta, so the meta's control is the name beside it. */
    @Test
    fun aMembersMetaWriteAndCoverNameTheMemberAsTheMetasEditorAndLeaveTheName() {
        val t = tree()
        val node = guestCreate(t, t.inner)
        patch(t, node, """{"wrappedMeta":"bWV0YQ==","metaRevision":0}""")
        assertEquals(t.member, row(node).metaEditor)
        assertEquals(guest(t), row(node).nameEditor, "a meta write is no write of the name")

        GuestTreeShare.place(t.link, node, t.inner, placementRevision = 0).statusCode(204)
        assertEquals(t.member, row(node).metaEditor, "a guest's rename is no write of the meta")
        assertEquals(guest(t), row(node).nameEditor)

        // The cover's meta write: set, then remove, each by the member, on a node the guest named.
        val covered = guestCreate(t, t.inner)
        val started = CoverKit.start(t.userId, t.scopeId, covered)
        CoverKit.set(t.userId, t.scopeId, covered, started.uploadId, expectedBlobId = null, wrappedMeta = "bTE=", metaRevision = 0).statusCode(204)
        assertEquals(t.member, row(covered).metaEditor)
        assertEquals(guest(t), row(covered).nameEditor)
        CoverKit.remove(t.userId, t.scopeId, covered, started.blobId, wrappedMeta = "bTI=", metaRevision = 1).statusCode(204)
        assertEquals(t.member, row(covered).metaEditor)
    }

    /** A restore re-seals the name only on its A1 retry; that write records its caller, a plain restore keeps the editor. */
    @Test
    fun aRestoreThatReSealsTheNameNamesItsCallerOneThatDoesNotKeepsTheEditor() {
        val t = tree()
        val kept = guestCreate(t, t.inner)
        trash(t, kept)
        restore(t, kept, encryptedName = null)
        assertEquals(guest(t), row(kept).nameEditor, "control: the name was not written, so its editor stays the guest")

        val resealed = guestCreate(t, t.inner)
        trash(t, resealed)
        restore(t, resealed, encryptedName = "cmVzZWFsZWQ=")
        assertEquals(t.member, row(resealed).nameEditor)
        assertEquals("cmVzZWFsZWQ=", row(resealed).encryptedName)
    }

    // --- guests ----------------------------------------------------------------------------------------------------

    @Test
    fun aGuestsRenameAndMoveTakeTheNameOverFromTheMember() {
        val t = tree()
        val renamed = memberCreate(t, parent = t.inner)
        assertEquals(t.member, row(renamed).nameEditor, "control: the member named it")
        GuestTreeShare.place(t.link, renamed, t.inner, placementRevision = 0).statusCode(204)
        assertEquals(guest(t), row(renamed).nameEditor)

        val moved = memberCreate(t, parent = t.inner)
        GuestTreeShare.place(t.link, moved, t.root, placementRevision = 0).statusCode(204)
        assertEquals(guest(t), row(moved).nameEditor)
    }

    @Test
    fun theEditorIsTheTokensIdentityNeverAnotherGuestOfTheLink() {
        val t = tree()
        val second = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        val node = guestCreate(t, t.inner)
        GuestTreeShare.place(second, node, t.inner, placementRevision = 0).statusCode(204)
        assertEquals(PrincipalKey.Share(identityOf(second)), row(node).nameEditor)
    }

    // --- the identity it names ---------------------------------------------------------------------------------------

    /** A guest identity of a deleted link lives while it is the last editor of a name, and goes with the last such
     *  node (`NodeGuestIdentityUsage`, `NodeSubtreePurge.erase`). */
    @Test
    fun anIdentityThatLastNamedANodeOutlivesItsLinkUntilTheNodeIsGone() {
        val t = tree()
        val node = guestCreate(t, t.inner)
        val identity = identityOf(t.link)
        // Negative control: an identity of a deleted link that names nothing goes at the delete.
        val idle = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        val idleIdentity = identityOf(idle)
        deleteShare(t, idle)
        assertNull(identityRow(idleIdentity), "control: nothing names it, so the delete dropped it")

        deleteShare(t, t.link)
        assertNotNull(identityRow(identity), "it last wrote a name: kept")

        trash(t, node)
        QuarkusTransaction.requiringNew().run { purge.eraseExpiredIn(t.scopeId, Instant.now().plusSeconds(60)) }
        assertNull(stored(node), "the node is erased")
        assertNull(identityRow(identity), "nothing names it any more: dropped with the node")
    }

    // --- HTTP and rows ---------------------------------------------------------------------------------------------

    private fun guest(t: Tree) = PrincipalKey.Share(identityOf(t.link))

    private fun guestCreate(t: Tree, parent: String): String {
        val id = UUID.randomUUID().toString()
        GuestTreeShare.create(t.link, parent, NodeEntity.FOLDER, nodeId = id).statusCode(201)
        return id
    }

    private fun memberCreate(t: Tree, parent: String, meta: String? = null): String {
        val id = UUID.randomUUID().toString()
        val metaField = meta?.let { ""","wrappedMeta":"$it"""" } ?: ""
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body(
                """{"nodeId":"$id","parentId":"$parent","kind":"FOLDER","encryptedName":"bg==","slugToken":"${GuestTreeShare.slug()}",""" +
                    """"orderIndex":0,"wrappedContentKey":"a21lbQ==","epoch":0$metaField}""",
            )
            .`when`().post("/api/scopes/${t.scopeId}/nodes").then().statusCode(201)
        return id
    }

    private fun memberPlace(t: Tree, node: String, parent: String, revision: Long) = patch(
        t, node,
        """{"parentId":"$parent","encryptedName":"cmVuYW1lZA==","slugToken":"${GuestTreeShare.slug()}",""" +
            """"wrappedContentKey":"cmV3cmFw","epoch":0,"placementRevision":$revision}""",
    )

    private fun patch(t: Tree, node: String, body: String) {
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON).body(body)
            .`when`().patch("/api/scopes/${t.scopeId}/nodes/$node").then().statusCode(204)
    }

    private fun trash(t: Tree, node: String) {
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/$node").then().statusCode(204)
    }

    private fun restore(t: Tree, node: String, encryptedName: String?) {
        val name = encryptedName?.let { ""","encryptedName":"$it"""" } ?: ""
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"${GuestTreeShare.slug()}"$name}""")
            .`when`().post("/api/scopes/${t.scopeId}/nodes/$node/restore").then().statusCode(204)
    }

    private fun deleteShare(t: Tree, link: Link) {
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/shares/${link.shareId}").then().statusCode(204)
    }

    /** The node's row, live or trashed, or null once it is erased. */
    private fun stored(id: String): NodeEntity? = QuarkusTransaction.requiringNew().call { NodeEntity.findById(id) }

    private fun row(id: String): NodeEntity = checkNotNull(stored(id)?.takeIf { it.deletedAt == null }) { "no live node $id" }

    private fun identityOf(link: Link): String =
        QuarkusTransaction.requiringNew().call { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }

    private fun identityRow(id: String): ShareIdentityEntity? = QuarkusTransaction.requiringNew().call { ShareIdentityEntity.findById(id) }
}
