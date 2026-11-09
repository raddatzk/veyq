package me.raddatz.veyq.mobile.words

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
 * another [Notice] (a word inside the sentence) or a [Joined] list of those.
 */
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
 * name as written, a day (ISO, `2026-11-10`) or an amount in a currency's minor units.
 */
sealed class Piece {
    class Said(val notice: Notice) : Piece() {
        override fun equals(other: Any?) = other is Said && other.notice == notice
        override fun hashCode() = notice.hashCode()
        override fun toString() = "Said($notice)"
    }

    class Plain(val text: String) : Piece() {
        override fun equals(other: Any?) = other is Plain && other.text == text
        override fun hashCode() = text.hashCode()
        override fun toString() = "Plain($text)"
    }

    class Day(val iso: String) : Piece() {
        override fun equals(other: Any?) = other is Day && other.iso == iso
        override fun hashCode() = iso.hashCode()
        override fun toString() = "Day($iso)"
    }

    class Money(val amount: Long, val currency: String?, val minorUnits: Int) : Piece() {
        override fun equals(other: Any?) = other is Money && other.amount == amount && other.currency == currency && other.minorUnits == minorUnits
        override fun hashCode() = amount.hashCode()
        override fun toString() = "Money($amount $currency/$minorUnits)"
    }
}

/** A count of bytes in a sentence: each platform writes it as its own file sizes read ("1.2 MB"). */
class Bytes(val count: Long) {
    override fun equals(other: Any?) = other is Bytes && other.count == count
    override fun hashCode() = count.hashCode()
    override fun toString() = "Bytes($count)"
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
