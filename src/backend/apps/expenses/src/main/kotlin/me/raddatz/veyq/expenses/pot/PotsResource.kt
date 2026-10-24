package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.items.ItemsCursor
import me.raddatz.veyq.core.items.ItemsETag
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.node.rekey.RekeyOutcome
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.validation.validated
import me.raddatz.veyq.expenses.generated.api.ExpensesApi
import me.raddatz.veyq.expenses.generated.model.PotAppendRequest
import me.raddatz.veyq.expenses.generated.model.PotErasureRequest
import me.raddatz.veyq.expenses.generated.model.PotGuestWriter
import me.raddatz.veyq.expenses.generated.model.PotRekeyPage
import me.raddatz.veyq.expenses.generated.model.PotRekeyStage
import me.raddatz.veyq.expenses.generated.model.PotRekeyWrap
import me.raddatz.veyq.expenses.generated.model.PotRevision
import me.raddatz.veyq.expenses.generated.model.PotRevisionConflict
import me.raddatz.veyq.expenses.generated.model.PotRevisionPage
import me.raddatz.veyq.expenses.generated.model.PotRevisionRef
import me.raddatz.veyq.expenses.generated.model.PotRevisionWrite
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.ZoneOffset
import java.util.UUID

/**
 * HTTP adapter for a member's Pots (tag `expenses` of `expenses/expenses.openapi.yaml`, ADR 0108). Implements the
 * generated [ExpensesApi]; thin (ADR 0001) — delegates to [PotsHandler] and [PotRekeyHandler]. `@Authenticated`; the
 * caller must hold a grant for the scope (a non-member gets the gate's 404). Operator-blind: only ciphertext, ids,
 * counters, marks, writers and times cross.
 */
@Authenticated
@RateLimitExempt(
    reason = "Authenticated + membership-gated, as a Memory's media plumbing: a Pot's live list and its writes are " +
        "the main path of the application, and a per-user cap on it has to be verified against real traffic before it " +
        "ships. Recorded with the media's open item in cc.security.rate-limit (§ Unbounded by decision).",
)
class PotsResource(
    private val handler: PotsHandler,
    private val rekey: PotRekeyHandler,
    private val identity: SecurityIdentity,
) : ExpensesApi {

    override fun listPotRevisions(scopeId: UUID, nodeId: UUID, ifNoneMatch: String?, since: String?, sinceDelete: Int?, limit: Int?): Response =
        when (
            val o = handler.list(
                identity.principal.name, scopeId.toString(), nodeId.toString(),
                ItemsETag.parse(ifNoneMatch), ItemsCursor.parse(since), sinceDelete ?: 0, ItemsPage.limitOf(limit),
            )
        ) {
            is PotListOutcome.Page -> Response.ok(
                PotRevisionPage(
                    revisions = o.revisions.map(::toMember),
                    more = o.more,
                    ledgerRevision = o.ledgerRevision,
                    deleteRevision = o.deleteRevision,
                    guestWriters = o.guestWriters.map { PotGuestWriter(UUID.fromString(it.identityId), it.shareId, it.sealedName) },
                    cursor = o.cursor,
                    revisionIds = o.refs?.map(::toRef),
                ),
            ).header("ETag", ItemsETag.of(o.ledgerRevision)).build() // the ETag doubles as the client's next If-None-Match
            PotListOutcome.NotModified -> Response.notModified().build() // 304 — no body, no revision query
            is HttpFailure -> httpError(o)
        }

    override fun appendPotRevisions(scopeId: UUID, nodeId: UUID, potAppendRequest: PotAppendRequest): Response =
        respond(
            handler.append(
                identity.principal.name, scopeId.toString(), nodeId.toString(), potAppendRequest.nodeEpoch.validated(),
                potAppendRequest.revisions.map(::toWrite),
            ),
        )

    override fun erasePotRecord(scopeId: UUID, nodeId: UUID, potErasureRequest: PotErasureRequest): Response {
        val r = potErasureRequest
        val write = RevisionWrite(r.entryId.toString(), r.revision, false, r.sealedBody, r.wrappedRevisionKey)
        return respond(handler.erase(identity.principal.name, scopeId.toString(), nodeId.toString(), r.nodeEpoch.validated(), write))
    }

    override fun listPotRekey(scopeId: UUID, nodeId: UUID, since: String?, limit: Int?): Response =
        when (
            val o = rekey.list(
                identity.principal.name, scopeId.toString(), nodeId.toString(),
                ItemsCursor.parse(since)?.let { RevisionRef.parse(it.id) }, ItemsPage.limitOf(limit),
            )
        ) {
            is PotRekeyListOutcome.Page -> Response.ok(
                PotRekeyPage(
                    revisions = o.revisions.items.map { PotRekeyWrap(UUID.fromString(it.entryId), it.revision, it.wrappedRevisionKey) },
                    more = o.revisions.more,
                    cursor = o.revisions.cursor?.wire(),
                ),
            ).build()
            is PotRekeyListOutcome.Refused -> httpError(o.failure)
        }

    override fun stagePotRekey(scopeId: UUID, nodeId: UUID, rekeyId: UUID, potRekeyStage: PotRekeyStage): Response {
        val wraps = potRekeyStage.revisions.associate { RevisionRef(it.entryId.toString(), it.revision) to it.wrappedRevisionKey }
        if (wraps.size != potRekeyStage.revisions.size) return httpError(RekeyOutcome.InvalidRequest) // one value per revision
        return when (
            val o = rekey.stage(
                identity.principal.name, scopeId.toString(), nodeId.toString(), rekeyId.toString(), potRekeyStage.fromEpoch.validated(), wraps,
            )
        ) {
            RekeyOutcome.Staged -> Response.noContent().build()
            is HttpFailure -> httpError(o)
            else -> error("not a staging answer: $o")
        }
    }

    companion object {
        fun toWrite(w: PotRevisionWrite) =
            RevisionWrite(w.entryId.toString(), w.revision, w.tombstone.validated(), w.sealedBody, w.wrappedRevisionKey)

        fun toRef(r: RevisionRef) = PotRevisionRef(UUID.fromString(r.entryId), r.revision)

        /** A write's answer: `204`, the conflict with its stale records, or the refusal's `(status, code)`. */
        fun respond(o: PotWriteOutcome): Response = when (o) {
            PotWriteOutcome.Done -> Response.noContent().build()
            is PotWriteOutcome.Stale -> Response.status(Response.Status.CONFLICT)
                .entity(PotRevisionConflict(code = o.code, stale = o.stale.map(::toRef))).build()
            is HttpFailure -> httpError(o)
        }

        /** The member's shape: the guest's ([SharePotsResource]) is its own and has no writer (inbox #190). */
        private fun toMember(r: PotRevisionEntity) = PotRevision(
            entryId = UUID.fromString(r.entryId),
            revision = r.revision,
            tombstone = r.tombstone,
            erasure = r.erasure,
            sealedBody = r.sealedBody,
            wrappedRevisionKey = r.wrappedRevisionKey,
            writer = r.writer.format(),
            createdAt = r.createdAt.atOffset(ZoneOffset.UTC),
        )
    }
}
