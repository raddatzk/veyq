package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.memories.media.MediaStore
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItems
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Slice tests for the Memory tree (en.memory.tree, cr-0027): create folder/Memory/chapter + list; slug
 * uniqueness per parent (409); structural rules (422 — a chapter only lives in a Memory); scope-access
 * (non-member 404); rename + delete-subtree; operator-blind (the server stores only the ciphertext the
 * client sent). UUID-unique seeds so the JVM-lived H2 mem DB can't bleed across tests/classes.
 *
 * **Split** (cr-platform-gradle-modules step 8a): the methods that run with core alone are `NodesCoreSliceTest` in `:core`.
 */
@QuarkusTest
class NodesSliceTest {

    @Inject
    lateinit var nodes: NodesStore

    @Inject
    lateinit var media: MediaStore

    @Test
    fun aRenameOverlappingAMediaAddKeepsTheMediaRevision() {
        // The rename reads the Memory before a media add's revision bump commits and flushes after it. A full-row
        // UPDATE wrote the older `mediaRevision` back: the live-updates signal stepped backwards, and a client whose
        // last seen revision was the restored one missed the add (uc.memory.live-updates, inbox #224). Since inbox #341
        // the counter is no column of the node row at all (Memories' veyq_media_revision), so a node write cannot reach
        // it; this keeps the observable pinned across that move.
        repeat(RACE_ROUNDS) { round ->
            val (_, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val renamed = "cmVuYW1lZA==" // "renamed"
            whileUncommitted(
                change = { media.bumpMediaRevision(memory) },
                overlapping = { nodes.applyUpdate(scopeId, memory, rootRename(scopeId, renamed), null, null, null) },
            )
            QuarkusTransaction.requiringNew().run {
                val node = NodeEntity.findById(memory)!!
                assertEquals(1, media.revisions(memory).first, "round $round: the rename rolled the media revision back")
                // Control: the rename itself landed.
                assertEquals(renamed, node.encryptedName, "round $round")
            }
        }
    }

    @Test
    fun createTreeThenList() {
        val (userId, scopeId) = seedMemberScope()
        val folder = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"Zm9sZGVy","slugToken":"Zm9sZGVyLXNsdWc=","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
        val memory = createNode(userId, scopeId, """{"parentId":"$folder","nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bWVtb3J5","slugToken":"bWVtLXNsdWc=","orderIndex":0,"wrappedContentKey":"a21lbS13cmFw"}""")
        // a chapter carries its OWN K_mem, wrapped under the parent Memory's K_mem (recursive K_mem, cr-0060 / ADR 0012)
        val chapter = createNode(userId, scopeId, """{"parentId":"$memory","nodeId":"${UUID.randomUUID()}","kind":"CHAPTER","encryptedName":"Y2hhcHRlcg==","slugToken":"Y2hhcC1zbHVn","orderIndex":0,"wrappedContentKey":"a2NoYXAtd3JhcA=="}""")

        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("kind", hasItems("FOLDER", "MEMORY", "CHAPTER"))
            // operator-blind: both the Memory's and the chapter's wrapped K_mem come back verbatim; folder at root.
            .body("find { it.id == '$memory' }.wrappedContentKey", equalTo("a21lbS13cmFw"))
            .body("find { it.id == '$chapter' }.wrappedContentKey", equalTo("a2NoYXAtd3JhcA=="))
            .body("find { it.id == '$folder' }.parentId", equalTo(null))
    }

    /** ADR 0080 Decision 1, an application's kind: a Memory without its wrap is a 400 like a folder's
     *  (`NodesCoreSliceTest`); the control with a wrap is a 201. */
    @Test
    fun aMemoryWithoutAWrapIs400() {
        val (userId, scopeId) = seedMemberScope()
        val bare = "\"kind\":\"MEMORY\",\"encryptedName\":\"bQ==\",\"slugToken\":\"bm8td3JhcC1t\",\"orderIndex\":0"
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("{\"nodeId\":\"${UUID.randomUUID()}\",$bare}")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(400)
        createNode(userId, scopeId, "{\"nodeId\":\"${UUID.randomUUID()}\",$bare,\"wrappedContentKey\":\"a21lbQ==\"}")
    }

    @Test
    fun chapterAtRootIs422() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"CHAPTER","encryptedName":"Yw==","slugToken":"Y2hhcA==","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(422).body("code", equalTo("illegal_structure"))
    }

