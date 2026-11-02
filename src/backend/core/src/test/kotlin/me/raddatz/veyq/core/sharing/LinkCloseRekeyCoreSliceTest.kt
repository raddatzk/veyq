package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestNodeRekey
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR 0114's acceptance criteria (§ Bite tests), each with a positive control on the same predicate: closing a link
 * re-keys the subtree below it. Core alone, every node a `FOLDER`; the probes written before it was built are
 * `LinkCloseRekeyProbeCoreSliceTest`'s.
 *
 * Every world is a root folder holding a child and a trashed grandchild — the subtree a close marks, live and trashed —
 * and a folder beside it, outside the link, that no close of the root's link may mark. The sweep runs over the whole
 * test database, which other classes' links share, so its cases read their own rows, never its count.
 */
@QuarkusTest
class LinkCloseRekeyCoreSliceTest {

    @Inject lateinit var store: SharingStore
    @Inject lateinit var sweep: ShareExpiryCloseSweep

    // --- decision 1: every close kind marks the subtree, and only it ---

    @Test
    fun aRevokeMarksTheSubtreeDue() = closes { w, link -> revoke(w, link).statusCode(204) }

    @Test
    fun aDeleteMarksTheSubtreeDue() = closes { w, link ->
        given().header("Authorization", bearer(w.owner)).`when`().delete("/api/shares/$link").then().statusCode(204)
    }

