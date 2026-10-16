package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.DROP_PRIVATE_KEY
import me.raddatz.veyq.core.testing.TestFixtures.DROP_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Slice tests for the **WRITE_ONLY** write-only drop-box share (uc.sharing.share-uploader, cr-0102, ADR 0031)
 * against the in-memory store. Covers the drop-box's two structural guarantees + the delete-own:
 *  - **write-only:** the anonymous reads of a WRITE_ONLY share serve nothing of the subtree **even with existing
 *    media** (the resolve no node; Memories' media read, `ShareUploaderSliceTest`, no media and no identity) —
 *    "cannot see others" is enforced by the `canView` gate, never gallery filtering;
 *  - **never `K_node` (ADR 0031 point 7):** it serves the Memory's drop public key instead of the wrap, the drop
 *    private key is set on the Memory once, a WRITE_ONLY identity must hand over its wrapped key, and every reader
 *    of the Memory — a viewer link, a member's media page and buckets — is served what opens an upload;
 *  - **contribute:** an uploader names itself + uploads, and the media **attaches to the shared root stamped
 *    with its identity** (same path as READ_WRITE);
 *  - **delete-own:** the token-gated `DELETE /media/{id}` removes + reclaims the caller's own media (a single-row
 *    ownership check) — a **foreign identity's** media is a `404`, a **bad token** is a `403`, and a **READ**
 *    share rejects the delete (`403`).
 *
 * **Split from `ShareUploaderSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `ShareUploaderSliceTest` in `:apps:memories`. The bodies moved verbatim,
 * but for `MemoriesNodeKinds.MEMORY` (a Memories class `:core` cannot import) spelled as its wire literal `"MEMORY"`.
 */
@QuarkusTest
class ShareUploaderCoreSliceTest {

    @Inject
    lateinit var sharing: SharingStore

    @Inject
    lateinit var nodes: NodesStore

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="

    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun aRenameOverlappingTheFirstUploaderLinkKeepsItsDropKey() {
        // The rename reads the Memory before the first WRITE_ONLY link's drop-key write commits and flushes after it.
        // A full-row UPDATE wrote the `null` drop key it had read back: the link answered CREATED, and every upload
        // sealed to it could no longer be opened by the Memory's members (inbox #224).
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, "MEMORY", parentId = scopeId, wrappedContentKey = CONTENT_KEY)
            val renamed = "cmVuYW1lZA==" // "renamed"
            whileUncommitted(
                change = {
                    val shareId = TestIds.shareId()
                    val created = sharing.create(
                        shareId, userId, scopeId, memory, ShareEntity.WRITE_ONLY,
                        ShareKeys.Drop(DROP_PUBLIC_KEY, DROP_PRIVATE_KEY), sealedName, null, null, null, null, null,
                        false, null, null, null, ShareWriteProof.verifier(shareId),
                    )
                    assertEquals(SharingStore.CreateResult.CREATED, created, "round $round")
                },
                overlapping = {
                    nodes.applyUpdate(
                        scopeId, memory,
                        NodesStore.Placement(scopeId, renamed, "cmVuYW1lLXNsdWc=", "cmUtd3JhcA==", epoch = null, expectedRevision = 0),
                        null, null, null,
                    )
                },
            )
            QuarkusTransaction.requiringNew().run {
                val node = NodeEntity.findById(memory)!!
                assertEquals(DROP_PRIVATE_KEY, node.sealedDropPrivateKey, "round $round: the rename wiped the drop key")
                // Control: the rename itself landed.
                assertEquals(renamed, node.encryptedName, "round $round")
            }
        }
    }

    // --- helpers ---
}