    @Test
    fun renameThenDeleteSubtree() {
        val (userId, scopeId) = seedMemberScope()
        val folder = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"b2xk","slugToken":"b2xkLXM=","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
        val memory = createNode(userId, scopeId, """{"parentId":"$folder","nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"bS1z","orderIndex":0,"wrappedContentKey":"aw=="}""")

        // rename the folder (new ciphertext name + recomputed slug + re-wrapped key, a placement write at the root)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"bmV3","slugToken":"bmV3LXM=",$REWRAP}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$folder")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("find { it.id == '$folder' }.encryptedName", equalTo("bmV3"))

        // delete the folder → its subtree (the memory) goes too
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$folder")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("id", org.hamcrest.CoreMatchers.not(hasItems(folder, memory)))
    }

    @Test
    fun crossScopeDeleteIsRejected() {
        // victim scope B holds a Memory
        val (userB, scopeB) = seedMemberScope()
        val victimNode = createNode(userB, scopeB, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"dmljdGlt","slugToken":"dmljLXM=","orderIndex":0,"wrappedContentKey":"aw=="}""")
        // attacker is a member of their OWN scope A, not of B (every account is a member of its personal scope)
        val (userA, scopeA) = seedMemberScope()

        // addressing the foreign node through the attacker's own scope must NOT delete it: a NoScope-shaped 404,
        // indistinguishable from an absent scope (non-enumerable) — never a 204 that hard-deletes another scope's node.
        given().header("Authorization", bearer(userA))
            .`when`().delete("/api/scopes/$scopeA/nodes/$victimNode")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))

        // the foreign node survives under B
        given().header("Authorization", bearer(userB)).`when`().get("/api/scopes/$scopeB/nodes")
            .then().statusCode(200).body("id", hasItems(victimNode))
    }

    @Test
    fun wrappedMetaRoundTripsAndPartialUpdateLeavesItUnchanged() {
        val (userId, scopeId) = seedMemberScope()
        // create a Memory carrying an opaque sealed wrappedMeta blob (cr-0067) — the server never inspects it
        val memory = createNode(
            userId, scopeId,
            """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bWVt","slugToken":"bWV0YS1z","orderIndex":0,"wrappedContentKey":"aw==","wrappedMeta":"bWV0YS12MQ=="}""",
        )
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$memory' }.wrappedMeta", equalTo("bWV0YS12MQ=="))

        // a rename that OMITS wrappedMeta must leave the sealed blob unchanged (partial semantics)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"bmV3","slugToken":"bmV3LXNsdWc=",$REWRAP}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$memory' }.wrappedMeta", equalTo("bWV0YS12MQ=="))

