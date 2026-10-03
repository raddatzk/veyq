package com.veyq.app.features.sharing

import com.veyq.app.core.blob.BlobEntity
import com.veyq.app.core.node.NodeEntity
import com.veyq.app.core.quota.StorageUsage
import com.veyq.app.core.sharing.ShareEntity
import com.veyq.app.core.sharing.ShareIdentityEntity
import com.veyq.app.features.content.NodesStore
import com.veyq.app.features.media.BucketEntity
import com.veyq.app.features.media.MediaEntity
import com.veyq.app.features.media.TestBlob
import com.veyq.app.shared.TestIds
import com.veyq.app.shared.blobstore.StoreReclaimEntity
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.DROP_PRIVATE_KEY
import com.veyq.app.testing.TestFixtures.DROP_PUBLIC_KEY
import com.veyq.app.testing.TestFixtures.WRAPPED_UPLOADER_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.identityKeyJson
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.TestFixtures.shareKeysJson
import com.veyq.app.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Slice tests for the **UPLOADER** write-only drop-box share (uc.sharing.share-uploader, cr-0102, ADR 0031)
 * against the in-memory store. Covers the drop-box's two structural guarantees + the delete-own:
 *  - **write-only:** the anonymous resolve of an UPLOADER share serves `media: []` **even with existing media**
 *    (and no identities) — "cannot see others" is enforced by the `canView` gate, never gallery filtering;
 *  - **never `K_mem` (ADR 0031 point 7):** it serves the Memory's drop public key instead of the wrap, the drop
 *    private key is set on the Memory once, an UPLOADER identity must hand over its wrapped key, and every reader
 *    of the Memory — a viewer link, a member's media page and buckets — is served what opens an upload;
 *  - **contribute:** an uploader names itself + uploads, and the media **attaches to the shared root stamped
 *    with its identity** (same path as CONTRIBUTOR);
 *  - **delete-own:** the token-gated `DELETE /media/{id}` removes + reclaims the caller's own media (a single-row
 *    ownership check) — a **foreign identity's** media is a `404`, a **bad token** is a `403`, and a **VIEWER**
 *    share rejects the delete (`403`).
 */
@QuarkusTest
class ShareUploaderSliceTest {

    @Inject
    lateinit var quota: StorageUsage

    @Inject
    lateinit var sharing: SharingStore

    @Inject
    lateinit var nodes: NodesStore

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val sealedUploaderName = "c2VhbGVkLXVwbG9hZGVyLW5hbWU="

    @Test
    fun uploaderResolveServesNoMediaEvenWithExisting() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        seedMedia(scopeId, memory, identityId = null) // a curator's existing photo on the Memory
        val shareId = createShare(userId, memory, role = "UPLOADER")

        // the anonymous resolve of an UPLOADER share is write-only: NO media, NO identities, NO nodes, and NO wrap —
        // only the drop public key (the guest seals its own identity key to it, ADR 0031 point 7), the node it binds
        // that wrap to, and the sealed title. The guest is never handed K_mem.
        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("role", equalTo("UPLOADER"))
            .body("wrap", nullValue())
            .body("sealedDropPublicKey", equalTo(DROP_PUBLIC_KEY))
            .body("nodeId", equalTo(memory))
            .body("sealedName", equalTo(sealedName))
            .body("media.size()", equalTo(0)) // the existing photo is NOT served
            .body("identities.size()", equalTo(0))
            .body("nodes.size()", equalTo(0)) // not even the root node — no K_mem-decryptable node metadata leaks

        // control, same Memory: a VIEWER resolve still carries the wrap, and no drop public key
        val viewerShare = createShare(userId, memory, role = "VIEWER")
        given().`when`().get("/api/shares/$viewerShare")
            .then().statusCode(200)
            .body("wrap", equalTo(wrap))
            .body("sealedDropPublicKey", nullValue())

