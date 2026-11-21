package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.offline.Queued
import me.raddatz.veyq.mobile.offline.QueuedBlob
import me.raddatz.veyq.mobile.offline.QueuedNodes
import me.raddatz.veyq.mobile.offline.QueuedOutcome
import me.raddatz.veyq.mobile.offline.QueuedSender
import me.raddatz.veyq.mobile.offline.QueuedWrite

/**
 * A Pot's entries added, edited and deleted offline (ADR 0153 decision 3's Expenses row), queued as the person's intent and
 * sent through the online save ([PotSession.sendQueued]): the revision written on the one the person was shown
 * ([Entry.basedOn]; a new entry's first), naming **the heads the person read** — never the ones held at the send —, its
 * receipts sealed at once under their own `K_blob`s and uploaded before the revision, as online.
 *
 * - **One write per entry** (its id `entry:<entryId>`): a second change of an entry still waiting replaces the first and
 *   keeps what the first saw (the revision and the heads); a delete of an entry made offline drops it from the queue.
 * - **Sending:** a revision someone else wrote meanwhile (`409 stale_revision`) is held for the person, whose values stay
 *   in the write ([entry]) for the conflict's editor, beside the newer version the Pot now shows — unless the revision the
 *   server holds is this very one (a send whose answer was lost): then it is sent. A receipt the storage has no room for
 *   refuses the write (`quota_exceeded`): the person discards it or saves a copy. A Pot gone or switched off refuses it.
 */
object QueuedExpenses {
    @Serializable
    @PlatformApi
    @HiddenFromObjC
    data class ReceiptIntent(val id: String, val blob: String, val type: String, val name: String? = null, val modifiedAt: Long? = null)

    @Serializable
    @PlatformApi
    @HiddenFromObjC
    data class EntryIntent(val entryId: String, val basedOn: Long? = null, val body: JsonObject, val receipts: List<ReceiptIntent> = emptyList())

    /** A queued entry as the person left it: [content] their values ([Deleted] for a delete), on [basedOn] (null: a new one). */
    class Entry internal constructor(val entryId: String, val basedOn: Long?, val content: RevisionContent, val receipts: Int)

    /** Write [write]'s entry, or null where it is no queued Pot entry. */
    fun entry(write: QueuedWrite): Entry? {
        if (write.op != Queued.POT_ENTRY.name) return null
        val i = runCatching { QueuedNodes.decode(EntryIntent.serializer(), write) }.getOrNull() ?: return null
        val body = runCatching { RevisionFormat.read(i.body) }.getOrNull() ?: return null
        return Entry(i.entryId, i.basedOn, body.content, i.receipts.size)
    }

    /** The name and type receipt blob [blobId] of queued entry [write] is saved under (*Save a copy*); null where it is none. */
    @PlatformApi
    @HiddenFromObjC
    fun receiptOf(write: QueuedWrite, blobId: String): Pair<String?, String?>? {
        if (write.op != Queued.POT_ENTRY.name) return null
        val i = runCatching { QueuedNodes.decode(EntryIntent.serializer(), write) }.getOrNull() ?: return null
        return i.receipts.firstOrNull { it.blob == blobId }?.let { it.name to it.type }
    }

    private fun idOf(entryId: String) = ENTRY + entryId

    /**
     * The receipts held entry [write] carries that the server has not seen, opened under their `K_blob`s into memory as a
     * picked receipt is held (`PendingReceipt`) for the held entry's editor; none where one does not open.
     */
    internal suspend fun heldReceipts(account: me.raddatz.veyq.mobile.session.VeyqAccount, write: QueuedWrite): List<PotSession.PendingReceipt> {
        val box = account.outbox ?: return emptyList()
        val i = runCatching { QueuedNodes.decode(EntryIntent.serializer(), write) }.getOrNull() ?: return emptyList()
        return i.receipts.mapNotNull { r ->
            val blob = write.blobs.firstOrNull { it.id == r.blob } ?: return@mapNotNull null
            val key = me.raddatz.veyq.mobile.crypto.unb64(blob.key)
            try {
                var bytes = ByteArray(0)
                for (n in 0 until blob.chunkCount) bytes += account.suite.openFrame(key, n, blob.chunkCount, box.frame(blob, n) ?: return@mapNotNull null)
                PotSession.PendingReceipt(r.id, bytes, r.type, r.name, r.modifiedAt)
            } finally {
                key.fill(0)
            }
        }
    }

