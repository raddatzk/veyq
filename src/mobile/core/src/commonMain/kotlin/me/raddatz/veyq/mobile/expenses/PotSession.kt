package me.raddatz.veyq.mobile.expenses

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.api.expenses.apis.ExpensesApi
import me.raddatz.veyq.mobile.api.models.UpdateNodeRequest
import me.raddatz.veyq.mobile.api.expenses.models.PotAppendRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import me.raddatz.veyq.mobile.session.NodeEpochWrite
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.NodeRekey
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * One Pot opened by a member or a link's guest (ADR 0150 decision 5: through a [PotDoor] — a member's [TenantPotDoor], a
 * guest's `GuestPotDoor`), a port of `src/web/src/features/expenses/potSession.ts`: its settings from the node's
 * sealed meta, its revisions read page by page and merged by id, each opened at the address it was read by, the ledger
 * and the ledger check over them, and every write. Expenses is an application: it uses core's session, never the reverse.
 */
class PotSession internal constructor(private val door: PotDoor, private var node: OpenNode) {
    private val suite = door.suite
    private val codec = RevisionCodec(suite)

    /** The Pot's settings, or null when its meta carries none that reads (no balances, no money entries then). */
    var meta: PotMeta? = openMeta()
        private set

    val name: String get() = node.name

    private val held = LinkedHashMap<String, HeldRevision>()
    private var ledgerTag: String? = null
    private var deleteRevision = 0
    private var cursor: String? = null

    /** The ledger over what is held, or null without settings. */
    var ledger: Ledger.Pot? = null
        private set

    /** What the last read found wrong with the ledger (ADR 0108 decision 5); complete for an empty Pot. */
    var check: LedgerCheck = LedgerCheck.of(emptyList())
        private set

    /**
     * Shown from the offline store, read-only, its ledger as of when (ADR 0153 decision 6, ADR 0140 decision 5): the kept
     * whole ledger read while the server was unreachable, or the account gone offline since the last read ([wentOffline]);
     * null while the server answers.
     */
    var offline: me.raddatz.veyq.mobile.session.OfflineAsOf? = null
        private set

    /** When the server last answered a read of the ledger (the device's clock), what [wentOffline] says it is as of. */
    private var readAt: Long? = null

    /** Whether this member may write here: Expenses is on for the tenant (a space pending deletion is not opened), online. */
    val canWrite: Boolean get() = meta != null && door.writesEntries && offline == null

    /** Whether this member may change the Pot's settings, a Pot without any included: Expenses is on for the tenant, online. */
    val canChangeSettings: Boolean get() = door.changesSettings && offline == null

    /** The account went offline with the Pot open: it shows what it read, read-only, as of its last read. */
    internal fun wentOffline() {
        if (offline == null) offline = me.raddatz.veyq.mobile.session.OfflineAsOf(readAt)
    }

    /** Whether a member reads the Pot ([PotDoor.member]); false for a link's guest. */
    val member: Boolean get() = door.member

    /** The waits before each retry while the Pot is not re-keyed since a rotation (`REKEY_RETRY_DELAYS_MS`). */
    internal var rekeyWaitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS

    private fun openMeta(): PotMeta? = PotMeta.opened(suite, node)

    /** The tenant a member id of this Pot means a person in, for a download (`PotExport`). */
    internal val tenantId: String get() = door.tenantId

    /** What is held, for a pure rule over it. */
    internal fun heldRevisions(): List<HeldRevision> = held.values.toList()

    /**
     * Reads what changed since the last read (the first one reads everything): the first page conditional on the held
     * ledger revision, later pages by cursor; a page after an erasure names the revisions still held. True when anything
     * moved. One read at a time: two at once would each set the cursor and revisions they ended at, and an older one
     * finishing last would set them back — or, after an erasure, drop what the newer one added.
     */
    @Throws(Exception::class)
    suspend fun refresh(): Boolean = reading.withLock { read() }