    @Test
    fun aReissueMarksTheSubtreeDueAndTheNewLinkFollowsTheNewKey() {
        val w = world()
        val old = createShare(w, w.root)
        val fresh = TestIds.shareId()
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$fresh",${ShareWriteProof.field(fresh)},"wrap":"d3JhcA==","sealedName":"c2VhbGVk",""" +
                    """${ShareWriteProof.recovery(epoch = 0, nodeEpoch = 0)}}""",
            )
            .`when`().post("/api/shares/$old/reissue").then().statusCode(201)
        assertSubtreeDue(w)
        // The new link is open: the root's re-key wraps its next key under it, never under the old one.
        parts(w).statusCode(200).body("shares.shareId", hasItem(fresh)).body("shares.shareId", not(hasItem(old)))
    }

    @Test
    fun aBulkSwitchOffMarksTheSubtreeDue() = closes { w, _ ->
        given().header("Authorization", bearer(w.owner)).`when`().post("/api/scopes/${w.scopeId}/shares/revoke").then().statusCode(200)
    }

    @Test
    fun aRemovedMembersSwitchOffMarksTheSubtreeDue() = closes { w, _ ->
        // A removal's switch-off of the member's links, inside the removal's transaction under the scope row's lock.
        assertEquals(1, QuarkusTransaction.requiringNew().call {
            ScopeEntity.lockRow(w.scopeId)
            store.deactivateSharesByOwnerInScope(w.owner, w.scopeId)
        })
    }

    @Test
    fun theEndDateSweepMarksTheSubtreeDue() = closes { w, link ->
        expireInPlace(link)
        sweep.sweep(Instant.now())
        assertTrue(row(link).expiryClosed, "the sweep recorded its close")
    }

    /** Before the close nothing is due (the control); after it the root, its child and its trashed grandchild are, the
     *  folder beside them is not, and the link carries no key any more. */
    private fun closes(close: (World, String) -> Unit) {
        val w = world()
        val link = createShare(w, w.root)
        assertFalse(listOf(w.root, w.child, w.trashed, w.beside).any(TestNodeRekey::isDue), "control: an open link marks nothing")
        close(w, link)
        assertSubtreeDue(w)
        assertNull(row(link).wrap, "a closed link keeps no key of the root")
    }

    private fun assertSubtreeDue(w: World) {
        assertTrue(TestNodeRekey.isDue(w.root), "the root")
        assertTrue(TestNodeRekey.isDue(w.child), "a live node below it")
        assertTrue(TestNodeRekey.isDue(w.trashed), "a trashed node below it")
        assertFalse(TestNodeRekey.isDue(w.beside), "a node outside the link")
    }

    // --- decision 2: a WRITE_ONLY close marks nothing ---

    @Test
    fun aWriteOnlyLinksCloseMarksNothingAndClearsItsDropKey() {
        val w = world()
        val dropBox = writeOnlyShare(w)
        assertEquals(true, QuarkusTransaction.requiringNew().call { store.revoke(w.owner, dropBox) })
        assertFalse(TestNodeRekey.isDue(w.root), "a WRITE_ONLY close re-keys nothing")
        assertNull(row(dropBox).sealedDropPublicKey, "its sealed drop public key goes with the close")
        // Control: a viewable link on the same root, closed the same way, marks it.
        val viewer = createShare(w, w.root)
        assertEquals(true, QuarkusTransaction.requiringNew().call { store.revoke(w.owner, viewer) })
        assertTrue(TestNodeRekey.isDue(w.root))
    }

    // --- decisions 3 and 4: a due node waits, a replaced key is stale ---

    @Test
    fun aWriteIntoADueNodeWaitsAndThePreCloseEpochIsStaleAfterTheReKey() {
        val w = world()
        val link = createShare(w, w.root)
        meta(w, revision = 0, nodeEpoch = 0).statusCode(204) // control: the open link's root takes the write
        revoke(w, link).statusCode(204)
        meta(w, revision = 1, nodeEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        TestNodeRekey.finish(w.scopeId)
        meta(w, revision = 1, nodeEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        meta(w, revision = 1, nodeEpoch = 1).statusCode(204) // the root's own key's epoch now
    }

    // --- decision 5: open links only, every identity ---

    @Test
    fun aCloseReWrapsNoClosedLinkAndReSealsEveryIdentity() {
        val w = world()
        val open = createShare(w, w.root, role = "READ_WRITE")
        val closed = createShare(w, w.root, role = "READ_WRITE")
        val openGuest = contributor(open)
        val closedGuest = contributor(closed)
        revoke(w, closed).statusCode(204)
        parts(w).statusCode(200)
            .body("shares.shareId", hasItem(open)) // control: the open sibling follows the new key
            .body("shares.shareId", not(hasItem(closed)))
            .body("identities.identityId", hasItem(openGuest))
            .body("identities.identityId", hasItem(closedGuest))
        // The commit takes exactly that: the open link's new wrap and both names, and the closed link stays keyless.
        val rekeyId = UUID.randomUUID()
        stage(
            w, rekeyId,
            """"shares":[{"shareId":"$open","wrap":"$NEW_WRAP"}],"identities":[""" +
                """{"identityId":"$openGuest","sealedDisplayName":"bmV3"},{"identityId":"$closedGuest","sealedDisplayName":"bmV3"}]""",
        ).statusCode(204)
        commitRoot(w, rekeyId, scopeEpoch = 0).statusCode(204)
        assertEquals(NEW_WRAP, row(open).wrap)
        assertNull(row(closed).wrap)
        assertEquals("bmV3", QuarkusTransaction.requiringNew().call { ShareIdentityEntity.findById(closedGuest)!!.sealedDisplayName })
    }

    @Test
    fun aCommitMissingAClosedLinksIdentityIsRefused() {
        val w = world()
        val open = createShare(w, w.root, role = "READ_WRITE")
        val closed = createShare(w, w.root, role = "READ_WRITE")
        val closedGuest = contributor(closed)
        // The guest's upload below the root keeps its identity past the link's delete (`SharingStore.dropDeadIdentities`).
        seedAttachment(w.scopeId, w.child, "COVER", shareIdentityId = closedGuest)
        given().header("Authorization", bearer(w.owner)).`when`().delete("/api/shares/$closed").then().statusCode(204)
        assertEquals(true, QuarkusTransaction.requiringNew().call { ShareEntity.findById(closed)!!.deleted })
        val rekeyId = UUID.randomUUID()
        stage(w, rekeyId, """"shares":[{"shareId":"$open","wrap":"$NEW_WRAP"}]""").statusCode(204)
        commitRoot(w, rekeyId, scopeEpoch = 0).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        // Control: with the tombstone's contributor re-sealed too, it commits.
        stage(w, rekeyId, """"shares":[{"shareId":"$open","wrap":"$NEW_WRAP"}],"identities":[{"identityId":"$closedGuest","sealedDisplayName":"bmV3"}]""")
            .statusCode(204)
        commitRoot(w, rekeyId, scopeEpoch = 0).statusCode(204)
    }

    // --- decision 6: the sweep marks an expired link once ---

    @Test
    fun theSweepClosesAnExpiredLinkOnceAndLeavesAnOpenOne() {
        val w = world()
        val expired = createShare(w, w.root)
        val other = world()
        val open = createShare(other, other.root, expiresAt = Instant.now().plusSeconds(3600))
        expireInPlace(expired)
        sweep.sweep(Instant.now())
        assertTrue(row(expired).expiryClosed)
        assertSubtreeDue(w)
        assertFalse(TestNodeRekey.isDue(other.root), "control: a link before its end date is left open")
        assertEquals(CONTENT_KEY_WRAP, row(open).wrap)
        TestNodeRekey.finish(w.scopeId)
        sweep.sweep(Instant.now())
        assertFalse(TestNodeRekey.isDue(w.root), "a link the sweep closed is not closed again: nothing marked a second time")
    }

    // --- decision 7: a reopen carries the key ---

    @Test
    fun aReactivationWithoutTheKeyIsRefusedAndWithItOpensTheLink() {
        val w = world()
        val link = createShare(w, w.root)
        revoke(w, link).statusCode(204)
        reactivate(w, link, """{${ShareWriteProof.reopen(0)}}""").statusCode(409).body("code", equalTo("node_rekey_pending"))
        TestNodeRekey.finish(w.scopeId)
        reactivate(w, link, """{"nodeEpoch":1}""").statusCode(422).body("code", equalTo("link_key_required"))
        reactivate(w, link, """{"nodeEpoch":1,"wrap":"$NEW_WRAP"}""").statusCode(422).body("code", equalTo("link_key_required"))
        reactivate(w, link, """{"nodeEpoch":1,"sealedDropPublicKey":"ZHJvcA==","sealedName":"bmFtZQ=="}""")
            .statusCode(400).body("code", equalTo("invalid_request"))
        reactivate(w, link, """{${ShareWriteProof.reopen(0)}}""").statusCode(409).body("code", equalTo("stale_epoch"))
        given().`when`().get("/api/shares/$link").then().statusCode(404)
        reactivate(w, link, """{${ShareWriteProof.reopen(1)}}""").statusCode(204)
        given().`when`().get("/api/shares/$link").then().statusCode(200)
            .body("wrap", equalTo(ShareWriteProof.REOPEN_WRAP)).body("sealedName", equalTo(ShareWriteProof.REOPEN_NAME))
    }

    @Test
    fun anEditMovingAPassedEndDateIntoTheFutureCarriesTheKey() {
        val w = world()
        val link = createShare(w, w.root)
        expireInPlace(link)
        val future = Instant.now().plusSeconds(3600)
        edit(w, link, """{"expiresAt":"$future"}""").statusCode(422).body("code", equalTo("link_key_required"))
        // An edit that reopens nothing carries no key.
        val other = createShare(w, w.beside)
        edit(w, other, """{"expiresAt":"$future",${ShareWriteProof.reopen(0)}}""").statusCode(400)
        edit(w, link, """{"expiresAt":"$future",${ShareWriteProof.reopen(0)}}""").statusCode(204)
        assertEquals(ShareWriteProof.REOPEN_WRAP, row(link).wrap)
        assertFalse(row(link).expiryClosed)
        given().`when`().get("/api/shares/$link").then().statusCode(200)
        sweep.sweep(Instant.now())
        assertEquals(ShareWriteProof.REOPEN_WRAP, row(link).wrap, "the reopened link is open: the sweep leaves it")
        assertFalse(TestNodeRekey.isDue(w.root))
    }

    @Test
    fun anEditSettingAPassedEndDateClosesTheLinkAtOnce() {
        val w = world()
        val link = createShare(w, w.root)
        edit(w, link, """{"expiresAt":"${Instant.now().minusSeconds(60)}"}""").statusCode(204)
        assertSubtreeDue(w)
        assertTrue(row(link).expiryClosed)
        assertNull(row(link).wrap)
        TestNodeRekey.finish(w.scopeId)
        sweep.sweep(Instant.now())
        assertFalse(TestNodeRekey.isDue(w.root), "the edit carried the close out: the sweep marks nothing again")
    }

    // --- decision 8: a key staged before the close does not land ---

    @Test
    fun aWrapStagedBeforeARevokeIsRefusedAndAnOpenSiblingsLands() {
        val w = world()
        val sibling = createShare(w, w.root)
        val link = createShare(w, w.root)
        markScopeDue(w)
        val rekeyId = UUID.randomUUID()
        stage(w, rekeyId, """"shares":[{"shareId":"$sibling","wrap":"$NEW_WRAP"},{"shareId":"$link","wrap":"$NEW_WRAP"}]""").statusCode(204)
        revoke(w, link).statusCode(204)
        commitRoot(w, rekeyId, scopeEpoch = 0).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertNull(row(link).wrap)
        // Control: minted afresh after the close, the open sibling's wrap lands and the closed link stays keyless.
        val fresh = UUID.randomUUID()
        stage(w, fresh, """"shares":[{"shareId":"$sibling","wrap":"$NEW_WRAP"}]""").statusCode(204)
        commitRoot(w, fresh, scopeEpoch = 0).statusCode(204)
        assertEquals(NEW_WRAP, row(sibling).wrap)
        assertNull(row(link).wrap)
    }

    // --- helpers ---

    private data class World(
        val owner: String, val scopeId: String, val root: String, val child: String, val trashed: String, val beside: String,
    )

    private fun world(): World {
        val (owner, scopeId, _) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val trashed = seedNode(scopeId, NodeEntity.FOLDER, parentId = child, wrappedContentKey = CONTENT_KEY)
        val beside = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(trashed)!!.deletedAt = Instant.now() }
        return World(owner, scopeId, root, child, trashed, beside)
    }

    private fun createShare(w: World, nodeId: String, role: String = "READ", expiresAt: Instant? = null): String {
        val id = TestIds.shareId()
        val expiry = expiresAt?.let { ""","expiresAt":"$it"""" }.orEmpty()
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$id",${ShareWriteProof.field(id)},"nodeId":"$nodeId","role":"$role","wrap":"$CONTENT_KEY_WRAP",""" +
                    """"sealedName":"c2VhbGVk",${ShareWriteProof.recovery(epoch = 0, nodeEpoch = TestNodeRekey.epochOf(nodeId))}$expiry}""",
            )
            .`when`().post("/api/shares").then().statusCode(201)
        return id
    }

    /** A WRITE_ONLY link on the root, persisted as its row: core's `FOLDER` takes no WRITE_ONLY link (`ShareRoots`), and
     *  the close is decided by the role alone. */
    private fun writeOnlyShare(w: World): String {
        val id = TestIds.shareId()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                this.id = id; ownerAccount = w.owner; scopeId = w.scopeId; nodeId = w.root; role = ShareEntity.WRITE_ONLY
                sealedDropPublicKey = "ZHJvcA=="; sealedName = "c2VhbGVk"; sealedShareKey = "cmVjb3Zlcnk="; sealedShareKeyEpoch = 0
                createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    /** A READ_WRITE guest's identity on [shareId], its name under the root's key; returns its id. */
    private fun contributor(shareId: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ShareIdentityEntity().apply {
                this.id = id; this.shareId = shareId; sealedDisplayName = "bmFtZQ=="; tokenHash = "h-$id".take(64)
            }.persist()
        }
        return id
    }

    /** The link's end date passes: set in its row, as time would — no request moves it. */
    private fun expireInPlace(shareId: String) = QuarkusTransaction.requiringNew().run {
        ShareEntity.findById(shareId)!!.expiresAt = Instant.now().minusSeconds(1)
    }

    /** Every node of the scope due, as a rotation's commit leaves it — the epoch unchanged here (core's parts only). */
    private fun markScopeDue(w: World) = QuarkusTransaction.requiringNew().run {
        NodeEntity.update("rekeyDue = true where scopeId = ?1", w.scopeId)
    }

    private fun row(shareId: String): ShareEntity = QuarkusTransaction.requiringNew().call { ShareEntity.findById(shareId)!! }

    private fun revoke(w: World, shareId: String): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).`when`().post("/api/shares/$shareId/revoke").then()

    private fun reactivate(w: World, shareId: String, body: String): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/shares/$shareId/reactivate").then()

    private fun edit(w: World, shareId: String, body: String): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON).body(body)
            .`when`().patch("/api/shares/$shareId").then()

    private fun meta(w: World, revision: Long, nodeEpoch: Int): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"bWV0YQ==","metaRevision":$revision,"nodeEpoch":$nodeEpoch}""")
            .`when`().patch("/api/scopes/${w.scopeId}/nodes/${w.root}").then()

    private fun parts(w: World): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).`when`().get("/api/scopes/${w.scopeId}/rekey/nodes/${w.root}").then()

    /** Stage the root's core parts under [rekeyId]: [members] are the JSON members after `fromEpoch`, the root's own
     *  child entry included where it has one. */
    private fun stage(w: World, rekeyId: UUID, members: String): ValidatableResponse {
        val child = QuarkusTransaction.requiringNew().call { NodeEntity.findById(w.child)!! }
        val entry = """"children":[{"nodeId":"${w.child}","placementRevision":${child.placementRevision},""" +
            """"encryptedName":"bmV3","slugToken":"${slug()}","wrappedContentKey":"bmV3LXdyYXA="}]"""
        return given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body("""{"fromEpoch":${TestNodeRekey.epochOf(w.root)},$entry,$members}""")
            .`when`().post("/api/scopes/${w.scopeId}/rekey/nodes/${w.root}/$rekeyId/parts").then()
    }

    private fun commitRoot(w: World, rekeyId: UUID, scopeEpoch: Int): ValidatableResponse {
        val root = QuarkusTransaction.requiringNew().call { NodeEntity.findById(w.root)!! }
        return given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body(
                """{"nodeEpoch":${root.epoch},"scopeEpoch":$scopeEpoch,"placementRevision":${root.placementRevision},""" +
                    """"metaRevision":${root.metaRevision},"encryptedName":"bmV3LXJvb3Q=","slugToken":"${slug()}",""" +
                    """"wrappedContentKey":"bmV3LXJvb3Qtd3JhcA=="}""",
            )
            .`when`().post("/api/scopes/${w.scopeId}/rekey/nodes/${w.root}/$rekeyId").then()
    }

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private companion object {
        const val CONTENT_KEY_WRAP = "d3JhcA=="
        const val NEW_WRAP = "bmV3LXNoYXJlLXdyYXA="
    }
}
