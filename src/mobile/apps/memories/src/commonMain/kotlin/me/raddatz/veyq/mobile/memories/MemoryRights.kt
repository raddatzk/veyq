package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.session.ShareLinks

/**
 * What a Memories page may offer to whoever looks (the web's `features/memories/rights.ts`, inbox #401: share view = member
 * view, rights only remove functions): one FAIL-CLOSED answer over who looks ([Access]) and the node on screen, so a
 * [GalleryModel] — the one model for a member and a link's guest — offers an action only where this says yes. The matrix
 * is written down once, in `docs/apps/memories.md` § What a guest may do (ADR 0080 decision 6); this is its port. Every
 * field defaults to false: an unknown role or kind grants nothing, so a new kind or a new role never inherits a right by
 * accident.
 *
 * The node-generic rights — share, rename, move, delete of the node on screen, its cover — are core's header's
 * (`NodeHeader.rights`), not here. The fields no app draws a control for yet (date and place, the banner, marks, the
 * filter, participants, the media trash, the organizer) are ported now, so the CRs that draw them add no rule.
 */
object MemoryRights {
    /** Who looks at a Memories page. */
    sealed class Access {
        /**
         * A member of the space: [readonly] while the space's deletion is pending (no write, no new link), [memoriesOn] false
         * where the tenant switched Memories off (no write — but a delete to the trash, its restore and reading stay, the
         * server's exemptions), [inIdentityFolder] where the level is a guest's identity folder (Depot's drop box: no Memory
         * there).
         */
        data class Member(val readonly: Boolean = false, val memoriesOn: Boolean = true, val inIdentityFolder: Boolean = false) : Access()

        /** A link's guest, by the link's role ([ShareLinks.READ], [ShareLinks.READ_WRITE], [ShareLinks.WRITE_ONLY]). */
        data class Guest(val role: String) : Access()

        /** Nobody this build knows: nothing. */
        object None : Access()
    }

    /** Which media a delete reaches: none; a guest's own (a purge, irreversible: asked first); any (a member's trash, with Undo). */
    enum class DeleteMedia { NONE, OWN, ANY }

    data class Rights(
        /** Open what lies below: a Memory from a level, a chapter from a Memory. */
        val navigate: Boolean = false,
        /** Open a medium and download its original. */
        val download: Boolean = false,
        /** Upload media into the node on screen (a Memory or a chapter). */
        val upload: Boolean = false,
        val deleteMedia: DeleteMedia = DeleteMedia.NONE,
        /** Create a Memory at a folder level (or a member's space top). */
        val createMemory: Boolean = false,
        /** Create a chapter in the Memory on screen (a chapter lives only directly in a Memory). */
        val createChapter: Boolean = false,
        /** Edit the node's date and place and a medium's capture time (the node's sealed meta). */
        val editMeta: Boolean = false,
        /** Set, re-frame or remove the node's banner. */
        val setBanner: Boolean = false,
        /** Mark media and filter by marks (sealed under the scope key). */
        val mark: Boolean = false,
        /** Narrow the gallery by its media's facts (a member's view only). */
        val filterFacts: Boolean = false,
        /** Show who was there (a member's, on a Memory). */
        val participants: Boolean = false,
        /** The node's media trash (restore, purge). */
        val mediaTrash: Boolean = false,
        /** The contributions organizer (buckets). */
        val buckets: Boolean = false,
    )

    /** Nothing: what an unknown viewer, role or kind gets. */
    val NONE = Rights()

    private const val MEMORY = "MEMORY"
    private const val CHAPTER = "CHAPTER"
    private const val FOLDER = "FOLDER"

    private fun isMemoriesNode(kind: String?) = kind == MEMORY || kind == CHAPTER
    private fun isLevel(kind: String?) = kind == null || kind == FOLDER

    /**
     * The rights of [access] on the node on screen of [kind]: a `MEMORY` or `CHAPTER` opened, or the level whose Memories
     * show (`FOLDER`; null at a member's space top).
     */
    fun of(access: Access, kind: String?): Rights = when (access) {
        is Access.Member -> member(access, kind)
        is Access.Guest -> guest(access.role, kind)
        Access.None -> NONE
    }

    private fun member(access: Access.Member, kind: String?): Rights {
        // Memories switched off is read-only: no write, no new link — but a delete to the trash and its restore stay, as
        // do reading, downloading and the member's own marks (the server's exemptions).
        val remove = !access.readonly
        val write = remove && access.memoriesOn
        if (isLevel(kind)) return Rights(navigate = true, createMemory = write && !access.inIdentityFolder)
        if (!isMemoriesNode(kind)) return NONE
        return Rights(
            navigate = true,
            download = true,
            mark = true,
            filterFacts = true,
            mediaTrash = true,
            buckets = true,
            upload = write,
            deleteMedia = if (remove) DeleteMedia.ANY else DeleteMedia.NONE,
            createChapter = write && kind == MEMORY,
            editMeta = write,
            setBanner = write,
            participants = kind == MEMORY,
        )
    }

    private fun guest(role: String, kind: String?): Rights = when (role) {
        ShareLinks.READ -> when {
            isLevel(kind) -> if (kind == FOLDER) Rights(navigate = true) else NONE
            isMemoriesNode(kind) -> Rights(navigate = true, download = true)
            else -> NONE
        }
        // A folder link's contributor creates no Memory (Memories declares no kind guest-creatable); on a Memory or chapter
        // it uploads into the node on screen and deletes its own media only.
        ShareLinks.READ_WRITE -> when {
            isLevel(kind) -> if (kind == FOLDER) Rights(navigate = true) else NONE
            isMemoriesNode(kind) -> Rights(navigate = true, download = true, upload = true, deleteMedia = DeleteMedia.OWN)
            else -> NONE
        }
        // The drop: no key below the root, so nothing to open or download; it sends into the root and removes its own.
        ShareLinks.WRITE_ONLY -> if (isMemoriesNode(kind)) Rights(upload = true, deleteMedia = DeleteMedia.OWN) else NONE
        else -> NONE
    }
}
