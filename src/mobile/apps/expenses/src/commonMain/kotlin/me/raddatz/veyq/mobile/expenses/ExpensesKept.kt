package me.raddatz.veyq.mobile.expenses

import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.PlatformApi
import me.raddatz.veyq.mobile.api.expenses.models.PotRevisionPage
import me.raddatz.veyq.mobile.offline.ApplicationKept
import me.raddatz.veyq.mobile.offline.EntryKey
import me.raddatz.veyq.mobile.offline.KeptRead
import me.raddatz.veyq.mobile.offline.KeptReach

/** Expenses' kept read (ADR 0153 decision 6): a Pot's ledger; its receipts are its application blobs, which core keeps. */
@PlatformApi
@HiddenFromObjC
object ExpensesKept : ApplicationKept {
    /**
     * Per Pot: its whole ledger, every page of a read without `since`, `ledgerRevision` its revision. Kept by each whole
     * read — the Pot's open and a pin's sync —, replaced and never merged; the live poll's deltas are not kept, so an entry
     * a co-member deleted or an erasure dropped is gone from the next whole answer.
     */
    val POT_REVISIONS = KeptRead("listPotRevisions", PotRevisionPage.serializer())

    override val reads = listOf(POT_REVISIONS)
    override val listings = listOf(POT_REVISIONS)

    override suspend fun below(at: KeptReach, node: String, into: MutableSet<EntryKey>) {
        into += at.key(POT_REVISIONS, node)
    }
}