    /**
     * One tick of the live poll (the web's `potSession.ts` poll): a delta read, and the receipts read again when it moved
     * or the last receipt read failed. Whether anything moved; null when skipped, while another read runs (a write's
     * re-read): that one brings it all. A failed read throws, which backs the poll off.
     */
    @Throws(Exception::class)
    suspend fun poll(): Boolean? {
        if (reading.isLocked) return null
        val moved = refresh()
        val receiptsMoved = (moved || receiptsDue) && readReceipts()
        return moved || receiptsMoved
    }


    /**
     * The ledger read whole after an own write (#1038): the whole read is the kept one (ADR 0153 decision 6), so it
     * replaces the kept copy — never merged — and what the person just wrote is there when the device goes offline. A
     * delta read would show it here but keep nothing. A guest's door reads whole anyway.
     */
    private suspend fun readWhole(): Boolean = reading.withLock {
        cursor = null
        read()
    }

    private val reading = Mutex()
    private val receiptsReading = Mutex()
    private var receiptsDue = false

    private suspend fun read(): Boolean {
        val r = door.read(node, ledgerTag, cursor, deleteRevision) ?: run {
            // the server answered: the ledger held is current, and online again where it was not
            readAt = nowMs()
            return (offline != null).also { offline = null }
        }
        offline = r.offline
        if (r.offline == null) readAt = nowMs()
        // a whole read (a guest's, the link's whole answer; a member's open, kept offline) replaces what is held, never merges
        if (r.whole) held.clear()
        r.keep?.let { ids -> held.keys.retainAll(ids) }
        // A re-served id (a re-key's new wrap) replaces the held row.
        Revisions.open(codec, node.scopeId, node.id, node.key, r.rows).forEach { held[it.id] = it }
        ledgerTag = r.tag
        deleteRevision = r.deleteRevision
        cursor = r.cursor
        val all = held.values.toList()
        ledger = meta?.let { Ledger.build(all, it.base) }
        check = LedgerCheck.of(all)
        return true
    }

    /** What a write came to. */
    enum class WriteOutcome {
        WRITTEN,
        /** Its sums do not add up, it names someone who is no participant, or it breaks another rule: nothing was sent. */
        INVALID,
        /** A person with a balance cannot be deactivated or deleted: nothing was sent. */
        NOT_SETTLED,
        /** Another device wrote first: the Pot was read again, the write may be made again on what it shows now. */
        STALE,
        /** The Pot is not re-keyed since its space's key changed, and still was not after waiting: try again later. */
        BUSY,
        /** The operator blocked the Pot. */
        BLOCKED,
        /** The Pot is shown offline (ADR 0153 decision 6): nothing was sent; a write waits for the server (`offline_read_only`). */
        OFFLINE,
        /** The server refused it otherwise (switched off, removed, an id taken): nothing was stored. */
        REFUSED,
    }

    /**
     * Appends [writes] in one batch, all or none ([PotWrites.seal] on what is held), then reads again. A `409
     * stale_revision` re-reads and answers [WriteOutcome.STALE]; the rest is [atNodeEpoch]'s.
     */
    @Throws(Exception::class)
    suspend fun write(writes: List<PotWrite>): WriteOutcome {
        if (offline != null) return WriteOutcome.OFFLINE
        val base = meta?.base ?: return WriteOutcome.INVALID
        return atNodeEpoch { n ->
            val revisions = PotWrites.seal(codec, n.scopeId, n.id, n.key, held.values.toList(), writes, base)
            suspend { door.append(n, revisions) }
        }
    }

    /** Deletes or removes participant [participantId] by its sealed erasure (members only; settled people only). */
    @Throws(Exception::class)
    suspend fun erase(participantId: String): WriteOutcome {
        if (offline != null) return WriteOutcome.OFFLINE
        val base = meta?.base ?: return WriteOutcome.INVALID
        return atNodeEpoch { n ->
            val request = PotWrites.erasure(codec, n.scopeId, n.id, n.key, n.epoch, held.values.toList(), participantId, base)
            suspend { door.erase(n, request) }
        }
    }

