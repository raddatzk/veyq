package me.raddatz.veyq.mobile.navigation

/**
 * The actions of a node's header, for every kind (the web's `core/node/nodeHeader.ts`, `standardNodeActions` and
 * `coreNodeActions.ts`, ADR 0110): core builds them, the application narrows its kind's rights, supplies its doors and
 * may add actions of its own, and each app draws the [Plan] in its platform's way — the [Plan.buttons] as icon buttons in
 * the bar, the rest in one "more" menu. The rules are the web's:
 *
 * - core's actions sit around the application's: the heart before them, the copy right after the move, the download and
 *   the report after them all ([placed]);
 * - the kind's priority (`header.order`) puts the ids it lists first, in its order; every other follows in that merge
 *   order, so an action no order names still appears ([ordered]);
 * - the move, the copy, the report and the delete are never buttons: they are the menu's last group, behind a separator,
 *   the delete last ([Slot.LAST]);
 * - an app has no rename in place and its cover is no visual one taps, so both are menu entries, never buttons
 *   ([Slot.MENU]).
 *
 * How many buttons fit is the platform's answer (`maxButtons`), not a measurement: a bar's room is the platform's.
 */
object NodeHeader {
    const val FAVORITE = "favorite"
    const val SHARE = "share"
    const val RENAME = "rename"
    const val MOVE = "move"
    const val COPY = "copy"
    const val COVER = "cover"
    const val DOWNLOAD = "download"
    const val REPORT = "report"
    const val DELETE = "delete"

    /** Where an action may go: a button where it fits, else the menu ([ANY]); the menu ([MENU]); the menu's last group. */
    enum class Slot { ANY, MENU, LAST }

    /** One action: its id (core's above, or the application's own), and where it may go. */
    data class Action(val id: String, val slot: Slot = Slot.ANY)

    /** Where a core action sits among the application's: before them, after them, or right after the action [afterId]. */
    class Placement private constructor(val before: Boolean, val afterId: String?) {
        companion object {
            val BEFORE = Placement(true, null)
            val AFTER = Placement(false, null)
            fun after(id: String) = Placement(false, id)
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
     * always — then narrowed where the node is a guest's folder or lies in one ([inIdentityFolder]).
     */
    fun rights(
        kind: String, favoriteKnown: Boolean, shareable: Boolean, linksOn: Boolean, rename: Boolean, takesCover: Boolean,
        coverOffered: Boolean, copy: Boolean, download: Boolean, report: Boolean, inGuestFolder: Boolean,
    ): Rights {
        val granted = Rights(
            favorite = favoriteKnown, share = shareable && linksOn, rename = rename, move = true,
            cover = takesCover && coverOffered, copy = copy, download = download, report = report, delete = true,
        )
        return if (inGuestFolder) inIdentityFolder(granted, kind) else granted
    }

    /**
     * A core action's words in the header: the heart's by whether it is pinned, the share sheet's title of the kind, the
     * cover's by whether one is set. Null for the delete (its kind's own title) and an application's own action.
     */
    fun label(id: String, kind: String, pinned: Boolean?, hasCover: Boolean): me.raddatz.veyq.mobile.words.Msg? = when (id) {
        FAVORITE -> if (pinned == true) me.raddatz.veyq.mobile.words.Msg.NODE_UNFAVORITE else me.raddatz.veyq.mobile.words.Msg.NODE_FAVORITE
        SHARE -> me.raddatz.veyq.mobile.session.ShareWords.of(kind).title
        RENAME -> me.raddatz.veyq.mobile.words.Msg.NODE_RENAME
        MOVE -> me.raddatz.veyq.mobile.words.Msg.NODE_MOVE
        COPY -> me.raddatz.veyq.mobile.words.Msg.COPY_ACTION
        COVER -> if (hasCover) me.raddatz.veyq.mobile.words.Msg.COVER_CHANGE else me.raddatz.veyq.mobile.words.Msg.COVER_SET
        DOWNLOAD -> me.raddatz.veyq.mobile.words.Msg.NODE_DOWNLOAD
        REPORT -> me.raddatz.veyq.mobile.words.Msg.REPORT_ACTION
        else -> null
    }

    /** The header's split: the [buttons] in order, then the menu's [menu] group and, after a separator, its [last] group. */
    data class Plan(val buttons: List<String>, val menu: List<String>, val last: List<String>) {
        /** Whether there is a "more" menu at all. */
        val hasMenu: Boolean get() = menu.isNotEmpty() || last.isNotEmpty()
    }

    /**
     * The plan of a node's header: core's actions where [rights] grants them, around the application's [own] (in its
     * order), in the kind's priority [order], at most [maxButtons] of them buttons.
     */
    fun plan(rights: Rights, order: List<String>, maxButtons: Int, own: List<Action> = emptyList()): Plan {
        // the standard ones (`standardNodeActions`), as the application's list starts: rename, share, move, delete
        val standard = listOfNotNull(
            Action(RENAME, Slot.MENU).takeIf { rights.rename },
            Action(SHARE).takeIf { rights.share },
            Action(MOVE, Slot.LAST).takeIf { rights.move },
            Action(DELETE, Slot.LAST).takeIf { rights.delete },
        )
        // core's own (`coreNodeActions`): the heart before, the copy beside the move, cover, download and report after
        val core = listOfNotNull(
            (Action(FAVORITE) to Placement.BEFORE).takeIf { rights.favorite },
            (Action(COVER, Slot.MENU) to Placement.AFTER).takeIf { rights.cover },
            (Action(COPY, Slot.LAST) to Placement.after(MOVE)).takeIf { rights.copy },
            (Action(DOWNLOAD) to Placement.AFTER).takeIf { rights.download },
            (Action(REPORT, Slot.LAST) to Placement.AFTER).takeIf { rights.report },
        )
        val actions = ordered(placed(core, standard + own), order)
        val buttons = actions.filter { it.slot == Slot.ANY }.take(maxButtons.coerceAtLeast(0)).map { it.id }
        val menu = actions.filter { it.slot != Slot.LAST && it.id !in buttons }.map { it.id }
        val lastGroup = actions.filter { it.slot == Slot.LAST }
        // the delete last of all (`partitionHeaderActions`: a danger action ends the menu)
        val last = lastGroup.filter { it.id != DELETE }.map { it.id } + lastGroup.filter { it.id == DELETE }.map { it.id }
        return Plan(buttons, menu, last)
    }

    /** The web's `placeCoreActions`: core's before, the application's each followed by core's beside it, core's after. */
    internal fun placed(core: List<Pair<Action, Placement>>, app: List<Action>): List<Action> {
        val ids = app.map { it.id }.toSet()
        val before = core.filter { it.second.before }.map { it.first }
        fun beside(id: String) = core.filter { it.second.afterId == id }.map { it.first }
        val after = core.filter { !it.second.before && (it.second.afterId == null || it.second.afterId !in ids) }.map { it.first }
        return before + app.flatMap { listOf(it) + beside(it.id) } + after
    }

    /** The web's `orderHeaderActions`: the ids [order] lists first, in its order, then every other in the given order. */
    internal fun ordered(actions: List<Action>, order: List<String>): List<Action> {
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return actions.filter { it.id in rank }.sortedBy { rank.getValue(it.id) } + actions.filter { it.id !in rank }
    }
}
