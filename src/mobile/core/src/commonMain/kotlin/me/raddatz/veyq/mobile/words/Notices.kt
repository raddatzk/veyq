package me.raddatz.veyq.mobile.words

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.depot.DepotFiles
import me.raddatz.veyq.mobile.depot.DropBoxes
import me.raddatz.veyq.mobile.depot.FolderUpload
import me.raddatz.veyq.mobile.expenses.PotCopy
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeRename
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.ShareLinkChanges
import me.raddatz.veyq.mobile.session.ShareLinks

/**
 * What a screen says, as the catalog's key and its arguments (ADR 0127 decisions 1 and 3): each platform renders it in
 * the person's language. An argument is a `String`, an `Int`, a [Bytes] (a size the platform formats its own way),
 * a [Moment], an [Amount], another [Notice] (a word inside the sentence) or a [Joined] list of those.
 */
@Serializable(with = NoticeSerializer::class)
class Notice(val msg: Msg, val args: List<Any> = emptyList()) {
    constructor(msg: Msg, vararg args: Any) : this(msg, args.toList())

    override fun equals(other: Any?) = other is Notice && other.msg == msg && other.args == args
    override fun hashCode() = msg.hashCode() * 31 + args.hashCode()
    override fun toString() = "Notice(${msg.key}, $args)"
}

/** Several values in a sentence, one after another with [separator] between them; each a `String` or a [Notice]. */
class Joined(val parts: List<Any>, val separator: String) {
    override fun equals(other: Any?) = other is Joined && other.parts == parts && other.separator == separator
    override fun hashCode() = parts.hashCode() * 31 + separator.hashCode()
    override fun toString() = "Joined($parts, '$separator')"
}

/**
 * One piece of a row's line, which a platform writes its own way and joins with the others: words of the catalog, a
 * name as written, a day (ISO, `2026-11-10`), a size in bytes or an amount in a currency's minor units.
 */
@Serializable
sealed class Piece {
    @Serializable @SerialName("said")
    class Said(val notice: Notice) : Piece() {
        override fun equals(other: Any?) = other is Said && other.notice == notice
        override fun hashCode() = notice.hashCode()
        override fun toString() = "Said($notice)"
    }

    @Serializable @SerialName("plain")
    class Plain(val text: String) : Piece() {
        override fun equals(other: Any?) = other is Plain && other.text == text
        override fun hashCode() = text.hashCode()
        override fun toString() = "Plain($text)"
    }

    @Serializable @SerialName("day")
    class Day(val iso: String) : Piece() {
        override fun equals(other: Any?) = other is Day && other.iso == iso
        override fun hashCode() = iso.hashCode()
        override fun toString() = "Day($iso)"
    }

    @Serializable @SerialName("size")
    class Size(val bytes: Long) : Piece() {
        override fun equals(other: Any?) = other is Size && other.bytes == bytes
        override fun hashCode() = bytes.hashCode()
        override fun toString() = "Size($bytes)"
    }

    @Serializable @SerialName("money")
    class Money(val amount: Long, val currency: String?, val minorUnits: Int) : Piece() {
        override fun equals(other: Any?) = other is Money && other.amount == amount && other.currency == currency && other.minorUnits == minorUnits
        override fun hashCode() = amount.hashCode()
        override fun toString() = "Money($amount $currency/$minorUnits)"
    }
}

/** A moment in a sentence (epoch ms): each platform writes it as its own date and time read ("Nov 10, 2026, 14:05"). */
class Moment(val epochMs: Long) {
    override fun equals(other: Any?) = other is Moment && other.epochMs == epochMs
    override fun hashCode() = epochMs.hashCode()
    override fun toString() = "Moment($epochMs)"
}

/** An amount in a sentence, in a currency's minor units: each platform writes it as its own money reads ("12,34 €"). */
class Amount(val amount: Long, val currency: String?, val minorUnits: Int) {
    override fun equals(other: Any?) = other is Amount && other.amount == amount && other.currency == currency && other.minorUnits == minorUnits
    override fun hashCode() = amount.hashCode()
    override fun toString() = "Amount($amount $currency/$minorUnits)"
}