    /**
     * A write at the Pot's epoch through core's [NodeEpochWrite] (the floor, `node_rekey_pending` and `stale_epoch`
     * handled there); [prepare] seals on the node it is handed, so an attempt after a re-read seals under its key now.
     */
    private suspend fun atNodeEpoch(prepare: (OpenNode) -> (suspend () -> PotDoor.Sent)): WriteOutcome {
        val result = try {
            NodeEpochWrite.run(
                waitsMs = rekeyWaitsMs,
                belowFloor = { door.belowFloor(node) },
                reread = ::reopen,
                pull = { door.pull(node) },
                attempt = {
                    val send = prepare(node)
                    val r = send()
                    val code = r.code
                    val answer = when {
                        r.status in 200..299 -> NodeEpochWrite.Answer.DONE
                        r.status == 409 && code == NODE_REKEY_PENDING -> NodeEpochWrite.Answer.REKEY_PENDING
                        r.status == 409 && code == STALE_EPOCH -> NodeEpochWrite.Answer.STALE_EPOCH
                        else -> NodeEpochWrite.Answer.OTHER
                    }
                    answer to (r.status to code)
                },
            )
        } catch (e: PotWriteRefused) {
            return if (e.reason == PotWriteRefused.Reason.NOT_SETTLED) WriteOutcome.NOT_SETTLED else WriteOutcome.INVALID
        } catch (e: RevisionFormatInvalid) {
            return WriteOutcome.INVALID
        } catch (e: IllegalArgumentException) {
            return WriteOutcome.INVALID
        }
        return when (result) {
            NodeEpochWrite.Result.Busy -> WriteOutcome.BUSY
            NodeEpochWrite.Result.Gone, NodeEpochWrite.Result.StaleAgain -> WriteOutcome.REFUSED
            is NodeEpochWrite.Result.Answered -> {
                val (status, code) = result.value
                when {
                    status in 200..299 -> {
                        readWhole()
                        WriteOutcome.WRITTEN
                    }
                    status == 409 && code == STALE_REVISION -> {
                        runCatching { refresh() }
                        WriteOutcome.STALE
                    }
                    status == 451 -> WriteOutcome.BLOCKED
                    else -> WriteOutcome.REFUSED
                }
            }
        }
    }

    /**
     * Reads the tree again and takes the Pot's node as it is now (its key and epoch may have moved), its settings with it,
     * and everything held read anew under that key. False when the Pot is no longer there to write to.
     */
    private suspend fun reopen(): Boolean {
        node = door.reread(node) ?: return false
        meta = openMeta()
        held.clear()
        ledgerTag = null
        deleteRevision = 0
        cursor = null
        refresh()
        return true
    }

    /**
     * The Pot was re-keyed on this device (a link to it switched off): its node taken as the tree holds it now, its new
     * key with it, and everything held read anew under that key — under the read lock, so a live poll in flight does not
     * interleave. False when the Pot is no longer there.
     */
    @Throws(Exception::class)
    suspend fun rekeyed(): Boolean = reading.withLock {
        node = door.reread(node) ?: return@withLock false
        meta = openMeta()
        held.clear()
        ledgerTag = null
        deleteRevision = 0
        cursor = null
        read()
    }

    /** A receipt picked in the editor, uploaded only when the entry is saved (`EntryEditor.vue`): [id] minted at pick. */
    class PendingReceipt(val id: String, internal val bytes: ByteArray, val type: String, val name: String?, val modifiedAt: Long?) {
        val size: Long get() = bytes.size.toLong()
    }

    /** The names of the receipts the last save left out because the space's storage is full; the entry was saved. */
    var skippedReceipts: List<String> = emptyList()
        private set

    /** The ids of the receipts the last save uploaded, whatever it came to: a save again lists them, never uploads them twice. */
    var uploadedReceipts: List<String> = emptyList()
        private set

