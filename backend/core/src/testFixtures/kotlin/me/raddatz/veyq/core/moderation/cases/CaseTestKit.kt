package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.block.ModerationProposalEntity
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.statement.ModerationObjectionEntity
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.moderation.statement.StatementRecipientEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.restassured.http.ContentType
import io.restassured.response.Response
import java.util.UUID

/**
 * A moderator deciding cases in slice tests (uc.moderation.review-a-case, uc.moderation.take-a-measure): the Server Owner
 * is the first moderator (first-run setup's shape), holding the epoch's signing key, and every call carries a fresh key
 * proof over its own operation and case — what the moderation surface sends. A note is opaque ciphertext to the server.
 */
object CaseTestKit {
    /** An opaque stand-in for a note sealed to the moderation key (the server never opens one). */
    const val SEALED_NOTE = "c2VhbGVkLW5vdGU="

    /** The moderator (the Server Owner) and the epoch's key. */
    class Moderator(val id: String, val key: ModerationTestKit.EpochKey)

    /**
     * Empty every moderation table — cases, reports and holds included, since the queue is instance-wide and the `%test`
     * database lives as long as the JVM — then mint epoch 1 to a fresh Server Owner: the moderator every test acts as.
     */
    fun moderator(): Moderator {
        QuarkusTransaction.requiringNew().run {
            ModerationObjectionEntity.deleteAll()
            StatementRecipientEntity.deleteAll()
            ModerationStatementEntity.deleteAll()
            ModerationProposalEntity.deleteAll()
            ModerationBlockEntity.deleteAll()
            HoldPinEntity.deleteAll()
            HoldRowEntity.deleteAll()
            HoldEntity.deleteAll()
            ReportShareEntity.deleteAll()
            ModerationReportEntity.deleteAll()
            ModerationCaseEntity.deleteAll()
        }
        ModerationTestKit.reset()
        val owner = UUID.randomUUID().toString().also { ensureUser(it, "case-owner-$it@example.com", "SERVER_OWNER") }
        val key = ModerationTestKit.epochKey().also { ModerationTestKit.enable(owner, it, IDENTITY_PUBLIC_KEY) }
        return Moderator(owner, key)
    }

    /** A key-proved call of [operation] over [caseId] — [method] on [path], with [body] when given. */
    fun Moderator.call(operation: String, caseId: String?, method: String, path: String, body: String? = null): Response {
        var spec = ModerationTestKit.asUser(id).headers(ModerationTestKit.proof(id, operation, key, caseId))
        if (body != null) spec = spec.contentType(ContentType.JSON).body(body)
        return spec.request(method, path)
    }

    fun Moderator.queue(state: String? = null): Response =
        call("listModerationCases", null, "GET", "/api/moderation/cases" + (state?.let { "?state=$it" } ?: ""))

    fun Moderator.case(caseId: String): Response = call("getModerationCase", caseId, "GET", "/api/moderation/cases/$caseId")

    fun Moderator.dismiss(caseId: String, note: Boolean = false, reporters: List<Pair<String, String>> = emptyList()): Response =
        call(
            "dismissModerationCase", caseId, "POST", "/api/moderation/cases/$caseId/dismissal",
            if (reporters.isEmpty()) noteBody(note)
            else """{"reporterNotices":[""" + reporters.joinToString(",") { (r, a) -> """{"reportId":"$r","address":"$a"}""" } + "]}",
        )

    /** An opaque stand-in for the facts sealed to one member's identity (the server never opens one). */
    const val SEALED_FACTS = "c2VhbGVkLWZhY3Rz"

    /** The recipients a step's statement would reach (`getModerationStatementRecipients`). */
    fun Moderator.recipients(caseId: String, kind: String, targetId: String? = null, shareId: String? = null): Response =
        call(
            "getModerationStatementRecipients", caseId, "GET",
            "/api/moderation/cases/$caseId/statement-recipients?kind=$kind" + (targetId?.let { "&targetId=$it" } ?: "") +
                (shareId?.let { "&shareId=$it" } ?: ""),
        )

