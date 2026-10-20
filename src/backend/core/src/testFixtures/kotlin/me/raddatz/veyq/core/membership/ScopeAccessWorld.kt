package me.raddatz.veyq.core.membership

import io.quarkus.arc.Arc
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.util.UUID

/**
 * One seeded instance holding a space in every state the scope-access gate tells apart
 * (uc.spaces.scope-access), each with real content in it — core's (a spare upload session and a job leased to the
 * space's owner) and every application's under test ([WorldContent]: Memories' node, medium and its blob, bucket and
 * share). The content is what lets a probe's **control** mean something: a call by a member that is refused by a
 * missing node answers 404 exactly like the gate's own refusal would, so a space without content would make every
 * control indistinguishable from a refusal.
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
 *
 * **Core's world names no application** (inbox #335). It builds with core alone (`ScopeAccessWorldCoreSliceTest`,
 * `:core`); an application's content comes from the [WorldContent] beans of the application under test, found
 * through `Arc` unless [contents] are handed in, and lands in [Space.content] — read through the application's typed
 * accessors (Memories' `space.nodeId`, `space.mediaId`, … in its own test fixtures), so a `:core` test that expects
 * Memories content does not compile.
 */
class ScopeAccessWorld(
    /** Every application's content; by default each [WorldContent] bean of the application under test. */
    private val contents: List<WorldContent> = discovered(),
) {

    data class Space(
        val tenantId: String,
        val domain: String,
        val scopeId: String,
        val ownerId: String,
        val uploadId: String,
        val jobId: String,
        /** The applications' content ids by name ([WorldContent.seed]). */
        val content: Map<String, String> = emptyMap(),
    ) {
        /** The content id an application seeded under [name] — red, naming it, when none did. */
        operator fun get(name: String): String = content[name]
            ?: error("no WorldContent seeded '$name' (seeded: ${content.keys.sorted()}) — is its application under test?")

        /** This space with the content id [name] replaced (a probe's invented id). */
        fun withContent(name: String, value: String): Space = copy(content = content + (name to value))
    }

    lateinit var live: Space
    lateinit var control: Space
    lateinit var deleting: Space
    lateinit var suspended: Space
    lateinit var stranger: String
    lateinit var proposal: String
    lateinit var proposalOnly: String

    /** A `Host` no tenant claims — so a refusal on it can only come from the gate, never from `TenantSuspensionFilter`. */
    val unclaimedHost = "unclaimed-${UUID.randomUUID()}.invalid"

    fun build(): ScopeAccessWorld {
        val tenantA = seedHostTenant()
        val tenantB = seedHostTenant()
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
    private fun seedHostTenant(): Pair<String, String> {
        val domain = "t-${UUID.randomUUID()}.example.com"
        return seedTenant(domain = domain) to domain
    }

    /** A GROUP owned by [ownerId], with core's content and then every application's, written through the API as its owner. */
    private fun seedSpace(tenant: Pair<String, String>, ownerId: String): Space {
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ScopeEntity().apply {
                id = scopeId; tenantId = tenant.first; kind = ScopeEntity.GROUP; encryptedName = B64
            }.persist()
        }
        member(ownerId, tenant.first, scopeId, MembershipEntity.OWNER)
        val auth = bearer(ownerId)

        given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"scopeId":"$scopeId","capabilityTag":"$JOB_TAG","sealedBody":"$B64"}""")
            .`when`().post("/api/tasks/enqueue").then().statusCode(201)
        val jobId = given().header("Authorization", auth).contentType(ContentType.JSON)
            .body("""{"capabilityTags":["$JOB_TAG"],"scopeId":"$scopeId"}""")
            .`when`().post("/api/tasks/claim")
            .then().statusCode(200).extract().path<String>("jobId")

        val core = Space(
            tenantId = tenant.first, domain = tenant.second, scopeId = scopeId, ownerId = ownerId,
            uploadId = startUpload(auth, scopeId, TestIds.uuid()), jobId = jobId,
        )
        val content = mutableMapOf<String, String>()
        for (c in contents) {
            val seeded = c.seed(core, auth)
            val clash = seeded.keys intersect content.keys
            check(clash.isEmpty()) { "${c::class.simpleName} seeds content names another WorldContent already did: $clash" }
            content += seeded
        }
        return core.copy(content = content)
    }

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

    companion object {
        const val B64 = "AQ=="

        /** A client-originated (non-structural) capability tag — any value but grant-delivery's may be enqueued. */
        const val JOB_TAG = "9f3a1c04"

        /** Every [WorldContent] bean of the application under test, in a stable order. */
        fun discovered(): List<WorldContent> =
            Arc.container().select(WorldContent::class.java).toList().sortedBy { it::class.java.name }

        /** A declared upload session in [scopeId] for the owner id [ownerId] (a medium's, or any), as [auth]; its id. */
        fun startUpload(auth: String, scopeId: String, ownerId: String): String =
            given().header("Authorization", auth).contentType(ContentType.JSON)
                .body("""{"items":[${TestBlob.SMALL.declare(B64, ownerId = ownerId)}]}""")
                .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
                .then().statusCode(200).extract().path("results[0].uploadId")
    }
}