    /**
     * Saves the editor's [form] as entry [entryId]: revision 1 of a fresh record when [basedOn] is null, else the next
     * revision on [basedOn], the revision the editor showed — so a revision another device wrote meanwhile is a `409`,
     * answered [WriteOutcome.STALE] and read again, never overwritten unseen. [pending] receipts are uploaded first (one
     * the storage has no room for is left out, [skippedReceipts]; any other failure stops the save); the revision lists
     * the form's receipts and those uploaded, and names the ones it drops ([Receipts.toDrop]). A form that forms no entry
     * is [WriteOutcome.INVALID] and sends nothing. [own] are receipts an earlier save of the same editor uploaded (the drop
     * rule answers for them as for its own).
     */
    @Throws(Exception::class)
    suspend fun saveEntry(entryId: String?, form: EntryForm, basedOn: Long?, pending: List<PendingReceipt> = emptyList(), own: Set<String> = emptySet()): WriteOutcome {
        skippedReceipts = emptyList()
        uploadedReceipts = emptyList()
        if (offline != null) return WriteOutcome.OFFLINE
        val known = ledger?.participants?.map { it.id }?.toSet() ?: return WriteOutcome.INVALID
        if (form.contentOf { it in known }.content == null) return WriteOutcome.INVALID
        if (pending.any { it.bytes.size > Receipts.MAX_BYTES }) return WriteOutcome.INVALID
        if (form.receipts.size + pending.size > Receipts.MAX_PER_ENTRY) return WriteOutcome.INVALID
        val id = entryId ?: newId()
        val uploaded = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (r in pending) {
            try {
                door.receipts.upload({ node }, ::reopen, r.id, id, r.bytes, r.type, r.name, r.modifiedAt)
                uploaded += r.id
                uploadedReceipts = uploaded.toList()
            } catch (e: ApplicationBlobs.UploadRefused) {
                if (e.code == "quota_exceeded") skipped += r.name ?: Receipts.downloadName(null, r.type) else return WriteOutcome.REFUSED
            }
        }
        skippedReceipts = skipped
        val kept = form.receipts + uploaded
        val content = form.copy(receipts = kept).contentOf { it in known }.content ?: return WriteOutcome.INVALID
        if (uploaded.isNotEmpty()) readReceipts()
        val shown = basedOn?.let { b -> held.values.firstOrNull { it.entryId == id && it.revision == b } }
        val on = shown?.let { s -> ((s.body?.content as? MoneyContent)?.receipts.orEmpty()) to s.createdAt }
        val dropped = Receipts.toDrop(receipts, id, kept, on, uploaded.toSet() + own)
        return write(listOf(PotWrite(id, content, basedOn = basedOn, droppedReceipts = dropped))).also { if (it == WriteOutcome.WRITTEN) readReceipts() }
    }

    private var receiptsTag: String? = null

    /** Uploads [bytes] as receipt [receiptId] of entry [entryId], which a write then lists (a copy's receipts, written ahead
     *  of the batch holding their entry). Throws `ApplicationBlobs.UploadRefused` with the server's code. */
    internal suspend fun uploadReceipt(entryId: String, receiptId: String, bytes: ByteArray, type: String, name: String?, modifiedAt: Long?) {
        door.receipts.upload({ node }, ::reopen, receiptId, entryId, bytes, type, name, modifiedAt)
    }

    /** Every receipt the Pot holds, each with its sealed name and type where they opened, dropped ones included. */
    var receipts: List<Receipts.Receipt> = emptyList()
        private set

    /** Reads the Pot's receipts again, conditional on the listing's tag. True when anything moved. */
    @Throws(Exception::class)
    suspend fun readReceipts(): Boolean = receiptsReading.withLock { readReceiptsOnce() }

    private suspend fun readReceiptsOnce(): Boolean {
        val listing = runCatching { door.receipts.list(node, receiptsTag) }.getOrElse {
            receiptsDue = true
            return false
        }
        receiptsDue = false
        if (listing == null) return false
        receiptsTag = listing.etag
        val n = node
        receipts = listing.blobs.filter { it.role == Receipts.ROLE }.map { b ->
            val meta = door.receipts.metaOf(n, b)
            Receipts.Receipt(b.id, b.ownerId, b, meta?.name, meta?.type, b.size, b.createdAt, b.dropped, b.blocked, meta?.modifiedAt)
        }
        return true
    }

    /**
     * A receipt's bytes as the viewer shows them, opened under the Pot's key; a blocked receipt opens nothing. Its frames
     * are kept in the offline store (ADR 0153 decision 6: a receipt shown opens offline, as a Depot file's do).
     */
    @Throws(Exception::class)
    suspend fun openReceipt(receiptId: String): ByteArray? = readReceipt(receiptId, keep = true)

