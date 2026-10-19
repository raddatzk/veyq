package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.memories.banner.BannerKit
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * The tenant storage quota (uc.admin.tenant-storage-quota, ADR 0073 §1–§3, §5): the Server Owner sets it on the admin
 * channel, and every start into the tenant — a member's upload, a cover, a link upload — is reserved against it under
 * the tenant-row lock, admitted whole or refused whole `quota_exceeded` with no session left behind.
 *
 * The usage terms are pinned EXACTLY through [assertTenantHolds]: a one-blob member start is refused at
 * `expected + S - 1` and admitted at `expected + S`. That pair is each case's negative and its positive control on the
 * same predicate, so it goes red whichever way the SUM drifts — a term dropped (too little) or a tenant over-reached
 * (too much). The fixture's content lives in scope B and every probe starts into scope A, so a term that is counted
 * is counted across the tenant's scopes, not only in the scope being uploaded into.
 *
 * **Split from `TenantStorageQuotaSliceTest`** (cr-platform-gradle-modules step 8a): these methods need Memories in the application at runtime, so they live in
 * `:apps:memories`; the rest of the class is `TenantStorageQuotaSliceTest` in `:core`. The bodies moved verbatim.
 */
@QuarkusTest
class TenantStorageQuotaMemoriesSliceTest {

    private val s = TestBlob.SMALL

    private val d = TestBlob.ofPlaintext(100) // a size no probe blob has, so its bytes are visible in the pin

    /** A tenant whose owner [userId] owns two personal scopes, each with one Memory: A is probed, B holds content. */
    data class Tenant(val tenantId: String, val userId: String, val scopeA: String, val memoryA: String, val scopeB: String, val memoryB: String)

    data class Link(val shareId: String, val token: String)

    @Test
    fun aCompletedUploadCounts() {
        val f = fixture()
        memberUpload(f, s, derivative = d)
        assertTenantHolds(f, s.byteSize + d.byteSize)
    }

    @Test
    fun trashedMediaCounts() {
        val f = fixture()
        val mediaId = memberUpload(f, s)
        trash(f, mediaId)
        assertTenantHolds(f, s.byteSize)
    }

