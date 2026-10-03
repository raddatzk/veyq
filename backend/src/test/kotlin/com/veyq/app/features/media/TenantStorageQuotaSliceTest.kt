package com.veyq.app.features.media

import com.veyq.app.core.blob.UploadSessionEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.sharing.ShareWriteProof
import com.veyq.app.shared.TestIds
import com.veyq.app.shared.adminchannel.givenAdminChannel
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.TenantRoots
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.shared.tenant.TenantEntity
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
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
 */
@QuarkusTest
class TenantStorageQuotaSliceTest {

    private val s = TestBlob.SMALL
    private val d = TestBlob.ofPlaintext(100) // a size no probe blob has, so its bytes are visible in the pin

    // --- the live defect ---

    /** Red before the fix: a member start into a tenant over its quota was admitted, sessions and all. */
    @Test
    fun aMemberStartOverTheQuotaIsRefusedWholeAndLeavesNoSession() {
        val f = fixture()
        setQuota(f.tenantId, 2 * s.byteSize - 1)
        memberStart(f, f.scopeA, s, s).then().statusCode(200)
            .body("results[0].error", equalTo("quota_exceeded"))
            .body("results[1].error", equalTo("quota_exceeded"))
            .body("results[0].uploadId", nullValue())
        assertEquals(0, sessionCount(f.scopeA), "a refused start opens no session")

        // control: the same request with room for it is admitted
        setQuota(f.tenantId, 2 * s.byteSize)
        memberStart(f, f.scopeA, s, s).then().statusCode(200)
            .body("results[0].uploadId", notNullValue())
            .body("results[1].uploadId", notNullValue())
        assertEquals(2, sessionCount(f.scopeA))
    }

    /** A cover is started through the same member door, so it is reserved the same way. */
    @Test
    fun aCoverStartOverTheQuotaIsRefused() {
        val f = fixture()
        setQuota(f.tenantId, s.byteSize - 1)
        memberStart(f, f.scopeA, s, ownerId = f.memoryA, role = "COVER").then().statusCode(200)
            .body("results[0].error", equalTo("quota_exceeded"))
        setQuota(f.tenantId, s.byteSize)
        memberStart(f, f.scopeA, s, ownerId = f.memoryA, role = "COVER").then().statusCode(200)
            .body("results[0].error", nullValue()) // control
    }

    @Test
    fun noQuotaIsTheDefaultAndAdmitsAnything() {
        val f = fixture()
        assertEquals(null, QuarkusTransaction.requiringNew().call { TenantEntity.findById(f.tenantId)!!.storageQuotaBytes })
        memberStart(f, f.scopeA, s, s, s).then().statusCode(200).body("results.error", equalTo(listOf(null, null, null)))
    }

