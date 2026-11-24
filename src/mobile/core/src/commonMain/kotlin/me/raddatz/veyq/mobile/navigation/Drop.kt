package me.raddatz.veyq.mobile.navigation

import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.PlatformApi
import me.raddatz.veyq.mobile.blob.DirectoryWalk
import me.raddatz.veyq.mobile.blob.LevelUploads
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.export.ImportDialog
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * One item of a drop as the platform read it (ADR 0154 decision 1; ADR 0138 decision 9): the platform tells a file from a
 * folder by its file system and hands each over as it is — never sorting, judging or saying anything itself.
 */
sealed class Dropped {
    /** A file, opened as a source the core reads at offsets. */
    class File(val source: UploadSource) : Dropped()

    /**
     * A folder at [path] ([shownName]: its name where the platform shows another than the path's last part), and what its
     * walk [found] (`DirectoryWalk.Found`); null where it did not read.
     */
    class Folder(val path: String, val shownName: String?, val found: List<DirectoryWalk.Found>?) : Dropped() {
        /** The folder's name as it is said and made. */
        val name: String get() = DirectoryWalk.rootName(shownName, path)
    }

    /** An item with no local path (a phone's or a cloud's virtual item, a remote location unmounted): refused by its [name]. */
    class NotLocal(val name: String) : Dropped()

    /** An item with a path that did not open: said by its [name]. */
    class Unread(val name: String) : Dropped()
}

/** What a drop landed on: the platform names it, the core judges it ([ShellModel.drop]). */
sealed class DropTarget {
    /** The level the shell shows (a folder, a space's top), its title [label] as the platform draws it (the batch's name). */
    class Level(val label: String) : DropTarget()

    /** The row of [folder] on the level shown: a drop target only where `ShellModel.dropTarget` says so, never by its kind. */
    class Row(val folder: NodeAt) : DropTarget()

    /**
     * A page that takes files put onto it ([PageIntake]: a Memory's or a chapter's gallery, its page open; the bucket a
     * Contributions view shows, inbox #1065).
     */
    class Page(val intake: PageIntake) : DropTarget() {
        /**
         * The drop onto this page, judged by the page alone ([PageIntake.dropIntake]): the same answer with or without a
         * tenant's shell — a link's guest, signed out, has none ([ShellModel.drop] asks this too).
         */
        fun drop(items: List<Dropped>, left: (UploadSource) -> Unit = {}): DropOutcome = when (val i = intake.dropIntake()) {
            PageIntake.Intake.Takes -> Drops.ontoPage(intake, items, left)
            is PageIntake.Intake.Refused -> DropOutcome.Refused(i.notice)
            PageIntake.Intake.NotHere -> DropOutcome.notHere
        }
    }
}

/**
 * What a drop came to, one typed answer the platform draws: [notices] are said in order (a folder or an item not taken,
 * what did not read), whatever the outcome.
 */
sealed class DropOutcome {
    abstract val notices: List<Notice>

    /** [queued] files uploaded (or queued offline) at once; folders are prepared and said by the uploads themselves. */
    class Uploaded(val queued: Int, override val notices: List<Notice>) : DropOutcome()

    /** One veyq download among the drop offered as an import ([offer], the web's one per drop); the rest uploaded. */
    class Offered(val offer: ImportDialog, val queued: Int, override val notices: List<Notice>) : DropOutcome()

    /**
     * The target takes no drop here (no right, a guest's identity folder, offline, Depot off, no level shown): [notice] says
     * so. Nothing of the drop was taken or handed back — its files are the caller's still (a phone's pick mode takes them).
     */
    class Refused(val notice: Notice) : DropOutcome() {
        override val notices: List<Notice> get() = listOf(notice)
    }

    // open to the composition's ShellModel until it is the platform's (#1215 slice 3), then internal again
    @PlatformApi
    @HiddenFromObjC
    companion object {
        val notHere get() = Refused(Notice(Msg.DROP_NOT_HERE))
    }
}

/** The drop's one sort and upload, behind `ShellModel.drop` (its gate decided there). */
@PlatformApi
@HiddenFromObjC
object Drops {
    /** What a drop says of the items it does not take whatever the target: those with no local path, those that did not read. */
    private fun unsaid(items: List<Dropped>): List<Notice> =
        items.filterIsInstance<Dropped.NotLocal>().map { Notice(Msg.DEPOT_DROP_NOT_LOCAL, it.name) } +
            listOfNotNull(Notices.notRead(items.filterIsInstance<Dropped.Unread>().map { it.name }))

    /**
     * A drop onto a level of [scopeId] under [parentId], named [label]: folders out first, the files sorted through the
     * folder's answer (Depot's `FolderShare`: any file), the files screened as one batch, then each folder — one import offered
     * per drop at most (the web's): once one is, the rest is the ordinary upload.
     */
    suspend fun ontoLevel(
        scopeId: String, parentId: String?, label: String, items: List<Dropped>, depot: LevelUploads,
        answer: ShareAnswer, left: (UploadSource) -> Unit, deletedName: String,
    ): DropOutcome {
        val notices = unsaid(items).toMutableList()
        val sorted = answer.sort(items.filterIsInstance<Dropped.File>().map { it.source })
        sorted.left.forEach { runCatching { left(it) } }
        var queued = 0
        var offer: ImportDialog? = null
        if (sorted.taken.isNotEmpty()) {
            val screened = depot.screen(scopeId, parentId, label, sorted.taken, left, deletedName)
            queued += screened.queued
            offer = screened.offer
        }
        for (folder in items.filterIsInstance<Dropped.Folder>()) {
            val found = folder.found
            if (found == null) {
                Notices.notRead(listOf(folder.name))?.let { notices += it }
                continue
            }
            if (offer == null) {
                val screened = depot.screenFolder(scopeId, parentId, label, folder.shownName, folder.path, found, deletedName)
                queued += screened.queued
                offer = screened.offer
            } else {
                queued += depot.uploadFolder(scopeId, parentId, folder.shownName, folder.path, found)
            }
        }
        return offer?.let { DropOutcome.Offered(it, queued, notices) } ?: DropOutcome.Uploaded(queued, notices)
    }

    /**
     * A drop onto [page]: what its answer keeps (Memories': photos and videos) uploaded into it at once, each other file said
     * by count and handed back, each folder said by name — not walked into one Memory, which would flatten it (owner
     * 2026-11-13).
     */
    fun ontoPage(page: PageIntake, items: List<Dropped>, left: (UploadSource) -> Unit): DropOutcome {
        val sorted = page.answer.sort(items.filterIsInstance<Dropped.File>().map { it.source })
        sorted.left.forEach { runCatching { left(it) } }
        val queued = if (sorted.taken.isEmpty()) 0 else page.upload(sorted.taken)
        if (queued == 0) sorted.taken.forEach { runCatching { left(it) } }
        val notices = page.foldersNotTaken(items.filterIsInstance<Dropped.Folder>().map { it.name }) +
            unsaid(items) + listOfNotNull(page.filesNotTaken(sorted.left.size))
        return DropOutcome.Uploaded(queued, notices)
    }
}
