package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.RequestClient
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import me.raddatz.veyq.core.validation.validated
import me.raddatz.veyq.expenses.generated.api.ShareExpensesApi
import me.raddatz.veyq.expenses.generated.model.SharePotAppendRequest
import me.raddatz.veyq.expenses.generated.model.SharePotRevision
import me.raddatz.veyq.expenses.generated.model.SharePotRevisionPage
import jakarta.annotation.security.PermitAll
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.expenses.generated.model.SharePotReceiptCompleteRequest
import me.raddatz.veyq.expenses.generated.model.SharePotReceiptPage
import me.raddatz.veyq.expenses.receipt.CompleteReceiptOutcome
import me.raddatz.veyq.expenses.receipt.ListReceiptsOutcome
import me.raddatz.veyq.expenses.receipt.ReceiptWire
import me.raddatz.veyq.expenses.receipt.ReceiptsHandler
import java.time.Duration
import jakarta.ws.rs.core.Response
import java.security.MessageDigest
import java.time.ZoneOffset
import java.util.UUID

/**
 * HTTP adapter for a guest's Pot on a share link (tag `share-expenses`). Implements the generated [ShareExpensesApi],
 * whose root `/api/shares/{shareId}/pots/{nodeId}/revisions` is longer than core's `sharing` root `/api/shares`, so
 * RESTEasy routes these paths here and every other share route to core. Thin (ADR 0001) — delegates to
 * [SharePotsHandler]. `@PermitAll`: an account-less guest reaches it, gated only by the link's state and the guest's
 * token. Each operation declares its own rate-limit posture.
 */