    /**
     * A receipt's bytes read for another purpose than showing it — a download, a copy (`PotExport`, `PotCopy`) — where
     * [keep] is false: they keep nothing on the device.
     */
    internal suspend fun readReceipt(receiptId: String, keep: Boolean): ByteArray? {
        val r = receipts.firstOrNull { it.receiptId == receiptId } ?: return null
        if (r.blocked) return null
        return runCatching { door.receipts.download(node, r.listed, keep) }.getOrNull()
    }

    /** Deletes entry [entryId] on [basedOn], the revision shown: a tombstone revision; it counts nowhere and stays in the
     *  Pot's list of deleted entries, restorable until its receipts are erased. */
    @Throws(Exception::class)
    suspend fun deleteEntry(entryId: String, basedOn: Long): WriteOutcome =
        write(listOf(PotWrite(entryId, Deleted, basedOn = basedOn)))

    /** Restores deleted entry [entryId] on [basedOn]: its last content written again as the next revision, while it is
     *  still restorable ([PotView.restorable]). */
    @Throws(Exception::class)
    suspend fun restoreEntry(entryId: String, basedOn: Long): WriteOutcome {
        val revisions = held.values.filter { it.entryId == entryId }.sortedBy { it.revision }
        if (revisions.lastOrNull()?.body?.content != Deleted) return WriteOutcome.INVALID
        val content = revisions.asReversed().firstNotNullOfOrNull { it.body?.content as? MoneyContent } ?: return WriteOutcome.INVALID
        return write(listOf(PotWrite(entryId, content, basedOn = basedOn)))
    }

    /**
     * Records settle-up suggestions as paid (the overview's "record payment"): one transfer each, in the base currency at
     * rate 1, dated [date] (`YYYY-MM-DD`), all in one batch.
     */
    @Throws(Exception::class)
    suspend fun recordPayments(payments: List<PotView.SettleRow>, date: String): WriteOutcome {
        val base = meta?.base ?: return WriteOutcome.INVALID
        if (payments.isEmpty()) return WriteOutcome.INVALID
        return write(payments.map {
            PotWrite(newId(), Transfer(null, date, base.currency, base.minorUnits, "1", it.amount, emptyList(), it.fromId, it.toId))
        })
    }

    /** Whether the tenant names an exchange-rate service: the editor then fills another currency's empty rate. */
    val ratesOffered: Boolean get() = door.ratesTemplate() != null

    /**
     * One unit of [currency] in the Pot's base from [day]'s table (`YYYY-MM-DD`, the device's date), and the day of the
     * rows it is computed from; null when the tenant names no service, the day's one request failed, or the table lacks
     * either currency — the rate is typed then.
     */
    @Throws(Exception::class)
    suspend fun rateFor(currency: String, day: String): ExchangeRates.Filled? {
        val base = meta?.base?.currency ?: return null
        if (currency == base) return null
        val template = door.ratesTemplate() ?: return null
        val table = door.dayTable(template, day) ?: return null
        return ExchangeRates.crossRate(table, currency, base)
    }

    /** The tenant's members by user id, for the history's writers (a member's read carries `user:{id}`). */
    internal val directory: Map<String, String> get() = door.directory

    /** The base currency is fixed once the Pot holds any money entry, live or deleted: every entry carries its rate to it. */
    val currencyFixed: Boolean get() = held.values.any { it.body?.content is MoneyContent }

