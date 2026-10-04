package com.veyq.core.membership

import com.veyq.core.blob.BlobEntity
import com.veyq.core.node.NodeEntity
import com.veyq.core.sharing.ShareEntity
import com.veyq.core.sharing.ShareIdentityEntity
import com.veyq.memories.media.BucketEntity
import com.veyq.memories.media.MediaEntity
import com.veyq.memories.media.TestBlob
import com.veyq.core.sharing.ShareWriteProof
import com.veyq.core.TestIds
import com.veyq.core.membership.ScopeAccessWorld.Companion.B64
import com.veyq.core.scope.ScopeEntity
import com.veyq.core.testing.TestFixtures.bearer
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import kotlin.test.assertEquals
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The client mints the id of every sealed object it creates, and the server stores it under exactly that id
 * (ADR 0072 Decision 3): each ciphertext of the create is sealed bound to that id, so a substituted id would leave
 * an object nobody can open. The server therefore refuses a taken id — `id_taken`, **the same answer wherever the
 * taken id lives** — and never overwrites the object that holds it.
 *
 * Every negative here sends, from the [ScopeAccessWorld.live] space, an id that already belongs to an object in the
 * [ScopeAccessWorld.control] space (another scope), and then reads that object back to prove it is unchanged. Its
 * control is the same create with a fresh id, which must succeed **under that id** — so the negative cannot pass by
 * the create refusing for some other reason, and the positive cannot pass by the server minting its own id.
 */
@QuarkusTest
class ClientMintedIdSliceTest {

    private lateinit var w: ScopeAccessWorld

    @BeforeEach
    fun seed() {
        w = ScopeAccessWorld().build()
    }

    private fun asOwner(): RequestSpecification =
        given().header("Authorization", bearer(w.live.ownerId)).contentType(ContentType.JSON)

    private fun asOwnerOnTenant(): RequestSpecification = asOwner().header("X-Forwarded-Host", w.live.domain)

    private fun read(block: () -> Unit) = QuarkusTransaction.requiringNew().run(block)

    // ── createNode ───────────────────────────────────────────────────────────────────────────────────────────

    private fun nodeBody(nodeId: String, slug: String) =
        """{"nodeId":"$nodeId","kind":"MEMORY","encryptedName":"bmV3","slugToken":"$slug","orderIndex":0,"wrappedContentKey":"$B64"}"""

    @Test
    fun createNodeRefusesAnIdTakenInAnotherScope() {
        asOwner().body(nodeBody(w.control.nodeId, "dGFrZW4="))
            .`when`().post("/api/scopes/${w.live.scopeId}/nodes")
            .then().statusCode(409).body("code", equalTo("id_taken"))
        read {
            val n = NodeEntity.findById(w.control.nodeId)!!
            assertEquals(w.control.scopeId, n.scopeId, "the taken node still lives in its own scope")
            assertEquals(B64, n.encryptedName, "the taken node's name is untouched")
        }

        val fresh = TestIds.uuid()
        asOwner().body(nodeBody(fresh, "ZnJlc2g="))
            .`when`().post("/api/scopes/${w.live.scopeId}/nodes")
            .then().statusCode(201).body("nodeId", equalTo(fresh))
    }

    // ── startBlobUploads + completeMedia ─────────────────────────────────────────────────────────────────────

    @Test
    fun startBlobUploadsRefusesABlobIdTakenInAnotherScope() {
        asOwner().body("""{"items":[${TestBlob.SMALL.declare(B64, blobId = w.control.blobId)}]}""")
            .`when`().post("/api/scopes/${w.live.scopeId}/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].error", equalTo("id_taken"))
            .body("results[0].uploadId", nullValue())
        read { assertEquals(w.control.scopeId, BlobEntity.findById(w.control.blobId)!!.scopeId) }

        asOwner().body("""{"items":[${TestBlob.SMALL.declare(B64)}]}""")
            .`when`().post("/api/scopes/${w.live.scopeId}/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].error", nullValue())
            .body("results[0].uploadId", notNullValue())
    }

    @Test
    fun completeMediaRefusesAMediaIdTakenInAnotherScope() {
        val taken = w.control.mediaId
        val upload = start(ownerId = taken)
        asOwner().body("""{"items":[{"mediaId":"$taken","originalUploadId":"$upload","nodeId":"${w.live.nodeId}"}]}""")
            .`when`().post("/api/scopes/${w.live.scopeId}/media-batch")
            .then().statusCode(200)
            .body("results[0].error", equalTo("id_taken"))
            .body("results[0].mediaId", nullValue())
        read {
            val m = MediaEntity.findById(taken)!!
            assertEquals(w.control.scopeId, m.scopeId, "the taken media still lives in its own scope")
            assertEquals(w.control.nodeId, m.nodeId, "the taken media is still attached where it was")
        }

        val fresh = TestIds.uuid()
        val freshUpload = start(ownerId = fresh)
        asOwner().body("""{"items":[{"mediaId":"$fresh","originalUploadId":"$freshUpload","nodeId":"${w.live.nodeId}"}]}""")
            .`when`().post("/api/scopes/${w.live.scopeId}/media-batch")
            .then().statusCode(200)
            .body("results[0].error", nullValue())
            .body("results[0].mediaId", equalTo(fresh))
    }

