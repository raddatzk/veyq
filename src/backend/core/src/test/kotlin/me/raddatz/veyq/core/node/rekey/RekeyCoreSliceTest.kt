package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.content.NodeCover
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.tasks.CapabilityTags
import me.raddatz.veyq.core.tasks.JobEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedScopeMember
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.spaces.scope.ScopesStore
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.inject.Inject
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A scope's re-key after a rotation (ADR 0109, uc.spaces.rekey-after-rotation), core alone: every node is a `FOLDER`.
 * The world: an owner's scope at epoch 0 holding a root folder R (with a cover attachment, a READ_WRITE link and one
 * guest identity on that link), a live child C and a trashed child T under R; then the scope rotates to epoch 1 and the
 * owner's grant follows. Every refusal reads the rows back unchanged, and each has a control on the same predicate.
 * Memories' part (media, buckets) is `MediaNodeRekeySliceTest`.
 */
@QuarkusTest
class RekeyCoreSliceTest {

    @Inject
    lateinit var scopes: ScopesStore

    /** ADR 0109 bite test 2, the listing half: every live and trashed node with its epoch; only a caller holding the
     *  current grant reads it. A plain member's legs are [aMemberWithTheCurrentGrantReKeysANode] and
     *  [aMemberWithAGrantBehindIsRefused]. */
    @Test
    fun theListingServesEveryNodeTrashedIncludedAndOnlyWithTheCurrentGrant() {
        val w = world(rotated = false)
        rotate(w.scopeId, to = 1, grantFollows = false)
        get(w, "/rekey/nodes").statusCode(403).body("code", equalTo("grant_behind"))
        grantAt(w.scopeId, w.owner, 1)
        get(w, "/rekey/nodes").statusCode(200)
            .body("currentEpoch", equalTo(1))
            .body("nodes.size()", equalTo(3))
            .body("nodes.find { it.id == '${w.trashed}' }.trashed", equalTo(true))
            .body("nodes.find { it.id == '${w.child}' }.trashed", equalTo(false))
            .body("nodes.find { it.id == '${w.root}' }.epoch", equalTo(0))
            .body("nodes.ownerOnly", equalTo(listOf(false, false, false)))
    }

    /** ADR 0111 decision 11: a re-key is not governance, so a plain member holding the current grant lists, reads, stages
     *  and commits it — top-down, as an owner does. (Red under the owner-only gate: `not_scope_owner` at the listing.) */
    @Test
    fun aMemberWithTheCurrentGrantReKeysANode() {
        val w = world()
        val member = seedScopeMember(w.scopeId, grantedEpoch = 1)
        get(w, "/rekey/nodes", caller = member).statusCode(200).body("nodes.size()", equalTo(3))
        get(w, "/rekey/nodes/${w.root}", caller = member).statusCode(200).body("shares[0].shareId", equalTo(w.shareId))
        val rekeyId = UUID.randomUUID()
        stageRootParts(w, rekeyId, caller = member)
        commitRoot(w, rekeyId, caller = member).statusCode(204)
        assertEquals(listOf(1, "bmV3LXJvb3Qtd3JhcA=="), listOf(row(w.root).epoch, row(w.root).wrappedContentKey))
        commitChild(w, UUID.randomUUID(), placementRevision = 1, caller = member).statusCode(204)
        assertEquals(1, row(w.child).epoch)
    }

    /** `grant_behind` is unchanged for a member: one whose grant lags the current epoch neither lists nor commits; the
     *  control is the same member once its grant follows. */
    @Test
    fun aMemberWithAGrantBehindIsRefused() {
        val w = world()
        val member = seedScopeMember(w.scopeId, grantedEpoch = 0)
        get(w, "/rekey/nodes", caller = member).statusCode(403).body("code", equalTo("grant_behind"))
        val rekeyId = UUID.randomUUID()
        post(w, "/rekey/nodes/${w.root}/$rekeyId", rootCommit(), caller = member).statusCode(403).body("code", equalTo("grant_behind"))
        assertEquals(0, row(w.root).epoch)
        grantAt(w.scopeId, member, 1)
        get(w, "/rekey/nodes", caller = member).statusCode(200) // control
    }

