package com.veyq.app.shared.http

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.Instant
import java.util.UUID

/**
 * The **enforcement guard** for the operator-blind membership boundary (ADR 0042, cr-0162): for every
 * authenticated, scope-addressed endpoint, a **non-member** must get the *same* answer for a scope that really
 * exists as for a scope id they invented. If the two differ in status or `code`, the endpoint is an **existence
 * oracle** — a stranger can enumerate scope ids by reading the difference, which breaks operator-blindness across
 * the membership boundary.
 *
 * This is a test rather than a `grep` on purpose, and the choice is load-bearing: the defect is guard **order**
 * (does the operation probe existence/kind *before* it gates on membership?), and order is not greppable. A new
 * endpoint that probes first fails here on the day it is written. Do **not** replace this with a static check.
 *
 * Adding a scope-addressed endpoint? Add a row to [endpoints]. The row's expected status is the *membership-guard*
 * answer (404), asserted alongside the identity check — otherwise a request rejected at the `@Valid` boundary
 * (400 for both probes) would pass the identity check while never reaching the guard at all.
 *
 * **Not covered here, deliberately:** token-gated endpoints, where a non-member reaches the resource *legitimately*
 * by possessing an unguessable secret — the `@PermitAll` share-guest ops (`SharingResource`) and tenant
 * `preview`/`register`/`accept` (`TenantsResource`). Those callers already know the resource exists, so their 403s
 * are correct and a membership guard there would break invite acceptance and public sharing.
 */
@QuarkusTest
class OperatorBlindProbeTest {

    @Inject lateinit var sessions: SessionService

    /** One probe: the request to fire, and the status the membership guard must answer a non-member with. */
    private data class Probe(
        val label: String,
        val method: String,
        /** Path with `{scopeId}` as the placeholder the probe substitutes. */
        val path: String,
        val body: String? = null,
        val expectedStatus: Int = 404,
    )

    private val someId = "00000000-0000-4000-8000-000000000001" // a nodeId/mediaId/userId placeholder — never reached
    private val b64 = "AQ=="

    /**
     * Every authenticated, scope-addressed endpoint in the API (the `contracts/api` specs: the paths under
     * `/api/scopes/{scopeId}`, plus `tasks.enqueue`, which addresses a scope in its body).
     */
    private val endpoints = listOf(
        // --- content ---
        Probe("content.createNode", "POST", "/api/scopes/{scopeId}/nodes",
            """{"kind":"FOLDER","encryptedName":"$b64","slugToken":"$b64","orderIndex":0}"""),
        Probe("content.listNodes", "GET", "/api/scopes/{scopeId}/nodes"),
        Probe("content.updateNode", "PATCH", "/api/scopes/{scopeId}/nodes/$someId", """{"encryptedName":"$b64"}"""),
        Probe("content.deleteNode", "DELETE", "/api/scopes/{scopeId}/nodes/$someId"),
        Probe("content.getFavorites", "GET", "/api/scopes/{scopeId}/favorites"),
        Probe("content.putFavorites", "PUT", "/api/scopes/{scopeId}/favorites", """{"ciphertext":"$b64"}"""),
        // --- media ---
        Probe("media.startBlobUploads", "POST", "/api/scopes/{scopeId}/blob-uploads-batch",
            """{"items":[{"chunkCount":1,"byteSize":1024,"wrappedBlobKey":"$b64"}]}"""),
        Probe("media.completeMedia", "POST", "/api/scopes/{scopeId}/media-batch",
            """{"items":[{"nodeId":"$someId","originalUploadId":"$someId","derivatives":[]}]}"""),
        Probe("media.listNodeMedia", "GET", "/api/scopes/{scopeId}/nodes/$someId/media"),
        Probe("media.listNodeBuckets", "GET", "/api/scopes/{scopeId}/nodes/$someId/buckets"),
        Probe("media.createBucket", "POST", "/api/scopes/{scopeId}/nodes/$someId/buckets", """{"sealedName":"$b64"}"""),
        Probe("media.renameBucket", "PATCH", "/api/scopes/{scopeId}/nodes/$someId/buckets/$someId", """{"sealedName":"$b64"}"""),
        Probe("media.deleteBucket", "DELETE", "/api/scopes/{scopeId}/nodes/$someId/buckets/$someId"),
        Probe("media.moveBucketMedia", "POST", "/api/scopes/{scopeId}/nodes/$someId/media-moves",
            """{"mediaIds":["$someId"],"targetBucketId":null}"""),
        Probe("media.commitCoverBlob", "POST", "/api/scopes/{scopeId}/nodes/$someId/cover-blob", """{"uploadId":"$someId"}"""),
        Probe("media.deleteCoverBlob", "DELETE", "/api/scopes/{scopeId}/nodes/$someId/cover-blob"),
        Probe("media.getMediaExif", "GET", "/api/scopes/{scopeId}/media/$someId/exif"),
        Probe("media.updateMediaMeta", "PUT", "/api/scopes/{scopeId}/media/$someId/meta", """{"wrappedMeta":"$b64"}"""),
        Probe("media.deleteMedia", "DELETE", "/api/scopes/{scopeId}/media/$someId"),
        Probe("media.restoreMedia", "POST", "/api/scopes/{scopeId}/media/$someId/restore"),
        Probe("media.purgeMedia", "POST", "/api/scopes/{scopeId}/media/$someId/purge"),
        Probe("media.presignBlobDownloads", "POST", "/api/scopes/{scopeId}/blob-downloads-batch",
            """{"items":[{"blobId":"$someId"}]}"""),
        // --- scopes (the sites cr-0162 reordered) ---
        Probe("scopes.listMembers", "GET", "/api/scopes/{scopeId}/members"),
        Probe("scopes.addGroupMember", "POST", "/api/scopes/{scopeId}/members", """{"username":"ghost"}"""),
        Probe("scopes.resolveGroupMember", "POST", "/api/scopes/{scopeId}/members/resolve", """{"username":"ghost"}"""),
        Probe("scopes.setMemberRole", "PATCH", "/api/scopes/{scopeId}/members/$someId", """{"role":"OWNER"}"""),
        Probe("scopes.removeMember", "DELETE", "/api/scopes/{scopeId}/members/$someId"),
        Probe("scopes.listEpochs", "GET", "/api/scopes/{scopeId}/epochs"),
        Probe("scopes.rotateScope", "POST", "/api/scopes/{scopeId}/epochs", """{"wrappedPrev":"$b64","grants":[]}"""),
        Probe("scopes.deleteGroup", "DELETE", "/api/scopes/{scopeId}"),
        Probe("scopes.restoreGroup", "POST", "/api/scopes/{scopeId}/restore"),
        Probe("scopes.renameGroup", "POST", "/api/scopes/{scopeId}/name", """{"encryptedName":"$b64","nameEpoch":0}"""),
        Probe("scopes.setScopeIcon", "POST", "/api/scopes/{scopeId}/icon", """{"sealedIcon":"$b64","iconEpoch":0}"""),
        Probe("scopes.clearScopeIcon", "DELETE", "/api/scopes/{scopeId}/icon"),
        // Invitation ops: gated on the caller's *own* membership, so a non-member gets `invitation_not_found` for
        // real and invented ids alike; decline is unconditionally idempotent (204 either way — no oracle).
        Probe("scopes.acceptInvitation", "POST", "/api/scopes/{scopeId}/invitations/accept"),
        Probe("scopes.declineInvitation", "POST", "/api/scopes/{scopeId}/invitations/decline", expectedStatus = 204),
        // --- tasks (addresses the scope in the body, not the path) ---
        Probe("tasks.enqueue", "POST", "/api/tasks/enqueue",
            """{"scopeId":"{scopeId}","capabilityTag":"9f3a1c04","sealedBody":"$b64"}"""),
    )

