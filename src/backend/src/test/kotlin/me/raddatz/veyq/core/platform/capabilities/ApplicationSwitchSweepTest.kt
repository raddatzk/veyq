package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.http.GeneratedApiOperations
import me.raddatz.veyq.core.membership.ScopeAccessWorld
import me.raddatz.veyq.core.membership.ScopeAccessWorld.Companion.B64
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.memories.testing.bucketId
import me.raddatz.veyq.memories.testing.mediaId
import me.raddatz.veyq.memories.testing.nodeId
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.function.Executable
import java.util.UUID

/**
 * **The completeness proof of an application switch** (uc.platform.switch-application-off): every write
 * operation of every application — derived from its generated API package ([GeneratedApiOperations]), never listed —
 * either answers `422 capability_disabled` while its application is off for the tenant, or is on the reasoned
 * [exempt] list. A new operation nobody classified is red here on the day its spec lands.
 *
 * Each operation is fired twice on its own [ScopeAccessWorld] (Memories' Memory, medium, bucket; a `READ_WRITE` and a
 * `WRITE_ONLY` link on it): with its application off for the live space's tenant, then — the control on the same
 * predicate — with it on. A gated one is refused off and not refused on (a probe that never reached the switch would be
 * refused, or not, alike both times); an exempt one is not refused off and never answers `400`, so its probe reached
 * the handler. Both anchor on `(status, code)`, never on "not 2xx". Core's writes of an application's nodes (create,
 * update, cover, a link, an upload's start) ask by kind and are each application's slice tests' (`MemoriesSwitchedOffSliceTest`,
 * `DepotSwitchedOffSliceTest`).
 */
@QuarkusTest
class ApplicationSwitchSweepTest {

    private data class Call(val method: String, val path: String, val body: String? = null, val guest: Boolean = false, val token: String? = null)

    /** What a call is fired into: the live space, and a guest's token on each write role of a link on its Memory. */
    private data class Ctx(val s: ScopeAccessWorld.Space, val readWrite: GuestTreeShare.Link, val writeOnly: GuestTreeShare.Link)

    private val slug get() = "c2x1Zy" + UUID.randomUUID().toString().replace("-", "").take(20)

