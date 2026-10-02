package com.veyq.app.features.sharing

import com.veyq.app.shared.TestIds
import com.veyq.app.features.media.TestBlob
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.identityKeyJson
import com.veyq.app.testing.TestFixtures.shareKeysJson
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * The permanent guard for cr-0164: the share-password gate fronts the guest **write** path, not only the read
 * path. Before this CR, `createShareIdentity` / `startShareBlobUploads` / `completeShareMedia` / `deleteShareMedia`
 * never called `passwordCheck`, and `createShareIdentity` mints the `contributorToken` for anyone holding the
 * shareId — so a link-holder who did not know the password could contribute to a password-protected share (and
 * for an UPLOADER drop-box, whose only capability is writing, the password was entirely functionless).
 *
 * This walks the full 4-op × 3-case table on **both** contributable roles (CONTRIBUTOR, UPLOADER):
 *   - **no token → 404** (non-enumerable — identical to an unknown share; the write path follows `resolveBlob`, not
 *     `resolve`, which serves a challenge shell),
 *   - **wrong token → 403 `share_password_incorrect`**,
 *   - **valid token → the op proceeds** past the gate.
 *
 * The gate runs **before** the role/token check (mirroring `resolve`/`resolveBlob`, ADR 0042 boundary-first): the
 * no-/wrong-token cases below present a **valid** `contributorToken` in the body and are still refused on the
 * password — proving the password is evaluated first and no role/existence signal leaks without it.
 */
@QuarkusTest
class SharePasswordWriteGateTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val pwSalt = "cHctc2FsdA=="
    private val pwToken = "cHctdG9rZW4=" // the correct X-Share-Password
    private val pwVerifier = sha256b64(pwToken)
    private val wrongToken = "d3JvbmctdG9rZW4="
    private val sealedName2 = "c2VhbGVkLWNvbnRyaWI=" // an anonymous contributor display name (opaque)

    @Test
    fun contributorWritePathIsPasswordGated() = assertWriteGate("CONTRIBUTOR")

    @Test
    fun uploaderWritePathIsPasswordGated() = assertWriteGate("UPLOADER")

    private fun assertWriteGate(role: String) {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createWithPassword(userId, seedNode(scopeId, wrappedContentKey = CONTENT_KEY), role)

        // --- createShareIdentity: no token → 404, wrong token → 403, valid token → 201 (+ the real contributorToken)
        val key = identityKeyJson(role)
        given().contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedName2"$key}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
        given().header("X-Share-Password", wrongToken).contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedName2"$key}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(403).body("code", equalTo("share_password_incorrect"))
        val contributorToken = given().header("X-Share-Password", pwToken).contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedName2"$key}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path<String>("contributorToken")

        // The no-/wrong-token calls below carry a VALID contributorToken — refusal proves the password is checked FIRST.
        val declares = """"items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]"""

        // --- startShareBlobUploads
        postBlobUploads(shareId, null, contributorToken, declares).then().statusCode(404).body("code", equalTo("share_not_found"))
        postBlobUploads(shareId, wrongToken, contributorToken, declares).then().statusCode(403).body("code", equalTo("share_password_incorrect"))
        val uploadId = postBlobUploads(shareId, pwToken, contributorToken, declares)
            .then().statusCode(200).extract().path<String>("results[0].uploadId")

        // --- completeShareMedia
        val complete = """"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]"""
        postComplete(shareId, null, contributorToken, complete).then().statusCode(404).body("code", equalTo("share_not_found"))
        postComplete(shareId, wrongToken, contributorToken, complete).then().statusCode(403).body("code", equalTo("share_password_incorrect"))
        val mediaId = postComplete(shareId, pwToken, contributorToken, complete)
            .then().statusCode(200).extract().path<String>("results[0].mediaId")

        // --- deleteShareMedia (its own 404 code is share_media_not_found)
        deleteMedia(shareId, null, contributorToken, mediaId).then().statusCode(404).body("code", equalTo("share_media_not_found"))
        deleteMedia(shareId, wrongToken, contributorToken, mediaId).then().statusCode(403).body("code", equalTo("share_password_incorrect"))
        deleteMedia(shareId, pwToken, contributorToken, mediaId).then().statusCode(204)
    }

    // --- request helpers (X-Share-Password omitted when pw is null) ---

    private fun postBlobUploads(shareId: String, pw: String?, token: String, declares: String) =
        base(pw).contentType(ContentType.JSON).body("""{"contributorToken":"$token",$declares}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")

    private fun postComplete(shareId: String, pw: String?, token: String, items: String) =
        base(pw).contentType(ContentType.JSON).body("""{"contributorToken":"$token",$items}""")
            .`when`().post("/api/shares/$shareId/media")

    private fun deleteMedia(shareId: String, pw: String?, token: String, mediaId: String) =
        base(pw).header("X-Contributor-Token", token).`when`().delete("/api/shares/$shareId/media/$mediaId")

    private fun base(pw: String?) = if (pw == null) given() else given().header("X-Share-Password", pw)

    // --- seed helpers (mirroring SharePasswordSliceTest) ---

    private fun sha256b64(tokenB64: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(tokenB64)))

    private fun createWithPassword(userId: String, nodeId: String, role: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId, wrap)},"sealedName":"$sealedName","passwordRequired":true,"pwSalt":"$pwSalt","pwVerifier":"$pwVerifier"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")
}