/** A count of bytes in a sentence: each platform writes it as its own file sizes read ("1.2 MB"). */
class Bytes(val count: Long) {
    override fun equals(other: Any?) = other is Bytes && other.count == count
    override fun hashCode() = count.hashCode()
    override fun toString() = "Bytes($count)"
}

/**
 * A [Msg] on the wire (the native bridge, CR core-as-a-native-library D6) as its catalog key — `words.json`'s, which
 * each platform's words are generated under — never as the entry's Kotlin name.
 */
object MsgKey : KSerializer<Msg> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("me.raddatz.veyq.mobile.words.MsgKey", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Msg) = encoder.encodeString(value.key)
    override fun deserialize(decoder: Decoder): Msg {
        val key = decoder.decodeString()
        return Msg.entries.firstOrNull { it.key == key } ?: throw SerializationException("no word has the key $key")
    }
}

/**
 * A [Notice] on the wire: its key and each argument tagged by what it is, so the platform across the bridge says it
 * as a platform here does ([NoticeArg]); an argument of another type is refused, never sent as its `toString`.
 */
object NoticeSerializer : KSerializer<Notice> {
    override val descriptor: SerialDescriptor = NoticeWire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Notice) = encoder.encodeSerializableValue(NoticeWire.serializer(), NoticeWire.of(value))
    override fun deserialize(decoder: Decoder): Notice = decoder.decodeSerializableValue(NoticeWire.serializer()).notice()
}

@Serializable @SerialName("Notice")
internal class NoticeWire(@Serializable(with = MsgKey::class) val msg: Msg, val args: List<NoticeArg>) {
    fun notice() = Notice(msg, args.map { it.arg() })

    companion object {
        fun of(n: Notice) = NoticeWire(n.msg, n.args.map(NoticeArg::of))
    }
}

/** One argument of a [Notice] as the wire carries it: each kind the class documents, by its own tag. */
@Serializable @SerialName("NoticeArg")
internal sealed class NoticeArg {
    abstract fun arg(): Any

    @Serializable @SerialName("text") class Text(val text: String) : NoticeArg() { override fun arg() = text }
    @Serializable @SerialName("int") class IntArg(val value: Int) : NoticeArg() { override fun arg() = value }
    @Serializable @SerialName("long") class LongArg(val value: Long) : NoticeArg() { override fun arg() = value }
    @Serializable @SerialName("bytes") class BytesArg(val count: Long) : NoticeArg() { override fun arg() = Bytes(count) }
    @Serializable @SerialName("moment") class MomentArg(val epochMs: Long) : NoticeArg() { override fun arg() = Moment(epochMs) }
    @Serializable @SerialName("amount") class AmountArg(val amount: Long, val currency: String?, val minorUnits: Int) : NoticeArg() {
        override fun arg() = Amount(amount, currency, minorUnits)
    }
    @Serializable @SerialName("notice") class Said(val notice: Notice) : NoticeArg() { override fun arg() = notice }
    @Serializable @SerialName("joined") class JoinedArg(val parts: List<NoticeArg>, val separator: String) : NoticeArg() {
        override fun arg() = Joined(parts.map { it.arg() }, separator)
    }

    companion object {
        fun of(a: Any): NoticeArg = when (a) {
            is String -> Text(a)
            is Int -> IntArg(a)
            is Long -> LongArg(a)
            is Bytes -> BytesArg(a.count)
            is Moment -> MomentArg(a.epochMs)
            is Amount -> AmountArg(a.amount, a.currency, a.minorUnits)
            is Notice -> Said(a)
            is Joined -> JoinedArg(a.parts.map(::of), a.separator)
            else -> throw SerializationException("a notice's argument of ${a::class.simpleName} has no form on the wire")
        }
    }
}

