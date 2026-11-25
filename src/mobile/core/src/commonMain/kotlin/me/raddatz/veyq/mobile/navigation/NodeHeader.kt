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
        offline: Boolean = false, online: Boolean = true, offlineOn: Boolean = false, viewOnly: Boolean = false,
    ): Rights {
        // core's three rules (`CoreRights`, the web's too): an app's header and row are a member's, on a node, under open keys;
        // the heart where this client draws the kind (and the pin set is known), the cover where its kind and page offer one
        // — a switched-off application's object offers none through its doors (`AppKinds.Facts.canWrite`) —, a kept link's
        // rename, move and delete as the node's
        val known = me.raddatz.veyq.mobile.AppKinds.header(kind) != null
        val bookmark = CoreRights.bookmark(member = true, node = true, keys = true, rename = rename, move = true, delete = true)
        val granted = Rights(
            favorite = favoriteKnown && CoreRights.favorite(member = true, node = true, known = known),
            share = shareable && linksOn, rename = bookmark.rename, move = bookmark.move,
            cover = CoreRights.cover(
                member = true, keys = true, readonly = false, node = true, inIdentityFolder = inGuestFolder, applicationOn = true,
                takesCover = takesCover && coverOffered,
            ),
            copy = copy, download = download, report = report, delete = bookmark.delete, offline = offline,
        )
        val narrowed = if (inGuestFolder) inIdentityFolder(granted, kind) else granted
        if (viewOnly) return viewOnly(narrowed)
        return if (online) narrowed else readOnly(narrowed).let { it.copy(offline = it.offline && offlineOn) }
    }

    /**
     * [rights] on a device that only views (a TV, ADR 0159 decisions 2-3): every write [readOnly] takes away, and every way
     * to take the node off the device too — the download and "Available offline" (a TV keeps no store). The report stays
     * where the server takes one: reporting is no authoring, and the DSA's notice mechanism (Art. 16) is plausibly owed
     * "easy to access" where the content is shown — the safe side, cheap to keep (ADR 0159's decision log).
     */
    fun viewOnly(rights: Rights): Rights = readOnly(rights).copy(download = false, offline = false, report = rights.report)

    /** [rights] while the server is unreachable: no write (the heart, the link, rename, move, cover, copy, report, delete). */
    fun readOnly(rights: Rights): Rights = rights.copy(
        favorite = false, share = false, rename = false, move = false, cover = false, copy = false, report = false, delete = false,
    )

    /**
     * A core action's words in the header: the heart's by whether it is pinned, the share sheet's title of the kind, the
     * cover's by whether one is set, a kept link's report its name's (the web's `report-name`) and its share's report (a
     * row's, which has no page to word it). Null for the delete (its
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
        // a kept link's row offers it with no page to word it (`ShellModel.rowPlan`): the page's word (`BookmarkPage.label`)
        HeaderAction.REPORT_SHARED -> me.raddatz.veyq.mobile.words.Msg.BOOKMARK_REPORT_SHARE
        HeaderAction.DELETE, HeaderAction.UPLOAD, HeaderAction.MUSIC, HeaderAction.BANNER, HeaderAction.REPLACE, HeaderAction.SEND,
        HeaderAction.OPEN_OWN_PAGE -> null
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
        val buttons = ArrayList<HeaderAction>()
        val menu = ArrayList<HeaderAction>()
        val last = ArrayList<HeaderAction>()
        planInto(granted(rights), order, maxButtons, own, buttons, menu, last)
        return Plan(buttons, menu, last)
    }

    /**
     * [plan] over the entries [granted] grants (each right its entry of one name, [granted]), written into [buttons], [menu]
     * and [last]: the web's façade plans with it, so no [Rights] or [Plan] is built on its path (ADR 0155 § The rights
     * and plans' slice). Written as loops over lists: no set, map or sort on a JavaScript path.
     */
    fun planInto(
        granted: List<HeaderAction>, order: List<HeaderAction>, maxButtons: Int, own: List<HeaderAction>,
        buttons: MutableList<HeaderAction>, menu: MutableList<HeaderAction>, last: MutableList<HeaderAction>,
    ) {
        val actions = ordered(placed(granted, own), order)
        var room = if (maxButtons < 0) 0 else maxButtons
        for (a in actions) if (a.slot == Slot.ANY && room > 0) { buttons.add(a); room-- }
        for (a in actions) if (a.slot != Slot.LAST && a !in buttons) menu.add(a)
        // a kept link's share report right after its name's (`coreNodeActions.ts`' `bookmarkReport`), the delete last of
        // all (`partitionHeaderActions`: a danger action ends the menu)
        for (a in actions) if (a.slot == Slot.LAST && a != HeaderAction.REPORT_SHARED && a != HeaderAction.DELETE) last.add(a)
        if (HeaderAction.REPORT_SHARED in actions) last.add(HeaderAction.REPORT_SHARED)
        if (HeaderAction.DELETE in actions) last.add(HeaderAction.DELETE)
    }

    /**
     * The entries [rights] grants: each right is the entry of its name — the heart [HeaderAction.FAVORITE], the link
     * [HeaderAction.SHARE], … "Available offline" [HeaderAction.OFFLINE] — in [RIGHTS]' order.
     */
    fun granted(rights: Rights): List<HeaderAction> {
        val out = ArrayList<HeaderAction>(RIGHTS.size)
        for (a in RIGHTS) if (grants(rights, a)) out.add(a)
        return out
    }

    private fun grants(r: Rights, a: HeaderAction): Boolean = when (a) {
        HeaderAction.FAVORITE -> r.favorite
        HeaderAction.SHARE -> r.share
        HeaderAction.RENAME -> r.rename
        HeaderAction.MOVE -> r.move
        HeaderAction.COVER -> r.cover
        HeaderAction.COPY -> r.copy
        HeaderAction.DOWNLOAD -> r.download
        HeaderAction.REPORT -> r.report
        HeaderAction.DELETE -> r.delete
        HeaderAction.OFFLINE -> r.offline
        HeaderAction.UPLOAD, HeaderAction.MUSIC, HeaderAction.BANNER, HeaderAction.REPLACE, HeaderAction.SEND,
        HeaderAction.OPEN_OWN_PAGE, HeaderAction.REPORT_SHARED -> false
    }

    /** The entries a [Rights] grants, one per right; every other entry is a page's own. */
    val RIGHTS: List<HeaderAction> = listOf(
        HeaderAction.FAVORITE, HeaderAction.SHARE, HeaderAction.RENAME, HeaderAction.MOVE, HeaderAction.COVER, HeaderAction.COPY,
        HeaderAction.DOWNLOAD, HeaderAction.REPORT, HeaderAction.DELETE, HeaderAction.OFFLINE,
    )

    /** The standard ones (`standardNodeActions`), as the application's list starts: rename, share, move, delete. */
    private val STANDARD: List<HeaderAction> = listOf(HeaderAction.RENAME, HeaderAction.SHARE, HeaderAction.MOVE, HeaderAction.DELETE)

    /**
     * Core's own (`coreNodeActions`) in their merge order: the heart before the application's, the copy beside the move
     * ([besideOf]), cover, download, "Available offline" and report after them.
     */
    val CORE_ORDER: List<HeaderAction> = listOf(
        HeaderAction.FAVORITE, HeaderAction.COVER, HeaderAction.COPY, HeaderAction.DOWNLOAD, HeaderAction.OFFLINE, HeaderAction.REPORT,
    )

    /** The application's entry a core one sits right after where the application offers it (the copy beside the move). */
    private fun besideOf(core: HeaderAction): HeaderAction? = if (core == HeaderAction.COPY) HeaderAction.MOVE else null

    /**
     * The web's `placeCoreActions`: core's granted ones before (the heart), the application's — the standard ones [granted]
     * grants, then [own] — each followed by core's beside it, then core's after.
     */
    internal fun placed(granted: List<HeaderAction>, own: List<HeaderAction>): List<HeaderAction> {
        val app = ArrayList<HeaderAction>()
        for (a in STANDARD) if (a in granted) app.add(a)
        app.addAll(own)
        val out = ArrayList<HeaderAction>()
        if (HeaderAction.FAVORITE in granted) out.add(HeaderAction.FAVORITE)
        for (a in app) {
            out.add(a)
            for (c in CORE_ORDER) if (c in granted && besideOf(c) == a) out.add(c)
        }
        for (c in CORE_ORDER) {
            if (c == HeaderAction.FAVORITE || c !in granted) continue
            val beside = besideOf(c)
            if (beside == null || beside !in app) out.add(c)
        }
        return out
    }

    /** The web's `orderHeaderActions`: the ids [order] lists first, in its order, then every other in the given order. */
    internal fun ordered(actions: List<HeaderAction>, order: List<HeaderAction>): List<HeaderAction> {
        val out = ArrayList<HeaderAction>(actions.size)
        for (o in order) if (o in actions && o !in out) out.add(o)
        for (a in actions) if (a !in order) out.add(a)
        return out
    }
}
