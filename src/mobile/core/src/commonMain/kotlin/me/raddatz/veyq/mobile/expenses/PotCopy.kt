package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A Pot copied to another place in its space (ADR 0082 decision 11, uc.platform.copy-node), the web's copy of a Pot — its
 * walk for a copy (`exportPot`, no CSV) replayed by its import (`importPot`) — with the source read from the open session
 * instead of an archive: what a download would carry, nothing more. The copy is a new Pot under fresh keys: its settings
 * and own categories, its people (a member stays one: the copy is in the same tenant; an erased person comes back as a
 * deactivated one under [deletedName]), the current revision of each live entry under ids minted anew with every reference
 * mapped, and each receipt that revision holds, uploaded under a new id bound to the new Pot BEFORE the batch whose revision
 * lists it. History, deleted entries, dropped or blocked receipts and the cover are not copied (the web's `copyLosses`). A
 * copy that fails part way is trashed, never left half written; its name is the original's, numbered past a sibling's
 * (`Name (2)` … `Name (50)`, the web's `MAX_NAME_TRIES`).
 */
object PotCopy {
    enum class Outcome { COPIED, NOTHING_TO_COPY, NAME_TAKEN, BUSY, BLOCKED, REFUSED, FAILED }

    class Copied(val outcome: Outcome, val nodeId: String? = null, val name: String? = null)

    /** The web's `MAX_NAME_TRIES`: the original's name, then `(2)` up to `(50)`. */
    const val MAX_NAME_TRIES = 50

    @Throws(Exception::class)
    suspend fun duplicate(
        source: PotSession, tenant: TenantSession, scopeId: String, parentId: String?, deletedName: String, uuid: () -> String,
    ): Copied {
        val meta = source.meta ?: return Copied(Outcome.NOTHING_TO_COPY)
        val ledger = source.ledger ?: return Copied(Outcome.NOTHING_TO_COPY)
        val live = ledger.entries.filter { it.status == Ledger.Status.LIVE && it.content != null }
        if (live.any { it.content!!.receipts.isNotEmpty() }) source.readReceipts()
        val listed = source.receipts.associateBy { it.receiptId }

        val ids = HashMap<String, String>()
        val id = { old: String -> ids.getOrPut(old, uuid) }
        // a receipt's ids in a map of their own: an id equal to a record's still gets one of its own
        val receiptIds = HashMap<String, String>()
        val receiptId = { old: String -> receiptIds.getOrPut(old, uuid) }
        val categories = meta.categories.map { (cid, name) -> id(cid) to name }
        for (e in live) id(e.id)

        class Upload(val newId: String, val old: Receipts.Receipt)
        val uploads = HashMap<String, List<Upload>>()
        val money = live.map { e ->
            val c = e.content!!
            val kept = c.receipts.mapNotNull { r -> listed[r]?.takeIf { it.entryId == e.id && !it.dropped && !it.blocked } }
            val mapped = remap(c, id, receiptId, kept.map { it.receiptId })
            if (kept.isNotEmpty()) uploads[id(e.id)] = kept.map { Upload(receiptId(it.receiptId), it) }
            PotWrite(id(e.id), mapped)
        }
        val named = live.flatMap { Ledger.namedParticipants(it.content!!) }.toSet()
        val people = ledger.participants.filter { !it.erased || it.id in named }.map { p ->
            PotWrite(id(p.id), if (p.erased) ParticipantBody(deletedName, false, p.joined) else ParticipantBody(p.name!!, p.active, p.joined, p.member))
        }

        // the original's name, numbered past a sibling's
        var created: PotCreate.Result? = null
        var name = source.name
        for (i in 1..MAX_NAME_TRIES) {
            name = if (i == 1) source.name else "${source.name} ($i)"
            val r = PotCreate.create(tenant, scopeId, parentId, name, meta.base, emptyList(), categories)
            if (r.outcome != PotCreate.Outcome.NAME_TAKEN) {
                created = r
                break
            }
        }
        val made = created ?: return Copied(Outcome.NAME_TAKEN)
        when (made.outcome) {
            PotCreate.Outcome.CREATED -> {}
            PotCreate.Outcome.BUSY -> return Copied(Outcome.BUSY)
            PotCreate.Outcome.BLOCKED -> return Copied(Outcome.BLOCKED)
            PotCreate.Outcome.REFUSED -> return Copied(Outcome.REFUSED)
            else -> return Copied(Outcome.FAILED)
        }
        val nodeId = made.nodeId!!
        val copied = runCatching {
            tenant.forgetTree()
            val pot = PotSession.open(tenant, scopeId, nodeId) ?: return@runCatching false
            (people + money).chunked(PotCreate.BATCH).all { batch ->
                for (w in batch) for (u in uploads[w.entryId].orEmpty()) {
                    val bytes = source.openReceipt(u.old.receiptId) ?: return@all false
                    pot.uploadReceipt(w.entryId, u.newId, bytes, u.old.type.orEmpty(), u.old.name, u.old.modifiedAt)
                }
                if (batch.any { uploads.containsKey(it.entryId) }) pot.readReceipts()
                pot.write(batch) == PotSession.WriteOutcome.WRITTEN
            }
        }.getOrDefault(false)
        if (!copied) {
            // the copy holds only part of the original: none is left behind (best effort, as the web)
            runCatching { NodeTrash.delete(tenant, scopeId, nodeId) }
            return Copied(Outcome.FAILED)
        }
        return Copied(Outcome.COPIED, nodeId, name)
    }

    /** [c] with every person (and the expense a refund refunds) mapped through [id], its own category too, and its receipts
     *  the [kept] ones mapped through [receipt] (the web's `remapEntry`). A default category's id stays. */
    internal fun remap(c: MoneyContent, id: (String) -> String, receipt: (String) -> String, kept: List<String>): MoneyContent {
        val receipts = kept.map(receipt)
        fun split(s: Split): Split = when (s) {
            is Split.Equal -> Split.Equal(s.participants.map(id))
            is Split.Shares -> Split.Shares(s.shares.map { (p, w) -> id(p) to w })
            is Split.Percent -> Split.Percent(s.percents.map { (p, w) -> id(p) to w })
            is Split.Amounts -> Split.Amounts(s.amounts.map { (p, w) -> id(p) to w })
        }
        fun allocation(a: Allocation) = Allocation(a.items.map { it.copy(split = split(it.split)) }, a.rest?.let(::split))
        fun category(cat: String) = if (UUID.matches(cat)) id(cat) else cat
        return when (c) {
            is Expense -> c.copy(category = category(c.category), receipts = receipts,
                payers = c.payers.map { it.copy(participant = id(it.participant)) }, allocation = allocation(c.allocation))
            is Refund -> c.copy(category = category(c.category), receipts = receipts,
                receivers = c.receivers.map { it.copy(participant = id(it.participant)) }, allocation = allocation(c.allocation),
                refunds = c.refunds?.let(id))
            is Transfer -> c.copy(receipts = receipts, from = id(c.from), to = id(c.to))
        }
    }

    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
}