    private fun start(ownerId: String): String =
        asOwner().body("""{"items":[${TestBlob.SMALL.declare(B64, ownerId = ownerId)}]}""")
            .`when`().post("/api/scopes/${w.live.scopeId}/blob-uploads-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path("results[0].uploadId")

    // ── createBucket ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun createBucketRefusesAnIdTakenInAnotherScope() {
        asOwner().body("""{"bucketId":"${w.control.bucketId}","sealedName":"bmV3"}""")
            .`when`().post("/api/scopes/${w.live.scopeId}/nodes/${w.live.nodeId}/buckets")
            .then().statusCode(409).body("code", equalTo("id_taken"))
        read {
            val b = BucketEntity.findById(w.control.bucketId)!!
            assertEquals(w.control.scopeId, b.scopeId)
            assertEquals(B64, b.sealedName, "the taken bucket's name is untouched")
        }

        val fresh = TestIds.uuid()
        asOwner().body("""{"bucketId":"$fresh","sealedName":"bmV3"}""")
            .`when`().post("/api/scopes/${w.live.scopeId}/nodes/${w.live.nodeId}/buckets")
            .then().statusCode(200).body("bucketId", equalTo(fresh))
    }

    // ── createShare + createShareIdentity ────────────────────────────────────────────────────────────────────

    private fun shareBody(shareId: String, nodeId: String, role: String = "VIEWER") =
        """{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"$role","wrap":"bmV3","sealedName":"bmV3"}"""

    @Test
    fun createShareRefusesAnIdTakenInAnotherScope() {
        asOwner().body(shareBody(w.control.shareId, w.live.nodeId))
            .`when`().post("/api/shares")
            .then().statusCode(409).body("code", equalTo("id_taken"))
        read {
            val s = ShareEntity.findById(w.control.shareId)!!
            assertEquals(w.control.scopeId, s.scopeId)
            assertEquals(w.control.nodeId, s.nodeId, "the taken share still shares what it shared")
            assertEquals(B64, s.wrap, "the taken share's wrap is untouched")
        }

        val fresh = TestIds.shareId()
        asOwner().body(shareBody(fresh, w.live.nodeId))
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", equalTo(fresh))
    }

