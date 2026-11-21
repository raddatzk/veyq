package me.raddatz.veyq.mobile.composition

import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.*
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.depot.DepotUploads
import me.raddatz.veyq.mobile.depot.FolderUpload
import me.raddatz.veyq.mobile.export.ImportDialog
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaUploads
import me.raddatz.veyq.mobile.memories.MemoryShare
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
     * walk [found] (`FolderUpload.Found`); null where it did not read.
     */
    class Folder(val path: String, val shownName: String?, val found: List<FolderUpload.Found>?) : Dropped() {
        /** The folder's name as it is said and made. */
        val name: String get() = FolderUpload.rootName(shownName, path)
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

    /** A Memory's or a chapter's gallery (its page open). */
    class Gallery(val gallery: GalleryModel) : DropTarget() {
        /**
         * The drop onto this gallery, judged by its rights alone (`GalleryModel.takesUploads`): the same answer with or
         * without a tenant's shell — a link's guest, signed out, has none ([ShellModel.drop] asks this too).
         */
        fun drop(items: List<Dropped>, left: (UploadSource) -> Unit = {}): DropOutcome =
            if (!gallery.takesUploads) DropOutcome.notHere else Drops.ontoGallery(gallery, items, left)
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

    internal companion object {
        val notHere get() = Refused(Notice(Msg.DROP_NOT_HERE))
    }
}

/** The drop's one sort and upload, behind `ShellModel.drop` (its gate decided there). */
internal object Drops {
    /** What a drop says of the items it does not take whatever the target: those with no local path, those that did not read. */
    private fun unsaid(items: List<Dropped>): List<Notice> =
        items.filterIsInstance<Dropped.NotLocal>().map { Notice(Msg.DEPOT_DROP_NOT_LOCAL, it.name) } +
            listOfNotNull(Notices.notRead(items.filterIsInstance<Dropped.Unread>().map { it.name }))

    /**
     * A drop onto a level of [scopeId] under [parentId], named [label]: folders out first, the files sorted through the
     * folder's answer ([FolderShare]'s: any file), the files screened as one batch, then each folder — one import offered
     * per drop at most (the web's): once one is, the rest is the ordinary upload.
     */
    suspend fun ontoLevel(
        scopeId: String, parentId: String?, label: String, items: List<Dropped>, depot: DepotUploads,
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
     * A drop onto [gallery]: its photos and videos queued into it at once ([MemoryShare]'s answer), each other file said
     * by count and handed back, each folder said by name — not walked into one Memory, which would flatten it (owner
     * 2026-11-13).
     */
    fun ontoGallery(gallery: GalleryModel, items: List<Dropped>, left: (UploadSource) -> Unit): DropOutcome {
        val sorted = MemoryShare.answer.sort(items.filterIsInstance<Dropped.File>().map { it.source })
        sorted.left.forEach { runCatching { left(it) } }
        val queued = if (sorted.taken.isEmpty()) 0 else gallery.upload(sorted.taken)
        if (queued == 0) sorted.taken.forEach { runCatching { left(it) } }
        val notices = items.filterIsInstance<Dropped.Folder>().map { Notice(Msg.MEMORY_DROP_FOLDER, it.name) } +
            unsaid(items) + listOfNotNull(MediaUploads.notTaken(sorted.left.size))
        return DropOutcome.Uploaded(queued, notices)
    }
}
