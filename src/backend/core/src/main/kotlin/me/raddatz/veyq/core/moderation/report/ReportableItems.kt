package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.moderation.hold.HeldRow

/**
 * Where an application's item lies — the space and the node it sits on — so core can check a report's reach
 * (uc.moderation.report) without naming an application table (ADR 0079). One implementation per application that keeps
 * records inside nodes (Memories: a medium); [locate] answers null for an id that is not one of its items, or whose row
 * is gone. Core asks every implementation and takes the single answer; two that both claim an id are a defect, refused
 * as no item at all.
 */
interface ReportableItems {
    fun locate(itemId: String): ReportableItem?

    /**
     * Every blob of the item [itemId] — trashed or not — or none for an id that is not one of this application's items:
     * what a content block on the item refuses to serve (uc.moderation.take-a-measure; `ModerationBlocks.blobBlocked`).
     */
    fun blobIdsOf(itemId: String): Set<String>

    /**
     * A content block on the item [itemId] was taken or lifted: the application tells its live lists, so a member's and a
     * guest's open view learn the item's new `blockedByOperator` (its keyset delta and revision, ADR 0033). Joins the
     * caller's transaction; an id that is not one of this application's items is no-op.
     */
    fun blockChanged(itemId: String)

    /**
     * Lock the row of the item [itemId] and answer whether it exists — trashed or not, as only its purge erases it — or
     * false for an id that is not one of this application's items. **Joins the caller's transaction**: a measure-taker's
     * half of the race with the item's purge (`MeasureTargets.lockStanding`), so the row's lock is the one every purge
     * of the item takes.
     */
    fun lockExisting(itemId: String): Boolean

    /**
     * The storage keys of the item [itemId]'s stored objects as a hold copied them ([held], its case's snapshot) — its row
     * and its blobs' rows, read after the item itself is gone — or none for an id that is not one of this application's
     * items. What a measure taken on the erased item hangs on where another row still names one (a cover or a banner
     * sharing the medium's object, `MeasureTargets.survivingObjects`, ADR 0093 § 6).
     */
    fun heldObjectsOf(itemId: String, held: List<HeldRow>): Set<String>
}

/** An item's place: its space and the node it sits on. */
data class ReportableItem(val scopeId: String, val nodeId: String)
