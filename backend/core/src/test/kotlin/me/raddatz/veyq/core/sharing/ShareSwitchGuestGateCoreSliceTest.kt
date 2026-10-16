package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.content.NodeCover
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.sharing.GuestPaths.Path
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.identityKeyJson
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A share switch reaches existing links at once (ADR 0088 decision 5, inbox #442), on core's eight guest operations
 * with core alone: the resolve, the terms, the blob, naming oneself, an upload's start and its window, and the two tree
 * operations. Three links on one shared folder, one per role, seeded as rows ([GuestTreeShare]); each switch refuses
 * its own role's link on every path exactly as an unknown link, leaves the other roles served, and serves its link
 * again once back on ([GuestPaths]). Memories' and Depot's guest operations are their own modules'
 * (`ShareSwitchGuestGateMemoriesSliceTest`, `ShareSwitchGuestGateDepotSliceTest`).
 *
 * "Served" is the path answering anything but the unknown link's answer: past the gate a folder takes no upload (no
 * application writes into a `FOLDER`, so a start is `share_not_contributable`), a READ link no write — each a refusal of
 * its own that only an admitted link reaches.
 */
@QuarkusTest
class ShareSwitchGuestGateCoreSliceTest {

    private val pwToken = "cHctdG9rZW4="

    private data class World(val ownerId: String, val scopeId: String, val root: String, val sub: String, val coverBlob: String)

    private fun world(): World {
        val (ownerId, scopeId) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val sub = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        // `sub` holds a folder, so no abandon past the gate erases it (an empty one is core's placeholder)
        seedNode(scopeId, NodeEntity.FOLDER, parentId = sub, wrappedContentKey = CONTENT_KEY)
        val cover = seedAttachment(scopeId, root, NodeCover.SLOT)
        return World(ownerId, scopeId, root, sub, cover.blobId)
    }

    @Test
    fun eachShareSwitchRefusesEveryCoreGuestPathOfItsRoleLikeAnUnknownLinkAndServesItAgainOnceBackOn() {
        val w = world()
        val links = listOf(ShareEntity.READ, ShareEntity.READ_WRITE, ShareEntity.WRITE_ONLY)
            .associateWith { GuestTreeShare.seed(w.ownerId, w.scopeId, w.root, it) }
        GuestPaths.assertEachShareSwitchReachesTheExistingLinksOfItsRole(links.mapValues { it.value.shareId }) { role ->
            paths(w, role, links.getValue(role).token)
        }
    }

    /**
     * The switch comes before the password (ADR 0088 decision 5): a switched-off password link answers neither the
     * locked challenge nor, with the right token, the link — either would tell a guest the link exists.
     */
    @Test
    fun aSwitchedOffPasswordLinkIsRefusedBeforeItsPasswordIsAsked() {
        val w = world()
        val link = GuestTreeShare.seed(w.ownerId, w.scopeId, w.root, ShareEntity.READ)
        QuarkusTransaction.requiringNew().run {
            ShareEntity.findById(link.shareId)!!.apply { passwordRequired = true; pwSalt = "cHctc2FsdA=="; pwVerifier = sha256b64(pwToken) }
        }
        val resolves = listOf(
            Path("resolveShare without a password") { id -> given().`when`().get("/api/shares/$id") },
            Path("resolveShare with the right password") { id -> given().header("X-Share-Password", pwToken).`when`().get("/api/shares/$id") },
        )
        // Control: on, the first is the locked challenge and the second the link — both served.
        assertTrue(GuestPaths.served(link.shareId, resolves, "password link, switch on").isEmpty())
        val refused = CapabilitySwitchFixtures.with(CapabilityIds.PUBLIC_SHARES) {
            GuestPaths.refusedLikeAnUnknownLink(link.shareId, resolves, "password link while public links are off")
        }
        assertTrue(refused.isEmpty(), refused.joinToString("\n"))
    }

    /** Core's guest operations for a link of [role] whose guest holds [token], each well-formed for any share id. */
    private fun paths(w: World, role: String, token: String): List<Path> = listOfNotNull(
        Path("resolveShare") { id -> given().`when`().get("/api/shares/$id") },
        Path("getShareTerms") { id -> given().`when`().get("/api/shares/$id/terms") },
        // A WRITE_ONLY link serves no blob even on (ADR 0031): its blob GET is the unknown link's 404 either way.
        if (role == ShareEntity.WRITE_ONLY) null
        else Path("resolveShareBlob") { id -> given().`when`().get("/api/shares/$id/blobs/${w.coverBlob}") },
        Path("createShareIdentity") { id ->
            given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(id))
                .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"bmFtZQ=="${identityKeyJson(role)}}""")
                .`when`().post("/api/shares/$id/identities")
        },
        Path("startShareBlobUploads") { id ->
            given().contentType(ContentType.JSON)
                .body("""{"contributorToken":"$token","items":[${TestBlob.SMALL.declare("d3JhcA==")}]}""")
                .`when`().post("/api/shares/$id/blob-uploads")
        },
        Path("presignShareBlobUploadWindows") { id ->
            given().contentType(ContentType.JSON)
                .body("""{"contributorToken":"$token","items":[{"uploadId":"${TestIds.uuid()}","fromChunk":0}]}""")
                .`when`().post("/api/shares/$id/blob-upload-windows")
        },
        Path("createShareNode") { id ->
            given().contentType(ContentType.JSON).header("X-Contributor-Token", token)
                .body(
                    """{"nodeId":"${UUID.randomUUID()}","parentId":"${w.root}","kind":"${NodeEntity.FOLDER}","encryptedName":"bmFtZQ==",""" +
                        """"slugToken":"${GuestTreeShare.slug()}","epoch":0,"wrappedContentKey":"d3JhcA=="}""",
                )
                .`when`().post("/api/shares/$id/nodes")
        },
        Path("updateShareNode") { id ->
            given().contentType(ContentType.JSON).header("X-Contributor-Token", token)
                .body(
                    """{"parentId":"${w.root}","encryptedName":"cGxhY2Vk","slugToken":"${GuestTreeShare.slug()}",""" +
                        """"wrappedContentKey":"cmUtd3JhcHBlZA==","placementRevision":${placementRevision(w.sub)}}""",
                )
                .`when`().patch("/api/shares/$id/nodes/${w.sub}")
        },
        // Past the gate a folder that holds one is no placeholder: a refusal only an admitted link reaches.
        Path("abandonShareNode") { id ->
            given().header("X-Contributor-Token", token).`when`().delete("/api/shares/$id/nodes/${w.sub}")
        },
    )

    private fun placementRevision(nodeId: String): Long = QuarkusTransaction.requiringNew().call { NodeEntity.findById(nodeId)!!.placementRevision }

    private fun sha256b64(tokenB64: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(tokenB64)))
}
