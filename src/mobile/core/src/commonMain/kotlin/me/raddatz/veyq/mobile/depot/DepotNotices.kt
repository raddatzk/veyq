package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

// Depot's lines of core's notices table ([Notices], ADR 0154 decision 4): extensions of it, so every client calls them
// where it called core's (Swift's `Notices.shared.folderUpload(r:)`, a category of `Notices`).

/** Why a new drop box (and its first link) was not made whole. */
fun Notices.dropBoxRefused(outcome: DropBoxes.Outcome): Notice? = when (outcome) {
    DropBoxes.Outcome.CREATED -> null
    DropBoxes.Outcome.NAME_TAKEN -> Notice(Msg.NODE_SLUG_TAKEN)
    DropBoxes.Outcome.LINK_FAILED -> Notice(Msg.DROPBOX_LINK_FAILED)
    else -> Notice(Msg.SHARE_FAILED)
}

/** What a folder upload says once its folders are made: its refusal, else the folders not made (one line each). */
fun Notices.folderUpload(r: FolderUpload.Report): List<Notice> = when (r.outcome) {
    FolderUpload.Outcome.NO_ROOM -> listOf(Notice(Msg.DEPOT_FOLDER_NO_ROOM, r.name, Bytes(r.bytes)))
    FolderUpload.Outcome.ROOT_FAILED -> listOf(Notice(Msg.DEPOT_FOLDER_FAILED, r.name))
    FolderUpload.Outcome.READY -> r.failedFolders.map { Notice(Msg.DEPOT_FOLDER_PART_FAILED, it) }
    FolderUpload.Outcome.NOTHING -> emptyList()
}

/** What a folder upload's walk left out ([FolderUpload.walked]): each link and each entry that did not read, one line each. */
fun Notices.folderLeftOut(w: FolderUpload.Walked): List<Notice> =
    w.links.map { Notice(Msg.DEPOT_FOLDER_LINK_SKIPPED, it) } + w.unreadable.map { Notice(Msg.DEPOT_FOLDER_UNREADABLE, it) }

/** A Depot upload that found a file of its name: its replace is offered (the notice's action, [Msg.DEPOT_REPLACE_TITLE]). */
fun Notices.replaceOffered(name: String) = Notice(Msg.DEPOT_REPLACE_CONFIRM, name)

/** A replace whose file someone replaced meanwhile: "Replace anyway" is offered ([Msg.DEPOT_REPLACE_ANYWAY]). */
fun Notices.replaceStale(name: String) = Notice(Msg.DEPOT_ERROR_STALE_FILE, name)
