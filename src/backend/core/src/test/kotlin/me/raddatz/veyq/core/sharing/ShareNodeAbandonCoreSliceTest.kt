package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.content.NodeCover
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare.Link
import me.raddatz.veyq.core.sharing.GuestTreeShare.abandon
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A guest takes back an empty upload placeholder (`abandonShareNode`, ADR 0080 decision 6), with core alone: what core
 * checks itself before it erases — the guest's reach (strictly inside the subtree, never an identity folder for a
 * `READ_WRITE` guest; strictly inside its own identity folder for a `WRITE_ONLY` one), that the calling identity last
 * wrote the node's name (ADR 0093 § 8), no live link rooted on it, no child, no upload a member or another identity
 * opened for the node — core's own `FOLDER` answer ([FolderGuestPlaceholders]), and that a kind nobody declares is
 * never one. Which nodes an application calls empty is held open by [CoreTestGuestPlaceholders] (every node of its
 * kind); Depot's own answer and the full-link upload this exists for are `ShareFilesSliceTest` / `DropBoxSliceTest`.
 * Each refusal beside its control, and each crafted to fail exactly ONE check: a seeded node names the guest that
 * abandons it as its name's editor unless the name is what the test is about. The positive control runs the real guest
 * path — `createShareNode`, then `abandonShareNode` — never a seeded node: web tolerates `not_a_placeholder` silently,
 * so an editor recorded wrongly on the create would fail no screen. A refusal writes nothing.
 *
 * The tree: `outer` at the scope root holds the shared `root` and, beside it, `outside`; `root` holds `a`.
 */
@QuarkusTest
class ShareNodeAbandonCoreSliceTest {

    @Inject lateinit var nodes: NodesStore