    /** Every write operation of every application, and the request that reaches it. */
    private val calls: Map<String, (Ctx) -> Call> = mapOf(
        // --- Memories ---
        "completeMedia" to { c ->
            Call("POST", "/api/scopes/${c.s.scopeId}/media-batch", """{"items":[{"nodeId":"${c.s.nodeId}","mediaId":"${TestBlob.ownerOf(c.s.uploadId)}","originalUploadId":"${c.s.uploadId}","derivatives":[],"nodeEpoch":0}]}""")
        },
        "createBucket" to { c ->
            Call("POST", "/api/scopes/${c.s.scopeId}/nodes/${c.s.nodeId}/buckets", """{"bucketId":"${UUID.randomUUID()}","sealedName":"$B64","nodeEpoch":0}""")
        },
        "renameBucket" to { c ->
            Call("PATCH", "/api/scopes/${c.s.scopeId}/nodes/${c.s.nodeId}/buckets/${c.s.bucketId}", """{"sealedName":"$B64","nodeEpoch":0}""")
        },
        "deleteBucket" to { c -> Call("DELETE", "/api/scopes/${c.s.scopeId}/nodes/${c.s.nodeId}/buckets/${c.s.bucketId}") },
        "moveBucketMedia" to { c ->
            Call("POST", "/api/scopes/${c.s.scopeId}/nodes/${c.s.nodeId}/media-moves", """{"mediaIds":["${c.s.mediaId}"],"targetBucketId":"${c.s.bucketId}"}""")
        },
        "updateMediaMeta" to { c ->
            Call("PUT", "/api/scopes/${c.s.scopeId}/media/${c.s.mediaId}/meta", """{"wrappedMeta":"$B64","metaRevision":0,"nodeEpoch":0}""")
        },
        "deleteMedia" to { c -> Call("DELETE", "/api/scopes/${c.s.scopeId}/media/${c.s.mediaId}") },
        "restoreMedia" to { c -> Call("POST", "/api/scopes/${c.s.scopeId}/media/${c.s.mediaId}/restore") },
        "purgeMedia" to { c -> Call("POST", "/api/scopes/${c.s.scopeId}/media/${c.s.mediaId}/purge") },
        "stageScopeItemFavoritesRekey" to { c ->
            Call("POST", "/api/scopes/${c.s.scopeId}/item-favorites-rekey/${UUID.randomUUID()}", """{"fromEpoch":0}""")
        },
        "stageMediaRekey" to { c ->
            Call("POST", "/api/scopes/${c.s.scopeId}/nodes/${c.s.nodeId}/media-rekey/${UUID.randomUUID()}", """{"fromEpoch":0}""")
        },
        "putItemFavorites" to { c ->
            Call("PUT", "/api/scopes/${c.s.scopeId}/nodes/${c.s.nodeId}/item-favorites", """{"ciphertext":"$B64","epoch":0}""")
        },
        "setMemoryBanner" to { c ->
            Call(
                "PUT", "/api/scopes/${c.s.scopeId}/nodes/${c.s.nodeId}/banner",
                """{"uploadId":"${c.s.uploadId}","expectedBlobId":null,"expectedPosterBlobId":null,"wrappedMeta":"$B64","metaRevision":0,"nodeEpoch":0}""",
            )
        },
        "removeMemoryBanner" to { c ->
            Call("POST", "/api/scopes/${c.s.scopeId}/nodes/${c.s.nodeId}/banner/removal", """{"blobId":"${UUID.randomUUID()}","posterBlobId":null,"wrappedMeta":"$B64","metaRevision":0,"nodeEpoch":0}""")
        },
        "completeShareMedia" to { c ->
            Call(
                "POST", "/api/shares/${c.readWrite.shareId}/media",
                """{"nodeEpoch":0,"contributorToken":"${c.readWrite.token}","items":[{"mediaId":"${UUID.randomUUID()}","originalUploadId":"${UUID.randomUUID()}"}]}""",
                guest = true,
            )
        },
        "deleteShareMedia" to { c ->
            Call("DELETE", "/api/shares/${c.readWrite.shareId}/media/${c.s.mediaId}", guest = true, token = c.readWrite.token)
        },
        // --- Depot (the world's node is a Memory: on, each answers its own refusal of the kind — past the switch) ---
        "completeFile" to { c ->
            Call("POST", "/api/scopes/${c.s.scopeId}/files/${c.s.nodeId}/content", """{"uploadId":"${c.s.uploadId}","nodeEpoch":0}""")
        },
        "completeShareFile" to { c ->
            Call(
                "POST", "/api/shares/${c.readWrite.shareId}/files/${c.s.nodeId}/content",
                """{"contributorToken":"${c.readWrite.token}","uploadId":"${UUID.randomUUID()}","nodeEpoch":0}""", guest = true,
            )
        },
        // --- Expenses (the world's node is a Memory: on, each answers `404 node_not_found` — no Pot, past the switch) ---
        "appendPotRevisions" to { c ->
            Call(
                "POST", "/api/scopes/${c.s.scopeId}/pots/${c.s.nodeId}/revisions",
                """{"nodeEpoch":0,"revisions":[{"entryId":"${UUID.randomUUID()}","revision":1,"tombstone":false,"sealedBody":"$POT_BODY","wrappedRevisionKey":"$B64"}]}""",
            )
        },
        "erasePotRecord" to { c ->
            Call(
                "POST", "/api/scopes/${c.s.scopeId}/pots/${c.s.nodeId}/erasures",
                """{"nodeEpoch":0,"entryId":"${UUID.randomUUID()}","revision":2,"sealedBody":"$POT_BODY","wrappedRevisionKey":"$B64"}""",
            )
        },
        "stagePotRekey" to { c ->
            Call(
                "POST", "/api/scopes/${c.s.scopeId}/pots/${c.s.nodeId}/rekey/${UUID.randomUUID()}",
                """{"fromEpoch":0,"revisions":[{"entryId":"${UUID.randomUUID()}","revision":1,"wrappedRevisionKey":"$B64"}]}""",
            )
        },
        "appendSharePotRevisions" to { c ->
            Call(
                "POST", "/api/shares/${c.readWrite.shareId}/pots/${c.s.nodeId}/revisions",
                """{"nodeEpoch":0,"contributorToken":"${c.readWrite.token}","revisions":[{"entryId":"${UUID.randomUUID()}","revision":1,"tombstone":false,"sealedBody":"$POT_BODY","wrappedRevisionKey":"$B64"}]}""",
                guest = true,
            )
        },
        "ensureShareDropFolder" to { c ->
            Call(
                "POST", "/api/shares/${c.writeOnly.shareId}/files/drop-folder",
                """{"contributorToken":"${c.writeOnly.token}","nodeId":"${UUID.randomUUID()}","encryptedName":"$B64","slugToken":"$slug","scopeEpoch":0,"wrappedContentKey":"$B64"}""",
                guest = true,
            )
        },
    )

