package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.crypto.Tokens
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * A share link with one named guest, seeded as rows (so any role on any kind — a `WRITE_ONLY` folder share
 * included, which [ShareRoots] refuses to create), and the guest's tree operations over HTTP (`createShareNode`,
 * `updateShareNode`, `abandonShareNode`, ADR 0080 decisions 6 and 10).
 */
object GuestTreeShare {

    /** A link on [nodeId] with [role] and its guest's contributor token. */
    data class Link(val shareId: String, val token: String)

    fun seed(ownerId: String, scopeId: String, nodeId: String, role: String = ShareEntity.READ_WRITE): Link {
        val shareId = TestIds.shareId()
        val token = Tokens.random()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = shareId; ownerAccount = ownerId; this.scopeId = scopeId; this.nodeId = nodeId; this.role = role
                if (role == ShareEntity.WRITE_ONLY) sealedDropPublicKey = "ZHJvcA==" else wrap = "d3JhcA=="
                sealedName = "c2VhbGVk"; sealedShareKey = "cmVjb3Zlcnk="; wrappedRecoveryValueKey = "d3JhcHBlZC12YWx1ZS1rZXk="; sealedShareKeyEpoch = 0
                writeVerifier = ShareWriteProof.verifier(shareId); createdAt = Instant.now()
            }.persist()
            ShareIdentityEntity().apply {
                id = UUID.randomUUID().toString(); this.shareId = shareId; sealedDisplayName = "bmFtZQ=="
                tokenHash = Tokens.hash(token)
            }.persist()
        }
        return Link(shareId, token)
    }

    /** A fresh slug token — two placements never collide unless a test says so. */
    fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    fun create(
        link: Link, parentId: String, kind: String, nodeId: String = UUID.randomUUID().toString(), slugToken: String = slug(),
        encryptedName: String = "bmFtZQ==", wrappedContentKey: String = "d3JhcA==", scopeEpoch: Int = 0, extra: String = "",
        parentEpoch: Int = 0,
    ): ValidatableResponse =
        given().contentType(ContentType.JSON).header("X-Contributor-Token", link.token)
            .body(
                """{"nodeId":"$nodeId","parentId":"$parentId","kind":"$kind","encryptedName":"$encryptedName",""" +
                    """"slugToken":"$slugToken","scopeEpoch":$scopeEpoch,"wrappedContentKey":"$wrappedContentKey","parentEpoch":$parentEpoch$extra}""",
            )
            .`when`().post("/api/shares/${link.shareId}/nodes").then()

    fun place(
        link: Link, nodeId: String, parentId: String, placementRevision: Long = 0, slugToken: String = slug(),
        encryptedName: String = "cGxhY2Vk", wrappedContentKey: String = "cmUtd3JhcHBlZA==", parentEpoch: Int = 0,
    ): ValidatableResponse =
        given().contentType(ContentType.JSON).header("X-Contributor-Token", link.token)
            .body(
                """{"parentId":"$parentId","encryptedName":"$encryptedName","slugToken":"$slugToken",""" +
                    """"wrappedContentKey":"$wrappedContentKey","placementRevision":$placementRevision,"parentEpoch":$parentEpoch}""",
            )
            .`when`().patch("/api/shares/${link.shareId}/nodes/$nodeId").then()

    /** Take back the empty upload placeholder [nodeId] (`abandonShareNode`). */
    fun abandon(link: Link, nodeId: String): ValidatableResponse =
        given().header("X-Contributor-Token", link.token).`when`().delete("/api/shares/${link.shareId}/nodes/$nodeId").then()
}