/**
 * Which notice each outcome raises — the one table both apps used to write twice (the web's words where it has
 * them): null where the outcome needs none (it went through).
 */
object Notices {
    /** [msg] alone, without arguments (Swift reaches a constructor's vararg only as a Kotlin array). */
    fun of(msg: Msg): Notice = Notice(msg)

    /** A node moved to the trash ([NodeTrash.Deleted.TRASHED], offered its undo), else why not. */
    fun trashed(outcome: NodeTrash.Deleted, name: String): Notice = when (outcome) {
        NodeTrash.Deleted.TRASHED -> Notice(Msg.TRASH_MOVED, name)
        NodeTrash.Deleted.GONE -> Notice(Msg.DELETE_REFUSED_GONE)
        NodeTrash.Deleted.SCOPE_DELETING -> Notice(Msg.DELETE_REFUSED_SCOPE_DELETING)
        NodeTrash.Deleted.FAILED -> Notice(Msg.DELETE_REFUSED_FAILED)
    }

    /** The undo of a trash did not bring [name] back. */
    fun trashUndoFailed(name: String) = Notice(Msg.TRASH_UNDO_FAILED, name)

    /** Why a move did not go through (`node.move.refused.*`). */
    fun moveRefused(outcome: NodeMove.Outcome): Notice? = when (outcome) {
        NodeMove.Outcome.MOVED -> null
        NodeMove.Outcome.NAME_TAKEN -> Notice(Msg.MOVE_REFUSED_SLUG_TAKEN)
        NodeMove.Outcome.NOT_ALLOWED, NodeMove.Outcome.BLOCKED -> Notice(Msg.MOVE_REFUSED_ILLEGAL_STRUCTURE)
        NodeMove.Outcome.GONE -> Notice(Msg.MOVE_REFUSED_GONE)
        NodeMove.Outcome.CONFLICT -> Notice(Msg.MOVE_REFUSED_STALE_PLACEMENT)
        NodeMove.Outcome.SCOPE_DELETING -> Notice(Msg.MOVE_REFUSED_SCOPE_DELETING)
        NodeMove.Outcome.BUSY -> Notice(Msg.MOVE_REFUSED_REKEY_PENDING)
        else -> Notice(Msg.MOVE_REFUSED_FAILED)
    }

    /** Why a rename did not land (`nodeNameErrorText`): a sibling holding the name is named with its kind's noun. */
    fun renameRefused(r: NodeRename.Result): Notice? = when (r.outcome) {
        NodeRename.Outcome.RENAMED -> null
        NodeRename.Outcome.NAME_TAKEN -> r.taken?.let { sibling ->
            Notice(Msg.NODE_NAME_TAKEN_BY, Notice(if (sibling.kind == "EXPENSES_POT") Msg.POT_A else Msg.NODE_AN_ITEM), sibling.name)
        } ?: Notice(Msg.NODE_SLUG_TAKEN)
        NodeRename.Outcome.NOT_ALLOWED -> Notice(Msg.NODE_ILLEGAL_STRUCTURE)
        NodeRename.Outcome.BUSY -> Notice(Msg.POT_BUSY)
        NodeRename.Outcome.FAILED -> Notice(Msg.NODE_SAVE_FAILED)
    }

    /** Why a Pot's copy did not go through. */
    fun potCopyRefused(outcome: PotCopy.Outcome): Notice? = when (outcome) {
        PotCopy.Outcome.COPIED -> null
        PotCopy.Outcome.NAME_TAKEN -> Notice(Msg.COPY_NAME_TAKEN)
        PotCopy.Outcome.NOTHING_TO_COPY -> Notice(Msg.COPY_NOTHING)
        PotCopy.Outcome.BUSY -> Notice(Msg.MOVE_REFUSED_REKEY_PENDING)
        PotCopy.Outcome.BLOCKED, PotCopy.Outcome.REFUSED -> Notice(Msg.MOVE_REFUSED_ILLEGAL_STRUCTURE)
        PotCopy.Outcome.FAILED -> Notice(Msg.COPY_FAILED)
    }

