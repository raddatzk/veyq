package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.depot.DepotUploads
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MemoryShare

/**
 * Where the pick mode's *Add here* puts what waits (ADR 0138 decision 3): the open page's own save, the place's answer
 * deciding what it takes. Core picks the target ([at], asked through `ShellModel.addHere`) so no app branches on kinds:
 *
 * - [Gallery] — a Memory's or a chapter's page open as the shell's object: Memories' answer, queued into its gallery;
 * - [Level] — the level the shell shows, a folder or a space's top: Depot's answer (a space's top asks as a folder),
 *   queued into Depot's upload queue under that level, as its picker's upload is.
 *
 * Each [answer] is the one `AppKinds.share` gives for the place's kind: what [take] queues and what a share extension
 * hands on (iOS's handoff, ADR 0138 decision 6).
 */
sealed class AddHere {
    /** The answer of the place's kind: what it takes of what waits. */
    abstract val answer: ShareAnswer

    /**
     * *Add here* pressed: what [answer] takes of what waits in [inbox] queued into the place, the rest let go, the share
     * ended (once). A level's files go into [depot] under [label] (the level's name, the batch's label), each handed to
     * [left] once that queue lets it go. Null where the place takes none of it now (nothing waits, no right, offline).
     */
    abstract fun take(inbox: ShareInbox, label: String, depot: DepotUploads?, left: (UploadSource) -> Unit): ShareInbox.Taken?

    /** A Memory's or a chapter's page: its gallery's upload. */
    class Gallery(val gallery: GalleryModel) : AddHere() {
        override val answer: ShareAnswer get() = MemoryShare.answer
        override fun take(inbox: ShareInbox, label: String, depot: DepotUploads?, left: (UploadSource) -> Unit) = MemoryShare.addHere(inbox, gallery)
    }

    /** The level shown: folder [parentId] of space [scopeId], or the space's top (null), at [place]. */
    data class Level(val scopeId: String, val parentId: String?, val place: ShareAnswer.Place) : AddHere() {
        override val answer: ShareAnswer get() = AppKinds.share(place.kind) ?: error("a level is a folder, whose kind answers")

        override fun take(inbox: ShareInbox, label: String, depot: DepotUploads?, left: (UploadSource) -> Unit): ShareInbox.Taken? {
            if (depot == null || !answer.mayChoose(place)) return null
            return inbox.queue(answer) { depot.upload(scopeId, parentId, label, it, left) }
        }

        /**
         * Files dropped onto the level (ADR 0138 decision 9): those [answer] takes — any file — uploaded under it at once
         * into [depot] named [label], each handed to [left] once that queue lets it go; the rest, and all where the level
         * may take nothing now, handed back at once. The number queued.
         */
        fun drop(files: List<UploadSource>, label: String, depot: DepotUploads?, left: (UploadSource) -> Unit): Int {
            val sorted = answer.sort(files)
            sorted.left.forEach { runCatching { left(it) } }
            if (depot == null || !answer.mayChoose(place)) {
                sorted.taken.forEach { runCatching { left(it) } }
                return 0
            }
            return depot.upload(scopeId, parentId, label, sorted.taken, left)
        }
    }

    companion object {
        /**
         * The page in [shell] that offers *Add here* for what waits in [inbox]: [gallery], the page of the shell's open
         * object, where Memories take one of the files there; else the level the shell shows — no object, application,
         * person's page, kept link or Search open — at the place [placeOf] its folder (null: the space's top), where its
         * answer may be chosen and takes one of them. Null anywhere else, and while nothing waits.
         */
        fun at(inbox: ShareInbox, shell: Shell, gallery: GalleryModel?, placeOf: (NodeAt?) -> ShareAnswer.Place): AddHere? {
            if (inbox.waiting.value == null) return null
            val open = shell.openObject
            if (open != null) {
                val g = gallery?.takeIf { it.node.nodeId == open.nodeId && MemoryShare.offered(inbox, it) } ?: return null
                return Gallery(g)
            }
            val level = shown(shell, placeOf) ?: return null
            return level.takeIf { inbox.offers(AppKinds.share(it.place.kind), it.place) }
        }

        /**
         * The level [shell] shows — no object, application, person's page, kept link or Search open — at the place
         * [placeOf] its folder (null: the space's top), where its answer may be chosen: what a drop onto it goes into.
         * Null where none is shown or nothing may be put there.
         */
        fun shown(shell: Shell, placeOf: (NodeAt?) -> ShareAnswer.Place): Level? {
            val space = shell.space ?: return null
            if (shell.openObject != null || shell.application != null || shell.person != null || shell.bookmark != null || shell.searching) return null
            val folder = shell.folderAt(space)
            val place = placeOf(folder)
            val answer = AppKinds.share(place.kind) ?: return null
            if (!answer.mayChoose(place)) return null
            return Level(space, folder?.nodeId, place)
        }
    }
}
