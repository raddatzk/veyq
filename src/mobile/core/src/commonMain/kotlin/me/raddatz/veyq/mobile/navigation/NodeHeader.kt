package me.raddatz.veyq.mobile.navigation

/**
 * The actions of a node's header, for every kind (the web's `core/node/nodeHeader.ts`, `standardNodeActions` and
 * `coreNodeActions.ts`, ADR 0110): core builds them, the application narrows its kind's rights, supplies its doors and
 * hands its own actions (every one a [HeaderAction]), and each app draws the [Plan] whole in its platform's way — the
 * [Plan.buttons] as icon buttons in the bar, the rest in one "more" menu, the [Plan.last] group behind a separator; its
 * drawing is exhaustive over [HeaderAction] (ADR 0154 decision 2: no fallback arm, no whitelist, no entry of its own).
 * The rules are the web's:
 *
 * - core's actions sit around the application's: the heart before them, the copy right after the move, the download and
 *   the report after them all ([placed]);
 * - the kind's priority (`header.order`, [me.raddatz.veyq.mobile.AppKinds.headerOrder]) puts the actions it lists first, in its order; every other follows in that merge
 *   order, so an action no order names still appears ([ordered]);
 * - the move, the copy, the report and the delete are never buttons: they are the menu's last group, behind a separator,
 *   the delete last ([Slot.LAST]);
 * - an app has no rename in place and its cover is no visual one taps, so both are menu entries, never buttons
 *   ([Slot.MENU]).
 *
 * How many buttons fit is the platform's answer (`maxButtons`), not a measurement: a bar's room is the platform's.
 */
object NodeHeader {
    /** Where an action may go: a button where it fits, else the menu ([ANY]); the menu ([MENU]); the menu's last group. */
    enum class Slot { ANY, MENU, LAST }

    /** Where a core action sits among the application's: before them, after them, or right after the action [afterId]. */
    class Placement private constructor(val before: Boolean, val afterId: HeaderAction?) {
        companion object {
            val BEFORE = Placement(true, null)
            val AFTER = Placement(false, null)
            fun after(id: HeaderAction) = Placement(false, id)
        }
    }

    /**
     * What may be offered on the node, every flag the page's rights answer AND its door (absent door: not offered, fail
     * closed): core's base narrowed by the application's rights function for its kind (a Pot of a switched-off Expenses
     * takes no cover and no copy).
     */
    data class Rights(
        /** The heart: the space's pin set is loaded and the node may be pinned. */
        val favorite: Boolean,
        val share: Boolean,
        val rename: Boolean,
        val move: Boolean,
        val cover: Boolean,
        /** A copy door: the application copies its kind (the web's `copy` door). */
        val copy: Boolean,
        /** A download door: the node's ZIP (the web's `download` door). */
        val download: Boolean,
        /** The server publishes a moderation key (ADR 0093: a legal duty, so wherever it does). */
        val report: Boolean,
        val delete: Boolean,
        /** "Available offline": the account keeps an offline store on this device and the kind is pinned (ADR 0140 decision 9). */
        val offline: Boolean = false,
    )

    /**
     * [rights] narrowed for a node that is a guest's identity folder or lies in one (the web's `nodeRights`, `folderRights`
     * and `coverRights`): no link roots there, nothing there is renamed or takes a cover, and a folder there is not deleted
     * as a node (the drop box's page removes a guest's folder; a member deletes in it file by file). Its move stays: out of
     * the drop box is the one placement it takes.
     */
    fun inIdentityFolder(rights: Rights, kind: String): Rights =
        rights.copy(share = false, rename = false, cover = false, delete = rights.delete && kind != "FOLDER")