    /**
     * ADR 0111 decision 11, last bullet (ADR 0080 Decision 11): a guest's identity folder and a node below one are
     * re-keyed by an owner only. The listing marks them `ownerOnly` for everyone; a member is refused `not_scope_owner`
     * at their parts, stage and commit and nothing moves — after it re-keyed their parent, so the refusal is not the
     * parent's `node_rekey_pending`. An owner then re-keys each, top-down. (A drop box's own kind is `DropBoxRekeySliceTest`'s:
     * core alone declares none.)
     *
     * The parent is the control on the same predicate, and the narrowing's own case: a node that merely **carries a drop
     * key** — its WRITE_ONLY link's, as a Memory with an uploader link does — is any member's. Its re-key, the drop key
     * replaced, the link's drop public key and the guest's `K_up` re-sealed (ADR 0109 point 5), lands from a member.
     * (Red under the drop-key predicate: `not_scope_owner` at the box.)
     */
    @Test
    fun aMemberReKeysADropKeyedNodeButIsRefusedAnIdentityFolderAndWhatLiesBelow() {
        val d = dropBoxWorld()
        val member = seedScopeMember(d.w.scopeId, grantedEpoch = 1)
        val listing = get(d.w, "/rekey/nodes", caller = member).statusCode(200)
        for (n in listOf(d.folder, d.inner)) listing.body("nodes.find { it.id == '$n' }.ownerOnly", equalTo(true))
        listing.body("nodes.find { it.id == '${d.box}' }.ownerOnly", equalTo(false))

        // the drop-keyed node is the member's: its parts read, staged and committed
        get(d.w, "/rekey/nodes/${d.box}", caller = member).statusCode(200)
            .body("shares[0].hasDropPublicKey", equalTo(true)).body("identities[0].wrappedIdentityKey", equalTo("b2xkLWstdXA="))
        val boxRekey = UUID.randomUUID()
        post(d.w, "/rekey/nodes/${d.box}/$boxRekey/parts", d.boxParts(), caller = member).statusCode(204)
        d.commitBox(boxRekey, caller = member).statusCode(204)
        assertEquals(listOf<Any?>(1, "bmV3LWRyb3AtcHJpdg=="), listOf(row(d.box).epoch, row(d.box).sealedDropPrivateKey))
        QuarkusTransaction.requiringNew().run {
            assertEquals("bmV3LWRyb3AtcHVi", ShareEntity.findById(d.w.shareId)!!.sealedDropPublicKey)
            assertEquals("bmV3LWstdXA=", ShareIdentityEntity.findById(d.w.identityId)!!.wrappedIdentityKey)
        }

        // the identity folder and what lies below it are an owner's
        for (n in listOf(d.folder, d.inner)) {
            get(d.w, "/rekey/nodes/$n", caller = member).statusCode(403).body("code", equalTo("not_scope_owner"))
            post(d.w, "/rekey/nodes/$n/${UUID.randomUUID()}/parts", """{"fromEpoch":0}""", caller = member)
                .statusCode(403).body("code", equalTo("not_scope_owner"))
        }
        d.commitFolder(UUID.randomUUID(), caller = member).statusCode(403).body("code", equalTo("not_scope_owner"))
        d.commitInner(caller = member).statusCode(403).body("code", equalTo("not_scope_owner"))
        assertEquals(listOf(0, 0), listOf(d.folder, d.inner).map { row(it).epoch }, "a refused re-key moves nothing")

        // an owner re-keys them: the identity folder (its child's entry staged), then the node below it
        val folderRekey = UUID.randomUUID()
        post(d.w, "/rekey/nodes/${d.folder}/$folderRekey/parts", d.folderParts()).statusCode(204)
        d.commitFolder(folderRekey).statusCode(204)
        d.commitInner(placementRevision = 1).statusCode(204)
        assertEquals(listOf(1, 1), listOf(d.folder, d.inner).map { row(it).epoch })
    }

    /** Two members re-keying the same node at once leave one commit and no lost write: each stages under its own re-key
     *  id; the first commit lands, the second answers `stale_epoch` (the node's row lock and `node.epoch >= current`),
     *  and the node holds the first one's key. */
    @Test
    fun twoMembersReKeyingTheSameNodeLeaveOneCommit() {
        val w = world()
        val a = seedScopeMember(w.scopeId, grantedEpoch = 1)
        val b = seedScopeMember(w.scopeId, grantedEpoch = 1)
        val (ra, rb) = UUID.randomUUID() to UUID.randomUUID()
        stageRootParts(w, ra, caller = a)
        stageRootParts(w, rb, caller = b)
        commitRoot(w, ra, caller = a).statusCode(204)
        post(w, "/rekey/nodes/${w.root}/$rb", rootCommit(wrap = "Yi1yb290LXdyYXA="), caller = b)
            .statusCode(409).body("code", equalTo("stale_epoch"))
        assertEquals(listOf(1, 1L, "bmV3LXJvb3Qtd3JhcA=="), listOf(row(w.root).epoch, row(w.root).placementRevision, row(w.root).wrappedContentKey))
    }