    /** Why a folder's (or a file's) copy did not go through; null [outcome] is a copy that threw. */
    fun subtreeCopyRefused(outcome: SubtreeCopy.Outcome?): Notice? = when (outcome) {
        SubtreeCopy.Outcome.COPIED -> null
        SubtreeCopy.Outcome.NAME_TAKEN -> Notice(Msg.COPY_NAME_TAKEN)
        SubtreeCopy.Outcome.NOTHING_TO_COPY -> Notice(Msg.COPY_NOTHING)
        else -> Notice(Msg.COPY_FAILED_ROLLED_BACK)
    }

    /** Why a new folder was not made; null [reason] is a create that threw. */
    fun folderRefused(reason: NodeCreate.Reason?): Notice =
        if (reason == NodeCreate.Reason.NAME_TAKEN) Notice(Msg.FOLDER_NAME_TAKEN) else Notice(Msg.FOLDER_FAILED)

    /** Why a report did not reach the moderators. */
    fun reportRefused(outcome: NodeReport.Outcome): Notice? = when (outcome) {
        NodeReport.Outcome.SENT -> null
        NodeReport.Outcome.TOO_MANY_OPEN -> Notice(Msg.REPORT_TOO_MANY_OPEN)
        NodeReport.Outcome.RATE_LIMITED -> Notice(Msg.REPORT_RATE_LIMITED)
        NodeReport.Outcome.UNAVAILABLE -> Notice(Msg.REPORT_UNAVAILABLE)
        NodeReport.Outcome.NOT_FOUND -> Notice(Msg.REPORT_NOT_FOUND)
        else -> Notice(Msg.REPORT_FAILED)
    }

    /** What a link's change (edit, renew, switch off or on, delete) left to say. */
    fun linkChanged(outcome: ShareLinkChanges.Outcome): Notice? = when (outcome) {
        ShareLinkChanges.Outcome.DONE -> null
        ShareLinkChanges.Outcome.DONE_REKEY_PENDING -> Notice(Msg.SHARE_CLOSED_REKEYING)
        ShareLinkChanges.Outcome.PASSWORD_LOST -> Notice(Msg.SHARE_PASSWORD_LOST)
        ShareLinkChanges.Outcome.GONE -> Notice(Msg.SHARE_GONE)
        ShareLinkChanges.Outcome.RULES -> Notice(Msg.SHARE_REQUIREMENT_REFUSED)
        ShareLinkChanges.Outcome.REISSUE_REQUIRED -> Notice(Msg.SHARE_REISSUE_HINT)
        else -> Notice(Msg.SHARE_FAILED)
    }

    /** Why a new link was not made. */
    /** A problem of an entry's form or its write path (the web's `expenses.entry.problem.*`); any other: an unknown participant. */
    fun entryProblem(problem: String): Msg = when (problem) {
        "title" -> Msg.PROBLEM_TITLE
        "date" -> Msg.PROBLEM_DATE
        "amount" -> Msg.PROBLEM_AMOUNT
        "rate" -> Msg.PROBLEM_RATE
        "payers" -> Msg.PROBLEM_PAYERS
        "split" -> Msg.PROBLEM_SPLIT
        "split-shares" -> Msg.PROBLEM_SPLIT_SHARES
        "split-percents" -> Msg.PROBLEM_SPLIT_PERCENTS
        "split-amounts" -> Msg.PROBLEM_SPLIT_AMOUNTS
        "items" -> Msg.PROBLEM_ITEMS
        "transfer" -> Msg.PROBLEM_TRANSFER
        "payers-unbalanced" -> Msg.PROBLEM_PAYERS_UNBALANCED
        "receivers-unbalanced" -> Msg.PROBLEM_RECEIVERS_UNBALANCED
        "items-above-total" -> Msg.PROBLEM_ITEMS_ABOVE_TOTAL
        "rest-unallocated" -> Msg.PROBLEM_REST_UNALLOCATED
        "percent-unbalanced" -> Msg.PROBLEM_PERCENT_UNBALANCED
        "amounts-unbalanced" -> Msg.PROBLEM_AMOUNTS_UNBALANCED
        "named-twice" -> Msg.PROBLEM_NAMED_TWICE
        else -> Msg.PROBLEM_UNKNOWN_PARTICIPANT
    }

