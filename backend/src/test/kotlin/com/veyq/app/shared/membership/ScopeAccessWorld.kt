package com.veyq.app.shared.membership

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.media.TestBlob
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.shared.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.notNullValue
import java.time.Instant
import java.util.UUID

/**
 * One seeded instance holding a space in every state the scope-access gate tells apart
 * (uc.spaces.scope-access), each with real content in it — a node, a medium and its blob, a sub-bucket, a spare
 * upload session, a share and a job leased to the space's owner. The content is what lets a probe's **control**
 * mean something: a call by a member that is refused by a missing node answers 404 exactly like the gate's own
 * refusal would, so a space without content would make every control indistinguishable from a refusal.
 *
 * The content is written through the API while every space is still live, and only then are the states flipped
 * (the tenant suspended, the group put into its deletion grace) — so every row is one the product itself could
 * have produced, and nothing here depends on the gate letting a seeding write through.
 *
 * - [live]: a GROUP in tenant A (ACTIVE), owned by [Space.ownerId], who is also tenant A's root OWNER.
 * - [control]: a second GROUP exactly like [live], same owner — where a probe fires its control. A separate space,
 *   so a negative the gate wrongly lets through (and that mutates, e.g. purges the medium) cannot take the
 *   control's state with it: the control then still answers about the gate, not about a neighbour's side effect.
 * - [deleting]: a GROUP in tenant A being deleted, owned by a root MEMBER of A.
 * - [suspended]: a GROUP in tenant B, which is SUSPENDED, owned by B's root OWNER.
 * - [stranger]: no row anywhere. [proposal]: an accepted root member of A with an unaccepted proposal on [live].
 *   [proposalOnly]: the same proposal and no other row in A — what the retired `tenantId`-only belonging check
 *   counted as a tenant member.
 */
class ScopeAccessWorld(
    private val sessions: SessionService,
    /** The role of each space's seeded share — a profile that switches VIEWER links off seeds UPLOADER ones. */
    private val shareRole: String = "VIEWER",
) {

    data class Space(
        val tenantId: String,
        val domain: String,
        val scopeId: String,
        val ownerId: String,
        val nodeId: String,
        val mediaId: String,
        val blobId: String,
        val bucketId: String,
        val uploadId: String,
        val shareId: String,
        val jobId: String,
    )

    lateinit var live: Space
    lateinit var control: Space
    lateinit var deleting: Space
    lateinit var suspended: Space
    lateinit var stranger: String
    lateinit var proposal: String
    lateinit var proposalOnly: String

    /** A `Host` no tenant claims — so a refusal on it can only come from the gate, never from `TenantSuspensionFilter`. */
    val unclaimedHost = "unclaimed-${UUID.randomUUID()}.invalid"

    fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    fun build(): ScopeAccessWorld {
        val tenantA = seedTenant()
        val tenantB = seedTenant()
        val ownerA = seedUser().also { member(it, tenantA.first, tenantA.first, MembershipEntity.OWNER) }
        val deleter = seedUser().also { member(it, tenantA.first, tenantA.first, MembershipEntity.MEMBER) }
        val ownerB = seedUser().also { member(it, tenantB.first, tenantB.first, MembershipEntity.OWNER) }

        live = seedSpace(tenantA, ownerA)
        control = seedSpace(tenantA, ownerA)
        deleting = seedSpace(tenantA, deleter)
        suspended = seedSpace(tenantB, ownerB)

        stranger = seedUser()
        proposal = seedUser().also {
            member(it, tenantA.first, tenantA.first, MembershipEntity.MEMBER)
            proposal(it, tenantA.first, live.scopeId)
        }
        proposalOnly = seedUser().also { proposal(it, tenantA.first, live.scopeId) }

        QuarkusTransaction.requiringNew().run {
            TenantEntity.update("status = ?1 where id = ?2", TenantEntity.SUSPENDED, tenantB.first)
            ScopeEntity.update("deletedAt = ?1 where id = ?2", Instant.now(), deleting.scopeId)
        }
        return this
    }

    /** A tenant with its root scope; returns (tenantId, domain). */
    private fun seedTenant(): Pair<String, String> {
        val tenantId = UUID.randomUUID().toString()
        val domain = "t-$tenantId.example.com"
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Probe"; this.domain = domain; ownerEmail = "o-$tenantId@example.com"
                status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = tenantId; this.tenantId = tenantId; kind = ScopeEntity.TENANT; createdAt = Instant.now()
            }.persist()
        }
        return tenantId to domain
    }

    /** A GROUP owned by [ownerId], with content written through the API as its owner. */
    private fun seedSpace(tenant: Pair<String, String>, ownerId: String): Space {
        val scopeId = UUID.randomUUID().toString()
        val nodeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply {
                id = scopeId; tenantId = tenant.first; kind = ScopeEntity.GROUP; encryptedName = B64; createdAt = Instant.now()
            }.persist()
            NodeEntity().apply {
                id = nodeId; this.scopeId = scopeId; parentId = scopeId; kind = NodeEntity.MEMORY
                encryptedName = B64; slugToken = "c2x1Zw$nodeId".take(120); orderIndex = 0
                wrappedContentKey = B64; createdAt = Instant.now()
            }.persist()
        }
        member(ownerId, tenant.first, scopeId, MembershipEntity.OWNER)
        val auth = token(ownerId)

        val completed = start(auth, scopeId)
        val done = given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"items":[{"originalUploadId":"$completed","nodeId":"$nodeId"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].mediaId", notNullValue()).extract()
        val bucketId = given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"sealedName":"$B64"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/buckets")
            .then().statusCode(200).extract().path<String>("bucketId")
        val shareId = given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"nodeId":"$nodeId","role":"$shareRole","wrap":"$B64","sealedName":"$B64"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path<String>("shareId")
        given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","capabilityTag":"$JOB_TAG","sealedBody":"$B64"}""")
            .`when`().post("/api/tasks/enqueue").then().statusCode(201)
        val jobId = given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["$JOB_TAG"],"scopeId":"$scopeId"}""")
            .`when`().post("/api/tasks/claim")
            .then().statusCode(200).extract().path<String>("jobId")

        return Space(
            tenantId = tenant.first, domain = tenant.second, scopeId = scopeId, ownerId = ownerId, nodeId = nodeId,
            mediaId = done.path("results[0].mediaId"), blobId = done.path("results[0].blobId"), bucketId = bucketId,
            uploadId = start(auth, scopeId), shareId = shareId, jobId = jobId,
        )
    }

    private fun start(auth: String, scopeId: String): String =
        given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare(B64)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).extract().path("results[0].uploadId")

    /** An accepted, granted membership. */
    private fun member(userId: String, tenantId: String, scopeId: String, role: String) =
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; this.role = role; wrappedGrant = B64; createdAt = Instant.now()
            }.persist()
        }

    /** An unaccepted consent proposal (ADR 0014 §5): grant-less, carrying only the sealed name-key. */
    private fun proposal(userId: String, tenantId: String, scopeId: String) =
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.MEMBER; pendingConsent = true
                sealedNameKey = B64; createdAt = Instant.now()
            }.persist()
        }

    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "probe-$userId@example.com"; serverRole = "MEMBER"; suiteId = "v1"
                identityPublicKey = B64; wrappedIdentityPrivateKey = B64; authPublicKey = B64; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }

    companion object {
        const val B64 = "AQ=="

        /** A client-originated (non-structural) capability tag — any value but grant-delivery's may be enqueued. */
        const val JOB_TAG = "9f3a1c04"
    }
}