    /** ADR 0109 point 2: top-down — a child before its parent is `node_rekey_pending`; the parent's commit swaps every
     *  staged part in at once and moves only its own epoch; then the child commits. */
    @Test
    fun aCommitIsTopDownAndSwapsEveryStagedPartInOneTransaction() {
        val w = world()
        // the parent's epoch as read before its commit: 0, which lags the scope's 1
        commitChild(w, rekeyId = UUID.randomUUID(), parentEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        assertEquals(0, row(w.child).epoch)

        val rekeyId = UUID.randomUUID()
        stageRootParts(w, rekeyId)
        commitRoot(w, rekeyId).statusCode(204)
        val root = row(w.root)
        assertEquals(listOf(1, 1L, "bmV3LXJvb3Qtd3JhcA=="), listOf(root.epoch, root.placementRevision, root.wrappedContentKey))
        // each child's entry is under the new key now, at the next placement revision; its own key and epoch untouched
        for ((id, wrap) in listOf(w.child to "Yy13cmFw", w.trashed to "dC13cmFw")) {
            val c = row(id)
            assertEquals(listOf(wrap, 1L, 0), listOf(c.wrappedContentKey, c.placementRevision, c.epoch), id)
        }
        // the trashed child keeps its freed slug token (its own id), whatever was staged
        assertEquals(w.trashed, row(w.trashed).slugToken)
        QuarkusTransaction.requiringNew().run {
            assertEquals("YmxvYi13cmFw", BlobEntity.findById(w.coverBlob)!!.wrappedBlobKey)
            assertEquals("c2hhcmUtd3JhcA==", ShareEntity.findById(w.shareId)!!.wrap)
            assertEquals("bmV3LW5hbWU=", ShareIdentityEntity.findById(w.identityId)!!.sealedDisplayName)
            assertEquals(0L, NodeRekeyStageEntity.count("rekeyId", rekeyId.toString()), "the commit took its stage")
            // ADR 0109 point 4: only the node key's wrap moved; the recovery copy stays at its epoch
            val share = ShareEntity.findById(w.shareId)!!
            assertEquals(listOf("cmVjb3Zlcnk=", 0), listOf(share.sealedShareKey, share.sealedShareKeyEpoch))
        }
        // ...so the link keeps showing what is added and stays marked for renewal
        given().header("Authorization", bearer(w.owner)).`when`().get("/api/shares?nodeId=${w.root}")
            .then().statusCode(200).body("find { it.shareId == '${w.shareId}' }.reissueRequired", equalTo(true))
        // the child, its parent now current
        commitChild(w, UUID.randomUUID(), placementRevision = 1).statusCode(204)
        assertEquals(1, row(w.child).epoch)
    }

    /** The commit's own shape before any answer about the instance's state (inbox #534; the contract's
     *  `dependentSchemas` on `CommitNodeRekeyRequest`): a `parentId` without its `parentEpoch`, and the path's `scopeId`
     *  as the parent — the root has the one form null. Answered after them, a body the contract refuses would read to the
     *  API fuzzer as one accepted and then refused for the state. */
    @Test
    fun aCommitWithAParentButNoParentEpochOrTheScopeAsItsParentIs400BeforeAnyStateAnswer() {
        val w = world()
        val (stranger, _) = seedMemberScope()
        val nobody = UUID.randomUUID().toString()
        for ((caller, node, body) in listOf(
            // a parent without its epoch: as the owner (refused for it, as it was), on a node nobody has (was 404),
            // with a node epoch not the stored one (was 409 stale_epoch), as a stranger (was the non-member's 404)
            Triple(w.owner, w.child, childCommit(w, parentEpoch = null)),
            Triple(w.owner, nobody, childCommit(w, parentEpoch = null)),
            Triple(w.owner, w.child, childCommit(w, parentEpoch = null, nodeEpoch = 1)),
            Triple(stranger, w.child, childCommit(w, parentEpoch = null)),
            // the scope as the root's parent, with or without an epoch: as the owner (was 400 after the node's state),
            // with a node epoch not the stored one (was 409 stale_epoch), as a stranger (was the non-member's 404)
            Triple(w.owner, w.root, rootCommit(parentId = w.scopeId)),
            Triple(w.owner, w.root, rootCommit(parentId = w.scopeId, parentEpoch = 1)),
            Triple(w.owner, w.root, rootCommit(parentId = w.scopeId, nodeEpoch = 1)),
            Triple(stranger, w.root, rootCommit(parentId = w.scopeId)),
        )) {
            given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body(body)
                .`when`().post("/api/scopes/${w.scopeId}/rekey/nodes/$node/${UUID.randomUUID()}").then()
                .statusCode(400).body("code", equalTo("invalid_request"))
        }
        assertEquals(listOf(0, 0), listOf(row(w.root).epoch, row(w.child).epoch), "a refused commit writes nothing")

        // controls, each answered for the state once the shape is right: the child with its parent's epoch waits for
        // the parent; the root by its one form commits once staged
        commitChild(w, UUID.randomUUID(), parentEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        val rekeyId = UUID.randomUUID()
        stageRootParts(w, rekeyId)
        commitRoot(w, rekeyId).statusCode(204)
        assertEquals(1, row(w.root).epoch)
    }

    /** CR decision 2, fail closed: a row sealed under the node's key with no staged value refuses the whole commit —
     *  nothing written — and the same commit lands once it is staged. */
    @Test
    fun aCommitMissingAStagedPartWritesNothing() {
        val w = world()
        val rekeyId = UUID.randomUUID()
        stageRootParts(w, rekeyId, skipTrashedChild = true)
        commitRoot(w, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals(listOf(0, CONTENT_KEY), listOf(row(w.root).epoch, row(w.root).wrappedContentKey))
        assertEquals(CONTENT_KEY, row(w.child).wrappedContentKey)
        QuarkusTransaction.requiringNew().run {
            assertEquals("YXR0YWNobWVudA==", BlobEntity.findById(w.coverBlob)!!.wrappedBlobKey)
            assertEquals("b2xkLXNoYXJlLXdyYXA=", ShareEntity.findById(w.shareId)!!.wrap)
        }

        stageRootParts(w, rekeyId) // control: the missing child staged too
        commitRoot(w, rekeyId).statusCode(204)
        assertEquals(1, row(w.root).epoch)
    }

    /** A staged child that moved since it was staged is stale: the re-keyer stages it again. */
    @Test
    fun aStagedChildThatMovedSinceIsStale() {
        val w = world()
        val rekeyId = UUID.randomUUID()
        stageRootParts(w, rekeyId)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(w.child)!!.placementRevision = 7 }
        commitRoot(w, rekeyId).statusCode(409).body("code", equalTo("rekey_parts_stale"))
        assertEquals(0, row(w.root).epoch)

        stageRootParts(w, rekeyId, childRevision = 7) // control
        commitRoot(w, rekeyId).statusCode(204)
    }

    /**
     * ADR 0109 bite test 3: a write stating a node's epoch from before → `stale_epoch`; into a node not re-keyed yet →
     * `node_rekey_pending`; into a re-keyed one → accepted. A create under C and a meta write on R, each before and after.
     */
    @Test
    fun writesWaitPerNodeAndRefuseAKeyFromBefore() {
        val w = world()
        create(w, parent = w.child, parentEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        meta(w, w.root, nodeEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))

        val rekeyId = UUID.randomUUID()
        stageRootParts(w, rekeyId)
        commitRoot(w, rekeyId).statusCode(204)
        // R had no payload, so its meta revision is still 0
        meta(w, w.root, nodeEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        meta(w, w.root, nodeEpoch = 1).statusCode(204) // control: R is re-keyed
        // C still lags: only that node waits
        create(w, parent = w.child, parentEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        create(w, parent = w.root, parentEpoch = 1).statusCode(201)

        commitChild(w, UUID.randomUUID(), placementRevision = 1).statusCode(204)
        create(w, parent = w.child, parentEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        create(w, parent = w.child, parentEpoch = 1).statusCode(201) // control
    }

    /** ADR 0109 bite test 7, the server's half: a trashed node comes back only once the re-key reached it — never with
     *  a key from before. */
    @Test
    fun aTrashedNodeIsRestoredOnlyOnceTheReKeyReachedIt() {
        val w = world()
        restore(w, w.trashed, parentEpoch = 0).statusCode(409).body("code", equalTo("node_rekey_pending"))
        val rekeyId = UUID.randomUUID()
        stageRootParts(w, rekeyId)
        commitRoot(w, rekeyId).statusCode(204)
        // its parent is re-keyed, it is not
        restore(w, w.trashed, parentEpoch = 1).statusCode(409).body("code", equalTo("node_rekey_pending"))
        assertTrue(row(w.trashed).deletedAt != null)

        QuarkusTransaction.requiringNew().run { NodeEntity.findById(w.trashed)!!.epoch = 1 } // the re-key reached it
        restore(w, w.trashed, parentEpoch = 0).statusCode(409).body("code", equalTo("stale_epoch"))
        restore(w, w.trashed, parentEpoch = 1).statusCode(204) // control
        assertTrue(row(w.trashed).deletedAt == null)
    }

    /** ADR 0109 bite test 2, the job half: a queued rotation whose epoch is in completes only once no node lags. */
    @Test
    fun aQueuedRotationCompletesOnlyOnceItsReKeyIsDone() {
        val w = world()
        val jobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            JobEntity().apply {
                id = jobId; scopeId = w.scopeId; capabilityTag = CapabilityTags.SCOPE_ROTATION; status = JobEntity.LEASED
                leaseHolder = w.owner; leaseExpiresAt = Instant.now().plusSeconds(600); attempts = 1; maxAttempts = 5
                createdAt = Instant.now(); rotatedEpoch = 1
            }.persist()
        }
        complete(w, jobId).statusCode(409).body("code", equalTo("rekey_incomplete"))
        assertTrue(ScopeRekeying.lags(w.scopeId))
        QuarkusTransaction.requiringNew().run { NodeEntity.update("epoch = 1 where scopeId = ?1", w.scopeId) }
        assertFalse(ScopeRekeying.lags(w.scopeId))
        complete(w, jobId).statusCode(204) // control
    }

    /** ADR 0109 point 5: a node with a drop key gets a new one, its WRITE_ONLY link (no `wrap`) the new public half, and
     *  every guest identity its `K_up` re-sealed — each required, or nothing is written. */
    @Test
    fun aDropKeyedNodeReplacesItsDropKeyAndEveryGuestsIdentityKey() {
        val (owner, scopeId) = seedMemberScope()
        val box = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val shareId = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().toByteArray()).take(43)
        val identityId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity.findById(box)!!.sealedDropPrivateKey = "b2xkLWRyb3AtcHJpdg=="
            ShareEntity().apply {
                id = shareId; ownerAccount = owner; this.scopeId = scopeId; nodeId = box; role = "WRITE_ONLY"
                sealedDropPublicKey = "b2xkLWRyb3AtcHVi"; sealedShareKey = "cmVjb3Zlcnk="; sealedShareKeyEpoch = 0; createdAt = Instant.now()
            }.persist()
            ShareIdentityEntity().apply {
                id = identityId; this.shareId = shareId; sealedDisplayName = "Z3Vlc3QtbmFtZQ=="; tokenHash = "h-$identityId".take(64)
                wrappedIdentityKey = "b2xkLWstdXA="
            }.persist()
        }
        rotate(scopeId, to = 1, grantFollows = true, owner = owner)
        val w = World(owner, scopeId, box, box, box, "", shareId, identityId)
        get(w, "/rekey/nodes/$box").statusCode(200)
            .body("shares[0].hasWrap", equalTo(false)).body("shares[0].hasDropPublicKey", equalTo(true))
            .body("identities[0].wrappedIdentityKey", equalTo("b2xkLWstdXA="))
        val rekeyId = UUID.randomUUID()
        fun commit() = post(
            w, "/rekey/nodes/$box/$rekeyId",
            """{"nodeEpoch":0,"epoch":1,"placementRevision":0,"metaRevision":0,"encryptedName":"Ym94","slugToken":"${slug()}","wrappedContentKey":"Ym94LXdyYXA=","sealedDropPrivateKey":"bmV3LWRyb3AtcHJpdg=="}""",
        )
        // the identity's key staged, the link's new public half not: refused, nothing written
        post(w, "/rekey/nodes/$box/$rekeyId/parts", """{"fromEpoch":0,"identities":[{"identityId":"$identityId","wrappedIdentityKey":"bmV3LWstdXA="}]}""").statusCode(204)
        commit().statusCode(409).body("code", equalTo("rekey_parts_missing"))
        assertEquals("b2xkLWRyb3AtcHJpdg==", row(box).sealedDropPrivateKey)
        post(w, "/rekey/nodes/$box/$rekeyId/parts", """{"fromEpoch":0,"shares":[{"shareId":"$shareId","sealedDropPublicKey":"bmV3LWRyb3AtcHVi"}]}""").statusCode(204)
        commit().statusCode(204) // control
        assertEquals("bmV3LWRyb3AtcHJpdg==", row(box).sealedDropPrivateKey)
        QuarkusTransaction.requiringNew().run {
            assertEquals("bmV3LWRyb3AtcHVi", ShareEntity.findById(shareId)!!.sealedDropPublicKey)
            assertEquals(null, ShareEntity.findById(shareId)!!.wrap, "a WRITE_ONLY link carries no wrap, before or after")
            assertEquals("bmV3LWstdXA=", ShareIdentityEntity.findById(identityId)!!.wrappedIdentityKey)
            assertEquals("Z3Vlc3QtbmFtZQ==", ShareIdentityEntity.findById(identityId)!!.sealedDisplayName, "a WRITE_ONLY name is under K_up: kept")
        }
    }

    /** CR decision 4: an owner's scope row reads `rekeying` while a node lags, derived (a member's row:
     *  [aMembersScopeRowReadsRekeyingWhileANodeLags]). */
    @Test
    fun anOwnersScopeRowReadsRekeyingWhileANodeLags() {
        val (owner, scopeId, tenantId) = seedMemberScope()
        seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        assertEquals(false, rowRekeying(owner, tenantId, scopeId), "control: nothing rotated")
        rotate(scopeId, to = 1, grantFollows = true, owner = owner)
        assertEquals(true, rowRekeying(owner, tenantId, scopeId))
        QuarkusTransaction.requiringNew().run { NodeEntity.update("epoch = 1 where scopeId = ?1", scopeId) }
        assertEquals(false, rowRekeying(owner, tenantId, scopeId))
    }

    /** ADR 0111 decision 11: `rekeying` is served to every member, so every member's device continues an unfinished
     *  re-key. (Red under the owner-only row: a member's read null.) */
    @Test
    fun aMembersScopeRowReadsRekeyingWhileANodeLags() {
        val (owner, scopeId, tenantId) = seedMemberScope(ScopeEntity.GROUP)
        seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val member = seedScopeMember(scopeId, grantedEpoch = 0)
        assertEquals(false, rowRekeying(member, tenantId, scopeId), "control: nothing rotated")
        rotate(scopeId, to = 1, grantFollows = true, owner = owner)
        grantAt(scopeId, member, 1)
        assertEquals(true, rowRekeying(member, tenantId, scopeId))
        QuarkusTransaction.requiringNew().run { NodeEntity.update("epoch = 1 where scopeId = ?1", scopeId) }
        assertEquals(false, rowRekeying(member, tenantId, scopeId))
    }

    /** ADR 0109 bite test 8: a re-key reads and writes no store object — every blob keeps its storage key and size, and
     *  nothing enters the reclaim list. */
    @Test
    fun aReKeyTouchesNoStoreObject() {
        val w = world()
        val before = QuarkusTransaction.requiringNew().call {
            BlobEntity.findById(w.coverBlob)!!.let { listOf(it.storageKey, it.chunkCount, it.byteSize, it.manifest) } to StoreReclaimEntity.count()
        }
        val rekeyId = UUID.randomUUID()
        stageRootParts(w, rekeyId)
        commitRoot(w, rekeyId).statusCode(204)
        val after = QuarkusTransaction.requiringNew().call {
            BlobEntity.findById(w.coverBlob)!!.let { listOf(it.storageKey, it.chunkCount, it.byteSize, it.manifest) } to StoreReclaimEntity.count()
        }
        assertEquals(before, after)
        QuarkusTransaction.requiringNew().run { assertEquals("YmxvYi13cmFw", BlobEntity.findById(w.coverBlob)!!.wrappedBlobKey) }
    }

    // --- helpers ---

    private data class World(
        val owner: String, val scopeId: String, val root: String, val child: String, val trashed: String,
        val coverBlob: String, val shareId: String, val identityId: String,
    )

    private fun world(rotated: Boolean = true): World {
        val (owner, scopeId) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val trashed = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        given().header("Authorization", bearer(owner)).`when`().delete("/api/scopes/$scopeId/nodes/$trashed").then().statusCode(204)
        val cover = seedAttachment(scopeId, root, NodeCover.SLOT, uploaderUserId = owner)
        val shareId = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().toByteArray()).take(43).padEnd(43, 'A')
        val identityId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = shareId; ownerAccount = owner; this.scopeId = scopeId; nodeId = root; role = "READ_WRITE"
                wrap = "b2xkLXNoYXJlLXdyYXA="; sealedShareKey = "cmVjb3Zlcnk="; sealedShareKeyEpoch = 0; createdAt = Instant.now()
                sealedName = "bGluaw==" // a listed share is never a tombstone
            }.persist()
            ShareIdentityEntity().apply {
                id = identityId; this.shareId = shareId; sealedDisplayName = "b2xkLW5hbWU="; tokenHash = "h-$identityId".take(64)
            }.persist()
        }
        if (rotated) rotate(scopeId, to = 1, grantFollows = true, owner = owner)
        return World(owner, scopeId, root, child, trashed, cover.blobId, shareId, identityId)
    }

    private fun rotate(scopeId: String, to: Int, grantFollows: Boolean, owner: String? = null) {
        QuarkusTransaction.requiringNew().run { ScopeEntity.findById(scopeId)!!.currentEpoch = to }
        if (grantFollows) grantAt(scopeId, owner!!, to)
    }

    private fun grantAt(scopeId: String, userId: String, epoch: Int) {
        QuarkusTransaction.requiringNew().run {
            MembershipEntity.update("grantedEpoch = ?1 where scopeId = ?2 and userId = ?3", epoch, scopeId, userId)
        }
    }

    private fun stageRootParts(w: World, rekeyId: UUID, skipTrashedChild: Boolean = false, childRevision: Long = 0, caller: String = w.owner) {
        val children = buildList {
            add("""{"nodeId":"${w.child}","placementRevision":$childRevision,"encryptedName":"Yy1uYW1l","slugToken":"${slug()}","wrappedContentKey":"Yy13cmFw"}""")
            if (!skipTrashedChild) {
                add("""{"nodeId":"${w.trashed}","placementRevision":0,"encryptedName":"dC1uYW1l","slugToken":"${slug()}","wrappedContentKey":"dC13cmFw"}""")
            }
        }
        post(
            w, "/rekey/nodes/${w.root}/$rekeyId/parts",
            """{"fromEpoch":0,"children":[${children.joinToString(",")}],
               "blobs":[{"blobId":"${w.coverBlob}","wrappedBlobKey":"YmxvYi13cmFw"}],
               "shares":[{"shareId":"${w.shareId}","wrap":"c2hhcmUtd3JhcA=="}],
               "identities":[{"identityId":"${w.identityId}","sealedDisplayName":"bmV3LW5hbWU="}]}""",
            caller,
        ).statusCode(204)
    }

    private fun commitRoot(w: World, rekeyId: UUID, caller: String = w.owner): ValidatableResponse =
        post(w, "/rekey/nodes/${w.root}/$rekeyId", rootCommit(), caller)

    private fun commitChild(w: World, rekeyId: UUID, placementRevision: Long = 0, parentEpoch: Int = 1, caller: String = w.owner): ValidatableResponse =
        post(w, "/rekey/nodes/${w.child}/$rekeyId", childCommit(w, parentEpoch, placementRevision), caller)

    /** The root's commit body; [parentId] null leaves it out, as the root's one form. */
    private fun rootCommit(parentId: String? = null, parentEpoch: Int? = null, nodeEpoch: Int = 0, wrap: String = "bmV3LXJvb3Qtd3JhcA=="): String {
        val parent = parentId?.let { "\"parentId\":\"$it\"," } ?: ""
        val epoch = parentEpoch?.let { "\"parentEpoch\":$it," } ?: ""
        return """{$parent$epoch"nodeEpoch":$nodeEpoch,"epoch":1,"placementRevision":0,"metaRevision":0,"encryptedName":"bmV3LXJvb3Q=","slugToken":"${slug()}","wrappedContentKey":"$wrap"}"""
    }

    /**
     * An owner's GROUP scope rotated to epoch 1: a drop-keyed node `box` (a drop key, one WRITE_ONLY link, one guest
     * identity — a `FOLDER`, since core alone declares no drop box kind), that guest's identity folder `folder` under it
     * (entry under the guest's `K_up`), and a folder `inner` the guest made inside it. Every node still at epoch 0.
     */
    private inner class DropBox {
        val w: World
        val box: String
        val folder: String
        val inner: String

        init {
            val (owner, scopeId) = seedMemberScope(ScopeEntity.GROUP)
            box = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            val shareId = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().toByteArray()).take(43)
            val identityId = UUID.randomUUID().toString()
            QuarkusTransaction.requiringNew().run {
                NodeEntity.findById(box)!!.sealedDropPrivateKey = "b2xkLWRyb3AtcHJpdg=="
                ShareEntity().apply {
                    id = shareId; ownerAccount = owner; this.scopeId = scopeId; nodeId = box; role = "WRITE_ONLY"
                    sealedDropPublicKey = "b2xkLWRyb3AtcHVi"; sealedShareKey = "cmVjb3Zlcnk="; sealedShareKeyEpoch = 0; createdAt = Instant.now()
                }.persist()
                ShareIdentityEntity().apply {
                    id = identityId; this.shareId = shareId; sealedDisplayName = "Z3Vlc3QtbmFtZQ=="; tokenHash = "h-$identityId".take(64)
                    wrappedIdentityKey = "b2xkLWstdXA="
                }.persist()
            }
            folder = seedNode(scopeId, NodeEntity.FOLDER, parentId = box, wrappedContentKey = CONTENT_KEY)
            QuarkusTransaction.requiringNew().run { NodeEntity.findById(folder)!!.shareIdentityId = identityId }
            inner = seedNode(scopeId, NodeEntity.FOLDER, parentId = folder, wrappedContentKey = CONTENT_KEY)
            rotate(scopeId, to = 1, grantFollows = true, owner = owner)
            w = World(owner, scopeId, box, folder, inner, "", shareId, identityId)
        }

        fun boxParts(): String =
            """{"fromEpoch":0,"shares":[{"shareId":"${w.shareId}","sealedDropPublicKey":"bmV3LWRyb3AtcHVi"}],
               "identities":[{"identityId":"${w.identityId}","wrappedIdentityKey":"bmV3LWstdXA="}]}"""

        fun folderParts(): String =
            """{"fromEpoch":0,"children":[{"nodeId":"$inner","placementRevision":0,"encryptedName":"aS1uYW1l","slugToken":"${slug()}","wrappedContentKey":"aS13cmFw"}]}"""

        fun commitBox(rekeyId: UUID, caller: String = w.owner): ValidatableResponse = post(
            w, "/rekey/nodes/$box/$rekeyId",
            """{"nodeEpoch":0,"epoch":1,"placementRevision":0,"metaRevision":0,"encryptedName":"Ym94","slugToken":"${slug()}","wrappedContentKey":"Ym94LXdyYXA=","sealedDropPrivateKey":"bmV3LWRyb3AtcHJpdg=="}""",
            caller,
        )

        fun commitFolder(rekeyId: UUID, caller: String = w.owner): ValidatableResponse = post(
            w, "/rekey/nodes/$folder/$rekeyId",
            """{"nodeEpoch":0,"epoch":1,"parentId":"$box","parentEpoch":1,"placementRevision":0,"metaRevision":0,"encryptedName":"Zm9sZGVy","slugToken":"${slug()}","wrappedContentKey":"Zm9sZGVyLXdyYXA="}""",
            caller,
        )

        fun commitInner(placementRevision: Long = 0, caller: String = w.owner): ValidatableResponse = post(
            w, "/rekey/nodes/$inner/${UUID.randomUUID()}",
            """{"nodeEpoch":0,"epoch":1,"parentId":"$folder","parentEpoch":1,"placementRevision":$placementRevision,"metaRevision":0,"encryptedName":"aW5uZXI=","slugToken":"${slug()}","wrappedContentKey":"aW5uZXItd3JhcA=="}""",
            caller,
        )
    }

    private fun dropBoxWorld() = DropBox()

    /** The child's commit body under the root; [parentEpoch] null leaves it out. */
    private fun childCommit(w: World, parentEpoch: Int?, placementRevision: Long = 0, nodeEpoch: Int = 0): String {
        val epoch = parentEpoch?.let { "\"parentEpoch\":$it," } ?: ""
        return """{"nodeEpoch":$nodeEpoch,"epoch":1,"parentId":"${w.root}",$epoch"placementRevision":$placementRevision,"metaRevision":0,"encryptedName":"bmV3LWM=","slugToken":"${slug()}","wrappedContentKey":"bmV3LWMtd3JhcA=="}"""
    }

    private fun create(w: World, parent: String, parentEpoch: Int): ValidatableResponse = post(
        w, "/nodes",
        """{"nodeId":"${UUID.randomUUID()}","parentId":"$parent","parentEpoch":$parentEpoch,"kind":"FOLDER","encryptedName":"bmV3","slugToken":"${slug()}","orderIndex":0,"epoch":1,"wrappedContentKey":"d3JhcA=="}""",
    )

    private fun meta(w: World, node: String, nodeEpoch: Int, revision: Long = 0): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"bWV0YQ==","metaRevision":$revision,"nodeEpoch":$nodeEpoch}""")
            .`when`().patch("/api/scopes/${w.scopeId}/nodes/$node").then()

    private fun restore(w: World, node: String, parentEpoch: Int): ValidatableResponse =
        post(w, "/nodes/$node/restore", """{"slugToken":"${slug()}","parentEpoch":$parentEpoch}""")

    private fun complete(w: World, jobId: String): ValidatableResponse =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/tasks/$jobId/complete").then()

    private fun get(w: World, path: String, caller: String = w.owner): ValidatableResponse =
        given().header("Authorization", bearer(caller)).`when`().get("/api/scopes/${w.scopeId}$path").then()

    private fun post(w: World, path: String, body: String, caller: String = w.owner): ValidatableResponse =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/${w.scopeId}$path").then()

    /** [userId]'s scope row as a fresh read: each in its own transaction, so no copy loaded by the last one answers. */
    private fun rowRekeying(userId: String, tenantId: String, scopeId: String): Boolean? =
        QuarkusTransaction.requiringNew().call { scopes.listMine(userId, tenantId).single { it.id == scopeId }.rekeying }

    private fun row(id: String): NodeEntity = QuarkusTransaction.requiringNew().call { NodeEntity.findById(id)!! }

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())
}
