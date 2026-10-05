package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.core.quota.StorageQuota
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.identityKeyJson
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import me.raddatz.veyq.memories.media.ShareMediaRead
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
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
 * The per-link storage limit (uc.sharing.share-link-storage-limit, ADR 0073 §2–§6) through the anonymous
 * READ_WRITE start. A link's usage is its contributions' blobs (the medium's own and each derivative's, trash
 * included) plus the sessions its identities opened; a start whose admissible items do not fit is refused whole,
 * `quota_exceeded`, with no session left behind; concurrent starts serialize on the tenant row.
 *
 * Most cases pin the usage EXACTLY through [assertLinkHolds]: a one-blob start is refused at `expected + S - 1` and
 * admitted at `expected + S`. That pair is each case's negative and its positive control on the same predicate, and
 * it goes red whichever way the SUM drifts — a term dropped (too little) or a scope over-reached (too much).
 */
@QuarkusTest
class ShareLinkStorageLimitSliceTest {

    private val s = TestBlob.SMALL
    private val d = TestBlob.ofPlaintext(100) // a derivative of a size no original here has, so its bytes are visible

    @Inject
    lateinit var quota: StorageQuota

    /**
     * The link's usage read directly, both terms at once: a contributed medium with a derivative (the blob term)
     * plus an open session (the session term), beside a member's upload into the same Memory that is not the
     * link's. 300 = 44 + 128 + 128 is what the one-SQL `linkUsage` returned before its blob term moved behind
     * `UploadAdmission` (recorded on the tree before that split): the split is exact only if the terms still add up.
     */
    @Test
    fun aLinksUsageIsItsContributedBlobsPlusItsOpenSessions() {
        val f = fixture()
        contribute(f, s, derivative = d)
        startOk(f, d)
        memberUpload(f, d)
        assertEquals(300L, s.byteSize + 2 * d.byteSize, "the fixture's sizes")
        assertEquals(300L, QuarkusTransaction.requiringNew().call { quota.linkUsage(f.shareId) })
    }

    /** The live defect (red before the fix): a link start over the limit left sessions open for the scope. */
    @Test
    fun aLinkStartOverTheLimitIsRefusedWholeAndLeavesNoSession() {
        val f = fixture()
        setLimit(f.tenantId, 1)
        start(f, s, s).then().statusCode(200)
            .body("results[0].error", equalTo("quota_exceeded"))
            .body("results[1].error", equalTo("quota_exceeded"))
            .body("results[0].uploadId", nullValue())
        assertEquals(0, sessionCount(f.scopeId), "a refused start opens no session")

        // control: the same request with room for it is admitted
        setLimit(f.tenantId, 2 * s.byteSize)
        start(f, s, s).then().statusCode(200)
            .body("results[0].uploadId", notNullValue())
            .body("results[1].uploadId", notNullValue())
        assertEquals(2, sessionCount(f.scopeId))
    }

    @Test
    fun aTrashedContributionStillCounts() {
        val f = fixture()
        val mediaId = contribute(f, s)
        QuarkusTransaction.requiringNew().run { MediaEntity.findById(mediaId)!!.deletedAt = Instant.now() }
        assertLinkHolds(f, s.byteSize)
    }

    @Test
    fun aDerivativeCounts() {
        val f = fixture()
        contribute(f, s, derivative = d)
        assertLinkHolds(f, s.byteSize + d.byteSize)
    }

    @Test
    fun anOpenSessionCounts() {
        val f = fixture()
        startOk(f, d)
        assertLinkHolds(f, d.byteSize)
    }

    @Test
    fun aContributorsOwnDeleteFreesAtOnce() {
        val f = fixture()
        val mediaId = contribute(f, s, derivative = d)
        assertLinkHolds(f, s.byteSize + d.byteSize, admit = false) // the positive control: it counted before

        given().header("X-Contributor-Token", f.token)
            .`when`().delete("/api/shares/${f.shareId}/media/$mediaId")
            .then().statusCode(204)
        assertLinkHolds(f, 0)
    }

    @Test
    fun aSecondLinkOfTheTenantHasItsOwnRoom() {
        val f = fixture()
        val other = link(f, createShare(f, "READ_WRITE"), "READ_WRITE")
        contribute(f, s)
        setLimit(f.tenantId, s.byteSize)
        startOk(other, s) // the other link holds nothing of the first's
        start(f, s).then().statusCode(200).body("results[0].error", equalTo("quota_exceeded")) // control: the first is full
    }