    @Test
    fun aTrashedNodeCounts() {
        val f = fixture()
        memberUpload(f, s)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(f.memoryB)!!.deletedAt = Instant.now() }
        assertTenantHolds(f, s.byteSize)
    }

    @Test
    fun aScopeInItsDeletionGraceCounts() {
        val f = fixture()
        memberUpload(f, s)
        QuarkusTransaction.requiringNew().run { ScopeEntity.findById(f.scopeB)!!.deletedAt = Instant.now() }
        assertTenantHolds(f, s.byteSize)
    }

    @Test
    fun aCoverCounts() {
        val f = fixture()
        val uploadId = memberStartOk(f, f.scopeB, d, ownerId = f.memoryB, role = "COVER")
        CoverKit.set(f.userId, f.scopeB, f.memoryB, uploadId, expectedBlobId = null).statusCode(204)
        assertTenantHolds(f, d.byteSize)
    }

    /** A Memory's banner (ADR 0102) counts with both its slots: the video and its poster are the tenant's bytes too. */
    @Test
    fun aBannerCounts() {
        val f = fixture()
        val poster = TestBlob.ofPlaintext(37) // a size neither probe nor `d` has, so a dropped slot shows in the pin
        val banner = memberStartOk(f, f.scopeB, d, ownerId = f.memoryB, role = MemoryBlobRoles.BANNER)
        val posterUpload = memberStartOk(f, f.scopeB, poster, ownerId = f.memoryB, role = MemoryBlobRoles.BANNER_POSTER)
        BannerKit.set(f.userId, f.scopeB, f.memoryB, banner, posterUpload).statusCode(204)
        assertTenantHolds(f, d.byteSize + poster.byteSize)
    }

    /** Purge deletes the blob rows and leaves a reclaim tombstone, which carries no scope and is not counted. */
    @Test
    fun aPurgeFreesTheUsage() {
        val f = fixture()
        val mediaId = memberUpload(f, s, derivative = d)
        trash(f, mediaId)
        assertTenantHolds(f, s.byteSize + d.byteSize, admit = false) // the positive control: it counted before
        given().header("Authorization", bearer(f.userId))
            .`when`().post("/api/scopes/${f.scopeB}/media/$mediaId/purge")
            .then().statusCode(204)
        assertTenantHolds(f, 0)
    }

    @Test
    fun anotherTenantsObjectsDoNotCount() {
        val f = fixture()
        val other = fixture()
        memberUpload(other, s, derivative = d)
        memberStartOk(other, other.scopeB, d)
        assertTenantHolds(f, 0)
    }

    @Test
    fun aLinkStartWithinItsLimitIntoATenantOverItsQuotaIsRefused() {
        val f = fixture()
        val link = link(f)
        setLinkLimit(f.tenantId, 10 * s.byteSize) // the link has room
        setQuota(f.tenantId, s.byteSize - 1)
        linkStart(link, s).then().statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        assertEquals(0, sessionCount(f.scopeA))
        setQuota(f.tenantId, s.byteSize)
        linkStart(link, s).then().statusCode(200).body("results[0].error", nullValue()) // control
    }

    @Test
    fun aLinkStartOverItsLimitIntoATenantWithRoomIsRefused() {
        val f = fixture()
        val link = link(f)
        setQuota(f.tenantId, 10 * s.byteSize) // the tenant has room
        setLinkLimit(f.tenantId, s.byteSize - 1)
        linkStart(link, s).then().statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        assertEquals(0, sessionCount(f.scopeA))
        setLinkLimit(f.tenantId, s.byteSize)
        linkStart(link, s).then().statusCode(200).body("results[0].error", nullValue()) // control
    }

    /** A lowered quota deletes nothing, and both readers see the usage beside it. */
    @Test
    fun bothReadersSeeTheQuotaBesideTheUsage() {
        val f = fixture()
        val so = seedUser(serverRole = "SERVER_OWNER")
        memberUpload(f, s)
        putQuota(so, f.tenantId, "1").then().statusCode(204)
        givenAdminChannel().header("Authorization", bearer(so))
            .`when`().get("/api/tenants").then().statusCode(200)
            .body("find { it.tenantId == '${f.tenantId}' }.storageUsedBytes", equalTo(s.byteSize.toInt()))
            .body("find { it.tenantId == '${f.tenantId}' }.storageQuotaBytes", equalTo(1))
        given().header("Authorization", bearer(f.userId))
            .`when`().get("/api/tenants/${f.tenantId}/storage").then().statusCode(200)
            .body("usedBytes", equalTo(s.byteSize.toInt()))
            .body("quotaBytes", equalTo(1))
        clearQuota(so, f.tenantId).then().statusCode(204)
        given().header("Authorization", bearer(f.userId))
            .`when`().get("/api/tenants/${f.tenantId}/storage").then().statusCode(200)
            .body("usedBytes", equalTo(s.byteSize.toInt())) // nothing was deleted
            .body("quotaBytes", nullValue())
    }

    // --- the live defect ---

    // --- the terms of the tenant sum ---

    // --- the two checks are independent (ADR 0073 §1, §4) ---

    // --- concurrency (A5) ---

    // --- setTenantStorageQuota / clearTenantStorageQuota: channel, gate, set, clear ---

    // --- the usage probe ---

    /**
     * The tenant holds exactly [expected] bytes: one member blob of `S` into scope A is refused at `expected + S - 1`
     * (no session opened) and — unless [admit] is false — admitted at `expected + S`, which leaves that session open.
     */
    private fun assertTenantHolds(f: Tenant, expected: Long, admit: Boolean = true) {
        val before = sessionCount(f.scopeA)
        setQuota(f.tenantId, expected + s.byteSize - 1)
        memberStart(f, f.scopeA, s).then().statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        assertEquals(before, sessionCount(f.scopeA), "a refused start opens no session")
        if (!admit) return
        setQuota(f.tenantId, expected + s.byteSize)
        memberStartOk(f, f.scopeA, s)
    }

    // --- fixture ---

    private fun fixture(): Tenant {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeA = UUID.randomUUID().toString()
        val scopeB = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "q-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE
            }.persist()
            for (scopeId in listOf(scopeA, scopeB)) {
                ScopeEntity().apply {
                    id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL
                }.persist()
                MembershipEntity().apply {
                    id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                    this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
                }.persist()
            }
            TenantRoots.join(userId, tenantId, MembershipEntity.OWNER) // the tenant owner
        }
        return Tenant(tenantId, userId, scopeA, seedNode(scopeA, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY), scopeB, seedNode(scopeB, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))
    }

    /** A READ_WRITE link on Memory A, with one identity. */
    private fun link(f: Tenant): Link {
        val shareId = given().header("Authorization", bearer(f.userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"${f.memoryA}","role":"READ_WRITE","wrap":"d3JhcA==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path<String>("shareId")
        val token = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"c2VhbGVkLW5hbWU="}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path<String>("contributorToken")
        return Link(shareId, token)
    }

    private fun linkStart(link: Link, blob: TestBlob): Response =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}","items":[${blob.declare("d3JhcA==")}]}""")
            .`when`().post("/api/shares/${link.shareId}/blob-uploads")

    private fun memberStart(
        f: Tenant,
        scopeId: String,
        vararg blobs: TestBlob,
        ownerId: String? = null,
        role: String = "ORIGINAL",
    ): Response =
        given().header("Authorization", bearer(f.userId)).contentType(ContentType.JSON)
            .body("""{"items":${blobs.joinToString(",", "[", "]") { it.declare("b3JpZw==", ownerId = ownerId ?: UUID.randomUUID().toString(), role = role) }}}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")

    private fun memberStartOk(f: Tenant, scopeId: String, blob: TestBlob, ownerId: String? = null, role: String = "ORIGINAL"): String =
        memberStart(f, scopeId, blob, ownerId = ownerId, role = role).then().statusCode(200)
            .body("results[0].error", nullValue())
            .extract().path("results[0].uploadId")

    /** A member's upload into Memory B (with an optional THUMBNAIL derivative), completed; returns the media id. */
    private fun memberUpload(f: Tenant, original: TestBlob, derivative: TestBlob? = null): String {
        val mediaId = UUID.randomUUID().toString()
        val items = listOfNotNull(
            original.declare("b3JpZw==", ownerId = mediaId),
            derivative?.declare("dGh1bWI=", ownerId = mediaId, role = "THUMBNAIL"),
        )
        val ids = given().header("Authorization", bearer(f.userId)).contentType(ContentType.JSON)
            .body("""{"items":${items.joinToString(",", "[", "]")}}""")
            .`when`().post("/api/scopes/${f.scopeB}/blob-uploads-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path<List<String>>("results.uploadId")
        val derivs = if (derivative == null) "" else ""","derivatives":[{"kind":"THUMBNAIL","uploadId":"${ids[1]}"}]"""
        given().header("Authorization", bearer(f.userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"${f.memoryB}","mediaId":"$mediaId","originalUploadId":"${ids[0]}"$derivs}]}""")
            .`when`().post("/api/scopes/${f.scopeB}/media-batch")
            .then().statusCode(200).body("results[0].mediaId", equalTo(mediaId))
        return mediaId
    }

    private fun trash(f: Tenant, mediaId: String) {
        given().header("Authorization", bearer(f.userId))
            .`when`().delete("/api/scopes/${f.scopeB}/media/$mediaId")
            .then().statusCode(204)
    }

    private fun putQuota(userId: String, tenantId: String, quota: String): Response =
        givenAdminChannel().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"quotaBytes":$quota}""")
            .`when`().put("/api/tenants/$tenantId/storage-quota")

    private fun clearQuota(userId: String, tenantId: String): Response =
        givenAdminChannel().header("Authorization", bearer(userId))
            .`when`().delete("/api/tenants/$tenantId/storage-quota")

    private fun setQuota(tenantId: String, bytes: Long?) =
        QuarkusTransaction.requiringNew().run { TenantEntity.findById(tenantId)!!.storageQuotaBytes = bytes }

    private fun setLinkLimit(tenantId: String, bytes: Long) =
        QuarkusTransaction.requiringNew().run { TenantEntity.findById(tenantId)!!.shareLinkLimitBytes = bytes }

    private fun sessionCount(scopeId: String): Long =
        QuarkusTransaction.requiringNew().call { UploadSessionEntity.count("scopeId", scopeId) }
}