    /** The writes an application switch never refuses, each with its reason (uc.platform.switch-application-off). */
    private val exempt: Map<String, String> = buildMap {
        fun reason(why: String, vararg ops: String) = ops.forEach { put(it, why) }
        reason(
            "a delete to the trash and its restore: what exists stays removable while the application is off, and a " +
                "restored item is read-only like the rest (owner 2026-10-23)",
            "deleteMedia", "restoreMedia", "deleteBucket",
        )
        reason("a guest takes back its own medium: a removal, which an application off never refuses", "deleteShareMedia")
        reason("the purge out of the trash: an erasure (E2)", "purgeMedia")
        reason(
            "a participant's erasure: a removal — its name goes when a member says so, whether or not Expenses takes " +
                "writes (E2, CR cr-expenses-ledger)",
            "erasePotRecord",
        )
        reason(
            "a member's own marks on its items: the member's blob, which a Master-Key rotation re-tags through this same " +
                "write and the server cannot tell from a new mark (E5, ADR 0068)",
            "putItemFavorites",
        )
        reason("a rotation's re-key: the security of what exists (E1, ADR 0109)", "stageMediaRekey", "stagePotRekey", "stageScopeItemFavoritesRekey")
    }

    private fun applicationOperations(): Map<String, List<GeneratedApiOperations.Operation>> =
        GeneratedApiOperations.apiPackages().filter { it != CORE_API_PACKAGE }
            .associate { pkg -> pkg.removePrefix("me.raddatz.veyq.").substringBefore('.') to GeneratedApiOperations.of(listOf(pkg)).filter { it.verb != "GET" } }

    @Test
    fun everyWriteOfEveryApplicationIsGatedOrExemptWithAReason() {
        val byApplication = applicationOperations()
        val ids = byApplication.values.flatten().map { it.id }.toSet()
        assertAll(
            Executable { assertTrue(byApplication.keys.containsAll(listOf(CapabilityIds.MEMORIES, CapabilityIds.DEPOT, CapabilityIds.EXPENSES)), "the applications were derived: ${byApplication.keys}") },
            Executable { assertTrue(byApplication.keys.all { it in CapabilityIds.ADVERTISED }, "every application has its own register entry: ${byApplication.keys}") },
            Executable {
                assertTrue((ids - calls.keys).isEmpty(), "unclassified writes — have the application's handler ask ApplicationSwitch.requireWritable and probe it here, or exempt it with its reason: ${(ids - calls.keys).sorted()}")
            },
            Executable { assertTrue((calls.keys - ids).isEmpty(), "classified writes the contract no longer has: ${(calls.keys - ids).sorted()}") },
            Executable { assertTrue((exempt.keys - calls.keys).isEmpty(), "an exemption needs its probe too: ${(exempt.keys - calls.keys).sorted()}") },
        )
        // A probe must fire the route its label names.
        val routes = byApplication.values.flatten().associateBy { it.id }
        val dummy = ScopeAccessWorld.Space(SOME_ID, "d.example.com", SOME_ID, SOME_ID, SOME_ID, SOME_ID)
        val link = GuestTreeShare.Link("s", "t")
        calls.forEach { (id, call) ->
            val c = call(Ctx(dummy.withAllContent(), link, link))
            val route = routes[id] ?: return@forEach
            assertEquals(route.verb, c.method, "$id fires ${c.method}, the contract says ${route.verb}")
            assertTrue(route.matches(c.path), "$id fires ${c.path}, which is not its route ${route.path}")
        }
    }