        // an UPLOADER row stored with a wrap (the shape before the drop key) still resolves without it: the role
        // decides what the resolve hands out, not only what was stored
        val legacy = TestIds.shareId()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                // `this@ShareUploaderSliceTest.` is load-bearing: inside `apply` a bare `wrap` is the entity's own
                // (still null) field, which seeded a row with no wrap and made this control pass vacuously.
                id = legacy; ownerAccount = userId; this.scopeId = scopeId; nodeId = memory; role = ShareEntity.UPLOADER
                this.wrap = this@ShareUploaderSliceTest.wrap; sealedName = this@ShareUploaderSliceTest.sealedName; enabled = true; createdAt = Instant.now()
            }.persist()
        }
        given().`when`().get("/api/shares/$legacy").then().statusCode(200).body("role", equalTo("UPLOADER")).body("wrap", nullValue())
    }

    @Test
    fun theKeysAShareCarriesFollowItsRole() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val drop = "\"sealedDropPublicKey\":\"$DROP_PUBLIC_KEY\",\"sealedDropPrivateKey\":\"$DROP_PRIVATE_KEY\""

        // an UPLOADER share that carries a wrap is refused — the guest would hold K_mem — and nothing is written
        postShare(userId, memory, "UPLOADER", "\"wrap\":\"$wrap\",$drop").then().statusCode(400).body("code", equalTo("invalid_request"))
        postShare(userId, memory, "UPLOADER", "\"wrap\":\"$wrap\"").then().statusCode(400).body("code", equalTo("invalid_request"))
        QuarkusTransaction.requiringNew().run { assertNull(NodeEntity.findById(memory)!!.sealedDropPrivateKey) }
        // a VIEWER share that carries a drop key is refused, as is one with no wrap
        postShare(userId, memory, "VIEWER", "\"wrap\":\"$wrap\",$drop").then().statusCode(400).body("code", equalTo("invalid_request"))
        postShare(userId, memory, "VIEWER", "\"sealedDropPublicKey\":\"$DROP_PUBLIC_KEY\"").then().statusCode(400)
        // controls: each role's own keys are accepted
        postShare(userId, memory, "UPLOADER", drop).then().statusCode(201)
        postShare(userId, memory, "VIEWER", "\"wrap\":\"$wrap\"").then().statusCode(201)
    }

    @Test
    fun theDropKeyIsSetOnceOnTheMemory() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val public = "\"sealedDropPublicKey\":\"$DROP_PUBLIC_KEY\""

        // a first UPLOADER share without the private half has nothing to seal to — refused, nothing written
        postShare(userId, memory, "UPLOADER", public).then().statusCode(409).body("code", equalTo("drop_key_missing"))
        // the first one with it stores it on the Memory
        postShare(userId, memory, "UPLOADER", "$public,\"sealedDropPrivateKey\":\"$DROP_PRIVATE_KEY\"").then().statusCode(201)
        QuarkusTransaction.requiringNew().run { assertEquals(DROP_PRIVATE_KEY, NodeEntity.findById(memory)!!.sealedDropPrivateKey) }
        // a second private half would orphan every upload sealed to the first — refused, and the first stays
        val second = "c2Vjb25kLWRyb3AtcHJpdmF0ZQ=="
        postShare(userId, memory, "UPLOADER", "$public,\"sealedDropPrivateKey\":\"$second\"")
            .then().statusCode(409).body("code", equalTo("drop_key_exists"))
        QuarkusTransaction.requiringNew().run { assertEquals(DROP_PRIVATE_KEY, NodeEntity.findById(memory)!!.sealedDropPrivateKey) }
        // control: a later UPLOADER share seals to the Memory's existing key and brings none
        postShare(userId, memory, "UPLOADER", public).then().statusCode(201)
    }

    @Test
    fun aRenameOverlappingTheFirstUploaderLinkKeepsItsDropKey() {
        // The rename reads the Memory before the first UPLOADER link's drop-key write commits and flushes after it.
        // A full-row UPDATE wrote the `null` drop key it had read back: the link answered CREATED, and every upload
        // sealed to it could no longer be opened by the Memory's members (inbox #224).
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
            val renamed = "cmVuYW1lZA==" // "renamed"
            whileUncommitted(
                change = {
                    val shareId = TestIds.shareId()
                    val created = sharing.create(
                        shareId, userId, scopeId, memory, ShareEntity.UPLOADER,
                        ShareKeys.Drop(DROP_PUBLIC_KEY, DROP_PRIVATE_KEY), sealedName, null, null, null, null, null,
                        false, null, null, null, ShareWriteProof.verifier(shareId),
                    )
                    assertEquals(SharingStore.CreateResult.CREATED, created, "round $round")
                },
                overlapping = { nodes.applyUpdate(scopeId, memory, renamed, null, null, null, null, null) },
            )
            QuarkusTransaction.requiringNew().run {
                val node = NodeEntity.findById(memory)!!
                assertEquals(DROP_PRIVATE_KEY, node.sealedDropPrivateKey, "round $round: the rename wiped the drop key")
                // Control: the rename itself landed.
                assertEquals(renamed, node.encryptedName, "round $round")
            }
        }
    }

    @Test
    fun anUploaderIdentityHandsOverItsWrappedKey() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val uploader = createShare(userId, memory, role = "UPLOADER")
        val contributor = createShare(userId, memory, role = "CONTRIBUTOR")
        val keyed = ",\"wrappedIdentityKey\":\"$WRAPPED_UPLOADER_KEY\""

        // an UPLOADER identity without its key could never be read by anyone — refused
        postIdentity(uploader, "").then().statusCode(400).body("code", equalTo("invalid_request"))
        val id = postIdentity(uploader, keyed).then().statusCode(201).extract().path<String>("identityId")
        QuarkusTransaction.requiringNew().run { assertEquals(WRAPPED_UPLOADER_KEY, ShareIdentityEntity.findById(id)!!.wrappedIdentityKey) }
        // a CONTRIBUTOR seals under K_mem and has no key to hand over — refused with one; control: without, accepted
        postIdentity(contributor, keyed).then().statusCode(400).body("code", equalTo("invalid_request"))
        postIdentity(contributor, "").then().statusCode(201)
    }

    @Test
    fun everyReaderOfTheMemoryIsServedWhatOpensAnUpload() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val uploader = createShare(userId, memory, role = "UPLOADER")
        val contributor = createShare(userId, memory, role = "CONTRIBUTOR")
        val uploaderIdentity = createIdentity(uploader)
        val contributorIdentity = postIdentity(contributor, "").then().statusCode(201).extract().path<String>("identityId")
        seedMedia(scopeId, memory, identityId = uploaderIdentity)
        seedMedia(scopeId, memory, identityId = contributorIdentity)
        val viewer = createShare(userId, memory, role = "VIEWER")

        // a viewer link: the Memory's sealed drop private key on its node, and the uploader identity's wrapped key
        // (served although it belongs to another share); the contributor's own share lists none for it
        given().`when`().get("/api/shares/$viewer").then().statusCode(200)
            .body("nodes[0].sealedDropPrivateKey", equalTo(DROP_PRIVATE_KEY))
            .body("identities.size()", equalTo(1))
            .body("identities[0].identityId", equalTo(uploaderIdentity))
            .body("identities[0].wrappedIdentityKey", equalTo(WRAPPED_UPLOADER_KEY))
        // a member: the node, the media page (drop key + one uploader key — the contributor's rows need none) and
        // the uploader's primary bucket
        val auth = bearer(userId)
        given().header("Authorization", auth).`when`().get("/api/scopes/$scopeId/nodes").then().statusCode(200)
            .body("find { it.id == '$memory' }.sealedDropPrivateKey", equalTo(DROP_PRIVATE_KEY))
        given().header("Authorization", auth).`when`().get("/api/scopes/$scopeId/nodes/$memory/media").then().statusCode(200)
            .body("media.size()", equalTo(2))
            .body("sealedDropPrivateKey", equalTo(DROP_PRIVATE_KEY))
            .body("uploaderKeys.size()", equalTo(1))
            .body("uploaderKeys[0].identityId", equalTo(uploaderIdentity))
            .body("uploaderKeys[0].wrappedIdentityKey", equalTo(WRAPPED_UPLOADER_KEY))
        given().header("Authorization", auth).`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets").then().statusCode(200)
            .body("sealedDropPrivateKey", equalTo(DROP_PRIVATE_KEY))
            .body("buckets.find { it.ownerKey == 'share:$uploaderIdentity' }.wrappedIdentityKey", equalTo(WRAPPED_UPLOADER_KEY))
            // the contributor's primary is there (so the null below is not a missing row) and carries no key
            .body("buckets.find { it.ownerKey == 'share:$contributorIdentity' }.isPrimary", equalTo(true))
            .body("buckets.find { it.ownerKey == 'share:$contributorIdentity' }.wrappedIdentityKey", nullValue())
    }

    /**
     * B3 (ADR 0029 §7) — deleting a link deletes the LINK: the photos its guest added belong to the Memory, and an
     * uploader guest's photo opens only through their identity's wrapped key and the Memory's drop key. Both stay,
     * and so does every byte (a delete reclaims nothing).
     */
    @Test
    fun theGuestsUploadsSurviveTheirLink() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "UPLOADER")
        val (identityId, token) = createIdentityWithToken(shareId)
        val uploadId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).extract().path<String>("results[0].uploadId")
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200)
        val usageBefore = QuarkusTransaction.requiringNew().call { quota.tenantUsage(tenantId) }

        given().header("Authorization", bearer(userId)).`when`().delete("/api/shares/$shareId").then().statusCode(204)

        val auth = bearer(userId)
        given().header("Authorization", auth).`when`().get("/api/scopes/$scopeId/nodes/$memory/media").then().statusCode(200)
            .body("media.size()", equalTo(1))
            .body("uploaderKeys.find { it.identityId == '$identityId' }.wrappedIdentityKey", equalTo(WRAPPED_UPLOADER_KEY))
        given().header("Authorization", auth).`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets").then().statusCode(200)
            .body("buckets.find { it.ownerKey == 'share:$identityId' }.sealedName", equalTo(sealedUploaderName))
        QuarkusTransaction.requiringNew().run {
            assertEquals(DROP_PRIVATE_KEY, NodeEntity.findById(memory)!!.sealedDropPrivateKey)
            assertEquals(usageBefore, quota.tenantUsage(tenantId))
        }
    }

    @Test
    fun uploaderShareHasNoBlobDownloadCapability() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val mediaId = seedMedia(scopeId, memory, identityId = null) // a curator photo — its blob IS in the subtree
        val blobId = QuarkusTransaction.requiringNew().call { MediaEntity.findById(mediaId)!!.blobId }

        // an UPLOADER share is role-gated at the blob endpoint too — even an in-subtree blob id is a 404 (write-only
        // is a real capability, not just id-secrecy). Contrast: a VIEWER share of the same memory passes the role
        // gate and only fails downstream at the (in-memory) store — a 503, proving the gate is what blocks UPLOADER.
        val uploaderShare = createShare(userId, memory, role = "UPLOADER")
        given().`when`().get("/api/shares/$uploaderShare/blobs/$blobId").then().statusCode(404)

        val viewerShare = createShare(userId, memory, role = "VIEWER")
        given().`when`().get("/api/shares/$viewerShare/blobs/$blobId").then().statusCode(503)
    }

    @Test
    fun uploaderNamesUploadsAttachesStampedAndDeletesOwn() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "UPLOADER")

        // name yourself — NO bearer (uploader path is open like CONTRIBUTOR); identity + capability token out
        val idResp = postIdentity(shareId, identityKeyJson("UPLOADER"))
            .then().statusCode(201)
            .body("identityId", notNullValue()).body("contributorToken", notNullValue())
            .extract()
        val identityId = idResp.path<String>("identityId")
        val contributorToken = idResp.path<String>("contributorToken")

        val uploadId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).extract().path<String>("results[0].uploadId")

        val mediaId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200).extract().path<String>("results[0].mediaId")

        // the media attached to the shared root, stamped with the uploader's identity (verified in the DB — the
        // resolve deliberately serves the uploader nothing)
        QuarkusTransaction.requiringNew().run {
            val m = MediaEntity.findById(mediaId)!!
            assertEquals(memory, m.nodeId)
            assertEquals(identityId, m.shareIdentityId)
        }
        // and it is STILL invisible to the uploader's own resolve
        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("media.size()", equalTo(0))

        // the key the delete-own must not lose — read while the rows that hold it still exist
        val storageKey = QuarkusTransaction.requiringNew().call {
            BlobEntity.findById(MediaEntity.findById(mediaId)!!.blobId)!!.storageKey
        }

        // delete-own — the token rides the X-Contributor-Token header; the media + its blob are reclaimed
        given().header("X-Contributor-Token", contributorToken)
            .`when`().delete("/api/shares/$shareId/media/$mediaId")
            .then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            assertNull(MediaEntity.findById(mediaId), "media row reclaimed")
            assertEquals(0, BlobEntity.count("scopeId", scopeId), "blob rows reclaimed")
        }
        assert(QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", storageKey) } == 1L) {
            "the contributor's own delete must leave a durable reclaim row for the blob it deleted"
        }
        // a second delete of the now-gone media is a non-enumerable 404
        given().header("X-Contributor-Token", contributorToken)
            .`when`().delete("/api/shares/$shareId/media/$mediaId")
            .then().statusCode(404).body("code", equalTo("share_media_not_found"))
    }

    /**
     * A guest's delete reads its media under that row's lock: a second delete of the same upload (a retry, a double
     * tap) waits for the first and finds nothing, the `404` a replay gets. Read plainly before the wait, it deleted
     * the rows the first had already removed, found none, and answered 500 (the shape of inbox #310).
     */
    @Test
    fun aDeleteOwnOverlappingItselfAnswersAsAReplay() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
            val identity = createIdentity(createShare(userId, memory, role = "UPLOADER"))
            val mediaId = seedMedia(scopeId, memory, identityId = identity)
            val second = whileUncommitted(
                change = { assertEquals(true, sharing.deleteContributedMedia(scopeId, mediaId, identity), "round $round") },
                overlapping = { sharing.deleteContributedMedia(scopeId, mediaId, identity) },
            )
            assertEquals(false, second, "round $round: the second delete answers as a replay")
            QuarkusTransaction.requiringNew().run { assertNull(MediaEntity.findById(mediaId), "round $round") }
        }
    }

    @Test
    fun foreignIdentityDeleteIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "UPLOADER")

        // identity A owns a media (seeded), identity B holds a valid token but did not upload it
        val identityA = createIdentity(shareId)
        val (_, tokenB) = createIdentityWithToken(shareId)
        val mediaOfA = seedMedia(scopeId, memory, identityId = identityA)

        given().header("X-Contributor-Token", tokenB)
            .`when`().delete("/api/shares/$shareId/media/$mediaOfA")
            .then().statusCode(404).body("code", equalTo("share_media_not_found"))
        // A's media is untouched
        QuarkusTransaction.requiringNew().run { assertEquals(mediaOfA, MediaEntity.findById(mediaOfA)?.id) }
    }

    @Test
    fun badTokenDeleteIs403() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "UPLOADER")
        val mediaId = seedMedia(scopeId, memory, identityId = createIdentity(shareId))

        // a well-formed but never-issued token — rejected before any ownership check
        given().header("X-Contributor-Token", "notARealToken000000000000000000000000000000")
            .`when`().delete("/api/shares/$shareId/media/$mediaId")
            .then().statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    @Test
    fun viewerShareRejectsUploadAndDelete() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "VIEWER")
        val mediaId = seedMedia(scopeId, memory, identityId = null)

        // a VIEWER share accepts neither an identity (upload) nor a delete
        given().contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedUploaderName"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(403).body("code", equalTo("share_not_contributable"))
        given().header("X-Contributor-Token", "notARealToken000000000000000000000000000000")
            .`when`().delete("/api/shares/$shareId/media/$mediaId")
            .then().statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    // --- helpers ---

    private fun createShare(userId: String, nodeId: String, role: String): String =
        postShare(userId, nodeId, role, shareKeysJson(role, nodeId, wrap)).then().statusCode(201).extract().path("shareId")

    private fun postShare(userId: String, nodeId: String, role: String, keys: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",$keys,"sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")

    private fun postIdentity(shareId: String, key: String) =
        given().contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedUploaderName"$key}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")

    /** An identity on an UPLOADER share — with the wrapped key such an identity must hand over. */
    private fun createIdentity(shareId: String): String = createIdentityWithToken(shareId).first

    private fun createIdentityWithToken(shareId: String): Pair<String, String> {
        val r = postIdentity(shareId, identityKeyJson("UPLOADER")).then().statusCode(201).extract()
        return r.path<String>("identityId") to r.path<String>("contributorToken")
    }

    private fun seedMedia(scopeId: String, nodeId: String, identityId: String?): String {
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"; createdAt = Instant.now()
            }.persist()
            if (identityId == null) {
                MediaEntity().apply {
                    id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId; createdAt = Instant.now()
                }.persist()
                return@run
            }
            // as `MediaStore.attach` does: a guest's upload lands in their primary bucket, named by their identity
            val bucket = BucketEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
                ownerKey = "share:$identityId"; sealedName = ShareIdentityEntity.findById(identityId)?.sealedDisplayName
                isPrimary = true; createdAt = Instant.now()
            }.also { it.persist() }
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                shareIdentityId = identityId; createdBy = bucket.ownerKey; bucketId = bucket.id; createdAt = Instant.now()
            }.persist()
        }
        return mediaId
    }
}
