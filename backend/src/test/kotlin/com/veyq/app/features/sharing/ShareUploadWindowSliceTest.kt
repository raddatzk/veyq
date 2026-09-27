package com.veyq.app.features.sharing

import com.veyq.app.shared.TestIds
import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.media.MediaBudgetBranchSliceTest
import com.veyq.app.features.media.TestBlob
import com.veyq.app.features.media.UploadSessionEntity
import com.veyq.app.shared.blobstore.FakePresigningBlobStore
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.TenantRoots
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.shared.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The contributor half of windowed presigning (uc.sharing.share-contributor A2, ADR 0013 §2): a session is bound to
 * the share identity that opened it, so a second identity on the same link can neither complete it nor extend it,
 * and every window runs the link's whole gate again — a revoked link stops an in-flight upload at its next window.
 *
 * Shares [MediaBudgetBranchSliceTest]'s profile ([FakePresigningBlobStore]) so a window has URLs to sign.
 */
@QuarkusTest
@TestProfile(MediaBudgetBranchSliceTest.FakePresignProfile::class)
class ShareUploadWindowSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Inject
    lateinit var fakeStore: FakePresigningBlobStore

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun aSecondIdentityCannotCompleteAnotherIdentitysSession() {
        val shareId = contributorShare().shareId
        val first = identity(shareId)
        val second = identity(shareId)
        val uploadId = startStaged(shareId, first, TestBlob.SMALL)

        complete(shareId, second, uploadId)
            .body("results[0].error", equalTo("upload_not_found"))
            .body("results[0].mediaId", nullValue())
        // control: the identity that opened it completes the same session
        complete(shareId, first, uploadId)
            .body("results[0].error", nullValue())
            .body("results[0].mediaId", notNullValue())
    }

    @Test
    fun aSecondIdentityGetsNoWindowOfAnotherIdentitysSession() {
        val shareId = contributorShare().shareId
        val first = identity(shareId)
        val second = identity(shareId)
        val uploadId = start(shareId, first, TestBlob.ofChunks(2))

        window(shareId, second, uploadId, fromChunk = 1).statusCode(200)
            .body("results[0].error", equalTo("upload_not_found"))
            .body("results[0].presignedPuts", nullValue())
        window(shareId, first, uploadId, fromChunk = 1).statusCode(200)
            .body("results[0].error", nullValue())
            .body("results[0].presignedPuts.size()", equalTo(1))
    }

    @Test
    fun revokingTheLinkStopsAnInFlightUploadAtItsNextWindow() {
        // ADR 0013 §2 / uc.sharing.share-contributor A2: a window runs the link's gate again, so a revocation reaches
        // an upload already in flight. Control: the same session's window, before the revocation, is signed.
        val share = contributorShare()
        val contributor = identity(share.shareId)
        val uploadId = start(share.shareId, contributor, TestBlob.ofChunks(3))
        window(share.shareId, contributor, uploadId, fromChunk = 1).statusCode(200).body("results[0].error", nullValue())

        given().header("Authorization", token(share.owner))
            .`when`().post("/api/shares/${share.shareId}/revoke").then().statusCode(204)

        window(share.shareId, contributor, uploadId, fromChunk = 2).statusCode(404).body("code", equalTo("share_not_found"))
    }

    // --- helpers ---

    private data class Share(val owner: String, val shareId: String)

    private fun contributorShare(): Share {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId)
        val shareId = given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"${TestIds.shareId()}","nodeId":"$memory","role":"CONTRIBUTOR","wrap":"d3JhcA==","sealedName":"c2VhbGVk"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path<String>("shareId")
        return Share(userId, shareId)
    }

    private fun identity(shareId: String): String =
        given().contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"bmFtZQ=="}""")
            .`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path("contributorToken")

    private fun start(shareId: String, contributorToken: String, blob: TestBlob): String =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[${blob.declare("d3JhcA==")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path("results[0].uploadId")

    private fun startStaged(shareId: String, contributorToken: String, blob: TestBlob): String {
        val uploadId = start(shareId, contributorToken, blob)
        val storageKey = QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey }
        blob.stage(fakeStore, storageKey)
        return uploadId
    }

    private fun window(shareId: String, contributorToken: String, uploadId: String, fromChunk: Int) =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[{"uploadId":"$uploadId","fromChunk":$fromChunk}]}""")
            .`when`().post("/api/shares/$shareId/blob-upload-windows")
            .then()

    private fun complete(shareId: String, contributorToken: String, uploadId: String) =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200)

    private fun seedNode(scopeId: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId; kind = NodeEntity.MEMORY
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = "a21lbQ=="; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    private fun seedMemberScope(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "t-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(userId, tenantId)
        }
        return userId to scopeId
    }

    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "u-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
