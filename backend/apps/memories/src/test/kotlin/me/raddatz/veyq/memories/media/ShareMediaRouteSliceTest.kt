package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Every `/api/shares/…` route of both owners reaches **its own** handler. Core's `sharing` tag is one generated
 * interface rooted at `/api/shares`; Memories' `share-media` tag is another, rooted at `/api/shares/{shareId}/media`
 * (`memories/share-media.openapi.yaml`). RESTEasy resolves a request to the resource class with the most specific
 * root first and never falls back, so a root that widens (backend/CLAUDE.md, the `item-favorites` case) or a route
 * left behind answers 404/405 on exactly the routes no other suite looks at. A green suite for one tag proves
 * nothing about the other's routes, so every route is called here.
 *
 * Each row's anchor is an answer only its handler gives: core's sharing handlers name an unknown link
 * `share_not_found` (or, for the curator's create and list, the node and the caller's own links; for a guest's tree
 * operations on the real link, the `illegal_scope` of a create inside a Memory, the `node_not_found` of a placement
 * of an unknown node and the `illegal_structure` of an abandon of the root); Memories' list
 * answers a real link with its two lists, its complete answers a real link's bogus upload per item (`upload_not_found` in a 200), and its delete names an unknown
 * contribution `share_media_not_found`. A route that misses its class answers RESTEasy's bare 404/405, none of these.
 * The table is held to the generated interfaces: its operations are exactly every `/api/shares` operation of both
 * generated API packages, so a route added to either spec without a row here is red.
 */
@QuarkusTest
class ShareMediaRouteSliceTest {

    private val sealedName = "c2VhbGVkLW5hbWU="
    private val sealedUploaderName = "c2VhbGVkLXVwbG9hZGVyLW5hbWU="

    /** What one row calls and what only its handler answers. */
    private class Probe(val method: String, val path: String, val call: (World) -> ValidatableResponse)

    /** A member, a Memory, a READ_WRITE link on it with one named guest, and an id no link has. */
    private class World(val userId: String, val memory: String, val shareId: String, val token: String, val unknown: String)

