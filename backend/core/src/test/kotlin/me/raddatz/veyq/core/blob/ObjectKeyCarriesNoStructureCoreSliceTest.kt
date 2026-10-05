package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.blobstore.BlobStore.Companion.chunkKey
import io.quarkus.test.junit.QuarkusTest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The S3 object key must carry **no structure** (ADR 0044's companion sensor, inbox #97 / cr-0171).
 *
 * The operator *runs the object store*: they list the bucket and read every key, without touching the database
 * and without breaking anything. It is the most operator-visible surface veyq has. A key minted as
 * `tenant/{name}/{title}.jpg` would hand them every filename, album title and tenant name **while the content
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
 * **Split from `ObjectKeyCarriesNoStructureSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `ObjectKeyCarriesNoStructureSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class ObjectKeyCarriesNoStructureCoreSliceTest {

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

    @Test
    fun chunkKeyAppendsOnlyTheIndex() {
        val storageKey = UUID.randomUUID().toString()

        assertEquals("$storageKey/0", chunkKey(storageKey, 0))
        assertEquals("$storageKey/7", chunkKey(storageKey, 7))
        // The whole key the operator sees is `<uuid>/<index>` — the prefix stays bare, the suffix is an integer.
        // No extension: an operator who sees `.jpg` learns the media type the client deliberately never sends.
        assertTrue(
            Regex("^[0-9a-fA-F-]{36}/\\d+$").matches(chunkKey(storageKey, 3)),
            "chunk key must be exactly <uuid>/<index>, but was '${chunkKey(storageKey, 3)}'")
    }
}
