package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import java.util.UUID

/**
 * Reports in slice tests (uc.moderation.report). The server opens nothing a report carries, so the sealed part and the
 * report shares are opaque base64 here; what a test reads back is that it was stored as it was sent.
 */
object ReportTestKit {
    /** An opaque stand-in for a sealed report (the server never opens one). */
    const val SEALED_REPORT = "AXNlYWxlZC1yZXBvcnQ="

    /** An opaque stand-in for a report share. */
    const val SEALED_SHARE = "AnJlcG9ydC1zaGFyZQ=="

    /** Mint epoch 1 to a fresh Server Owner, after emptying every moderation table: a report needs an epoch. */
    fun epochOne(): ModerationTestKit.EpochKey {
        ModerationTestKit.reset()
        val owner = UUID.randomUUID().toString().also { ensureUser(it, "report-owner-$it@example.com", "SERVER_OWNER") }
        return ModerationTestKit.epochKey().also { ModerationTestKit.enable(owner, it, IDENTITY_PUBLIC_KEY) }
    }

    /** One item of a report body: `{"kind":…,"id":…,"sealedShare":…}`, the id left out for a LINK. */
    fun item(kind: String, id: String?, sealedShare: String = SEALED_SHARE): String =
        """{"kind":"$kind",${id?.let { """"id":"$it",""" } ?: ""}"sealedShare":"$sealedShare"}"""

    /** A report body. */
    fun body(items: List<String>, category: String = "ILLEGAL_CONTENT", epoch: Int = 1, receiptAddress: String? = null): String =
        """{"category":"$category","epoch":$epoch,"sealedReport":"$SEALED_REPORT","items":[${items.joinToString(",")}]""" +
            (receiptAddress?.let { ""","receiptAddress":"$it"""" } ?: "") + "}"

    /** A member's report of [items] in [scopeId]. */
    fun asMember(userId: String, scopeId: String, body: String): Response =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/reports")

    /** A guest's report through [shareId], holding the whole link unless [writeProof] is false. */
    fun asGuest(shareId: String, body: String, writeProof: Boolean = true, password: String? = null): Response {
        var spec = given().contentType(ContentType.JSON).body(body)
        if (writeProof) spec = spec.header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId))
        if (password != null) spec = spec.header("X-Share-Password", password)
        return spec.`when`().post("/api/shares/$shareId/reports")
    }

    /** A `READ`/`READ_WRITE` share of [nodeId] by its scope's member [ownerId], created as a client does; its id. */
    fun share(ownerId: String, nodeId: String, role: String = "READ"): String {
        val shareId = TestIds.shareId()
        given().header("Authorization", bearer(ownerId)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"$role","wrap":"d3JhcA==",""" +
                    """"sealedName":"bmFtZQ==",${ShareWriteProof.recovery()}}""",
            )
            .`when`().post("/api/shares").then().statusCode(201)
        return shareId
    }

    /** The open case of an item, or null. */
    fun openCase(kind: String, itemId: String): ModerationCaseEntity? =
        QuarkusTransaction.requiringNew().call { ModerationCaseEntity.find("openKey", "$kind:$itemId").firstResult() }

    /** The report shares handed to [caseId]. */
    fun sharesOf(caseId: String): List<ReportShareEntity> =
        QuarkusTransaction.requiringNew().call { ReportShareEntity.list("caseId", caseId) }

    fun report(reportId: String): ModerationReportEntity? =
        QuarkusTransaction.requiringNew().call { ModerationReportEntity.findById(reportId) }

    /** Every report row of [account] — what a refused report must not add to. */
    fun reportsOf(account: String): Long =
        QuarkusTransaction.requiringNew().call { ModerationReportEntity.count("reporterAccount", account) }
}