    private val probes: Map<String, Probe> = linkedMapOf(
        // --- core `sharing` (root /api/shares) ---
        "createShare" to Probe("POST", "/api/shares") { w ->
            val id = TestIds.shareId()
            val node = TestIds.uuid()
            authed(w).body("""{"shareId":"$id",${ShareWriteProof.field(id)},"nodeId":"$node","role":"READ",${shareKeysJson("READ", node)},"sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
                .`when`().post("/api/shares").then().statusCode(404).body("code", equalTo("node_not_found"))
        },
        "listShares" to Probe("GET", "/api/shares") { w ->
            authed(w).`when`().get("/api/shares").then().statusCode(200).body("shareId", hasItem(w.shareId))
        },
        "revokeShare" to Probe("POST", "/api/shares/{shareId}/revoke") { w ->
            authed(w).`when`().post("/api/shares/${w.unknown}/revoke").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "reactivateShare" to Probe("POST", "/api/shares/{shareId}/reactivate") { w ->
            authed(w).`when`().post("/api/shares/${w.unknown}/reactivate").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "reissueShare" to Probe("POST", "/api/shares/{shareId}/reissue") { w ->
            val id = TestIds.shareId()
            authed(w).body("""{"shareId":"$id",${ShareWriteProof.field(id)},"wrap":"d3JhcA==","sealedName":"$sealedName","sealedShareKey":"cmVjb3ZlcmFibGUta2V5","sealedShareKeyEpoch":1}""")
                .`when`().post("/api/shares/${w.unknown}/reissue").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "resolveShare" to Probe("GET", "/api/shares/{shareId}") { w ->
            given().`when`().get("/api/shares/${w.unknown}").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "updateShare" to Probe("PATCH", "/api/shares/{shareId}") { w ->
            authed(w).body("{}").`when`().patch("/api/shares/${w.unknown}").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "setShareSealedName" to Probe("PUT", "/api/shares/{shareId}/sealed-name") { w ->
            authed(w).body("""{"sealedName":"$sealedName"}""").`when`().put("/api/shares/${w.unknown}/sealed-name")
                .then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "deleteShare" to Probe("DELETE", "/api/shares/{shareId}") { w ->
            authed(w).`when`().delete("/api/shares/${w.unknown}").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "getShareTerms" to Probe("GET", "/api/shares/{shareId}/terms") { w ->
            given().`when`().get("/api/shares/${w.unknown}/terms").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "resolveShareBlob" to Probe("GET", "/api/shares/{shareId}/blobs/{blobId}") { w ->
            given().`when`().get("/api/shares/${w.unknown}/blobs/${TestIds.uuid()}").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "createShareIdentity" to Probe("POST", "/api/shares/{shareId}/identities") { w ->
            given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(w.unknown))
                .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedUploaderName"}""")
                .`when`().post("/api/shares/${w.unknown}/identities").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "startShareBlobUploads" to Probe("POST", "/api/shares/{shareId}/blob-uploads") { w ->
            given().contentType(ContentType.JSON).body("""{"contributorToken":"${w.token}","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
                .`when`().post("/api/shares/${w.unknown}/blob-uploads").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "presignShareBlobUploadWindows" to Probe("POST", "/api/shares/{shareId}/blob-upload-windows") { w ->
            given().contentType(ContentType.JSON).body("""{"contributorToken":"${w.token}","items":[{"uploadId":"${TestIds.uuid()}","fromChunk":0}]}""")
                .`when`().post("/api/shares/${w.unknown}/blob-upload-windows").then().statusCode(404).body("code", equalTo("share_not_found"))
        },
        "createShareNode" to Probe("POST", "/api/shares/{shareId}/nodes") { w ->
            // A real READ_WRITE link on a Memory: Memories declares no guest tree operation inside its Memory
            // (GuestNodeRules), so only this handler answers `illegal_scope` here.
            val id = TestIds.uuid()
            given().contentType(ContentType.JSON).header("X-Contributor-Token", w.token)
                .body("""{"nodeId":"$id","parentId":"${w.memory}","kind":"FOLDER","encryptedName":"bmFtZQ==","slugToken":"c2x1Zw==","epoch":0,"wrappedContentKey":"d3JhcA=="}""")
                .`when`().post("/api/shares/${w.shareId}/nodes").then().statusCode(422).body("code", equalTo("illegal_scope"))
        },
        "updateShareNode" to Probe("PATCH", "/api/shares/{shareId}/nodes/{nodeId}") { w ->
            given().contentType(ContentType.JSON).header("X-Contributor-Token", w.token)
                .body("""{"parentId":"${w.memory}","encryptedName":"bmFtZQ==","slugToken":"c2x1Zw==","wrappedContentKey":"d3JhcA==","placementRevision":0}""")
                .`when`().patch("/api/shares/${w.shareId}/nodes/${TestIds.uuid()}").then().statusCode(404).body("code", equalTo("node_not_found"))
        },
        "abandonShareNode" to Probe("DELETE", "/api/shares/{shareId}/nodes/{nodeId}") { w ->
            // The link's root (the Memory) is never a guest's to erase: only this handler answers `illegal_structure`.
            given().header("X-Contributor-Token", w.token).`when`().delete("/api/shares/${w.shareId}/nodes/${w.memory}")
                .then().statusCode(422).body("code", equalTo("illegal_structure"))
        },
        "reportInShare" to Probe("POST", "/api/shares/{shareId}/reports") { w ->
            // A real link holding its write proof, a report sealed to an epoch that is not the current one: only the
            // report handler answers `moderation_epoch_changed`.
            given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(w.shareId))
                .body("""{"category":"TERMS_BREACH","epoch":999999,"sealedReport":"cmVwb3J0","items":[{"kind":"NODE","id":"${w.memory}","sealedShare":"c2hhcmU="}]}""")
                .`when`().post("/api/shares/${w.shareId}/reports").then().statusCode(409).body("code", equalTo("moderation_epoch_changed"))
        },
        // --- Memories `share-media` (root /api/shares/{shareId}/media) ---
        "listShareMedia" to Probe("GET", "/api/shares/{shareId}/media") { w ->
            given().`when`().get("/api/shares/${w.shareId}/media").then().statusCode(200)
                .body("media.size()", equalTo(0)).body("identities.size()", equalTo(0))
        },
        "completeShareMedia" to Probe("POST", "/api/shares/{shareId}/media") { w ->
            given().contentType(ContentType.JSON)
                .body("""{"contributorToken":"${w.token}","items":[{"mediaId":"${TestIds.uuid()}","originalUploadId":"${TestIds.uuid()}"}]}""")
                .`when`().post("/api/shares/${w.shareId}/media").then().statusCode(200).body("results[0].error", equalTo("upload_not_found"))
        },
        "deleteShareMedia" to Probe("DELETE", "/api/shares/{shareId}/media/{mediaId}") { w ->
            given().header("X-Contributor-Token", w.token).`when`().delete("/api/shares/${w.shareId}/media/${TestIds.uuid()}")
                .then().statusCode(404).body("code", equalTo("share_media_not_found"))
        },
    )

    @Test
    fun theTableIsEveryShareRouteOfBothGeneratedApis() {
        val generated = shareOperations()
        assertEquals(21, generated.size, "the /api/shares operations of both specs: $generated")
        assertEquals(generated, probes.mapValues { (_, p) -> "${p.method} ${p.path}" }, "a route without a row, or a row without a route")
    }

    @Test
    fun everyShareRouteReachesItsOwnHandler() {
        val w = world()
        for ((operationId, probe) in probes) {
            runCatching { probe.call(w) }.onFailure { throw AssertionError("$operationId (${probe.method} ${probe.path}) did not reach its handler", it) }
        }
    }

    @Test
    fun theAnchorsTellTheTwoOwnersApart() {
        // Control: one unknown link, two DELETE routes that share the /api/shares/{shareId} prefix — core's curator
        // delete names the link, Memories' delete-own names the contribution. Each anchor is its own handler's.
        val w = world()
        authed(w).`when`().delete("/api/shares/${w.unknown}").then().statusCode(404).body("code", equalTo("share_not_found"))
        given().header("X-Contributor-Token", w.token).`when`().delete("/api/shares/${w.unknown}/media/${TestIds.uuid()}")
            .then().statusCode(404).body("code", equalTo("share_media_not_found"))
        // A route neither owner serves answers no handler's code: the bare miss every row above would show.
        val miss = given().`when`().get("/api/shares/${w.shareId}/media/${TestIds.uuid()}/nothing").then().statusCode(404).extract().asString()
        assertFalse(miss.contains("share_"), "a route no owner serves answered a handler's code: $miss")
    }

    // --- helpers ---

    private fun authed(w: World) = given().header("Authorization", bearer(w.userId)).contentType(ContentType.JSON)

    private fun world(): World {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = TestIds.shareId()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$memory","role":"READ_WRITE",${shareKeysJson("READ_WRITE", memory)},"sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then().statusCode(201)
        val token: String = given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId))
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedUploaderName"}""")
            .`when`().post("/api/shares/$shareId/identities").then().statusCode(201).extract().path("contributorToken")
        return World(userId, memory, shareId, token, TestIds.shareId())
    }

    /** operationId → "VERB path" of every generated operation under `/api/shares`, from both owners' API packages. */
    private fun shareOperations(): Map<String, String> =
        API_PACKAGES.flatMap(::apiClassNames).flatMap { name ->
            val type = Class.forName(name, false, javaClass.classLoader)
            val base = type.getAnnotation(Path::class.java)?.value.orEmpty()
            type.declaredMethods.mapNotNull { m ->
                val verb = VERBS.entries.firstOrNull { m.isAnnotationPresent(it.value) }?.key ?: return@mapNotNull null
                val path = (base + (m.getAnnotation(Path::class.java)?.value ?: "")).replace("//", "/")
                if (path == "/api/shares" || path.startsWith("/api/shares/")) m.name to "$verb $path" else null
            }
        }.toMap()

    private fun apiClassNames(apiPackage: String): List<String> {
        val dir = apiPackage.replace('.', '/')
        val names = System.getProperty("java.class.path").split(File.pathSeparator).map(::File).flatMap { entry ->
            when {
                entry.isDirectory -> File(entry, dir).listFiles().orEmpty().map { it.name }
                entry.isFile && entry.name.endsWith(".jar") -> JarFile(entry).use { jar ->
                    jar.entries().asSequence().map { it.name }
                        .filter { it.startsWith("$dir/") && it.count { c -> c == '/' } == dir.count { c -> c == '/' } + 1 }
                        .map { it.substringAfterLast('/') }.toList()
                }
                else -> emptyList()
            }
        }.filter { it.endsWith(".class") && '$' !in it }.map { "$apiPackage.${it.removeSuffix(".class")}" }.distinct()
        check(names.isNotEmpty()) { "no generated API interface in $apiPackage on the classpath" }
        return names
    }

    private companion object {
        val API_PACKAGES = listOf("me.raddatz.veyq.core.generated.api", "me.raddatz.veyq.memories.generated.api")
        val VERBS = linkedMapOf(
            "GET" to GET::class.java, "POST" to POST::class.java, "PUT" to PUT::class.java,
            "PATCH" to PATCH::class.java, "DELETE" to DELETE::class.java,
        )
    }
}
