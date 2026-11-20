package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.expenses.Deleted
import me.raddatz.veyq.mobile.expenses.Expense
import me.raddatz.veyq.mobile.expenses.QueuedExpenses
import me.raddatz.veyq.mobile.expenses.Refund
import me.raddatz.veyq.mobile.expenses.Transfer
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * One write made offline as the uploads tray lists it (ADR 0153 decision 5, the CR's W5): its [id], where it writes
 * ([tenant], [scope], [node]: the platform groups under the destination's label, [QueuedTray]'s `nameOf`), what it is
 * ([title]: a kind's word with the name the person gave, never more of its content), where it is ([state]) with the line
 * under it ([said]: *Waiting to send*, or why it is held or refused), and what it offers: *Discard* always, *Open* for a
 * held one ([opens]), *Save a copy* for a refused one carrying a file ([copies], how many files it carries).
 */
@Serializable
@SerialName("QueuedItem")
data class QueuedItem(
    val id: String,
    val op: Queued,
    val tenant: String,
    val scope: String,
    val node: String?,
    /** The name of the place it writes into (a Memory, a folder, a Pot), where the app holds it; null otherwise. */
    val where: String?,
    val title: Notice,
    val state: QueuedWrite.State,
    val said: Notice,
    val opens: Boolean,
    val copies: Int,
    val at: Long,
)

/**
 * Where a held write's *Open* goes (ADR 0153 decision 4: the online conflict's own screen), typed so a platform opens it
 * without reading the write: [Entry] the Pot's entry editor over the person's values ([me.raddatz.veyq.mobile.expenses.PotModel.heldEditor]
 * with [write]), [Rename] the node's rename dialog with the person's [name] in it (a rename that lands drops the held one,
 * `ShellModel.rename`), [Place] the page the write was made on (a capture time's Memory, a create's level: [node] null is
 * the space's top), where the person looks and then sends it no more (*Discard*).
 */
@Serializable
@SerialName("QueuedOpen")
sealed class QueuedOpen {
    @Serializable @SerialName("QueuedOpen.Entry")
    data class Entry(val tenant: String, val scope: String, val pot: String, val entry: String, val write: String) : QueuedOpen()

    @Serializable @SerialName("QueuedOpen.Rename")
    data class Rename(val tenant: String, val scope: String, val node: String, val name: String) : QueuedOpen()

    @Serializable @SerialName("QueuedOpen.Place")
    data class Place(val tenant: String, val scope: String, val node: String?) : QueuedOpen()
}

/**
 * What deletes the writes made offline with the store (ADR 0153 decision 2, owner 2026-11-19), each with the confirm its
 * ask offers: a sign-out, "Stop trusting this device", "Remove all offline content".
 */
@Serializable
@SerialName("QueuedLoss")
enum class QueuedLoss(@Serializable(with = me.raddatz.veyq.mobile.words.MsgKey::class) val confirm: Msg) {
    SIGN_OUT(Msg.OFFLINE_SIGN_OUT_ANYWAY),
    STOP_TRUSTING(Msg.OFFLINE_STOP_TRUSTING_ANYWAY),
    REMOVE_ALL(Msg.OFFLINE_REMOVE_ANYWAY),
}

/** The ask before a [QueuedLoss] while writes wait: its [question] naming how many go, its [confirm] (*Cancel* beside it). */
@Serializable
@SerialName("QueuedAsk")
class QueuedAsk(val question: Notice, @Serializable(with = me.raddatz.veyq.mobile.words.MsgKey::class) val confirm: Msg)

/** The words of the writes made offline (ADR 0153 decision 5; drafted in the CR's phase 5, the owner's to change). */
object QueuedShown {
    /** Under the offline banner while writes wait: their count; null while none does. */
    fun banner(count: Int): Notice? = if (count > 0) Notice(Msg.OFFLINE_BANNER_WAITING, count) else null

    /** What a run of the outbox came to, said after a reconnect: how many were sent, how many need the person. */
    fun report(r: OutboxSender.Report): List<Notice> = listOfNotNull(
        r.sent.takeIf { it > 0 }?.let { Notice(Msg.OFFLINE_SENT, it) },
        (r.held + r.refused).takeIf { it > 0 }?.let { Notice(Msg.OFFLINE_NEED_YOU, it) },
    )

    /**
     * What is asked before a sign-out, a "Stop trusting" or a "Remove all offline content" deletes [count] writes waiting
     * (ADR 0153 decision 2): the question; null where none waits (nothing to ask). The confirm is [Msg.OFFLINE_SIGN_OUT_ANYWAY],
     * [Msg.OFFLINE_STOP_TRUSTING_ANYWAY] or [Msg.OFFLINE_REMOVE_ANYWAY].
     */
    fun lost(count: Int): Notice? = if (count > 0) Notice(Msg.OFFLINE_WRITES_LOST, count) else null

    /** The ask before [loss] while [count] writes wait ([lost] with the loss's confirm); null where none waits (nothing to ask). */
    fun ask(loss: QueuedLoss, count: Int): QueuedAsk? = lost(count)?.let { QueuedAsk(it, loss.confirm) }

    /** A held or refused write's reason as said; *Waiting to send* for one that waits. */
    fun said(write: QueuedWrite): Notice = when (write.state) {
        QueuedWrite.State.WAITING -> Notice(Msg.OFFLINE_WAITING)
        QueuedWrite.State.HELD -> Notice(if (write.reason in NAMES) Msg.OFFLINE_HELD_NAME else Msg.OFFLINE_HELD_CHANGED)
        QueuedWrite.State.REFUSED -> Notice(
            when (write.reason) {
                "not_found", "parent_gone", "node_not_found", "scope_deleting", "tenant_gone", "http_404" -> Msg.OFFLINE_REFUSED_GONE
                "quota_exceeded" -> Msg.OFFLINE_REFUSED_NO_ROOM
                "illegal_structure", "blocked", "capability_disabled", "http_403", "forbidden" -> Msg.OFFLINE_REFUSED_NOT_ALLOWED
                else -> Msg.OFFLINE_REFUSED_OTHER
            },
        )
    }

    private val NAMES = setOf(QueuedNodes.NAME_TAKEN, QueuedNodes.STALE_PLACEMENT, QueuedNodes.ID_TAKEN, "file_exists")

    /** What [write] is, as its tray row names it. */
    fun title(write: QueuedWrite): Notice {
        fun field(k: String) = (write.intent[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (Queued.of(write.op)) {
            Queued.FOLDER_CREATE -> Notice(Msg.QUEUED_FOLDER, field("name"))
            Queued.MEMORY_CREATE -> Notice(if (field("kind") == "CHAPTER") Msg.QUEUED_CHAPTER else Msg.QUEUED_MEMORY, field("name"))
            Queued.NODE_RENAME -> Notice(Msg.QUEUED_RENAME, field("name"))
            Queued.NODE_TRASH -> Notice(Msg.QUEUED_TRASH)
            Queued.MEDIA_ADD -> Notice(Msg.QUEUED_MEDIUM, field("name"))
            Queued.MEDIA_CAPTURE_TIME -> Notice(Msg.QUEUED_CAPTURE_TIME)
            Queued.MEDIA_TRASH -> Notice(Msg.QUEUED_MEDIUM_TRASH)
            Queued.MEDIA_MARK -> Notice(Msg.QUEUED_MARK)
            Queued.DEPOT_FILE_ADD -> Notice(Msg.QUEUED_FILE, field("name"))
            Queued.POT_ENTRY -> when (val c = QueuedExpenses.entry(write)?.content) {
                Deleted -> Notice(Msg.QUEUED_ENTRY_DELETED)
                is Expense -> Notice(Msg.QUEUED_ENTRY, c.title)
                is Refund -> Notice(Msg.QUEUED_ENTRY, c.title)
                is Transfer -> Notice(Msg.QUEUED_ENTRY, c.title.orEmpty())
                else -> Notice(Msg.QUEUED_ENTRY, "")
            }
            null -> Notice(Msg.OFFLINE_REFUSED_OTHER)
        }
    }

    /** [write] as the tray lists it, [where] the name of the place it writes into. */
    fun item(write: QueuedWrite, where: String?): QueuedItem = QueuedItem(
        write.id, Queued.of(write.op) ?: Queued.NODE_TRASH, write.tenant, write.scope, write.node, where, title(write), write.state, said(write),
        opens = write.state == QueuedWrite.State.HELD, copies = if (write.state == QueuedWrite.State.REFUSED) carried(write).size else 0, at = write.at,
    )

    /** The files [write] carries that a person would save: a medium's original (never its tiers), a file, each receipt. */
    internal fun carried(write: QueuedWrite): List<QueuedBlob> {
        val tiers = setOfNotNull((write.intent["thumbnail"] as? JsonPrimitive)?.contentOrNull, (write.intent["preview"] as? JsonPrimitive)?.contentOrNull)
        return write.blobs.filter { it.id !in tiers }
    }
}

/**
 * The uploads tray's list of writes made offline (ADR 0153 decision 5, the CR's W5), over the account's outbox: every
 * write waiting, held or refused, oldest first ([items], read again at each change), and their actions — [discard] (with
 * what waits for it), [saveCopy] (a refused write's file, its plaintext handed only to the host's sink, the path the
 * person chose, ADR 0047). *Open* is the screen's own (the Pot's editor with [QueuedExpenses.entry], the rename dialog).
 * [nameOf] names the place a write goes into, from the trees the app holds (null where it holds none).
 */
class QueuedTray(private val account: VeyqAccount, private val nameOf: (scope: String, node: String?) -> String? = { _, _ -> null }) {
    private val box: Outbox? get() = account.outbox

    /** Every write made offline, as the tray lists it now. */
    suspend fun list(): List<QueuedItem> = box?.writes().orEmpty().map { w -> QueuedShown.item(w, placeOf(w)) }

    /** [list] at once and at each change of the outbox; empty where there is none. */
    val items: Flow<List<QueuedItem>>
        get() = (box?.changes ?: kotlinx.coroutines.flow.flowOf(0L)).map { list() }

    /** How many writes wait, are held or refused: the banner's count and the wipes' ask. */
    suspend fun count(): Int = box?.count() ?: 0

    /** The place a write goes into: its parent for a create, else the node it writes. */
    private fun placeOf(w: QueuedWrite): String? {
        val parent = (w.intent["parent"] as? JsonPrimitive)?.contentOrNull
        return when (Queued.of(w.op)) {
            Queued.FOLDER_CREATE, Queued.MEMORY_CREATE, Queued.DEPOT_FILE_ADD -> nameOf(w.scope, parent)
            else -> nameOf(w.scope, w.node)
        }
    }

    /** The ask before [loss] while writes wait here; null where none does. */
    suspend fun ask(loss: QueuedLoss): QueuedAsk? = QueuedShown.ask(loss, count())

    /** Where held write [id]'s *Open* goes ([QueuedOpen]); null where it is not held (nothing to decide) or not there. */
    suspend fun opens(id: String): QueuedOpen? {
        val w = box?.write(id)?.takeIf { it.state == QueuedWrite.State.HELD } ?: return null
        fun field(k: String) = (w.intent[k] as? JsonPrimitive)?.contentOrNull
        return when (Queued.of(w.op)) {
            Queued.POT_ENTRY -> {
                val e = QueuedExpenses.entry(w) ?: return null
                QueuedOpen.Entry(w.tenant, w.scope, w.node ?: return null, e.entryId, w.id)
            }
            Queued.NODE_RENAME -> QueuedOpen.Rename(w.tenant, w.scope, w.node ?: return null, field("name").orEmpty())
            Queued.FOLDER_CREATE, Queued.MEMORY_CREATE, Queued.DEPOT_FILE_ADD -> QueuedOpen.Place(w.tenant, w.scope, field("parent"))
            else -> QueuedOpen.Place(w.tenant, w.scope, w.node)
        }
    }

    /** Write [id] discarded unsent, with every write waiting for it. False where it is not there. */
    suspend fun discard(id: String): Boolean {
        val b = box ?: return false
        if (b.write(id) == null) return false
        QueuedNodes.dropWithDependents(b, id)
        return true
    }

    /** The name and type a refused write's file [index] is saved under (a medium's, a file's, a receipt's); null where none. */
    suspend fun copyName(id: String, index: Int = 0): Pair<String?, String?>? {
        val w = box?.write(id) ?: return null
        val blob = QueuedShown.carried(w).getOrNull(index) ?: return null
        QueuedExpenses.receiptOf(w, blob.id)?.let { return it }
        fun field(k: String) = (w.intent[k] as? JsonPrimitive)?.contentOrNull
        return field("name") to field("type")
    }

    /**
     * File [index] of refused write [id] handed to [sink] frame by frame as plaintext — the only plaintext of a queued
     * write ever made, and only into the path the person chose through the host's save dialog. False where there is none.
     */
    suspend fun saveCopy(id: String, index: Int = 0, sink: suspend (ByteArray) -> Unit): Boolean {
        val b = box ?: return false
        val w = b.write(id)?.takeIf { it.state == QueuedWrite.State.REFUSED } ?: return false
        val blob = QueuedShown.carried(w).getOrNull(index) ?: return false
        val key = unb64(blob.key)
        try {
            for (i in 0 until blob.chunkCount) {
                val frame = b.frame(blob, i) ?: return false
                sink(account.suite.openFrame(key, i, blob.chunkCount, frame))
            }
            return true
        } finally {
            key.fill(0)
        }
    }
}