    @TestFactory
    fun noScopeAddressedEndpointDistinguishesANonMemberFromANonExistentScope(): List<DynamicTest> {
        val (realScopeId, _) = seedGroupWithAnOwner()
        val stranger = seedUser()
        val inventedScopeId = UUID.randomUUID().toString()

        return endpoints.map { probe ->
            DynamicTest.dynamicTest(probe.label) {
                val onReal = fire(probe, stranger, realScopeId)
                val onInvented = fire(probe, stranger, inventedScopeId)

                assertEquals(
                    onReal.statusCode() to onReal.code(), onInvented.statusCode() to onInvented.code(),
                    "${probe.label} is an existence oracle: a non-member gets a different answer for a scope that " +
                        "exists than for one that does not, so scope ids are enumerable. The membership guard must " +
                        "be the FIRST guard — before any existence/kind/state probe (ADR 0042).",
                )
                assertEquals(
                    probe.expectedStatus, onReal.statusCode(),
                    "${probe.label} answered a non-member ${onReal.statusCode()} (${onReal.code()}), not the " +
                        "expected ${probe.expectedStatus} of the membership guard. If this is a 400 the request " +
                        "never reached the guard (fix the probe's body); otherwise the guard is wrong.",
                )
            }
        }
    }

    private fun fire(probe: Probe, userId: String, scopeId: String): Response {
        val spec = given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}")
        probe.body?.let { spec.contentType(ContentType.JSON).body(it.replace("{scopeId}", scopeId)) }
        val path = probe.path.replace("{scopeId}", scopeId)
        return when (probe.method) {
            "GET" -> spec.`when`().get(path)
            "POST" -> spec.`when`().post(path)
            "PUT" -> spec.`when`().put(path)
            "PATCH" -> spec.`when`().patch(path)
            "DELETE" -> spec.`when`().delete(path)
            else -> error("unsupported method ${probe.method}")
        }
    }

    /** The `code` of an error envelope, or null for a body-less success. */
    private fun Response.code(): String? = if (statusCode() in 200..299) null else jsonPath().getString("code")

    /** A real GROUP scope in an ACTIVE tenant, owned by a granted member. Returns (groupScopeId, ownerId). */
    private fun seedGroupWithAnOwner(): Pair<String, String> {
        val ownerId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val groupId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Probe"; domain = "probe-$tenantId.example.com"
                ownerEmail = "owner-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = tenantId; this.tenantId = tenantId; kind = ScopeEntity.TENANT; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = groupId; this.tenantId = tenantId; kind = ScopeEntity.GROUP
                encryptedName = "$b64"; createdAt = Instant.now()
            }.persist()
            listOf(tenantId, groupId).forEach { scope ->
                MembershipEntity().apply {
                    id = UUID.randomUUID().toString(); userId = ownerId; this.tenantId = tenantId
                    scopeId = scope; role = MembershipEntity.OWNER; wrappedGrant = b64; createdAt = Instant.now()
                }.persist()
            }
        }
        return groupId to ownerId
    }

    /** A user with no membership anywhere — the stranger doing the probing. */
    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "probe-$userId@example.com"; serverRole = "MEMBER"; suiteId = "v1"
                identityPublicKey = b64; wrappedIdentityPrivateKey = b64; authPublicKey = b64
                createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
