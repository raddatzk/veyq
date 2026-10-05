package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.blobstore.RecordingBlobStore
import me.raddatz.veyq.core.blobstore.StoreReclaimSweep
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Test

/**
 * Slice tests for the node trash (uc.memory.delete-node, ADR 0061): a delete soft-deletes the whole subtree, the
 * space's trash lists the deleted ROOTS, restore brings the subtree back intact, and `NodeSubtreePurge`
 * hard-erases it past the retention window. The share half lives in
 * [me.raddatz.veyq.core.sharing.ShareDeletedNodeSliceTest] — the two guest paths need their own controls.
 *
 * Four of these pin decisions that are load-bearing precisely because they look like details:
 * - the slug is freed by the **write** (`slug_token := the node's own id`), because `ux_node_parent_slug` is a
 *   plain unique index spanning trashed rows and MariaDB has no filtered index;
 * - `slugTaken` therefore must **keep seeing** trashed rows, or validation passes and the database rejects the
 *   insert afterwards — a clean 409 turned into a 500;
 * - the delete's own traversal must **reach already-trashed descendants** and re-stamp them, or the inner rows
 *   age out first and the purge erases a Memory inside a folder still sitting restorable in the trash;
 * - the cover blob is reclaimed by the **purge**, not by the delete, or a restore hands back a Memory whose
 *   cover is already gone.
 *
 * The store half is observed through the [RecordingBlobStore] seam — the in-memory store's `delete` is a no-op
 * that proves nothing — and it takes **two** components since ADR 0062: the purge records the key prefixes in
 * its own transaction, [StoreReclaimSweep] is the only thing that deletes objects, so the purge case drives both.
 * UUID-unique seeds so the JVM-lived H2 mem DB can't bleed across tests/classes.
 *
 * **Split from `NodeTrashSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `NodeTrashSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class NodeTrashCoreSliceTest {

    @Inject
    lateinit var purge: NodeSubtreePurge

    /** A restore of a subtree the purge already ran on is a non-enumerable 404 (uc.memory.delete-node A2). */
    @Test
    fun restoreOfAnUnknownSubtreeIs404() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"slugToken":"Z29uZQ=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes/${UUID.randomUUID()}/restore")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    // --- 1. the subtree leaves the tree and shows up in the space's trash ---

    // --- 2. the slug is freed immediately, by the write ---

    // --- 3. restore into a taken name is a clean rejection, not a 500 ---

    // --- 4. slugTaken must NOT learn the predicate ---

    // --- 5. the delete's traversal must reach already-trashed descendants ---

    // --- 6. the purge reclaims all three arms ---

    // --- helpers ---
}