    /**
     * Writes the Pot's settings: [change] maps the settings as the server holds them (null for none that read) to the
     * new ones, or to null for no write; it runs again on the blob a `409 stale_revision` hands back, so two members
     * changing the settings at once both keep theirs (ADR 0077). Through core's [NodeMetaWrite], at the Pot's epoch.
     */
    @Throws(Exception::class)
    suspend fun writeMeta(change: (PotMeta?) -> PotMeta?): WriteOutcome {
        if (offline != null) return WriteOutcome.OFFLINE
        val result = try {
            NodeEpochWrite.run(
                waitsMs = rekeyWaitsMs,
                belowFloor = { door.belowFloor(node) },
                reread = ::reopen,
                pull = { door.pull(node) },
                attempt = {
                    val n = node
                    val r = NodeMetaWrite.write(
                        NodeMetaWrite.State(n.wrappedMeta, n.metaRevision),
                        open = { NodeMetaWrite.openForWrite(suite, n.scopeId, n.id, n.key, it) },
                        seal = { NodeMetaWrite.seal(suite, n.scopeId, n.id, n.key, it) },
                        edit = { envelope ->
                            change(PotMeta.of(envelope))?.let { JsonObject(envelope + (PotMeta.KEY to it.toJson())) }
                        },
                        send = { wrapped, revision -> door.updateMeta(n, wrapped, revision) },
                    )
                    val answer = when {
                        r !is NodeMetaWrite.Result.Refused -> NodeEpochWrite.Answer.DONE
                        r.status == 409 && r.code == NODE_REKEY_PENDING -> NodeEpochWrite.Answer.REKEY_PENDING
                        r.status == 409 && r.code == STALE_EPOCH -> NodeEpochWrite.Answer.STALE_EPOCH
                        else -> NodeEpochWrite.Answer.OTHER
                    }
                    answer to r
                },
            )
        } catch (e: NodeMetaWrite.Unreadable) {
            return WriteOutcome.REFUSED
        } catch (e: IllegalArgumentException) {
            return WriteOutcome.INVALID
        }
        return when (result) {
            NodeEpochWrite.Result.Busy -> WriteOutcome.BUSY
            NodeEpochWrite.Result.Gone, NodeEpochWrite.Result.StaleAgain -> WriteOutcome.REFUSED
            is NodeEpochWrite.Result.Answered -> when (val r = result.value) {
                is NodeMetaWrite.Result.Wrote -> {
                    reopen()
                    WriteOutcome.WRITTEN
                }
                is NodeMetaWrite.Result.Unchanged -> WriteOutcome.WRITTEN
                is NodeMetaWrite.Result.Refused -> if (r.status == 451) WriteOutcome.BLOCKED else WriteOutcome.REFUSED
            }
        }
    }

    /** Sets the base currency ([minorUnits] the platform's digits for it, 0 to 4) while the Pot holds no money entry. */
    @Throws(Exception::class)
    suspend fun setBaseCurrency(currency: String, minorUnits: Int): WriteOutcome {
        if (currencyFixed || !RevisionFormat.CURRENCY.matches(currency) || minorUnits !in 0..4) return WriteOutcome.INVALID
        return writeMeta { current ->
            if (current?.base?.currency == currency) null
            else PotMeta(Ledger.BaseCurrency(currency, minorUnits), current?.categories.orEmpty())
        }
    }

    /** Adds an own category (1 to 100 characters; at most 200), picked for any entry of this Pot after the defaults. */
    @Throws(Exception::class)
    suspend fun addCategory(name: String): WriteOutcome {
        val n = name.trim().takeIf { RevisionFormat.codePoints(it) in 1..PotMeta.MAX_NAME } ?: return WriteOutcome.INVALID
        if (meta == null) return WriteOutcome.INVALID
        val id = newId()
        return writeMeta { current ->
            val now = current ?: return@writeMeta null
            if (now.categories.size >= PotMeta.MAX_OWN) throw IllegalArgumentException("/categories")
            now.copy(categories = now.categories.filter { it.first != id } + (id to n))
        }
    }

    /** Renames own category [id]; never removed in the first cut (an entry names it by id). */
    @Throws(Exception::class)
    suspend fun renameCategory(id: String, name: String): WriteOutcome {
        val n = name.trim().takeIf { RevisionFormat.codePoints(it) in 1..PotMeta.MAX_NAME } ?: return WriteOutcome.INVALID
        return writeMeta { current ->
            val now = current ?: return@writeMeta null
            if (now.categories.none { it.first == id }) return@writeMeta null
            now.copy(categories = now.categories.map { if (it.first == id) id to n else it })
        }
    }

    /** The Pot's space's current members, the directory a member participant reads by (its current name, or a former
     *  member once it lists them no more); null until read, or when the read failed. */
    var members: List<Person>? = null
        private set

    /** Reads the space's current members again. */
    @Throws(Exception::class)
    suspend fun readMembers() {
        members = runCatching { door.members(node.scopeId) }.getOrNull()
    }