    /**
     * What a saved entry says when receipts were left out for want of room (the web's `expenses.receipts.noRoom`): the entry
     * is saved and its editor closes; a receipt is attached again once there is room. Null when none was left out.
     */
    fun receiptsSkipped(names: List<String>): Notice? =
        if (names.isEmpty()) null else Notice(Msg.RECEIPTS_NO_ROOM, Joined(names, ", "))

    /** Why an entry was not saved (`PotWrites` outcome names). */
    fun entryRefused(outcome: String): Msg = when (outcome) {
        "STALE" -> Msg.ED_STALE
        "BLOCKED" -> Msg.ED_BLOCKED
        "BUSY" -> Msg.POT_BUSY
        else -> Msg.ED_SAVE_FAILED
    }

    /** Why a write to a Pot's settings or people was not done. */
    fun potRefused(outcome: String): Msg = when (outcome) {
        "STALE" -> Msg.ENTRY_STALE
        "NOT_SETTLED" -> Msg.PEOPLE_SETTLE_FIRST
        "BUSY" -> Msg.POT_BUSY
        "BLOCKED" -> Msg.POT_BLOCKED
        "INVALID" -> Msg.PEOPLE_INVALID
        else -> Msg.PEOPLE_FAILED
    }

    /** Why a new Pot was not created (`PotCreate.Outcome` names). */
    fun potCreateRefused(outcome: String): Msg = when (outcome) {
        "NAME_TAKEN" -> Msg.CREATE_POT_NAME_TAKEN
        "PEOPLE_NOT_ADDED" -> Msg.CREATE_POT_PEOPLE_FAILED
        "BUSY" -> Msg.POT_BUSY
        "BLOCKED" -> Msg.POT_BLOCKED
        "INVALID" -> Msg.CREATE_POT_INVALID
        else -> Msg.CREATE_POT_FAILED
    }

    /** A node a download or a copy left out, with why (the web's `export.reason.*`, `node.copy.reason.*`). */
    fun leftOut(s: me.raddatz.veyq.mobile.export.NodeExport.Skipped): Notice = Notice(
        Msg.LEFT_OUT_LINE, s.name,
        Notice(
            when (s.reason) {
                me.raddatz.veyq.mobile.export.NodeExport.NOTHING_TO_EXPORT -> Msg.LEFT_OUT_NOTHING
                me.raddatz.veyq.mobile.export.SubtreeCopy.NOT_COPYABLE -> Msg.LEFT_OUT_NOT_COPYABLE
                else -> Msg.LEFT_OUT_UNSUPPORTED
            },
        ),
    )

    /** What switching a link off says: nothing once closed, that its re-key is still due, or that it failed. */
    fun linkClosed(closed: ShareLinks.Closed): Notice? = when (closed) {
        ShareLinks.Closed.CLOSED -> null
        ShareLinks.Closed.CLOSED_REKEY_PENDING -> Notice(Msg.SHARE_CLOSED_REKEYING)
        ShareLinks.Closed.FAILED -> Notice(Msg.SHARE_FAILED)
    }

    fun linkRefused(outcome: ShareLinks.Outcome): Notice? = when (outcome) {
        ShareLinks.Outcome.CREATED -> null
        ShareLinks.Outcome.PASSWORD_REQUIRED, ShareLinks.Outcome.EXPIRY_REJECTED -> Notice(Msg.SHARE_REQUIREMENT_REFUSED)
        ShareLinks.Outcome.NOT_ALLOWED -> Notice(Msg.SHARE_NOT_ALLOWED)
        ShareLinks.Outcome.SCOPE_DELETING -> Notice(Msg.DELETE_REFUSED_SCOPE_DELETING)
        ShareLinks.Outcome.BUSY -> Notice(Msg.MOVE_REFUSED_REKEY_PENDING)
        else -> Notice(Msg.SHARE_FAILED)
    }