    /**
     * The editor's [form] queued as entry [entryId] of [session]'s Pot (null: a new one) on [basedOn], the revision the
     * editor showed, with [pending] receipts sealed at once — the online save's checks first (a form that forms no entry, a
     * receipt too large or too many: nothing queued). The write queued; null where nothing was (no store, the floor, a
     * refusal). The receipts' plaintext is not kept: the caller lets the picked files go.
     */
    suspend fun saveEntry(
        session: PotSession, entryId: String?, form: EntryForm, basedOn: Long?, pending: List<PotSession.PendingReceipt>, nowMs: Long,
    ): QueuedWrite? {
        val account = session.queueAccount ?: return null
        val box = account.outbox ?: return null
        val known = session.ledger?.participants?.map { it.id }?.toSet() ?: return null
        if (form.contentOf { it in known }.content == null) return null
        if (pending.any { it.bytes.size > Receipts.MAX_BYTES }) return null
        if (form.receipts.size + pending.size > Receipts.MAX_PER_ENTRY) return null
        val id = entryId ?: session.newId()
        val before = box.write(idOf(id))?.let { w -> runCatching { QueuedNodes.decode(EntryIntent.serializer(), w) }.getOrNull()?.let { w to it } }
        val sealed = ArrayList<Pair<QueuedBlob, ReceiptIntent>>()
        try {
            for (r in pending) {
                val blob = box.sealBlob(r.bytes) ?: return null
                sealed += blob to ReceiptIntent(r.id, blob.id, r.type, r.name, r.modifiedAt)
            }
            val ids = form.receipts + pending.map { it.id }
            val content = form.copy(receipts = ids).contentOf { it in known }.content ?: return null
            // a change of an entry still waiting keeps what the first change saw: the revision and the heads
            val heads = before?.second?.body?.let { runCatching { RevisionFormat.read(it).heads }.getOrNull() } ?: Revisions.headsToName(session.heldRevisions())
            val on = if (before != null) before.second.basedOn else basedOn
            val earlier = before?.let { (w, i) -> i.receipts.filter { it.id in ids }.mapNotNull { r -> w.blobs.firstOrNull { it.id == r.blob }?.let { it to r } } }.orEmpty()
            val receipts = earlier + sealed
            val intent = EntryIntent(id, on, RevisionFormat.toJson(RevisionBody(heads, content)), receipts.map { it.second })
            val write = QueuedWrite(
                idOf(id), Queued.POT_ENTRY.name, session.tenantId, session.scopeId, node = session.nodeId,
                intent = QueuedNodes.encode(EntryIntent.serializer(), intent), blobs = receipts.map { it.first }, at = before?.first?.at ?: nowMs,
            )
            if (!box.put(write)) return null
            // the receipts the change no longer lists are gone with it
            before?.first?.blobs?.filter { b -> receipts.none { it.first.id == b.id } }?.forEach { box.dropBlob(it) }
            sealed.clear()
            return write
        } finally {
            sealed.forEach { box.dropBlob(it.first) }
        }
    }

    /**
     * Entry [entryId] of [session]'s Pot deleted on [basedOn], the revision shown. One made offline and never sent leaves
     * the queue instead. False where nothing was queued.
     */
    suspend fun deleteEntry(session: PotSession, entryId: String, basedOn: Long, nowMs: Long): Boolean {
        val account = session.queueAccount ?: return false
        val box = account.outbox ?: return false
        val before = box.write(idOf(entryId))?.let { w -> runCatching { QueuedNodes.decode(EntryIntent.serializer(), w) }.getOrNull()?.let { w to it } }
        if (before != null && before.second.basedOn == null) {
            box.remove(before.first.id)
            return true
        }
        val heads = before?.second?.body?.let { runCatching { RevisionFormat.read(it).heads }.getOrNull() } ?: Revisions.headsToName(session.heldRevisions())
        val intent = EntryIntent(entryId, before?.second?.basedOn ?: basedOn, RevisionFormat.toJson(RevisionBody(heads, Deleted)))
        val write = QueuedWrite(
            idOf(entryId), Queued.POT_ENTRY.name, session.tenantId, session.scopeId, node = session.nodeId,
            intent = QueuedNodes.encode(EntryIntent.serializer(), intent), at = before?.first?.at ?: nowMs,
        )
        if (!box.put(write)) return false
        before?.first?.blobs?.forEach { box.dropBlob(it) }
        return true
    }

    internal val entrySender = QueuedSender { tenant, w ->
        val i = QueuedNodes.decode(EntryIntent.serializer(), w)
        val box = tenant.account.outbox ?: return@QueuedSender QueuedOutcome.Refused("refused")
        val node = w.node ?: return@QueuedSender QueuedOutcome.Refused("refused")
        val body = runCatching { RevisionFormat.read(i.body) }.getOrNull() ?: return@QueuedSender QueuedOutcome.Refused("invalid")
        val session = PotSession.open(tenant, w.scope, node) ?: return@QueuedSender QueuedOutcome.Refused(QueuedNodes.NOT_FOUND)
        val receipts = i.receipts.map { r ->
            val blob = w.blobs.firstOrNull { it.id == r.blob } ?: return@QueuedSender QueuedOutcome.Refused(me.raddatz.veyq.mobile.offline.Outbox.FRAME_GONE)
            PotSession.QueuedReceipt(r.id, r.type, r.name, r.modifiedAt, box.sealed(blob))
        }
        when (session.sendQueued(i.entryId, body.content, i.basedOn, body.heads, receipts)) {
            PotSession.WriteOutcome.WRITTEN -> QueuedOutcome.Sent
            PotSession.WriteOutcome.STALE -> {
                // the revision held there is this very one (a send whose answer was lost): sent
                val mine = (i.basedOn ?: 0) + 1
                if (session.heldRevisions().any { it.entryId == i.entryId && it.revision == mine && it.body?.content == body.content }) QueuedOutcome.Sent
                else QueuedOutcome.Held(STALE_REVISION)
            }
            PotSession.WriteOutcome.BUSY, PotSession.WriteOutcome.OFFLINE, PotSession.WriteOutcome.QUEUED -> QueuedOutcome.Waiting
            PotSession.WriteOutcome.BLOCKED -> QueuedOutcome.Refused("blocked")
            PotSession.WriteOutcome.INVALID, PotSession.WriteOutcome.NOT_SETTLED -> QueuedOutcome.Refused("invalid")
            PotSession.WriteOutcome.REFUSED -> QueuedOutcome.Refused(session.lastRefusal ?: "refused")
            // a member's queued write is never a guest's: no name is asked
            PotSession.WriteOutcome.UNNAMED -> QueuedOutcome.Refused("refused")
        }
    }

    private const val ENTRY = "entry:"
    internal const val STALE_REVISION = "stale_revision"
}