    @Test
    fun createShareIdentityRefusesAnIdTakenInAnotherShare() {
        val controlShare = TestIds.shareId()
        asOwner().body(shareBody(controlShare, w.control.nodeId, "CONTRIBUTOR")).`when`().post("/api/shares")
            .then().statusCode(201)
        val liveShare = TestIds.shareId()
        asOwner().body(shareBody(liveShare, w.live.nodeId, "CONTRIBUTOR")).`when`().post("/api/shares")
            .then().statusCode(201)
        val taken = TestIds.uuid()
        given().contentType(ContentType.JSON).body("""{"identityId":"$taken","sealedDisplayName":"$B64"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(controlShare)).`when`().post("/api/shares/$controlShare/identities")
            .then().statusCode(201).body("identityId", equalTo(taken))

        given().contentType(ContentType.JSON).body("""{"identityId":"$taken","sealedDisplayName":"bmV3"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(liveShare)).`when`().post("/api/shares/$liveShare/identities")
            .then().statusCode(409).body("code", equalTo("id_taken"))
        read {
            val i = ShareIdentityEntity.findById(taken)!!
            assertEquals(controlShare, i.shareId, "the taken identity still belongs to its own share")
            assertEquals(B64, i.sealedDisplayName, "the taken identity's name is untouched")
        }

        val fresh = TestIds.uuid()
        given().contentType(ContentType.JSON).body("""{"identityId":"$fresh","sealedDisplayName":"bmV3"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(liveShare)).`when`().post("/api/shares/$liveShare/identities")
            .then().statusCode(201).body("identityId", equalTo(fresh))
    }

    // ── completeMedia binds each upload to the media and the role it was started for ─────────────────────────

    @Test
    fun completeMediaRefusesAnUploadStartedForAnotherMediaOrRole() {
        val mediaId = TestIds.uuid()
        val original = start(ownerId = mediaId)
        val cases = listOf(
            // a thumbnail started for another media
            asOwner().body("""{"items":[${TestBlob.SMALL.declare(B64, ownerId = TestIds.uuid(), role = "THUMBNAIL")}]}"""),
            // a blob started for this media, but as its PREVIEW
            asOwner().body("""{"items":[${TestBlob.SMALL.declare(B64, ownerId = mediaId, role = "PREVIEW")}]}"""),
        ).map { it.`when`().post("/api/scopes/${w.live.scopeId}/blob-uploads-batch").then().statusCode(200).extract().path<String>("results[0].uploadId") }
        for (thumb in cases) {
            asOwner().body(
                """{"items":[{"mediaId":"$mediaId","originalUploadId":"$original","nodeId":"${w.live.nodeId}",""" +
                    """"derivatives":[{"kind":"THUMBNAIL","uploadId":"$thumb"}]}]}""",
            ).`when`().post("/api/scopes/${w.live.scopeId}/media-batch")
                .then().statusCode(200).body("results[0].error", equalTo("upload_mismatch"))
            read { assertEquals(null, MediaEntity.findById(mediaId), "nothing was attached") }
        }

        // control: the same original with a thumbnail started for this media in its own role
        val thumb = asOwner().body("""{"items":[${TestBlob.SMALL.declare(B64, ownerId = mediaId, role = "THUMBNAIL")}]}""")
            .`when`().post("/api/scopes/${w.live.scopeId}/blob-uploads-batch").then().statusCode(200)
            .extract().path<String>("results[0].uploadId")
        asOwner().body(
            """{"items":[{"mediaId":"$mediaId","originalUploadId":"$original","nodeId":"${w.live.nodeId}",""" +
                """"derivatives":[{"kind":"THUMBNAIL","uploadId":"$thumb"}]}]}""",
        ).`when`().post("/api/scopes/${w.live.scopeId}/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue()).body("results[0].mediaId", equalTo(mediaId))
    }

    // ── the reads carry what an opener binds from its address (ADR 0072) ─────────────────────────────────────

    @Test
    fun readsCarryTheBindingFieldsTheirOpenersNeed() {
        given().`when`().get("/api/shares/${w.live.shareId}")
            .then().statusCode(200)
            .body("scopeId", equalTo(w.live.scopeId))
            .body("nodes[0].epoch", equalTo(0))
        asOwner().`when`().get("/api/shares?nodeId=${w.live.nodeId}")
            .then().statusCode(200).body("find { it.shareId == '${w.live.shareId}' }.scopeId", equalTo(w.live.scopeId))
        asOwner().`when`().get("/api/scopes/my-tenants")
            .then().statusCode(200).body("find { it.tenantId == '${w.live.tenantId}' }.grantedEpoch", equalTo(0))
        given().header("Authorization", bearer(w.proposal)).header("X-Forwarded-Host", w.live.domain)
            .`when`().get("/api/scopes/invitations")
            .then().statusCode(200).body("find { it.scopeId == '${w.live.scopeId}' }.nameEpoch", equalTo(0))
    }

    // ── createGroup + createPersonalScope ────────────────────────────────────────────────────────────────────

    @Test
    fun createGroupRefusesAScopeIdTakenByAnotherScope() {
        asOwnerOnTenant().body("""{"scopeId":"${w.control.scopeId}","encryptedName":"bmV3","wrappedGrant":"bmV3",${TestAdmission.SIGNATURE_JSON},${TestAdmission.json()}}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(409).body("code", equalTo("id_taken"))
        read { assertEquals(B64, ScopeEntity.findById(w.control.scopeId)!!.encryptedName, "the taken group is untouched") }

        val fresh = TestIds.uuid()
        asOwnerOnTenant().body("""{"scopeId":"$fresh","encryptedName":"bmV3","wrappedGrant":"bmV3",${TestAdmission.SIGNATURE_JSON},${TestAdmission.json()}}""")
            .`when`().post("/api/scopes/groups")
            .then().statusCode(201).body("scopeId", equalTo(fresh))
    }

    @Test
    fun createPersonalScopeRefusesAScopeIdTakenByAnotherScope() {
        asOwnerOnTenant().body("""{"scopeId":"${w.control.scopeId}","wrappedGrant":"bmV3",${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/scopes")
            .then().statusCode(409).body("code", equalTo("id_taken"))
        read {
            val s = ScopeEntity.findById(w.control.scopeId)!!
            assertEquals(ScopeEntity.GROUP, s.kind, "the taken scope is still the group it was")
        }

        val fresh = TestIds.uuid()
        asOwnerOnTenant().body("""{"scopeId":"$fresh","wrappedGrant":"bmV3",${TestAdmission.SIGNATURE_JSON}}""")
            .`when`().post("/api/scopes")
            .then().statusCode(201).body("scopeId", equalTo(fresh))
    }
}