    /** Why a new drop box (and its first link) was not made whole. */
    fun dropBoxRefused(outcome: DropBoxes.Outcome): Notice? = when (outcome) {
        DropBoxes.Outcome.CREATED -> null
        DropBoxes.Outcome.NAME_TAKEN -> Notice(Msg.NODE_SLUG_TAKEN)
        DropBoxes.Outcome.LINK_FAILED -> Notice(Msg.DROPBOX_LINK_FAILED)
        else -> Notice(Msg.SHARE_FAILED)
    }

    /** What a folder upload says once its folders are made: its refusal, else the folders not made (one line each). */
    fun folderUpload(r: FolderUpload.Report): List<Notice> = when (r.outcome) {
        FolderUpload.Outcome.NO_ROOM -> listOf(Notice(Msg.DEPOT_FOLDER_NO_ROOM, r.name, Bytes(r.bytes)))
        FolderUpload.Outcome.ROOT_FAILED -> listOf(Notice(Msg.DEPOT_FOLDER_FAILED, r.name))
        FolderUpload.Outcome.READY -> r.failedFolders.map { Notice(Msg.DEPOT_FOLDER_PART_FAILED, it) }
        FolderUpload.Outcome.NOTHING -> emptyList()
    }

    /** A Depot upload that found a file of its name: its replace is offered (the notice's action, [Msg.DEPOT_REPLACE_TITLE]). */
    fun replaceOffered(name: String) = Notice(Msg.DEPOT_REPLACE_CONFIRM, name)

    /** A replace whose file someone replaced meanwhile: "Replace anyway" is offered ([Msg.DEPOT_REPLACE_ANYWAY]). */
    fun replaceStale(name: String) = Notice(Msg.DEPOT_ERROR_STALE_FILE, name)

    /** Why an upload item did not go up (the line under it in the uploads list); null while it may still. */
    fun uploadProblem(item: UploadQueue.Item): Notice? {
        if (item.status != UploadQueue.Status.FAILED) return null
        return when (item.error) {
            UploadQueue.QUOTA_EXCEEDED -> Notice(Msg.UPLOAD_NO_SPACE)
            UploadQueue.APPLICATION_OFF -> Notice(Msg.UPLOAD_SWITCHED_OFF)
            DepotFiles.Codes.NAME_TAKEN -> Notice(Msg.UPLOAD_NAME_TAKEN)
            DepotFiles.Codes.BUSY -> Notice(Msg.MOVE_REFUSED_REKEY_PENDING)
            else -> Notice(Msg.UPLOAD_STATUS_FAILED)
        }
    }

    /** An upload item's state, for the uploads list ([uploadProblem] says a failure's why). */
    fun uploadStatus(item: UploadQueue.Item): Notice = when (item.status) {
        UploadQueue.Status.QUEUED -> Notice(Msg.UPLOAD_STATUS_QUEUED)
        UploadQueue.Status.PREPARING -> Notice(Msg.UPLOAD_STATUS_PREPARING)
        UploadQueue.Status.UPLOADING -> Notice(Msg.UPLOAD_STATUS_UPLOADING)
        UploadQueue.Status.DONE -> Notice(Msg.UPLOAD_STATUS_DONE)
        UploadQueue.Status.FAILED -> uploadProblem(item) ?: Notice(Msg.UPLOAD_STATUS_FAILED)
        UploadQueue.Status.CANCELLED -> Notice(Msg.UPLOAD_STATUS_CANCELLED)
    }

    /** Whether [item] is a medium (Memories) rather than a file (Depot): their refusals for room are worded apart. */
    fun isMedium(item: UploadQueue.Item): Boolean = item.job is MediaUpload.Job
}