    @TestFactory
    fun eachWriteIsRefusedWhileItsApplicationIsOffAndOnlyThen(): List<DynamicTest> =
        applicationOperations().flatMap { (application, ops) -> ops.map { application to it.id } }.filter { (_, id) -> id in calls }.sortedBy { it.second }
            .map { (application, id) -> DynamicTest.dynamicTest("$application: $id") { probe(application, id) } }

    private fun probe(application: String, id: String) {
        val w = ScopeAccessWorld().build()
        val s = w.live
        val ctx = Ctx(
            s,
            GuestTreeShare.seed(s.ownerId, s.scopeId, s.nodeId, ShareEntity.READ_WRITE),
            GuestTreeShare.seed(s.ownerId, s.scopeId, s.nodeId, ShareEntity.WRITE_ONLY),
        )
        if (id == "restoreMedia" || id == "purgeMedia") {
            given().header("Authorization", bearer(s.ownerId)).`when`().delete("/api/scopes/${s.scopeId}/media/${s.mediaId}").then().statusCode(204)
        }
        val off = TenantCapabilitySwitchFixtures.with(s.tenantId, application) { fire(w, s, calls.getValue(id)(ctx)).answer() }
        val on = fire(w, s, calls.getValue(id)(ctx)).answer()
        if (id in exempt) {
            assertAll(
                id,
                Executable { assertNotEquals(REFUSED, off, "[off] $id is exempt (${exempt[id]}) and was refused by the switch") },
                Executable { assertNotEquals(400, off.first, "[off] $id answered $off: its probe never reached the handler (fix its body)") },
            )
        } else {
            assertAll(
                id,
                Executable { assertEquals(REFUSED, off, "[off] $id writes into $application while it is switched off") },
                Executable { assertTrue(on != REFUSED && on.first != 400, "[on] $id answered $on — the refusal above proves nothing while the control is refused too") },
            )
        }
    }

    private fun fire(w: ScopeAccessWorld, s: ScopeAccessWorld.Space, call: Call): Response {
        val spec = given().header("X-Forwarded-Host", w.unclaimedHost)
        if (!call.guest) spec.header("Authorization", bearer(s.ownerId))
        call.token?.let { spec.header("X-Contributor-Token", it) }
        call.body?.let { spec.contentType(ContentType.JSON).body(it) }
        return when (call.method) {
            "POST" -> spec.`when`().post(call.path)
            "PUT" -> spec.`when`().put(call.path)
            "PATCH" -> spec.`when`().patch(call.path)
            "DELETE" -> spec.`when`().delete(call.path)
            else -> error("unsupported method ${call.method}")
        }
    }

    private fun Response.answer(): Pair<Int, String?> =
        statusCode() to if (statusCode() in 200..299) null else runCatching { jsonPath().getString("code") }.getOrNull()

    /** The dummy space with every content name Memories' world seeds, for the route check (no request is sent). */
    private fun ScopeAccessWorld.Space.withAllContent(): ScopeAccessWorld.Space =
        me.raddatz.veyq.memories.testing.MemoriesWorldContent.NAMES.fold(this) { acc, name -> acc.withContent(name, SOME_ID) }

    private companion object {
        const val SOME_ID = "00000000-0000-4000-8000-000000000001"
        const val CORE_API_PACKAGE = "me.raddatz.veyq.core.generated.api"
        val REFUSED: Pair<Int, String?> = 422 to "capability_disabled"

        /** A sealed Pot revision body of the smallest size class (744 base64 characters). */
        val POT_BODY = "A".repeat(744)
    }
}