    @Test
    fun aMembersUploadIntoTheSameMemoryDoesNotCount() {
        val f = fixture()
        memberUpload(f, d)
        assertLinkHolds(f, 0)
    }

    @Test
    fun anUploaderLinkIsBoundedToo() {
        val f = fixture(role = "WRITE_ONLY")
        contribute(f, s)
        assertLinkHolds(f, s.byteSize)
    }

    /** Three items where two fit → all three refused and no session: the request is admitted whole or not at all. */
    @Test
    fun aRequestIsAdmittedWholeOrRefusedWhole() {
        val f = fixture()
        setLimit(f.tenantId, 2 * s.byteSize)
        start(f, s, s, s).then().statusCode(200)
            .body("results.error", equalTo(listOf("quota_exceeded", "quota_exceeded", "quota_exceeded")))
        assertEquals(0, sessionCount(f.scopeId))
    }

    /** An item refused on its own (over the per-blob cap) is not charged: only the admissible items are summed. */
    @Test
    fun anInadmissibleItemKeepsItsOwnCodeAndIsNotCharged() {
        val f = fixture()
        setLimit(f.tenantId, s.byteSize)
        val oversize = """{"blobId":"${UUID.randomUUID()}","ownerId":"${UUID.randomUUID()}","role":"ORIGINAL","chunkCount":1,"byteSize":${Long.MAX_VALUE / 2},"wrappedBlobKey":"d3JhcA=="}"""
        startRaw(f, "[${s.declare("d3JhcA==")},$oversize]").then().statusCode(200)
            .body("results[0].uploadId", notNullValue())
            .body("results[1].error", equalTo("budget_exceeded"))
    }

    /** Starts racing through one link with room for exactly one: the tenant-row lock lets exactly one through. */
    @Test
    fun parallelStartsCannotOvershoot() {
        val f = fixture()
        setLimit(f.tenantId, s.byteSize)
        val n = 8
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = (1..n).map {
                pool.submit<String?> {
                    gate.await()
                    start(f, s).then().statusCode(200).extract().path<String?>("results[0].uploadId")
                }
            }
            gate.countDown()
            val admitted = futures.map { it.get(60, TimeUnit.SECONDS) }.count { it != null }
            assertEquals(1, admitted, "exactly one of $n parallel starts fits")
            assertEquals(1, sessionCount(f.scopeId))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aLoweredLimitDeletesNothingAndRefusesTheNextStart() {
        val f = fixture()
        val mediaId = contribute(f, s, derivative = d)
        putLimit(f, s.byteSize).then().statusCode(200).body("shareLinkLimitBytes", equalTo(s.byteSize.toInt()))

        start(f, s).then().statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        ShareMediaRead.of(f.shareId).then().statusCode(200)
            .body("media.find { it.mediaId == '$mediaId' }", notNullValue()) // nothing was deleted
        putLimit(f, s.byteSize + d.byteSize + s.byteSize).then().statusCode(200)
        startOk(f, s) // control: raising it again admits
    }

    @Test
    fun aZeroLimitRefusesEveryLinkStart() {
        val f = fixture()
        val other = link(f, createShare(f, "WRITE_ONLY"), "WRITE_ONLY")
        putLimit(f, 0).then().statusCode(200)
        start(f, s).then().statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        start(other, s).then().statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        memberStartOk(f, s) // control: a member start is not a link start
    }

    // --- the usage probe ---

    /**
     * The link holds exactly [expected] bytes: one blob of `S` is refused at `expected + S - 1` (no session opened)
     * and — unless [admit] is false — admitted at `expected + S`, which leaves that session open.
     */
    private fun assertLinkHolds(f: Link, expected: Long, admit: Boolean = true) {
        val before = sessionCount(f.scopeId)
        setLimit(f.tenantId, expected + s.byteSize - 1)
        start(f, s).then().statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        assertEquals(before, sessionCount(f.scopeId), "a refused start opens no session")
        if (!admit) return
        setLimit(f.tenantId, expected + s.byteSize)
        startOk(f, s)
    }

    // --- fixture ---

    data class Link(val tenantId: String, val scopeId: String, val memory: String, val ownerId: String, val shareId: String, val token: String)

    private fun fixture(role: String = "READ_WRITE"): Link {
        val ownerId = seedUser()
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
                id = UUID.randomUUID().toString(); userId = ownerId; this.tenantId = tenantId
                this.scopeId = scopeId; this.role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(ownerId, tenantId, MembershipEntity.OWNER) // the tenant owner, who sets the limit
        }
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val partial = Link(tenantId, scopeId, memory, ownerId, shareId = "", token = "")
        return link(partial, createShare(partial, role), role)
    }