    // --- the terms of the tenant sum ---

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
        given().header("Authorization", bearer(f.userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/${f.scopeB}/nodes/${f.memoryB}/cover-blob")
            .then().statusCode(200)
        assertTenantHolds(f, d.byteSize)
    }

    @Test
    fun anOpenSessionCounts() {
        val f = fixture()
        memberStartOk(f, f.scopeB, d)
        assertTenantHolds(f, d.byteSize)
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

    /** Three items where two fit → all three refused and no session: admitted whole or not at all. */
    @Test
    fun aRequestIsAdmittedWholeOrRefusedWhole() {
        val f = fixture()
        setQuota(f.tenantId, 2 * s.byteSize)
        memberStart(f, f.scopeA, s, s, s).then().statusCode(200)
            .body("results.error", equalTo(listOf("quota_exceeded", "quota_exceeded", "quota_exceeded")))
        assertEquals(0, sessionCount(f.scopeA))
    }

    // --- the two checks are independent (ADR 0073 §1, §4) ---

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

    // --- concurrency (A5) ---

    /** Member starts racing into one tenant with room for exactly one: the tenant-row lock lets exactly one through. */
    @Test
    fun parallelMemberStartsCannotOvershoot() {
        val f = fixture()
        setQuota(f.tenantId, s.byteSize)
        val n = 8
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = (1..n).map {
                pool.submit<String?> {
                    gate.await()
                    memberStart(f, f.scopeA, s).then().statusCode(200).extract().path<String?>("results[0].uploadId")
                }
            }
            gate.countDown()
            val admitted = futures.map { it.get(60, TimeUnit.SECONDS) }.count { it != null }
            assertEquals(1, admitted, "exactly one of $n parallel starts fits")
            assertEquals(1, sessionCount(f.scopeA))
        } finally {
            pool.shutdownNow()
        }
    }

    // --- setTenantStorageQuota / clearTenantStorageQuota: channel, gate, set, clear ---

    @Test
    fun theServerOwnerSetsTheQuotaOnTheListenerAndThePublicPortDoesNotKnowIt() {
        val f = fixture()
        val so = seedUser(serverRole = "SERVER_OWNER")
        given().header("Authorization", bearer(so)).contentType(ContentType.JSON)
            .body("""{"quotaBytes":123}""")
            .`when`().put("/api/tenants/${f.tenantId}/storage-quota")
            .then().statusCode(404)
        assertEquals(null, quotaOf(f.tenantId), "the public port wrote nothing")
        putQuota(so, f.tenantId, "123").then().statusCode(204) // control: the listener answers
        assertEquals(123L, quotaOf(f.tenantId))
        given().header("Authorization", bearer(so))
            .`when`().delete("/api/tenants/${f.tenantId}/storage-quota")
            .then().statusCode(404)
        assertEquals(123L, quotaOf(f.tenantId), "the public port cleared nothing")
    }

    @Test
    fun aTenantOwnerCannotSetOrClearTheirOwnQuota() {
        val f = fixture()
        putQuota(f.userId, f.tenantId, "999999999").then().statusCode(403).body("code", equalTo("not_server_owner"))
        assertEquals(null, quotaOf(f.tenantId))
        setQuota(f.tenantId, 7L)
        clearQuota(f.userId, f.tenantId).then().statusCode(403).body("code", equalTo("not_server_owner"))
        assertEquals(7L, quotaOf(f.tenantId))
    }

    @Test
    fun clearingTheQuotaAdmitsTheNextStart() {
        val f = fixture()
        val so = seedUser(serverRole = "SERVER_OWNER")
        putQuota(so, f.tenantId, "0").then().statusCode(204)
        memberStart(f, f.scopeA, s).then().statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        clearQuota(so, f.tenantId).then().statusCode(204)
        assertEquals(null, quotaOf(f.tenantId))
        memberStart(f, f.scopeA, s).then().statusCode(200).body("results[0].error", nullValue())
    }

    /** inbox #304: absence is never "no quota". An update without the field, or with null, is refused and keeps the
     *  quota it would have erased; clearing is its own call ([clearingTheQuotaAdmitsTheNextStart]). */
    @Test
    fun anUpdateWithoutTheQuotaIs400AndKeepsIt() {
        val f = fixture()
        val so = seedUser(serverRole = "SERVER_OWNER")
        putQuota(so, f.tenantId, "5").then().statusCode(204) // control: the same call with the field is taken
        givenAdminChannel().header("Authorization", bearer(so)).contentType(ContentType.JSON)
            .body("{}")
            .`when`().put("/api/tenants/${f.tenantId}/storage-quota")
            .then().statusCode(400)
        putQuota(so, f.tenantId, "null").then().statusCode(400)
        assertEquals(5L, quotaOf(f.tenantId), "a refused update erased nothing")
    }

    @Test
    fun aNegativeQuotaIs400AndAnUnknownTenantIs404() {
        val f = fixture()
        val so = seedUser(serverRole = "SERVER_OWNER")
        putQuota(so, f.tenantId, "-1").then().statusCode(400)
        putQuota(so, UUID.randomUUID().toString(), "1").then().statusCode(404).body("code", equalTo("tenant_not_found"))
        clearQuota(so, UUID.randomUUID().toString()).then().statusCode(404).body("code", equalTo("tenant_not_found"))
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

    /** A tenant whose owner [userId] owns two personal scopes, each with one Memory: A is probed, B holds content. */
    data class Tenant(val tenantId: String, val userId: String, val scopeA: String, val memoryA: String, val scopeB: String, val memoryB: String)

    data class Link(val shareId: String, val token: String)

    private fun fixture(): Tenant {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeA = UUID.randomUUID().toString()
        val scopeB = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "q-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            for (scopeId in listOf(scopeA, scopeB)) {
                ScopeEntity().apply {
                    id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL; createdAt = Instant.now()
                }.persist()
                MembershipEntity().apply {
                    id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                    this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
                }.persist()
            }
            TenantRoots.join(userId, tenantId, MembershipEntity.OWNER) // the tenant owner
        }
        return Tenant(tenantId, userId, scopeA, seedNode(scopeA, wrappedContentKey = CONTENT_KEY), scopeB, seedNode(scopeB, wrappedContentKey = CONTENT_KEY))
    }

    /** A CONTRIBUTOR link on Memory A, with one identity. */
    private fun link(f: Tenant): Link {
        val shareId = given().header("Authorization", bearer(f.userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"${f.memoryA}","role":"CONTRIBUTOR","wrap":"d3JhcA==","sealedName":"c2VhbGVk"}""")
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

    private fun quotaOf(tenantId: String): Long? =
        QuarkusTransaction.requiringNew().call { TenantEntity.findById(tenantId)!!.storageQuotaBytes }

    private fun sessionCount(scopeId: String): Long =
        QuarkusTransaction.requiringNew().call { UploadSessionEntity.count("scopeId", scopeId) }
}
