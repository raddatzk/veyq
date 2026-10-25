package me.raddatz.veyq.expenses.receipt

import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.expenses.generated.model.PotReceipt
import me.raddatz.veyq.expenses.generated.model.SharePotReceipt
import jakarta.ws.rs.core.Response
import java.security.MessageDigest
import java.time.ZoneOffset
import java.util.UUID

/**
 * The receipts' wire, the member's and the guest's (tags `expenses`, `share-expenses`): the two shapes — the guest's has
 * no writer field at all (inbox #190) — and the one paged, tagged answer both reads give.
 */
object ReceiptWire {

    /** The member's shape. */
    fun member(row: ReceiptRow) = PotReceipt(
        receiptId = UUID.fromString(row.receipt.id),
        entryId = UUID.fromString(row.receipt.entryId),
        blobId = UUID.fromString(row.blob.id),
        chunkCount = row.blob.chunkCount,
        byteSize = row.blob.byteSize,
        manifest = row.manifest,
        wrappedBlobKey = row.blob.wrappedBlobKey,
        wrappedBlobMeta = row.blob.wrappedBlobMeta,
        writer = row.receipt.writer.format(),
        createdAt = row.receipt.createdAt.atOffset(ZoneOffset.UTC),
        droppedAt = row.receipt.droppedAt?.atOffset(ZoneOffset.UTC),
        purgeAfter = row.purgeAfter?.atOffset(ZoneOffset.UTC),
        blockedByOperator = row.blocked,
    )

    /** The guest's shape — no writer (ADR 0108 decision 6, inbox #190). */
    fun guest(row: ReceiptRow) = SharePotReceipt(
        receiptId = UUID.fromString(row.receipt.id),
        entryId = UUID.fromString(row.receipt.entryId),
        blobId = UUID.fromString(row.blob.id),
        chunkCount = row.blob.chunkCount,
        byteSize = row.blob.byteSize,
        manifest = row.manifest,
        wrappedBlobKey = row.blob.wrappedBlobKey,
        wrappedBlobMeta = row.blob.wrappedBlobMeta,
        createdAt = row.receipt.createdAt.atOffset(ZoneOffset.UTC),
        droppedAt = row.receipt.droppedAt?.atOffset(ZoneOffset.UTC),
        purgeAfter = row.purgeAfter?.atOffset(ZoneOffset.UTC),
        blockedByOperator = row.blocked,
    )

    /**
     * One page of [all] (already in id order) past [after], at most [limit], built by [page]; a read without [after]
     * carries an `ETag` that is a digest of everything the read serves, every page of it, and answers `304` when
     * [ifNoneMatch] equals it (ADR 0081 decision 4: nothing outside the response, never the Pot's item revision). Each
     * receipt enters the digest as the generated model names it (its `toString` lists every field, so a field added to
     * the wire is in the tag without anyone remembering it) — truncated SHA-256, 128 bits, quoted, `ShareMediaTag`'s form.
     */
    fun <T : Any> respond(
        all: List<T>, idOf: (T) -> UUID, after: UUID?, limit: Int?, ifNoneMatch: String?, page: (List<T>, Boolean, String?) -> Any,
    ): Response {
        val from = after?.let { cut -> all.indexOfFirst { idOf(it).toString() > cut.toString() }.let { if (it < 0) all.size else it } } ?: 0
        val shown = all.drop(from).take(ItemsPage.limitOf(limit))
        val body = page(shown, from + shown.size < all.size, shown.lastOrNull()?.let { idOf(it).toString() })
        if (after != null) return Response.ok(body).build() // a later page is never conditional and carries no tag
        val sha = MessageDigest.getInstance("SHA-256")
        for (r in all) sha.update((r.toString() + "\n").toByteArray(Charsets.UTF_8))
        val tag = "\"" + sha.digest().take(16).joinToString("") { "%02x".format(it) } + "\""
        return if (ifNoneMatch?.trim() == tag) Response.notModified().build() else Response.ok(body).header("ETag", tag).build()
    }
}
