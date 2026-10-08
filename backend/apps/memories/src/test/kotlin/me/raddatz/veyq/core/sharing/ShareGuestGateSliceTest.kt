package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.identityKeyJson
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import me.raddatz.veyq.memories.media.ShareMediaRead
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * [ShareGuestGate] (cr-platform-contract-split slice 6.1): the guest gate as a core service, every refusal pinned to
 * the HTTP answer it has today, so a caller moving onto the gate cannot move a status.
 *
 * - **The write gate** through the existing guest write routes: each refusal kind once, the upload budget spent only
 *   where the caller asks (a delete after a spent budget still answers 204 — complete spends it, delete does not), and
 *   a root that is gone refused as not contributable.
 * - **The view gate** directly — no route calls it yet; slice 6.3's Memories read will. Each outcome beside what the
 *   resolve and the blob GET answer for the same share today, which is the mapping that read has to keep.
 *
 * Its own profile: an upload budget of 2 (one start + one complete) and a password budget of 1 wrong attempt per
 * share, so both 429s are reachable in a few calls. Every test mints its own share, and both budgets are per share.
 */
@QuarkusTest
@TestProfile(ShareGuestGateSliceTest.SmallBudgetsProfile::class)
class ShareGuestGateSliceTest {

    class SmallBudgetsProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.share.upload.rate.max-per-window" to "2",
            "veyq.share.upload.rate.window-seconds" to "3600",
            "veyq.share.password.rate.max-per-window" to "1",
            "veyq.share.password.rate.window-seconds" to "3600",
        )
    }

    @Inject
    lateinit var gate: ShareGuestGate

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val guestName = "c2VhbGVkLWd1ZXN0LW5hbWU="
    private val pwSalt = "cHctc2FsdA=="
    private val pwToken = "cHctdG9rZW4="
    private val wrongToken = "d3JvbmctdG9rZW4="

    // --- the write gate -------------------------------------------------------------------------------------------

    /** V18: completing spends the per-share upload budget, deleting one's own contribution does not. */
    @Test
    fun aDeleteAfterTheUploadBudgetIsSpentStillAnswers204() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, memory(scopeId), "READ_WRITE")
        val token = createIdentity(shareId, "READ_WRITE")
        val mediaId = upload(shareId, token) // start + complete: the budget of 2 is spent

        // control: the budget IS spent — the next write that spends it is refused
        startUpload(shareId, token).then().statusCode(429).body("code", equalTo("rate_limited"))

        deleteMedia(shareId, token, mediaId).then().statusCode(204)
    }

    @Test
    fun theWriteGateRefusesAnInactiveShareAsNotFound() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, memory(scopeId), "READ_WRITE")
        val token = createIdentity(shareId, "READ_WRITE")
        // control: past the gate, an unknown media is the delete's own 404
        deleteMedia(shareId, token, TestIds.uuid()).then().statusCode(404).body("code", equalTo("share_media_not_found"))

        given().header("Authorization", bearer(userId)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)

        complete(shareId, token, TestIds.uuid()).then().statusCode(404).body("code", equalTo("share_not_found"))
        window(shareId, token).then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun theWriteGateRefusesAMissingPasswordAsNotFoundAndAWrongOneAs403ThenAs429() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, memory(scopeId), "READ_WRITE", password = true)
        val token = createIdentity(shareId, "READ_WRITE", pw = pwToken)

        complete(shareId, token, TestIds.uuid()).then().statusCode(404).body("code", equalTo("share_not_found"))
        complete(shareId, token, TestIds.uuid(), pw = wrongToken)
            .then().statusCode(403).body("code", equalTo("share_password_incorrect"))
        deleteMedia(shareId, token, TestIds.uuid(), pw = wrongToken) // the one wrong attempt the profile allows is gone
            .then().statusCode(429).body("code", equalTo("rate_limited"))
        // control: the right password still passes, past an exhausted password budget
        deleteMedia(shareId, token, TestIds.uuid(), pw = pwToken).then().statusCode(404).body("code", equalTo("share_media_not_found"))
    }

    @Test
    fun theWriteGateRefusesASpentUploadBudgetOnCompleteAs429() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, memory(scopeId), "READ_WRITE")
        val token = createIdentity(shareId, "READ_WRITE")
        upload(shareId, token)

        complete(shareId, token, TestIds.uuid()).then().statusCode(429).body("code", equalTo("rate_limited"))
        // a window spends nothing either: refused for its own reason (no such session), not for the budget
        window(shareId, token).then().statusCode(200)
    }

    /**
     * Inbox #367: a start refused for its own request — a role the target's application takes from no guest, an unknown
     * node — opens no session and so spends none of the link's upload budget; the budget is the start's last step.
     * Three refusals against a budget of 2, then the start and the complete the budget holds both pass.
     */
    @Test
    fun aStartRefusedForItsOwnRequestSpendsNoUploadBudget() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, memory(scopeId), "READ_WRITE")
        val token = createIdentity(shareId, "READ_WRITE")

        startUpload(shareId, token, role = "COVER").then().statusCode(400).body("code", equalTo("invalid_request"))
        startUpload(shareId, token, role = "COVER").then().statusCode(400).body("code", equalTo("invalid_request"))
        startUpload(shareId, token, nodeId = TestIds.uuid()).then().statusCode(404)

        upload(shareId, token) // start + complete: the whole budget of 2, untouched by the refusals above
        // control: the budget IS spent now, and a start reports it
        startUpload(shareId, token).then().statusCode(429).body("code", equalTo("rate_limited"))
    }

    @Test
    fun theWriteGateRefusesAReadModeAndAForeignTokenAsNotContributable() {
        val (userId, scopeId) = seedMemberScope()
        val node = memory(scopeId)
        val contributable = createShare(userId, node, "READ_WRITE")
        val token = createIdentity(contributable, "READ_WRITE")
        val readOnly = createShare(userId, node, "READ")

        deleteMedia(readOnly, token, TestIds.uuid()).then().statusCode(403).body("code", equalTo("share_not_contributable"))
        complete(readOnly, token, TestIds.uuid()).then().statusCode(403).body("code", equalTo("share_not_contributable"))
        // a well-formed token of ANOTHER link names no identity of this one
        val foreign = createIdentity(createShare(userId, node, "READ_WRITE"), "READ_WRITE")
        deleteMedia(contributable, foreign, TestIds.uuid())
            .then().statusCode(403).body("code", equalTo("share_not_contributable"))
        // control: the same media id with the real token passes the gate
        deleteMedia(contributable, token, TestIds.uuid()).then().statusCode(404).body("code", equalTo("share_media_not_found"))
    }

    /**
     * A root whose row is gone names no kind, and the gate refuses it as not contributable. The purge tombstones the
     * share before it erases a Memory (`ShareDeletedNodeSliceTest.erasingAMemoryEndsItsLink`), so the share would be
     * the 404 first; the row is removed directly here to reach the gate's own branch.
     */
    @Test
    fun aGoneRootIsNotContributable() {
        val (userId, scopeId) = seedMemberScope()
        val root = memory(scopeId)
        val shareId = createShare(userId, root, "READ_WRITE")
        val token = createIdentity(shareId, "READ_WRITE")
        deleteMedia(shareId, token, TestIds.uuid()).then().statusCode(404).body("code", equalTo("share_media_not_found"))

        QuarkusTransaction.requiringNew().run { NodeEntity.deleteById(root) }

        deleteMedia(shareId, token, TestIds.uuid()).then().statusCode(403).body("code", equalTo("share_not_contributable"))
        window(shareId, token).then().statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    /** The gate hands out the root's kind and the identity, and `admitted` unevaluated: asked, it reads the row. */
    @Test
    fun theWriteGateAdmitsWithTheRootAndAnUnevaluatedRecheck() {
        val (userId, scopeId) = seedMemberScope()
        val root = memory(scopeId)
        val shareId = createShare(userId, root, "READ_WRITE")
        val token = createIdentity(shareId, "READ_WRITE")

        val g = QuarkusTransaction.requiringNew().call { gate.writeGate(shareId, null, token, spendUploadBudget = false) }
        assertTrue(g is ShareWriteGate.Admitted, "admitted: $g")
        g as ShareWriteGate.Admitted
        assertEquals(scopeId, g.scopeId)
        assertEquals(root, g.rootNodeId)
        assertEquals(MemoriesNodeKinds.MEMORY, g.rootKind)
        assertTrue(QuarkusTransaction.requiringNew().call { g.admitted() })

        // revoked after the gate passed: the re-check, asked later, sees it
        given().header("Authorization", bearer(userId)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)
        assertEquals(false, QuarkusTransaction.requiringNew().call { g.admitted() })
    }

    // --- the view gate --------------------------------------------------------------------------------------------

    @Test
    fun theViewGateServesTheLiveSubtreeAndTheRootKind() {
        val (userId, scopeId) = seedMemberScope()
        val root = memory(scopeId)
        val live = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val trashed = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, root, "READ")
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$trashed").then().statusCode(204)

        val g = viewGate(shareId, null) as ShareViewGate.Viewable
        assertEquals(scopeId, g.scopeId)
        assertEquals(root, g.rootNodeId)
        assertEquals(MemoriesNodeKinds.MEMORY, g.rootKind)
        assertEquals(setOf(root, live), g.liveNodeIds.toSet())

        // a trashed root: viewable, but nothing in it and no kind — as the resolve answers it today
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$root").then().statusCode(204)
        assertEquals(ShareViewGate.Viewable(scopeId, root, null, emptyList()), viewGate(shareId, null))
        resolve(shareId).then().statusCode(200).body("nodes.size()", equalTo(0)).body("rootKind", nullValue())
    }

    @Test
    fun theViewGateAnswersAWriteOnlyModeAsWriteOnly() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, memory(scopeId), "WRITE_ONLY")

        assertEquals(ShareViewGate.WriteOnly, viewGate(shareId, null))
        // the resolve serves nothing of the subtree, the media read no row and no identity, the blob GET nothing at all
        resolve(shareId).then().statusCode(200).body("nodes.size()", equalTo(0))
        ShareMediaRead.of(shareId).then().statusCode(200).body("media.size()", equalTo(0)).body("identities.size()", equalTo(0))
        blob(shareId).then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun theViewGateAnswersAnInactiveShareAsNotFound() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, memory(scopeId), "READ")
        given().header("Authorization", bearer(userId)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)

        assertEquals(ShareViewGate.NotFound, viewGate(shareId, null))
        resolve(shareId).then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun theViewGateAnswersTheThreePasswordOutcomes() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, memory(scopeId), "READ", password = true)

        // no token: Locked — the resolve's locked shell (200, the challenge only), the blob GET's 404
        assertEquals(ShareViewGate.Locked, viewGate(shareId, null))
        resolve(shareId).then().statusCode(200).body("passwordRequired", equalTo(true)).body("nodeId", nullValue())
        blob(shareId).then().statusCode(404).body("code", equalTo("share_not_found"))

        // a wrong token: 403, once — the profile's one wrong attempt — then 429
        assertEquals(ShareViewGate.PasswordIncorrect, viewGate(shareId, wrongToken))
        assertEquals(ShareViewGate.RateLimited, viewGate(shareId, wrongToken))
        resolve(shareId, wrongToken).then().statusCode(429).body("code", equalTo("rate_limited"))

        // control: the right token still passes
        assertTrue(viewGate(shareId, pwToken) is ShareViewGate.Viewable)
    }

    @Test
    fun aWrongPasswordOnTheResolveIs403() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, memory(scopeId), "READ", password = true)
        resolve(shareId, wrongToken).then().statusCode(403).body("code", equalTo("share_password_incorrect"))
    }

    // --- helpers ---

    private fun viewGate(shareId: String, pw: String?): ShareViewGate =
        QuarkusTransaction.requiringNew().call { gate.viewGate(shareId, pw) }

    private fun memory(scopeId: String) = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)

    private fun base(pw: String?) = if (pw == null) given() else given().header("X-Share-Password", pw)

    private fun resolve(shareId: String, pw: String? = null) = base(pw).`when`().get("/api/shares/$shareId")

    private fun blob(shareId: String) = given().`when`().get("/api/shares/$shareId/blobs/${TestIds.uuid()}")

    private fun createShare(userId: String, nodeId: String, role: String, password: Boolean = false): String {
        val pw = if (password) ""","passwordRequired":true,"pwSalt":"$pwSalt","pwVerifier":"${sha256b64(pwToken)}"""" else ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId, wrap)},"sealedName":"$sealedName",${ShareWriteProof.recovery()}$pw}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")
    }

    private fun createIdentity(shareId: String, role: String, pw: String? = null): String =
        base(pw).contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$guestName"${identityKeyJson(role)}}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path("contributorToken")

    private fun startUpload(shareId: String, token: String, role: String = "ORIGINAL", nodeId: String? = null) =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token"${nodeId?.let { ""","nodeId":"$it"""" } ?: ""},"items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw", role = role)}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")

    /** start + complete through the link → the media id (spends 2 of the upload budget). */
    private fun upload(shareId: String, token: String): String {
        val uploadId = startUpload(shareId, token).then().statusCode(200).extract().path<String>("results[0].uploadId")
        return given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200).extract().path("results[0].mediaId")
    }

    private fun complete(shareId: String, token: String, uploadId: String, pw: String? = null) =
        base(pw).contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[{"mediaId":"${TestIds.uuid()}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media")

    private fun window(shareId: String, token: String) =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$token","items":[{"uploadId":"${TestIds.uuid()}","fromChunk":0}]}""")
            .`when`().post("/api/shares/$shareId/blob-upload-windows")

    private fun deleteMedia(shareId: String, token: String, mediaId: String, pw: String? = null) =
        base(pw).header("X-Contributor-Token", token).`when`().delete("/api/shares/$shareId/media/$mediaId")

    private fun sha256b64(tokenB64: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(tokenB64)))
}