    /**
     * A header's rights as both apps build them (the web's `NodePage`): the heart once the pin set is known, a link where
     * the kind shares and the tenant's links are on, the kind's rename, a cover where the kind takes one and its page
     * offers it, its copy and download doors, the report where the server publishes its key; the move and the delete
     * always — then narrowed where the node is a guest's folder or lies in one ([inIdentityFolder]); "Available offline"
     * where [offline] offers it. Offline ([online] false) every write is taken away (ADR 0140 decision 7): what is left
     * reads — the download — or is this device's own: "Available offline" only where the node is made available already
     * ([offlineOn]: turning it off keeps no byte and asks no server). A pin offline is not offered: it cannot be sized and
     * confirmed before its first byte nor fetch (ADR 0140 decision 9), and nothing is queued (decision 7).
     */
    fun rights(
        kind: String, favoriteKnown: Boolean, shareable: Boolean, linksOn: Boolean, rename: Boolean, takesCover: Boolean,
        coverOffered: Boolean, copy: Boolean, download: Boolean, report: Boolean, inGuestFolder: Boolean,
        offline: Boolean = false, online: Boolean = true, offlineOn: Boolean = false,
    ): Rights {
        val granted = Rights(
            favorite = favoriteKnown, share = shareable && linksOn, rename = rename, move = true,
            cover = takesCover && coverOffered, copy = copy, download = download, report = report, delete = true, offline = offline,
        )
        val narrowed = if (inGuestFolder) inIdentityFolder(granted, kind) else granted
        return if (online) narrowed else readOnly(narrowed).let { it.copy(offline = it.offline && offlineOn) }
    }

    /** [rights] while the server is unreachable: no write (the heart, the link, rename, move, cover, copy, report, delete). */
    fun readOnly(rights: Rights): Rights = rights.copy(
        favorite = false, share = false, rename = false, move = false, cover = false, copy = false, report = false, delete = false,
    )

    /**
     * A core action's words in the header: the heart's by whether it is pinned, the share sheet's title of the kind, the
     * cover's by whether one is set, a kept link's report its name's (the web's `report-name`). Null for the delete (its
     * kind's own title) and for an application's own action (its page model's words: `GalleryModel.label`,
     * `FileModel.Header.label`, `BookmarkPage.label`).
     */
    fun label(id: HeaderAction, kind: String, pinned: Boolean?, hasCover: Boolean): me.raddatz.veyq.mobile.words.Msg? = when (id) {
        HeaderAction.FAVORITE -> if (pinned == true) me.raddatz.veyq.mobile.words.Msg.NODE_UNFAVORITE else me.raddatz.veyq.mobile.words.Msg.NODE_FAVORITE
        HeaderAction.SHARE -> me.raddatz.veyq.mobile.session.ShareWords.of(kind).title
        HeaderAction.RENAME -> me.raddatz.veyq.mobile.words.Msg.NODE_RENAME
        HeaderAction.MOVE -> me.raddatz.veyq.mobile.words.Msg.NODE_MOVE
        HeaderAction.COPY -> me.raddatz.veyq.mobile.words.Msg.COPY_ACTION
        HeaderAction.COVER -> if (hasCover) me.raddatz.veyq.mobile.words.Msg.COVER_CHANGE else me.raddatz.veyq.mobile.words.Msg.COVER_SET
        HeaderAction.DOWNLOAD -> me.raddatz.veyq.mobile.words.Msg.NODE_DOWNLOAD
        HeaderAction.REPORT ->
            if (me.raddatz.veyq.mobile.AppKinds.reportsNameOnly(kind)) me.raddatz.veyq.mobile.words.Msg.BOOKMARK_REPORT_NAME
            else me.raddatz.veyq.mobile.words.Msg.REPORT_ACTION
        HeaderAction.OFFLINE -> me.raddatz.veyq.mobile.words.Msg.OFFLINE_AVAILABLE
        HeaderAction.DELETE, HeaderAction.UPLOAD, HeaderAction.MUSIC, HeaderAction.BANNER, HeaderAction.REPLACE, HeaderAction.SEND,
        HeaderAction.OPEN_OWN_PAGE, HeaderAction.REPORT_SHARED -> null
    }