        // a partial update that SETS wrappedMeta replaces it
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"bWV0YS12Mg==","metaVersion":0}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$memory' }.wrappedMeta", equalTo("bWV0YS12Mg=="))
    }

    /**
     * B1 (ADR 0077, uc.memory.concurrent-curation): a meta write states the version it was built from, and a stale
     * one is refused with the current blob. Two members write from the same read: the first lands at 1, the second
     * — still stating 0 — must not replace the first one's blob. Before the compare-and-set it answered 204 and
     * replaced it. The control at the end is what keeps a server that refuses everything from passing.
     */
    @Test
    fun staleMetaWriteIs409WithTheCurrentBlobAndWritesNothing() {
        val (userId, scopeId) = seedMemberScope()
        val memory = createMemory(userId, scopeId, meta = "djA=")
        metaVersionOf(userId, scopeId, memory, expect = 0)

        patchNode(userId, scopeId, memory, """{"wrappedMeta":"YS12MQ==","metaVersion":0}""").then().statusCode(204)
        metaVersionOf(userId, scopeId, memory, expect = 1)

        patchNode(userId, scopeId, memory, """{"wrappedMeta":"Yi12MQ==","metaVersion":0}""")
            .then().statusCode(409)
            .body("code", equalTo("stale_version"))
            .body("wrappedMeta", equalTo("YS12MQ=="))
            .body("metaVersion", equalTo(1))
        listed(userId, scopeId, memory).body("find { it.id == '$memory' }.wrappedMeta", equalTo("YS12MQ=="))
        metaVersionOf(userId, scopeId, memory, expect = 1)

        // control: the loser re-applies on what the 409 handed it, states 1, and lands at 2
        patchNode(userId, scopeId, memory, """{"wrappedMeta":"Yi12Mg==","metaVersion":1}""").then().statusCode(204)
        listed(userId, scopeId, memory).body("find { it.id == '$memory' }.wrappedMeta", equalTo("Yi12Mg=="))
        metaVersionOf(userId, scopeId, memory, expect = 2)
    }

    /**
     * B2: `wrappedMeta` without its version is malformed — an old client cannot slip past the check by leaving it
     * out. A rename alone needs no version, and does not move it: only a meta write does.
     */
    @Test
    fun metaWithoutVersionIs400AndARenameLeavesTheVersionUnmoved() {
        val (userId, scopeId) = seedMemberScope()
        val memory = createMemory(userId, scopeId, meta = "djA=")

        patchNode(userId, scopeId, memory, """{"wrappedMeta":"YS12MQ=="}""")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        listed(userId, scopeId, memory).body("find { it.id == '$memory' }.wrappedMeta", equalTo("djA="))

        patchNode(userId, scopeId, memory, """{"encryptedName":"bmV3","slugToken":"cmVuYW1lZA==",$REWRAP}""").then().statusCode(204)
        metaVersionOf(userId, scopeId, memory, expect = 0)
        // control: the same blob with its version is accepted
        patchNode(userId, scopeId, memory, """{"wrappedMeta":"YS12MQ==","metaVersion":0}""").then().statusCode(204)
        metaVersionOf(userId, scopeId, memory, expect = 1)
    }

    /**
     * B4: a stale meta write that also renames is refused WHOLE — the rename does not land on its own, or the
     * caller's retry (which re-sends both) would be the only thing standing between it and a half-applied request.
     */
    @Test
    fun staleMetaWriteThatAlsoRenamesLeavesTheNameUnchanged() {
        val (userId, scopeId) = seedMemberScope()
        val memory = createMemory(userId, scopeId, meta = "djA=")
        patchNode(userId, scopeId, memory, """{"wrappedMeta":"YS12MQ==","metaVersion":0}""").then().statusCode(204)

        patchNode(userId, scopeId, memory, """{"encryptedName":"c3RhbGU=","slugToken":"c3RhbGU=",$REWRAP,"wrappedMeta":"Yi12MQ==","metaVersion":0}""")
            .then().statusCode(409).body("code", equalTo("stale_version"))
        listed(userId, scopeId, memory)
            .body("find { it.id == '$memory' }.encryptedName", equalTo("bWVt"))
            .body("find { it.id == '$memory' }.slugToken", equalTo("bWV0YS1z"))
            .body("find { it.id == '$memory' }.placementVersion", equalTo(0))
        // control: the same rename at the current version lands, name and meta together
        patchNode(userId, scopeId, memory, """{"encryptedName":"c3RhbGU=","slugToken":"c3RhbGU=",$REWRAP,"wrappedMeta":"Yi12MQ==","metaVersion":1}""")
            .then().statusCode(204)
        listed(userId, scopeId, memory)
            .body("find { it.id == '$memory' }.encryptedName", equalTo("c3RhbGU="))
            .body("find { it.id == '$memory' }.wrappedMeta", equalTo("Yi12MQ=="))
    }

    // --- helpers ---

    private companion object {
        /** The rest of a placement write at the scope root, built on a new node's version (ADR 0080 Decision 3). */
        const val REWRAP = """"wrappedContentKey":"cmUtd3JhcA==","epoch":0,"placementVersion":0"""
    }

    /** A rename of a scope-root node in place, built on placement version 0 — the store's own input. */
    private fun rootRename(scopeId: String, name: String) = NodesStore.Placement(
        parentSlot = scopeId, encryptedName = name, slugToken = "cmVuYW1lLXNsdWc=", wrappedContentKey = "cmUtd3JhcA==",
        epoch = null, expectedVersion = 0,
    )

    private fun createMemory(userId: String, scopeId: String, meta: String): String = createNode(
        userId, scopeId,
        """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bWVt","slugToken":"bWV0YS1z","orderIndex":0,"wrappedContentKey":"aw==","wrappedMeta":"$meta"}""",
    )

    private fun patchNode(userId: String, scopeId: String, nodeId: String, body: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().patch("/api/scopes/$scopeId/nodes/$nodeId")

    private fun listed(userId: String, scopeId: String, nodeId: String) =
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("find { it.id == '$nodeId' }", notNullValue())

    private fun metaVersionOf(userId: String, scopeId: String, nodeId: String, expect: Int) {
        listed(userId, scopeId, nodeId).body("find { it.id == '$nodeId' }.metaVersion", equalTo(expect))
    }

    private fun createNode(userId: String, scopeId: String, body: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(201).body("nodeId", notNullValue())
            .extract().path("nodeId")
}