    private data class Tree(val userId: String, val scopeId: String, val root: String, val outside: String, val a: String)

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val outer = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val root = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = CONTENT_KEY)
        val a = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, root, outside, a)
    }

    /** A node of the stand-in's kind whose name [by]'s guest last wrote — the one check it passes unless a test says. */
    private fun placeholder(t: Tree, parentId: String, by: Link): String =
        seedNode(t.scopeId, CoreTestGuestPlaceholders.KIND, parentId = parentId, wrappedContentKey = CONTENT_KEY, nameEditor = guest(by))

    /** An empty `FOLDER` the guest of [link] creates the way its client does (`createShareNode`). */
    private fun guestFolder(link: Link, parentId: String): String {
        val id = UUID.randomUUID().toString()
        GuestTreeShare.create(link, parentId, NodeEntity.FOLDER, nodeId = id).statusCode(201)
        return id
    }

    private fun guest(link: Link): PrincipalKey = PrincipalKey.Share(identityOf(link))

    @Test
    fun aContributorErasesAnEmptyPlaceholderAtOnceAndTheTreeRevisionMoves() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        val p = guestFolder(link, t.a) // the real guest path: what it records as the name's editor is what is asked
        val before = revision(t.scopeId)

        abandon(link, p).statusCode(204)

        assertNull(node(p), "erased, not trashed: no row is left")
        assertEquals(before + 1, revision(t.scopeId), "the erase is a change of the tree (ADR 0081)")
        given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/nodes").then().statusCode(200)
            .body("findAll { it.id == '$p' }.size()", equalTo(0))
        // Gone is gone: a second abandon finds nothing.
        abandon(link, p).statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun aNodeWithAChildLiveOrTrashedIsNoPlaceholder() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        val p = placeholder(t, t.a, link)
        val child = seedNode(t.scopeId, NodeEntity.FOLDER, parentId = p, wrappedContentKey = CONTENT_KEY)

        abandon(link, p).statusCode(409).body("code", equalTo("not_a_placeholder"))
        assertNotNull(node(p), "a refusal writes nothing")

        // A trashed child is restorable, so it still holds the parent.
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(child)!!.deletedAt = Instant.now() }
        abandon(link, p).statusCode(409).body("code", equalTo("not_a_placeholder"))

        // Control: without the child, the same node goes.
        QuarkusTransaction.requiringNew().run { NodeEntity.deleteById(child) }
        abandon(link, p).statusCode(204)
    }

    /** No guest cuts another's upload off: a session a member or another identity opened for the node keeps it. One
     *  this identity opened does not — it gives up its own upload. */
    @Test
    fun anUploadAnybodyElseHasOpenIntoTheNodeKeepsItTheGuestsOwnDoesNot() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        val other = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)

        val byMember = placeholder(t, t.a, link)
        openSession(t.scopeId, byMember, openerUserId = t.userId)
        abandon(link, byMember).statusCode(409).body("code", equalTo("not_a_placeholder"))

        val byOther = placeholder(t, t.a, link)
        val othersSession = openSession(t.scopeId, byOther, openerIdentity = identityOf(other))
        abandon(link, byOther).statusCode(409).body("code", equalTo("not_a_placeholder"))
        assertNotNull(node(byMember)); assertNotNull(node(byOther))

        // Control: the other's session gone, the same node goes.
        QuarkusTransaction.requiringNew().run { UploadSessionEntity.deleteById(othersSession) }
        abandon(link, byOther).statusCode(204)
        val byItself = placeholder(t, t.a, link)
        openSession(t.scopeId, byItself, openerIdentity = identityOf(link))
        abandon(link, byItself).statusCode(204)
    }

    /** Core's own kind ([FolderGuestPlaceholders]): an empty `FOLDER` is a placeholder — the folders a guest's
     *  folder-tree upload made before every file of it was refused. One with an attachment (a cover) or sealed metadata
     *  holds something. A member's empty folder is none: its name is the member's (ADR 0093 § 8). */
    @Test
    fun anEmptyFolderIsAPlaceholderOneWithACoverOrMetadataIsNot() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        val covered = seedNode(t.scopeId, NodeEntity.FOLDER, parentId = t.a, wrappedContentKey = CONTENT_KEY, nameEditor = guest(link))
        seedAttachment(t.scopeId, covered, NodeCover.SLOT, uploaderUserId = t.userId)
        abandon(link, covered).statusCode(409).body("code", equalTo("not_a_placeholder"))
        val described = seedNode(t.scopeId, NodeEntity.FOLDER, parentId = t.a, wrappedContentKey = CONTENT_KEY, nameEditor = guest(link))
        QuarkusTransaction.requiringNew().run {
            NodeEntity.findById(described)!!.apply { wrappedMeta = "bWV0YQ=="; metaWrittenBy(PrincipalKey.User(t.userId)) }
        }
        abandon(link, described).statusCode(409).body("code", equalTo("not_a_placeholder"))
        for (id in listOf(covered, described)) assertNotNull(node(id), "a refusal writes nothing")

        // A member's empty folder is no guest's placeholder (it was one before the name had an editor).
        val members = seedNode(t.scopeId, NodeEntity.FOLDER, parentId = t.a, wrappedContentKey = CONTENT_KEY, nameEditor = PrincipalKey.User(t.userId))
        abandon(link, members).statusCode(409).body("code", equalTo("not_a_placeholder"))
        assertNotNull(node(members))

        // Control: the guest's own empty folder, made the way its client makes it, goes.
        val empty = guestFolder(link, t.a)
        abandon(link, empty).statusCode(204)
        assertNull(node(empty))
    }

    /** The name's last editor decides whose placeholder it is (ADR 0093 § 8): a member who renamed the guest's empty
     *  folder made it the member's, another guest's is that guest's. Each beside the same node's control. */
    @Test
    fun onlyThePlaceholderWhoseNameTheCallingIdentityWroteLastIsErased() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        val other = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)

        val renamed = guestFolder(link, t.a)
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body(
                """{"parentId":"${t.a}","encryptedName":"cmVuYW1lZA==","slugToken":"${GuestTreeShare.slug()}",""" +
                    """"wrappedContentKey":"cmV3cmFw","epoch":0,"placementRevision":0,"parentEpoch":0}""",
            )
            .`when`().patch("/api/scopes/${t.scopeId}/nodes/$renamed").then().statusCode(204)
        abandon(link, renamed).statusCode(409).body("code", equalTo("not_a_placeholder"))

        val theirs = guestFolder(other, t.a)
        abandon(link, theirs).statusCode(409).body("code", equalTo("not_a_placeholder"))
        assertNotNull(node(renamed)); assertNotNull(node(theirs))

        // Controls: the guest that wrote the name last takes each back.
        GuestTreeShare.place(link, renamed, t.a, placementRevision = 1).statusCode(204) // the guest renames it back: its name again
        abandon(link, renamed).statusCode(204)
        abandon(other, theirs).statusCode(204)
    }

    /** D2, accepted (ADR 0080 decision 6, owner 2026-10-16): a move re-seals the name, so a guest that moved a member's
     *  empty folder is its name's last editor and takes it back — a name with nothing behind it, which the guest could
     *  already rename away. Pinned, so a change to it is a decision and not a side effect. */
    @Test
    fun aGuestThatMovedAMembersEmptyFolderMayTakeItBack() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        val members = seedNode(t.scopeId, NodeEntity.FOLDER, parentId = t.a, wrappedContentKey = CONTENT_KEY, nameEditor = PrincipalKey.User(t.userId))
        abandon(link, members).statusCode(409).body("code", equalTo("not_a_placeholder")) // control: before the move
        GuestTreeShare.place(link, members, t.root).statusCode(204)
        abandon(link, members).statusCode(204)
        assertNull(node(members))
    }

    /** D3: a node a live link is rooted on is never a placeholder — the erase would end that link (`NodeSubtreePurge`
     *  tombstones every link on the node). A revoked link still counts (a member can turn it back on); a deleted one is
     *  no link any more. */
    @Test
    fun anEmptyFolderThatCarriesALinkIsKeptAndTheLinkStaysLive() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        val folder = guestFolder(link, t.a)
        val carried = GuestTreeShare.seed(t.userId, t.scopeId, folder, ShareEntity.READ)
        abandon(link, folder).statusCode(409).body("code", equalTo("not_a_placeholder"))
        assertNotNull(node(folder))
        assertEquals(false, QuarkusTransaction.requiringNew().call { ShareEntity.findById(carried.shareId)!!.deleted }, "the link is live")

        given().header("Authorization", bearer(t.userId)).`when`().post("/api/shares/${carried.shareId}/revoke").then().statusCode(204)
        abandon(link, folder).statusCode(409).body("code", equalTo("not_a_placeholder"))

        // Control: the link deleted, the same folder goes.
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/shares/${carried.shareId}").then().statusCode(204)
        abandon(link, folder).statusCode(204)
        assertNull(node(folder))
    }

    /** Fail closed: a kind no implementation declares is never a placeholder, empty or not. */
    @Test
    fun aKindNobodyDeclaresIsNeverAPlaceholder() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        val undeclared = seedNode(t.scopeId, "NOT_A_KIND", parentId = t.a, wrappedContentKey = CONTENT_KEY, nameEditor = guest(link))
        abandon(link, undeclared).statusCode(409).body("code", equalTo("not_a_placeholder"))
        assertNotNull(node(undeclared))
    }

    @Test
    fun aContributorErasesNothingOutsideItsReach() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        // Outside the subtree is not found, the same as no node at all.
        val beside = placeholder(t, t.outside, link)
        abandon(link, beside).statusCode(404).body("code", equalTo("node_not_found"))
        abandon(link, UUID.randomUUID().toString()).statusCode(404).body("code", equalTo("node_not_found"))
        // The root itself is never erased by a guest.
        abandon(link, t.root).statusCode(422).body("code", equalTo("illegal_structure"))
        // A guest's identity folder, and what lies in it, is no READ_WRITE guest's (ADR 0080 decision 11).
        val folder = identityFolder(t, t.a, identityOf(link))
        val inside = placeholder(t, folder, link)
        abandon(link, inside).statusCode(422).body("code", equalTo("illegal_structure"))
        // A trashed placeholder is a member's to restore or purge.
        val trashed = placeholder(t, t.a, link)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(trashed)!!.deletedAt = Instant.now() }
        abandon(link, trashed).statusCode(404).body("code", equalTo("node_not_found"))
        for (id in listOf(beside, t.root, inside, trashed)) assertNotNull(node(id), "a refusal writes nothing")
        // Control: inside the reach, the same kind goes.
        abandon(link, placeholder(t, t.a, link)).statusCode(204)
    }

    /** A WRITE_ONLY guest's reach is the inside of its own identity folder in the root (ADR 0080 decision 11). */
    @Test
    fun aWriteOnlyGuestErasesOnlyInsideItsOwnIdentityFolder() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.WRITE_ONLY)
        // No identity folder yet: nothing to reach.
        val inRoot = placeholder(t, t.root, link)
        abandon(link, inRoot).statusCode(403).body("code", equalTo("share_not_contributable"))

        val own = identityFolder(t, t.root, identityOf(link))
        abandon(link, inRoot).statusCode(404).body("code", equalTo("node_not_found"))
        abandon(link, own).statusCode(422).body("code", equalTo("illegal_structure"))
        val another = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.WRITE_ONLY)
        val theirs = placeholder(t, identityFolder(t, t.root, identityOf(another)), link)
        abandon(link, theirs).statusCode(404).body("code", equalTo("node_not_found"))
        for (id in listOf(inRoot, own, theirs)) assertNotNull(node(id), "a refusal writes nothing")

        // Control: inside its own folder.
        val mine = placeholder(t, own, link)
        abandon(link, mine).statusCode(204)
        assertNull(node(mine))
    }

    @Test
    fun aReadGuestErasesNothingAndTheGateRefusesAsForTheOtherGuestWrites() {
        val t = tree()
        val read = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ)
        val p = placeholder(t, t.a, read)
        abandon(read, p).statusCode(403).body("code", equalTo("share_not_contributable"))
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.root, ShareEntity.READ_WRITE)
        abandon(link.copy(token = Tokens.random()), p).statusCode(403).body("code", equalTo("share_not_contributable"))
        abandon(Link(TestIds.shareId(), link.token), p).statusCode(404).body("code", equalTo("share_not_found"))
        given().header("Authorization", bearer(t.userId)).`when`().post("/api/shares/${link.shareId}/revoke").then().statusCode(204)
        abandon(link, p).statusCode(404).body("code", equalTo("share_not_found"))
        assertNotNull(node(p))
    }

    // --- rows ------------------------------------------------------------------------------------------------------

    private fun node(id: String): NodeEntity? = QuarkusTransaction.requiringNew().call { NodeEntity.findById(id) }

    private fun revision(scopeId: String): Long = QuarkusTransaction.requiringNew().call { nodes.treeRevision(scopeId) }

    private fun identityOf(link: Link): String =
        QuarkusTransaction.requiringNew().call { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }

    /** A guest's identity folder under [parentId], as the application's own operation makes it (core's column). */
    private fun identityFolder(t: Tree, parentId: String, identityId: String): String {
        val id = seedNode(t.scopeId, NodeEntity.FOLDER, parentId = parentId, wrappedContentKey = CONTENT_KEY)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(id)!!.shareIdentityId = identityId }
        return id
    }

    /** An upload session opened for [ownerId] (declared as its owner, as a start declares it), by an account or an identity. */
    private fun openSession(scopeId: String, ownerId: String, openerUserId: String? = null, openerIdentity: String? = null): String {
        val sessionId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            UploadSessionEntity().apply {
                id = sessionId; this.scopeId = scopeId; storageKey = "k-${UUID.randomUUID()}".take(64)
                chunkCount = 1; byteSize = 1; wrappedBlobKey = "d3JhcA=="; this.openerUserId = openerUserId
                openerShareIdentityId = openerIdentity; lastWindowAt = Instant.now(); blobId = UUID.randomUUID().toString()
                this.ownerId = ownerId; blobRole = "TEST_ROLE"
            }.persist()
        }
        return sessionId
    }
}