    /**
     * The header's split: the [buttons] in order, then the menu's [menu] group and, after a separator, its [last] group, the
     * delete last of all. A platform draws every entry, in this order (ADR 0154 decision 2).
     */
    data class Plan(val buttons: List<HeaderAction>, val menu: List<HeaderAction>, val last: List<HeaderAction>) {
        /** Whether there is a "more" menu at all. */
        val hasMenu: Boolean get() = menu.isNotEmpty() || last.isNotEmpty()
    }

    /**
     * The plan of a node's header: core's actions where [rights] grants them, around the application's [own] (in its
     * order), in the kind's priority [order], at most [maxButtons] of them buttons.
     */
    fun plan(rights: Rights, order: List<HeaderAction>, maxButtons: Int, own: List<HeaderAction> = emptyList()): Plan {
        val actions = ordered(placed(core(rights), standard(rights) + own), order)
        val buttons = actions.filter { it.slot == Slot.ANY }.take(maxButtons.coerceAtLeast(0))
        val menu = actions.filter { it.slot != Slot.LAST && it !in buttons }
        val lastGroup = actions.filter { it.slot == Slot.LAST }
        // a kept link's share report right after its name's (`coreNodeActions.ts`' `bookmarkReport`), the delete last of
        // all (`partitionHeaderActions`: a danger action ends the menu)
        val tail = listOf(HeaderAction.REPORT_SHARED, HeaderAction.DELETE)
        val last = lastGroup.filter { it !in tail } + tail.filter { it in lastGroup }
        return Plan(buttons, menu, last)
    }

    /** The standard ones (`standardNodeActions`), as the application's list starts: rename, share, move, delete. */
    internal fun standard(rights: Rights): List<HeaderAction> = listOfNotNull(
        HeaderAction.RENAME.takeIf { rights.rename },
        HeaderAction.SHARE.takeIf { rights.share },
        HeaderAction.MOVE.takeIf { rights.move },
        HeaderAction.DELETE.takeIf { rights.delete },
    )

    /**
     * Core's own (`coreNodeActions`) in their merge order ([CORE_ORDER]): the heart before, the copy beside the move, cover,
     * download, "Available offline" and report after.
     */
    internal fun core(rights: Rights): List<Pair<HeaderAction, Placement>> = listOfNotNull(
        (HeaderAction.FAVORITE to Placement.BEFORE).takeIf { rights.favorite },
        (HeaderAction.COVER to Placement.AFTER).takeIf { rights.cover },
        (HeaderAction.COPY to Placement.after(HeaderAction.MOVE)).takeIf { rights.copy },
        (HeaderAction.DOWNLOAD to Placement.AFTER).takeIf { rights.download },
        (HeaderAction.OFFLINE to Placement.AFTER).takeIf { rights.offline },
        (HeaderAction.REPORT to Placement.AFTER).takeIf { rights.report },
    )

    /** Core's actions in the order [core] merges them, every one granted — what a test holds to `coreNodeActions.ts`. */
    val CORE_ORDER: List<HeaderAction> get() = core(Rights(true, true, true, true, true, true, true, true, true, offline = true)).map { it.first }

    /** The web's `placeCoreActions`: core's before, the application's each followed by core's beside it, core's after. */
    internal fun placed(core: List<Pair<HeaderAction, Placement>>, app: List<HeaderAction>): List<HeaderAction> {
        val ids = app.toSet()
        val before = core.filter { it.second.before }.map { it.first }
        fun beside(id: HeaderAction) = core.filter { it.second.afterId == id }.map { it.first }
        val after = core.filter { !it.second.before && (it.second.afterId == null || it.second.afterId !in ids) }.map { it.first }
        return before + app.flatMap { listOf(it) + beside(it) } + after
    }

    /** The web's `orderHeaderActions`: the ids [order] lists first, in its order, then every other in the given order. */
    internal fun ordered(actions: List<HeaderAction>, order: List<HeaderAction>): List<HeaderAction> {
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return actions.filter { it in rank }.sortedBy { rank.getValue(it) } + actions.filter { it !in rank }
    }
}
