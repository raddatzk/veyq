package me.raddatz.veyq.mobile.navigation

/**
 * Where the app is (ADR 0122): Home (one space's tree) or an application the drawer chose, which object is open,
 * which of the bar's places is selected, and whether Search has taken the screen. Both platforms render this one
 * state; neither keeps a navigation state of its own beside it.
 */
data class Shell(
    /** null: Home is chosen — the space's tree, which belongs to every application. */
    val application: ApplicationId?,
    val openObject: ObjectRef? = null,
    val tab: BarTab? = null,
    val searching: Boolean = false,
    /** The space Home shows; kept while the drawer chooses an application, so Home returns to it. */
    val space: String? = null,
    /** Home's folders below the space's root, outermost first. */
    val path: List<FolderRef> = emptyList(),
    /** Home's filter: one application's objects (the folders stay), or null for everything. */
    val filter: ApplicationId? = null,
    /**
     * Where back leads from an open object: the screen it was opened from — Home, another application's list,
     * Search, another object. Null for an object the app opened on, whose back is its own application's list.
     */
    val back: Shell? = null,
    /**
     * Chosen from the sidebar a wide window keeps open: a top-level destination as Home and the applications are, which
     * back does not leave — the sidebar is the way elsewhere (owner 2026-11-10).
     */
    val top: Boolean = false,
) {
    val shape: ApplicationShape? get() = application?.let { a -> APPLICATION_SHAPES.first { it.id == a } }

    val isHome: Boolean get() = application == null && openObject == null

    /**
     * The bar's places before Search. Home has none (its filters sit at the top, owner 2026-11-08); an application
     * whose bar belongs to its object shows none until an object is open: its screen is then its objects' list.
     */
    val barTabs: List<BarTab>
        get() {
            val s = shape ?: return emptyList()
            return if (s.barLevel == BarLevel.OBJECT && openObject == null) emptyList() else s.tabs
        }

    val selectedTab: BarTab? get() = tab?.takeIf { it in barTabs } ?: barTabs.firstOrNull()

    /** The folder Home shows: null is the space's root. */
    val folder: FolderRef? get() = path.lastOrNull()

    /** Whether back has somewhere to go inside the app: an open object not chosen at the top, or a folder below the root. */
    val canGoBack: Boolean get() = (openObject != null && !top) || (isHome && path.isNotEmpty())

    /** The drawer chose an application (null: Home): its list, nothing open, the same space kept for Home. */
    fun choose(app: ApplicationId?): Shell = Shell(application = app, space = space)

    /** Home on another space, at its root (the switcher in Home's title). */
    fun switchSpace(scopeId: String): Shell = Shell(application = null, space = scopeId)

    fun enter(folder: FolderRef): Shell = copy(path = path + folder)

    /**
     * Home at folder [folderId] (null: the space's root), its path read from [tree] (the space's): where a folder renamed
     * or moved is now. A folder the tree does not hold, or a served parent cycle, ends the path where it breaks.
     */
    fun at(tree: List<NodeRef>, folderId: String?): Shell {
        val byId = tree.associateBy { it.nodeId }
        val path = ArrayList<FolderRef>()
        val seen = HashSet<String>()
        var id = folderId
        while (id != null && seen.add(id)) {
            val n = byId[id] ?: break
            if (n.isFolder) path.add(0, FolderRef(n.nodeId, n.name))
            id = n.parentId
        }
        return copy(path = path)
    }

    /** The drawer opens at the start only where nothing is chosen yet: Home, no space, no folder. */
    val drawerOpensAtStart: Boolean get() = isHome && space == null && path.isEmpty()

    /** The folder Home shows (null: the root) as a node action takes it, in space [scopeId]. */
    fun folderAt(scopeId: String): NodeAt? = folder?.let { NodeAt(scopeId, it.nodeId, NodeRef.FOLDER, it.name) }

    fun filter(app: ApplicationId?): Shell = copy(filter = app)

    /**
     * An object opens in its own application (from the drawer, a list, Home or Search), on its first place; back
     * returns to the screen it was opened from (ADR 0122 decision 7), not to that application's list.
     */
    fun open(ref: ObjectRef): Shell = Shell(application = ref.application, openObject = ref, space = space, back = this)

    /**
     * [ref] chosen from the sidebar a wide window keeps open: the object alone, nothing to go back to ([top]). An object
     * opened from it then has it behind.
     */
    fun openAtTop(ref: ObjectRef): Shell = Shell(application = ref.application, openObject = ref, space = space, top = true)

    /**
     * [ref] chosen from the list beside an open object (a wide window's list and detail): it takes the open one's place, so
     * back still leads where that one was opened from, not through every object chosen beside it.
     */
    fun openBeside(ref: ObjectRef): Shell =
        if (openObject == null) open(ref) else Shell(application = ref.application, openObject = ref, space = space, back = back)

    /** A wide window shows the application's list beside: an application chosen, not searching. */
    val showsListBeside: Boolean get() = application != null && !searching

    fun select(tab: BarTab): Shell = copy(tab = tab, searching = false)

    /** Back from an open object: to where it was opened from, or — opened on start — to its application's list. */
    fun close(): Shell = back ?: copy(openObject = null, tab = null)

    /** Back, whatever is shown: an object closes, a folder goes up to its parent. */
    fun goBack(): Shell = when {
        openObject != null && !top -> close()
        isHome && path.isNotEmpty() -> copy(path = path.dropLast(1))
        else -> this
    }

    fun search(on: Boolean): Shell = copy(searching = on)

    companion object {
        /**
         * Where the app opens (ADR 0122 decision 5): the object used last, or — with none — Home on [space] with the
         * drawer open.
         */
        fun start(lastUsed: ObjectRef?, space: String?): Shell =
            lastUsed?.let { Shell(application = it.application, openObject = it, space = space) } ?: Shell(application = null, space = space)
    }
}
