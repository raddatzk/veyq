package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.testing.TestFixtures.FIXTURE_EDITOR
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The S3 object key must carry **no structure** (ADR 0044's companion sensor, inbox #97 / cr-0171).
 *
 * The operator *runs the object store*: they list the bucket and read every key, without touching the database
 * and without breaking anything. It is the most operator-visible surface veyq has. A key minted as
 * `tenant/{name}/{title}.jpg` would hand them every filename, Memory title and tenant name **while the content
 * stayed perfectly encrypted and every other test in this repo stayed green** — because no other test looks at
 * the key. That is the whole reason this file exists: the failure is invisible to correctness testing.
 *
 * Today the key is a bare `UUID.randomUUID()` (`BlobUploadHandler.openSession`), which is right, and it is one helpful
 * edit away from being wrong. So this asserts two different things:
 *
 *  1. **Shape** — the minted key is a bare UUID and nothing else. This pins today's implementation.
 *  2. **Absence of structure** — the key contains none of the identifiers in scope when it was minted (tenant
 *     name, tenant id, user id, user email, scope id, node id). This pins the *invariant*, and it survives a
 *     legitimate future change of key format (a random base32 id, say) that would retire assertion 1.
 *
 * Hermetic: H2 + the in-memory store, no bytes, no e2e. The key is minted server-side and persisted before any
 * object is ever written, so `start` alone already exposes it; `media-batch` then proves `veyq_blob` inherits
 * the same bare key from the session rather than deriving a "nicer" one at attach time.
 *
 * **Split** (cr-platform-gradle-modules step 8a): the methods that run with core alone are `ObjectKeyCarriesNoStructureCoreSliceTest` in `:core`.
 */
@QuarkusTest
class ObjectKeyCarriesNoStructureSliceTest {

    /** A bare UUID: 8-4-4-4-12 hex, anchored — no prefix, no suffix, no path segment, no extension. */
    private val bareUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    @Test
    fun mintedObjectKeyIsABareUuidAndLeaksNoIdentifier() {
        val seed = seedEverything()

        val uploadId = given().header("Authorization", bearer(seed.userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.ofChunks(2).declare("d3JhcHBlZA==")}]}""")
            .`when`().post("/api/scopes/${seed.scopeId}/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].uploadId", notNullValue())
            .extract().path<String>("results[0].uploadId")

        val sessionKey = QuarkusTransaction.requiringNew().call<String> {
            UploadSessionEntity.findById(uploadId)!!.storageKey
        }
        assertBareAndOpaque(sessionKey, seed, "veyq_upload_session.storage_key")

        // Attach, so the key crosses into veyq_blob — the row the purge + presign paths read.
        val mediaId = given().header("Authorization", bearer(seed.userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","nodeEpoch":0,"originalUploadId":"$uploadId","nodeId":"${seed.nodeId}","wrappedMeta":"bWV0YQ=="}]}""")
            .`when`().post("/api/scopes/${seed.scopeId}/media-batch")
            .then().statusCode(200)
            .body("results[0].error", nullValue())
            .extract().path<String>("results[0].mediaId")

        val blobKey = QuarkusTransaction.requiringNew().call<String> {
            val media = MediaEntity.findById(mediaId)!!
            BlobEntity.findById(media.blobId)!!.storageKey
        }
        assertBareAndOpaque(blobKey, seed, "veyq_blob.storage_key")
        assertEquals(sessionKey, blobKey, "attach must inherit the session's key, never re-derive one")
    }

    private fun assertBareAndOpaque(key: String, seed: Seed, where: String) {
        assertTrue(bareUuid.matches(key), "$where must be a bare UUID, but was '$key' — a structured object key " +
            "hands the operator (who lists the bucket) what the ciphertext is hiding")
        for ((label, secret) in seed.identifiers()) {
            assertTrue(!key.contains(secret, ignoreCase = true), "$where leaked the $label into the object key: '$key'")
        }
    }

    private class Seed(
        val userId: String, val email: String, val tenantId: String, val tenantName: String,
        val scopeId: String, val nodeId: String,
    ) {
        /** Everything in scope when the key is minted — none of it may end up in the key. */
        fun identifiers() = listOf(
            "userId" to userId, "user email" to email, "tenantId" to tenantId,
            "tenant name" to tenantName, "scopeId" to scopeId, "nodeId" to nodeId,
        )
    }

    /** Seed a user + ACTIVE tenant + PERSONAL scope + a MEMORY node, each with a distinctive, greppable value. */
    private fun seedEverything(): Seed {
        val unique = UUID.randomUUID().toString().take(8)
        val seed = Seed(
            userId = UUID.randomUUID().toString(),
            email = "objectkey-$unique@example.com",
            tenantId = UUID.randomUUID().toString(),
            tenantName = "ObjectKeyTenant$unique",
            scopeId = UUID.randomUUID().toString(),
            nodeId = UUID.randomUUID().toString(),
        )
        QuarkusTransaction.requiringNew().run {
            newUser(id = seed.userId, email = seed.email).persist()
            TenantEntity().apply {
                id = seed.tenantId; name = seed.tenantName; domain = "t-${seed.tenantId}.example.com"
                ownerEmail = seed.email; status = TenantEntity.ACTIVE
            }.persist()
            ScopeEntity().apply {
                id = seed.scopeId; tenantId = seed.tenantId; kind = ScopeEntity.PERSONAL
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = seed.userId; tenantId = seed.tenantId
                scopeId = seed.scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="
                createdAt = Instant.now()
            }.persist()
            TenantRoots.join(seed.userId, seed.tenantId)
            NodeEntity().apply {
                id = seed.nodeId; scopeId = seed.scopeId; parentId = seed.scopeId; kind = MemoriesNodeKinds.MEMORY
                encryptedName = "bg=="; slugToken = "c2x1Zy0${seed.nodeId}".take(120); orderIndex = 0
                wrappedContentKey = "a21lbQ=="; createdAt = Instant.now(); nameWrittenBy(FIXTURE_EDITOR)
            }.persist()
        }
        return seed
    }
}