    /** A second identity-bearing handle on share [shareId] of [f]'s Memory. */
    private fun link(f: Link, shareId: String, role: String): Link {
        val token = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"c2VhbGVkLW5hbWU="${identityKeyJson(role)}}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path<String>("contributorToken")
        return f.copy(shareId = shareId, token = token)
    }

    private fun createShare(f: Link, role: String): String =
        given().header("Authorization", bearer(f.ownerId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"${f.memory}","role":"$role",${shareKeysJson(role, f.memory)},"sealedName":"c2VhbGVk"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")

    private fun start(f: Link, vararg blobs: TestBlob): Response =
        startRaw(f, blobs.joinToString(",", "[", "]") { it.declare("d3JhcA==") })

    private fun startRaw(f: Link, items: String): Response =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${f.token}","items":$items}""")
            .`when`().post("/api/shares/${f.shareId}/blob-uploads")

    private fun startOk(f: Link, blob: TestBlob): String =
        start(f, blob).then().statusCode(200)
            .body("results[0].error", nullValue())
            .extract().path("results[0].uploadId")

    /** Start + complete one medium through the link (with an optional THUMBNAIL derivative); returns its id. */
    private fun contribute(f: Link, original: TestBlob, derivative: TestBlob? = null): String {
        val mediaId = UUID.randomUUID().toString()
        val items = listOfNotNull(original.declare("b3JpZw==", ownerId = mediaId), derivative?.declare("dGh1bWI=", ownerId = mediaId, role = "THUMBNAIL"))
        val ids = startRaw(f, items.joinToString(",", "[", "]")).then().statusCode(200)
            .body("results[0].error", nullValue())
            .extract().path<List<String>>("results.uploadId")
        val derivs = if (derivative == null) "" else ""","derivatives":[{"kind":"THUMBNAIL","uploadId":"${ids[1]}"}]"""
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${f.token}","items":[{"mediaId":"$mediaId","originalUploadId":"${ids[0]}"$derivs}]}""")
            .`when`().post("/api/shares/${f.shareId}/media")
            .then().statusCode(200).body("results[0].mediaId", equalTo(mediaId))
        return mediaId
    }

    private fun memberStartOk(f: Link, blob: TestBlob, ownerId: String = UUID.randomUUID().toString()): String =
        given().header("Authorization", bearer(f.ownerId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare("b3JpZw==", ownerId = ownerId)}]}""")
            .`when`().post("/api/scopes/${f.scopeId}/blob-uploads-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path("results[0].uploadId")

    /** A member's own upload into the link's Memory, completed. */
    private fun memberUpload(f: Link, blob: TestBlob) {
        val mediaId = UUID.randomUUID().toString()
        val uploadId = memberStartOk(f, blob, ownerId = mediaId)
        given().header("Authorization", bearer(f.ownerId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"${f.memory}","mediaId":"$mediaId","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/scopes/${f.scopeId}/media-batch")
            .then().statusCode(200).body("results[0].mediaId", equalTo(mediaId))
    }

    private fun putLimit(f: Link, bytes: Long): Response =
        given().header("Authorization", bearer(f.ownerId)).contentType(ContentType.JSON)
            .body("""{"shareLinkLimitBytes":$bytes}""")
            .`when`().put("/api/tenants/${f.tenantId}/storage")

    private fun setLimit(tenantId: String, bytes: Long) =
        QuarkusTransaction.requiringNew().run { TenantEntity.findById(tenantId)!!.shareLinkLimitBytes = bytes }

    private fun sessionCount(scopeId: String): Long =
        QuarkusTransaction.requiringNew().call { UploadSessionEntity.count("scopeId", scopeId) }
}
