package me.raddatz.veyq.mobile.navigation

/**
 * Where the app is (ADR 0122): which application the drawer chose, which object is open in it, which of the bar's
 * places is selected, and whether Search has taken the screen. Both platforms render this one state; neither keeps a
 * navigation state of its own beside it.
 */
data class Shell(
    /** null: Spaces (the tree) is chosen, which belongs to every application. */
    val application: ApplicationId?,
    val openObject: ObjectRef? = null,
    val tab: BarTab? = null,
    val searching: Boolean = false,
) {
    val shape: ApplicationShape? get() = application?.let { a -> APPLICATION_SHAPES.first { it.id == a } }

    /**
     * The bar's places before Search. An application whose bar belongs to its object shows none until an object is
     * open: its screen is then the list of its objects.
     */
    val barTabs: List<BarTab>
        get() {
            val s = shape ?: return emptyList()
            return if (s.barLevel == BarLevel.OBJECT && openObject == null) emptyList() else s.tabs
        }

    val selectedTab: BarTab? get() = tab?.takeIf { it in barTabs } ?: barTabs.firstOrNull()

    /** The drawer chose an application: its list, nothing open. */
    fun choose(app: ApplicationId?): Shell = Shell(application = app)

    /** An object opens in its own application (from the drawer, a list, or Search), on its first place. */
    fun open(ref: ObjectRef): Shell = Shell(application = ref.application, openObject = ref)

    fun select(tab: BarTab): Shell = copy(tab = tab, searching = false)

    /** Back from an open object to its application's list. */
    fun close(): Shell = copy(openObject = null, tab = null)

    fun search(on: Boolean): Shell = copy(searching = on)

    companion object {
        /**
         * Where the app opens (ADR 0122 decision 4): the object used last, or — with none — Spaces with the drawer open.
         */
        fun start(lastUsed: ObjectRef?): Shell = lastUsed?.let { Shell(application = it.application, openObject = it) } ?: Shell(application = null)
    }
}