class SharePotsResource(
    private val handler: SharePotsHandler,
    private val receipts: ReceiptsHandler,
    /** The client a guest's write records (the guest-write record, ADR 0093 § 10): resolved once, handed down. */
    private val client: RequestClient,
    config: VeyqConfig,
) : ShareExpensesApi {

    /** The trash retention a tombstone's `purgeAfter` is derived from (decision 6), never stored. */
    private val retention: Duration = config.node().trash().retention()

    @PermitAll
    @RateLimitExempt(
        reason = "Guest read of a Pot's sealed revisions under a share — the Expenses half of resolveShare's read, as " +
            "listShareMedia is Memories'. Volume is unbounded BY DECISION, not by omission: neither available axis " +
            "fits, and the residual gap is recorded in cc.security.rate-limit (§ Unbounded by decision).",
    )
    override fun listSharePotRevisions(
        shareId: String, nodeId: UUID, xSharePassword: String?, after: String?, limit: Int?, ifNoneMatch: String?,
    ): Response = when (val o = handler.list(shareId, xSharePassword, nodeId.toString())) {
        is SharePotListOutcome.Listed -> {
            val all = o.revisions.map { toGuest(it, retention) }
            val from = after?.let(::afterIndex)?.let { cut -> all.indexOfFirst { key(it) > cut }.let { if (it < 0) all.size else it } } ?: 0
            val max = ItemsPage.limitOf(limit)
            val page = all.drop(from).take(max)
            val more = from + page.size < all.size
            val body = SharePotRevisionPage(revisions = page, more = more, cursor = page.lastOrNull()?.let { "${it.entryId},${it.revision}" })
            if (after != null) {
                Response.ok(body).build() // a later page is never conditional and carries no tag
            } else {
                val tag = SharePotTag.of(all)
                if (ifNoneMatch?.trim() == tag) Response.notModified().build() else Response.ok(body).header("ETag", tag).build()
            }
        }
        is HttpFailure -> httpError(o)
    }

    @PermitAll
    // A guest's batch creates revision rows, so it rides the per-share upload budget, as completeShareFile does.
    @RateLimit(key = RateLimitKey.SHARE)
    override fun appendSharePotRevisions(
        shareId: String, nodeId: UUID, sharePotAppendRequest: SharePotAppendRequest, xSharePassword: String?,
    ): Response {
        val r = sharePotAppendRequest
        return when (
            val o = handler.append(
                shareId, xSharePassword, r.contributorToken, nodeId.toString(), r.nodeEpoch.validated(),
                r.revisions.map(PotsResource::toWrite), client.seen(),
            )
        ) {
            is SharePotAppendOutcome.Written -> PotsResource.respond(o.outcome)
            is HttpFailure -> httpError(o)
        }
    }

    @PermitAll
    @RateLimitExempt(
        reason = "Guest read of a Pot's receipt references under a share — the Expenses half of resolveShare's read, as " +
            "listShareFiles is Depot's. Volume is unbounded BY DECISION, not by omission, recorded in cc.security.rate-limit " +
            "(§ Unbounded by decision) beside listSharePotRevisions.",
    )
    override fun listSharePotReceipts(
        shareId: String, nodeId: UUID, xSharePassword: String?, after: UUID?, limit: Int?, ifNoneMatch: String?,
    ): Response = when (val o = receipts.listShared(shareId, xSharePassword, nodeId.toString())) {
        is ListReceiptsOutcome.Listed -> ReceiptWire.respond(o.receipts.map(ReceiptWire::guest), { it.receiptId }, after, limit, ifNoneMatch) { page, more, cursor ->
            SharePotReceiptPage(receipts = page, more = more, cursor = cursor)
        }
        is HttpFailure -> httpError(o)
    }

    @PermitAll
    // A guest's receipt creates a receipt and a blob row, so it rides the per-share upload budget, as completeShareFile does.
    @RateLimit(key = RateLimitKey.SHARE)
    override fun completeSharePotReceipt(
        shareId: String, nodeId: UUID, sharePotReceiptCompleteRequest: SharePotReceiptCompleteRequest, xSharePassword: String?,
    ): Response {
        val r = sharePotReceiptCompleteRequest
        return when (
            val o = receipts.completeShared(
                shareId, xSharePassword, r.contributorToken, nodeId.toString(), r.entryId.toString(), r.receiptId.toString(),
                r.uploadId.toString(), r.nodeEpoch.validated(), client.seen(),
            )
        ) {
            is CompleteReceiptOutcome.Ok -> Response.ok(ReceiptWire.guest(o.receipt)).build()
            is HttpFailure -> httpError(o)
        }
    }

    private fun key(r: SharePotRevision): Pair<String, Long> = r.entryId.toString() to r.revision

    /** The contract's `after` (`<entryId>,<revision>`, its pattern already held at the boundary). */
    private fun afterIndex(raw: String): Pair<String, Long>? {
        val comma = raw.lastIndexOf(',')
        if (comma <= 0) return null
        return raw.substring(0, comma).lowercase() to (raw.substring(comma + 1).toLongOrNull() ?: return null)
    }

    private operator fun Pair<String, Long>.compareTo(other: Pair<String, Long>): Int =
        compareValuesBy(this, other, { it.first }, { it.second })

    /** The guest's shape — no writer field at all (ADR 0108 decision 6, inbox #190). */
    private fun toGuest(r: PotRevisionEntity, retention: Duration) = SharePotRevision(
        entryId = UUID.fromString(r.entryId),
        revision = r.revision,
        tombstone = r.tombstone,
        erasure = r.erasure,
        sealedBody = r.sealedBody,
        wrappedRevisionKey = r.wrappedRevisionKey,
        createdAt = r.createdAt.atOffset(ZoneOffset.UTC),
        purgeAfter = PotsResource.purgeAfterOf(r, retention),
    )
}

/**
 * The `ETag` of a guest's read of a Pot (ADR 0081 decision 4): a digest of everything the read serves, every page of
 * it — each revision as the generated model names it (its `toString` lists every field, so a field added to the wire is
 * in the tag without anyone remembering it), in the guest's order. Nothing outside the response: it does NOT follow the
 * Pot's item revision, which counts writes. Truncated SHA-256, 128 bits, quoted — `ShareMediaTag`'s form.
 */
internal object SharePotTag {
    fun of(revisions: List<SharePotRevision>): String {
        val sha = MessageDigest.getInstance("SHA-256")
        for (r in revisions) sha.update((r.toString() + "\n").toByteArray(Charsets.UTF_8))
        return "\"" + sha.digest().take(16).joinToString("") { "%02x".format(it) } + "\""
    }
}