    /**
     * Take a step with its statement of reasons: by default with facts — sealed (opaquely) for every member the server
     * lists as a recipient, as the decision panel does in the organisation mode — or, [facts] false, without any.
     * [factsFor] overrides the members the facts are sealed for (a stale list); [reporters] are `reportId to address`.
     */
    fun Moderator.take(
        caseId: String, kind: String, targetId: String? = null, shareId: String? = null, facts: Boolean = true,
        ground: String = "TERMS_BREACH", factsFor: List<String>? = null, reporters: List<Pair<String, String>> = emptyList(),
    ): Response {
        val members = when {
            !facts -> emptyList()
            factsFor != null -> factsFor
            // A refused step has no recipients to read: the take then meets the same refusal.
            else -> recipients(caseId, kind, targetId, shareId).let { if (it.statusCode == 200) it.path<List<String>>("accounts.accountId") else emptyList() }
        }
        val statement = if (!facts) """{"ground":"$ground"}""" else
            """{"ground":"$ground","facts":{"epoch":1,"sealedNote":"$SEALED_NOTE"},"factsFor":[""" +
                members.joinToString(",") { """{"accountId":"$it","sealedFacts":"$SEALED_FACTS"}""" } + "]}"
        val notices = reporters.joinToString(",") { (r, a) -> """{"reportId":"$r","address":"$a"}""" }
        return call(
            "takeModerationMeasure", caseId, "POST", "/api/moderation/cases/$caseId/measures",
            """{"kind":"$kind"""" + (targetId?.let { ""","targetId":"$it"""" } ?: "") + (shareId?.let { ""","shareId":"$it"""" } ?: "") +
                ""","statement":$statement,"reporterNotices":[$notices]}""",
        )
    }

    /** Decide an objection: `UPHOLD` or `LIFT`. */
    fun Moderator.decideObjection(caseId: String, objectionId: String, outcome: String): Response =
        call(
            "decideModerationObjection", caseId, "POST", "/api/moderation/cases/$caseId/objections/$objectionId/decision",
            """{"outcome":"$outcome"}""",
        )

    fun Moderator.lift(caseId: String, measureId: String): Response =
        call("liftModerationMeasure", caseId, "POST", "/api/moderation/cases/$caseId/measures/$measureId/lift", "{}")

    fun Moderator.propose(caseId: String, step: String, targetId: String? = null): Response =
        call(
            "proposeModerationStep", caseId, "POST", "/api/moderation/cases/$caseId/proposals",
            """{"step":"$step"""" + (targetId?.let { ""","targetId":"$it"""" } ?: "") + "}",
        )

    fun Moderator.extend(caseId: String, until: String, epoch: Int = 1): Response =
        call(
            "extendModerationHold", caseId, "POST", "/api/moderation/cases/$caseId/hold/extension",
            """{"until":"$until","reason":{"epoch":$epoch,"sealedNote":"$SEALED_NOTE"}}""",
        )

    fun Moderator.bar(caseId: String, reportId: String): Response =
        call("barModerationReporter", caseId, "POST", "/api/moderation/cases/$caseId/reports/$reportId/bar", "{}")

    fun Moderator.review(caseId: String): Response = call("getCaseReview", caseId, "GET", "/api/moderation/cases/$caseId/review")

    fun Moderator.reviewBlobs(caseId: String, blobIds: List<String>): Response =
        call(
            "presignCaseBlobs", caseId, "POST", "/api/moderation/cases/$caseId/review/blobs",
            """{"blobIds":[${blobIds.joinToString(",") { "\"$it\"" }}]}""",
        )

    private fun noteBody(note: Boolean) = if (note) """{"note":{"epoch":1,"sealedNote":"$SEALED_NOTE"}}""" else "{}"

    fun caseRow(caseId: String): ModerationCaseEntity = QuarkusTransaction.requiringNew().call { ModerationCaseEntity.findById(caseId)!! }

    fun holdOf(caseId: String): HoldEntity? = QuarkusTransaction.requiringNew().call { HoldEntity.find("caseId", caseId).firstResult() }

    fun pinsOf(holdId: String): Long = QuarkusTransaction.requiringNew().call { HoldPinEntity.count("holdId", holdId) }
}
