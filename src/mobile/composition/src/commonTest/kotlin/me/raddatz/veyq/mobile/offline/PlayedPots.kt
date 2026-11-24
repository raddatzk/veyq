package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.expenses.Deleted
import me.raddatz.veyq.mobile.expenses.Ledger
import me.raddatz.veyq.mobile.expenses.PotMeta
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.Receipts
import me.raddatz.veyq.mobile.expenses.RevisionAddress
import me.raddatz.veyq.mobile.expenses.RevisionBody
import me.raddatz.veyq.mobile.expenses.RevisionCodec
import me.raddatz.veyq.mobile.expenses.RevisionContent

// Expenses' half of the played server ([PlayedTenant], `:testing`, which names no application). It lives here, not in
// `:apps:expenses`' tests: most of its readers span Expenses and another application (QueuedTest, QueuedOverlayTest,
// QueuedDepotExpensesTest, OfflinePotPinsTest, OfflineBannerTest), and no module both reach may name an application —
// so OfflinePotTest, Expenses' alone, stays beside them rather than over a second copy (inbox #1260, ADR 0156 entry 78).

/** A Pot in [scope] whose settings are euros (its sealed meta's `pot`), with no revision yet. */
internal fun PlayedTenant.pot(scope: PlayedTenant.Scope, label: String, name: String = "Pot $label"): PlayedTenant.Node =
    node(scope, label, PotSession.KIND, name = name).also {
        it.meta = mapOf(PotMeta.KEY to PotMeta(Ledger.BaseCurrency("EUR", 2), emptyList()).toJson())
    }

/** The next revision of record [entryId] of Pot [pot] appended, carrying [content]; the ledger's revision moves. */
internal fun PlayedTenant.revise(pot: PlayedTenant.Node, entryId: String, content: RevisionContent): PlayedTenant.PotRevision {
    val revision = (pot.revisions.filter { it.entryId == entryId }.maxOfOrNull { it.revision } ?: 0) + 1
    val sealed = RevisionCodec(suite).seal(RevisionAddress(pot.scope.id, pot.id, entryId, revision), pot.key, RevisionBody(emptyList(), content))
    return PlayedTenant.PotRevision(entryId, revision, sealed.sealedBody, sealed.wrappedRevisionKey, content == Deleted).also {
        pot.revisions += it
        pot.ledgerRevision++
    }
}

/** A receipt of entry [entryId] in Pot [pot]: a one-frame blob with a marker, its type sealed; the listing's tag moves. */
internal fun PlayedTenant.receipt(pot: PlayedTenant.Node, label: String, entryId: String, type: String = "image/png"): PlayedTenant.Blob =
    keep(Blob(label, listOf("receipt $label".encodeToByteArray()), Receipts.ROLE, pot.id, { pot.key }, pot.scope.id, type, "$label.png")).also {
        pot.appBlobs += it to entryId
        pot.blobsRevision++
    }