    /** Every person a revision the Pot holds names, live or deleted: one of them is deleted, never removed. */
    val named: Set<String>
        get() = held.values.flatMap { r -> (r.body?.content as? MoneyContent)?.let(Ledger::namedParticipants).orEmpty() }.toSet()

    /** Adds a person by a name typed (1 to 100 characters), joining now. */
    @Throws(Exception::class)
    suspend fun addPerson(name: String): WriteOutcome {
        val n = name.trim().takeIf { RevisionFormat.codePoints(it) in 1..MAX_NAME } ?: return WriteOutcome.INVALID
        return write(listOf(PotWrite(newId(), ParticipantBody(n, true, nowMs()))))
    }

    /**
     * Adds member [userId] of the space as a person, joining now: their id and, sealed with it, their display name as it
     * is now (cut to 100 characters) — what a reader without the directory (a link guest) shows.
     */
    @Throws(Exception::class)
    suspend fun addMember(userId: String): WriteOutcome {
        val m = members?.firstOrNull { it.userId == userId } ?: return WriteOutcome.INVALID
        return write(listOf(PotWrite(newId(), ParticipantBody(cut(m.displayName, MAX_NAME), true, nowMs(), m.userId))))
    }

    /** Renames a person added by name (a member's name is the member's own and is never renamed). */
    @Throws(Exception::class)
    suspend fun rename(participantId: String, name: String): WriteOutcome {
        val p = participant(participantId) ?: return WriteOutcome.INVALID
        if (p.member != null) return WriteOutcome.INVALID
        val n = name.trim().takeIf { RevisionFormat.codePoints(it) in 1..MAX_NAME } ?: return WriteOutcome.INVALID
        return write(listOf(PotWrite(p.id, ParticipantBody(n, p.active, p.joined))))
    }

    /** Deactivates a settled person, or activates one again; the revision carries what never changes. */
    @Throws(Exception::class)
    suspend fun setActive(participantId: String, active: Boolean): WriteOutcome {
        val p = participant(participantId) ?: return WriteOutcome.INVALID
        return write(listOf(PotWrite(p.id, ParticipantBody(p.name ?: "", active, p.joined, p.member))))
    }

    private fun participant(id: String) = ledger?.participants?.firstOrNull { it.id == id && !it.erased }

    /** A fresh record id (a UUID v4), for an entry or a person this device adds. */
    fun newId(): String {
        val b = suite.p.randomBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = hex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    companion object {
        internal const val PAGE = 1000
        const val KIND = "EXPENSES_POT"
        private const val NODE_REKEY_PENDING = "node_rekey_pending"
        private const val STALE_EPOCH = "stale_epoch"
        private const val STALE_REVISION = "stale_revision"

        /** Opens the Pot [nodeId] of [scopeId] in [tenant] and reads it; null when the tenant holds no such open Pot. */
        suspend fun open(tenant: TenantSession, scopeId: String, nodeId: String): PotSession? {
            val node = tenant.trees()[scopeId]?.firstOrNull { it.id == nodeId && it.kind == KIND } ?: return null
            return open(TenantPotDoor(tenant), node)
        }

        /** Opens the Pot [node] through [door] (a member's [TenantPotDoor], a link guest's) and reads it. */
        internal suspend fun open(door: PotDoor, node: OpenNode): PotSession =
            PotSession(door, node).also {
                it.refresh()
                it.readMembers()
                it.readReceipts()
            }

        private const val MAX_NAME = 100

        /** [s] cut to its first [n] code points (a surrogate pair is never split). */
        internal fun cut(s: String, n: Int): String {
            var i = 0
            var count = 0
            while (i < s.length && count < n) {
                i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
                count++
            }
            return s.substring(0, i)
        }

        @OptIn(kotlin.time.ExperimentalTime::class)
        internal fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

        internal suspend fun codeOf(r: HttpResponse<*>): String? = codeIn(runCatching { r.response.bodyAsText() }.getOrDefault(""))

        internal fun codeIn(body: String): String? = runCatching {
            (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content
        }.getOrNull()

        @OptIn(kotlin.time.ExperimentalTime::class)
        internal fun epochMs(iso: String): Long = runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0)
    }
}
