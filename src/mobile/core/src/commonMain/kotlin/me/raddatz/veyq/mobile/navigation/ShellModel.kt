package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.offline.QueuedItem
import me.raddatz.veyq.mobile.offline.QueuedShown
import me.raddatz.veyq.mobile.offline.QueuedTray
import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.PlatformApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.NodeHeaderModel.Renamed
import me.raddatz.veyq.mobile.navigation.NodeHeaderModel.Trashed
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeRename
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.ShareLinkChanges
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.session.StorageFit
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

/**
 * The shell over one tenant (ADR 0122, ADR 0136 decision 2): what it lists — its spaces, nodes, objects and favorites as
 * this device shows them ([Hidden]), the drawer's short lists ([RecentUses]), the applications and Home's filters — and
 * where the person is ([Shell]), with every action on a node that changes what is listed: a favorite, the trash and its
 * undo, a rename, a move, a new folder, a copy. Each reads the lists anew and keeps the shell on what is still there: a
 * page whose node went leaves for its parent, a folder on Home's path that went or moved shows where it is now.
 *
 * A UI observes [state] (or [watch]) and calls the actions; what it draws and the device's words stay its own.
 *
 * The platform's (ADR 0156, inbox #1215): it names no application. Each application's pages over this shell — a gallery,
 * a file page, the uploads, a Memory made — are extensions in its own module (`memories/ShellPages.kt`,
 * `depot/ShellPages.kt`), reading the shell through the members opened to them below ([PlatformApi], hidden from
 * Objective-C); Swift calls them as before, as methods of a category on this class.
 */
class ShellModel(
    val tenant: TenantSession,
    private val hidden: Hidden,
    private val recent: RecentUses,
    @PlatformApi @HiddenFromObjC val now: () -> Long,
) : ShellDoor {
    /** What the shell lists, this device's hidden spaces taken out. */
    @Serializable
    class Lists(
        val spaces: List<SpaceRef>,
        val nodes: List<NodeRef>,
        val objects: List<ObjectRef>,
        val favorites: List<ObjectRef>,
        val applications: List<ApplicationId>,
        val filters: List<ApplicationId?>,
        val drawer: ArrangedList,
        /** What this device opened, of [objects] (Recent): an application's own list arranges by it too. */
        val uses: List<Use> = emptyList(),
    ) {
        /**
         * The chosen application's own list, by the one rule (ADR 0122 decision 5): its Favorites, its Recent, the rest —
         * each by last use. Its Recent was once left empty here, so on every app the list showed no Recent at all.
         */
        fun applicationList(app: ApplicationId): ArrangedList =
            arrange(favorites.filter { it.application == app }, uses.filter { it.ref.application == app }, objects.filter { it.application == app })

        /** Home's level: the shown folder's children in the shown space, under the shown filter. */
        fun level(shell: Shell): List<NodeRef> = shell.space?.let { Home.level(nodes, it, shell.folder?.nodeId, shell.filter) }.orEmpty()

        /** Home's level in its groups, folders apart ([Home.groups]). */
        fun groups(shell: Shell): List<Home.Group> = Home.groups(level(shell))

        fun space(shell: Shell): SpaceRef? = spaces.firstOrNull { it.scopeId == shell.space }

        /**
         * The drawer's sections that list something ([DrawerSection.shown]); an empty one is not drawn. Its New is the
         * shell's to say ([ShellModel.drawerSections] holds it): a platform draws that one.
         */
        internal val listedSections: List<DrawerSection> get() = DrawerSection.shown(drawer, applications)
    }

    /**
     * Serializable, since the native bridge sends it as it is (ADR 0123's Consequences, the bridge). [offline]: the lists
     * are the offline store's, read-only, as of when (ADR 0140 decision 5, the shell's banner: [banner]); null online.
     * [gone]: each item made available offline that was removed from this device since it is no longer available (ADR
     * 0140 decision 6), named where the shell held its name ([me.raddatz.veyq.mobile.offline.OfflineShown.gone]) — shown
     * until [goneSeen]. [pages]: the kept time each page of the tenant shows offline, by node id
     * ([me.raddatz.veyq.mobile.offline.PagesOffline]) — the open one's dates the banner.
     */
    @Serializable
    class State(
        val shell: Shell,
        val lists: Lists,
        val offline: me.raddatz.veyq.mobile.session.OfflineAsOf? = null,
        val gone: List<Notice> = emptyList(),
        @kotlinx.serialization.Transient val pages: Map<String, Long> = emptyMap(),
        /**
         * The shown level's selection while its Select mode is on ([LevelSelection], inbox #1049); null otherwise. Another
         * place clears it, and a node that left the level drops out of it.
         */
        val selection: LevelSelection.Picking? = null,
    ) {
        /**
         * The banner offline: "Offline — content as of {time}", the time the open page's own (a Pot's kept ledger, a
         * gallery's kept listing) where it has one, else the lists'; null online.
         */
        val banner: Notice? get() = me.raddatz.veyq.mobile.offline.OfflineShown.banner(offline, shell.openObject?.let { pages[it.nodeId] })

        internal fun moved(shell: Shell, lists: Lists = this.lists) = State(shell, lists, offline, gone, pages, selection)
    }

    /** The tenant as read, before what this device hides is taken out. */
    private class Raw(val spaces: List<SpaceRef>, val nodes: List<NodeRef>, val favorites: List<ObjectRef>, val off: Set<String>)

    private var raw: Raw? = null
    private val reading = Mutex()

    private val _state = MutableStateFlow<State?>(null)

    /** Every change of the state, its level's selection kept on what the level still holds ([reselected]). */
    private fun put(change: (State?) -> State?) = _state.update { prior -> change(prior)?.let { reselected(prior, it) } }
    val state: StateFlow<State?> = _state.asStateFlow()

    /** A watch of [state] for a platform without flows: stopped with [stop]. */
    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch =
        Watch(CoroutineScope(Dispatchers.Default).launch { state.collect { it?.let(listener) } })

    val shell: Shell? get() = _state.value?.shell

    /** The state now (what [watch] delivers next), for a platform that reads it at once after an action. */
    val current: State? get() = _state.value

    // ---- reading ----

    /**
     * Reads the tenant and opens the shell as the app starts on it: on the object last used ([openLastUsed]; not while
     * something shared waits for its place), else Home's default space.
     */
    suspend fun load(openLastUsed: Boolean) {
        reading.withLock { raw = read() }
        rebuild(start = true, openLastUsed = openLastUsed)
        // a re-key the tenant ran (its background resume, a link closed) changed keys the lists were read under
        if (rekeys == null) rekeys = CoroutineScope(Dispatchers.Default).launch { tenant.rekeyed.drop(1).collect { refresh() } }
        // offline the lists are the store's and say so; back online they are read anew (ADR 0140 decision 5)
        if (reaches == null) reaches = CoroutineScope(Dispatchers.Default).launch {
            tenant.account.reach.drop(1).collect { if (it == Reach.Online) refresh() else rebuild() }
        }
        // writes made offline sent after a reconnect: the lists are read anew, so what was made shows as itself (ADR 0153)
        if (sentWatch == null) sentWatch = CoroutineScope(Dispatchers.Default).launch {
            tenant.account.queuedReports.collect { if (it.sent > 0) refresh() }
        }
        // a rename made offline shows in place until it is sent, discarded or held (#1040); a folder made offline is entered
        // until it is sent, and Home leaves it where it is discarded or refused (#1095)
        val box = tenant.account.outbox
        if (renameWatch == null && box != null) renameWatch = CoroutineScope(Dispatchers.Default).launch {
            box.changes.collect {
                val made = madeQueued()
                if (made != madeUnsent) {
                    madeUnsent = made
                    rebuild()
                    leaveUnsent()
                }
                val now = renamesQueued()
                if (now != renamedOffline) {
                    renamedOffline = now
                    rebuild()
                    // a page open on a node whose queued name went (discarded, held) heads it as the lists do again
                    shell?.openObject?.let { open ->
                        current?.lists?.objects?.firstOrNull { it.scopeId == open.scopeId && it.nodeId == open.nodeId && it.name != open.name }
                            ?.let { o -> update { afterRename(it, o) } }
                    }
                }
            }
        }
        watchGone()
        if (pagesWatch == null) pagesWatch = CoroutineScope(Dispatchers.Default).launch {
            tenant.pagesOffline.shown.collect { p -> put { s -> s?.let { State(it.shell, it.lists, it.offline, it.gone, p) } } }
        }
    }

    private var pagesWatch: kotlinx.coroutines.Job? = null

    private var reaches: kotlinx.coroutines.Job? = null
    private var sentWatch: kotlinx.coroutines.Job? = null
    private var renameWatch: kotlinx.coroutines.Job? = null

    /**
     * The names given offline and waiting to be sent, by node id (ADR 0153 decision 5, #1040): the lists show each node
     * under it ([overlaid]). A rename held for the person shows the name the server holds, as its *Open* asks about it.
     */
    @kotlin.concurrent.Volatile
    private var renamedOffline: Map<String, String> = emptyMap()

    /**
     * The folders, Memories and chapters made offline on this device and not refused, each as the node it will be (its id
     * the write's, ADR 0153 decision 3): a folder made offline is entered as a level of its own until it is sent (#1095) —
     * Home's path holds it, a level's New and uploads queue into it, the tray names it as the place they go.
     */
    @kotlin.concurrent.Volatile
    private var madeUnsent: List<NodeRef> = emptyList()

    private suspend fun madeQueued(): List<NodeRef> =
        tenant.account.outbox?.writes().orEmpty()
            .filter { it.tenant == tenantId && it.state != me.raddatz.veyq.mobile.offline.QueuedWrite.State.REFUSED &&
                (it.op == me.raddatz.veyq.mobile.offline.Queued.FOLDER_CREATE.name || it.op == me.raddatz.veyq.mobile.offline.Queued.MEMORY_CREATE.name) }
            .map { QueuedShown.madeNode(it) }

    /** Whether [nodeId] is a folder made offline and not sent yet ([madeUnsent]). */
    private fun unsentFolder(nodeId: String?): Boolean = nodeId != null && madeUnsent.any { it.nodeId == nodeId && it.isFolder }

    /**
     * [scopeId]'s tree as it is now with the folders made there offline and not refused: what Home's path is read from
     * ([Shell.at]) — a folder made offline is where its queued create puts it, under the name it carries.
     */
    private suspend fun treeWithUnsent(scopeId: String): List<NodeRef> {
        madeUnsent = madeQueued()
        val read = overlaid(scopeNodes(scopeId))
        return read + madeUnsent.filter { m -> m.isFolder && m.scopeId == scopeId && read.none { it.nodeId == m.nodeId } }
    }

    /**
     * Home's path cut above a folder that is neither listed nor made offline and waiting any more — its create discarded
     * from the tray (with what waited in it) or refused at its send (#1095): Home shows the nearest level still there. The
     * tree is read anew first: a folder whose create was just sent is the server's now, and Home stays in it.
     */
    private suspend fun leaveUnsent() {
        val s = shell ?: return
        val space = s.space ?: return
        val listed = current?.lists?.nodes.orEmpty()
        if (s.path.all { f -> listed.any { it.nodeId == f.nodeId } || madeUnsent.any { it.nodeId == f.nodeId } }) return
        tenant.forgetTree()
        val tree = treeWithUnsent(space)
        update { sh ->
            if (sh.space != space) return@update sh
            val gone = sh.path.indexOfFirst { f -> tree.none { it.nodeId == f.nodeId } }
            if (gone < 0) sh else sh.at(tree, if (gone > 0) sh.path[gone - 1].nodeId else null)
        }
    }

    private suspend fun renamesQueued(): Map<String, String> =
        tenant.account.outbox?.writes().orEmpty()
            .filter { it.tenant == tenantId && it.op == me.raddatz.veyq.mobile.offline.Queued.NODE_RENAME.name && it.state == me.raddatz.veyq.mobile.offline.QueuedWrite.State.WAITING }
            .mapNotNull { w -> w.node?.let { it to me.raddatz.veyq.mobile.offline.QueuedNodes.decode(me.raddatz.veyq.mobile.offline.QueuedNodes.RenameIntent.serializer(), w).name } }
            .toMap()

    /** [nodes] under the names given offline and not sent yet ([renamedOffline]). */
    private fun overlaid(nodes: List<NodeRef>): List<NodeRef> {
        val names = renamedOffline
        return if (names.isEmpty()) nodes else nodes.map { n -> names[n.nodeId]?.let { n.copy(name = it) } ?: n }
    }
    private var goneWatch: kotlinx.coroutines.Job? = null

    /**
     * Every node name this shell read in this run, by node id: what names an item made available offline once its node
     * is gone from the lists (ADR 0140 decision 6) — the store keeps no name, so a node this shell never held goes nameless.
     */
    private val heldNames = HashMap<String, String>()

    /** The pins dropped since their node is no longer available, as the shell says them ([State.gone]). */
    private fun watchGone() {
        val pins = tenant.account.offlinePins ?: return
        if (goneWatch != null) return
        goneWatch = CoroutineScope(Dispatchers.Default).launch {
            pins.gone.collect { gone ->
                val said = gone.filter { it.tenant == tenant.tenant.tenantId }.map { me.raddatz.veyq.mobile.offline.OfflineShown.gone(heldNames[it.node]) }
                put { s -> s?.let { State(it.shell, it.lists, it.offline, said, it.pages) } }
            }
        }
    }

    /** The [State.gone] notices said: they are not said again (on any screen of the account). */
    suspend fun goneSeen() {
        tenant.account.offlinePins?.offline?.seenGone()
        put { s -> s?.let { State(it.shell, it.lists, it.offline, pages = it.pages) } }
    }

    /** Whether the account reaches its server: offline every write here is refused ([readOnly], ADR 0140 decision 7). */
    val online: Boolean get() = tenant.account.reach.value == Reach.Online

    /** The notice a write offline is refused with. */
    @PlatformApi
    @HiddenFromObjC
    val readOnly get() = Notice(Msg.OFFLINE_READ_ONLY)

    // ---- writes made offline (ADR 0153) ----

    @PlatformApi
    @HiddenFromObjC
    val tenantId: String get() = tenant.tenant.tenantId

    /** Whether a write of [q] made now is queued rather than sent: offline, where this device keeps the account's store. */
    @PlatformApi
    @HiddenFromObjC
    fun queues(q: me.raddatz.veyq.mobile.offline.Queued): Boolean = !online && tenant.account.queues(q)

    /** A write not queued for want of room on this device (ADR 0140's floor). */
    @PlatformApi
    @HiddenFromObjC
    val noRoom get() = Notice(Msg.OFFLINE_NO_ROOM_HERE)

    /** The wall clock (epoch ms): when a write made offline was made, as the tray says it. */
    @PlatformApi
    @HiddenFromObjC
    fun wallClock(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /**
     * The Memories and chapters made offline among [writes] and not sent yet, each as the node it will be (its id the
     * write's, ADR 0153 decision 3) with its write: what a level's row opens and a Memory's chapters list (#1040).
     */
    @PlatformApi
    @HiddenFromObjC
    fun queuedMade(writes: List<me.raddatz.veyq.mobile.offline.QueuedWrite>): List<Pair<NodeRef, me.raddatz.veyq.mobile.offline.QueuedWrite>> =
        writes.filter { it.op == me.raddatz.veyq.mobile.offline.Queued.MEMORY_CREATE.name }.map { w -> QueuedShown.madeNode(w) to w }

    /** [scopeId]'s nodes as [scopeNodes] reads them, with the Memories and chapters made there offline and not sent yet. */
    @PlatformApi
    @HiddenFromObjC
    suspend fun scopeNodesWithQueued(scopeId: String): List<NodeRef> {
        val read = scopeNodes(scopeId)
        val made = queuedMade(tenant.account.outbox?.writes().orEmpty().filter { it.tenant == tenantId && it.scope == scopeId }).map { it.first }
        return read + made.filter { m -> read.none { it.nodeId == m.nodeId } }
    }

    /** Whether [nodeId] is a Memory or a chapter made offline and not sent yet: its page opens empty, as of now (#1040). */
    @PlatformApi
    @HiddenFromObjC
    suspend fun madeOffline(nodeId: String): Boolean =
        tenant.account.outbox?.write(nodeId)?.op == me.raddatz.veyq.mobile.offline.Queued.MEMORY_CREATE.name

    /** The name of [nodeId] (null: the space) as the lists hold it: where a queued write goes, as the tray groups it. */
    private fun placeName(scopeId: String, nodeId: String?): String? {
        val lists = _state.value?.lists ?: return null
        return if (nodeId == null) lists.spaces.firstOrNull { it.scopeId == scopeId }?.name
        // a folder or a Memory made offline names the place too (#1095)
        else lists.nodes.firstOrNull { it.nodeId == nodeId }?.name ?: madeUnsent.firstOrNull { it.nodeId == nodeId }?.name
    }

    /**
     * The uploads tray's list of writes made offline on this device ([QueuedTray]), each
     * under the name of the place it writes into: its items, *Discard*, *Save a copy*.
     */
    fun queuedTray(): QueuedTray = QueuedTray(tenant.account, ::placeName)

    /**
     * Under the offline banner (ADR 0153 decision 5): how many writes wait, at once and at each change; null while none
     * does (and without a store).
     */
    val queuedBanner: kotlinx.coroutines.flow.Flow<Notice?>
        get() = tenant.account.outbox?.let { box -> box.changes.map { QueuedShown.banner(box.count()) } }
            ?: kotlinx.coroutines.flow.flowOf(null)

    /** After each reconnect that sent writes made offline: how many went and how many need the person (one notice each). */
    val queuedSent: kotlinx.coroutines.flow.Flow<List<Notice>>
        get() = tenant.account.queuedReports.map { QueuedShown.report(it) }

    /**
     * What a level shows in place of its nodes not sent yet (ADR 0153 decision 5): the folders, Memories,
     * chapters and files made offline under [parentId] (null: the space's top) of [scopeId], each a tray item marked
     * *Waiting to send* (or held, refused), at once and at each change.
     */
    fun queuedAt(scopeId: String, parentId: String?): kotlinx.coroutines.flow.Flow<List<QueuedItem>> {
        val box = tenant.account.outbox ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return box.changes.map {
            box.writes().filter { w ->
                w.tenant == tenantId && w.scope == scopeId && w.op in me.raddatz.veyq.mobile.offline.QueuedNodes.CREATES &&
                    (w.intent["parent"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p !is kotlinx.serialization.json.JsonNull }?.content == parentId
            }.map { QueuedShown.item(it, placeName(scopeId, parentId)) }
        }
    }

    /**
     * [queuedAt] the level the shell shows (a space's top or a folder of Home's), at once and as the shell moves and the
     * outbox changes; empty anywhere else (an application's list, an object's page).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val queuedHere: kotlinx.coroutines.flow.Flow<List<QueuedItem>>
        get() = state.map { st -> st?.shell?.takeIf { it.isHome }?.let { sh -> sh.space?.let { it to sh.folder?.nodeId } } }
            .distinctUntilChanged()
            .flatMapLatest { at -> at?.let { (space, folder) -> queuedAt(space, folder) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }

    /**
     * What a sign-out, "Stop trusting" or "Remove all offline content" asks first while writes made offline wait (ADR 0153
     * decision 2, owner 2026-11-19): the question naming how many are deleted; null where none waits (nothing to ask).
     */
    suspend fun queuedLost(): Notice? = QueuedShown.lost(tenant.account.queuedCount())

    /** What the shell shows offline: as of when its lists were last confirmed by the server; null online. */
    @PlatformApi
    @HiddenFromObjC
    fun offlineNow(): me.raddatz.veyq.mobile.session.OfflineAsOf? = when (val r = tenant.account.reach.value) {
        Reach.Online -> null
        is Reach.Offline -> me.raddatz.veyq.mobile.session.OfflineAsOf(tenant.offline?.asOf ?: r.asOf)
        is Reach.Ended -> me.raddatz.veyq.mobile.session.OfflineAsOf(tenant.offline?.asOf)
    }

    private var rekeys: kotlinx.coroutines.Job? = null

    // the shell as an application's model reads it ([ShellDoor])
    override suspend fun reread() = refresh()
    override fun place(): Shell? = state.value?.shell
    override fun spaces(): List<SpaceRef> = state.value?.lists?.spaces.orEmpty()
    override fun objects(): List<ObjectRef> = state.value?.lists?.objects.orEmpty()
    override fun openObject(ref: ObjectRef) = open(ref)

    /**
     * Reads the lists anew from the server (after a write, or what another device or a guest wrote); a read that fails
     * keeps what was listed. The session's tree is forgotten first: a session caches it ([TenantSession.trees]), and a
     * refresh that read the cache showed a guest's new folder only after a sign-in (found 2026-11-12; ADR 0136's Consequences).
     */
    suspend fun refresh(people: Boolean = false) {
        reading.withLock {
            tenant.forgetTree()
            runCatching { read() }.getOrNull()?.let { raw = it }
        }
        rebuild(start = false, openLastUsed = false)
        // a person's refresh (the desktops' Refresh, the phones' pull): the members too, and a picture replaced since
        if (people) runCatching { tenant.readPeople() }.getOrNull()?.let { tenant.pictures.peopleRead(it.values) }
    }

    private suspend fun read() = Raw(tenant.spaces(), tenant.nodes(), tenant.favorites(), tenant.applicationsOff())
        .also { r -> r.nodes.forEach { heldNames[it.nodeId] = it.name } }

    /** The lists anew from what was read (this device's hidden spaces changed). */
    fun rebuild() = rebuild(start = false, openLastUsed = false)

    private fun rebuild(start: Boolean, openLastUsed: Boolean) {
        val r = raw ?: return
        val spaces = hidden.visibleSpaces(r.spaces)
        val nodes = hidden.visibleNodes(overlaid(r.nodes), r.spaces)
        val objects = nodes.mapNotNull { it.asObject() }
        val names = renamedOffline
        val favorites = hidden.visibleObjects(r.favorites, r.spaces).map { f -> names[f.nodeId]?.let { f.copy(name = it) } ?: f }
        val lists = Lists(
            spaces, nodes, objects, favorites,
            applications = drawerApplications(r.off).map { it.id },
            filters = Home.filters(r.off),
            drawer = drawer(objects, favorites),
            uses = recent.resolve(objects),
        )
        put { prior ->
            State(next(prior?.shell.takeUnless { start }, spaces, if (openLastUsed) recent.lastUsed(objects) else null).relabelled(nodes), lists, offlineNow(), prior?.gone.orEmpty(), tenant.pagesOffline.shown.value)
        }
    }

    private fun drawer(objects: List<ObjectRef>, favorites: List<ObjectRef>) =
        arrange(favorites, recent.resolve(objects), favoritesLimit = DRAWER_FAVORITES, recentLimit = DRAWER_RECENT)

    // ---- where the person is ----

    /** Any move of the shell (its own transitions are `Shell`'s). */
    fun update(change: (Shell) -> Shell) {
        put { s -> s?.moved(change(s.shell)) }
    }

    fun choose(app: ApplicationId?) = update { it.choose(app) }
    fun switchSpace(scopeId: String) = update { it.switchSpace(scopeId) }
    fun enter(folder: FolderRef) = update { it.enter(folder) }
    fun filter(app: ApplicationId?) = update { it.filter(app) }
    fun goBack() = update { it.goBack() }
    fun select(tab: BarTab) = update { it.select(tab) }
    fun search(on: Boolean) = update { it.search(on) }

    /** Opens [ref], its use recorded (the drawer's Recent). */
    fun open(ref: ObjectRef) = opened(ref) { it.open(ref) }

    /** [ref] chosen from the sidebar a wide window keeps open ([Shell.openAtTop]); recent as any open. */
    fun openAtTop(ref: ObjectRef) = opened(ref) { it.openAtTop(ref) }

    /** [ref] chosen from the list beside the open object ([Shell.openBeside]); recent as any open. */
    fun openBeside(ref: ObjectRef) = opened(ref) { it.openBeside(ref) }

    /**
     * Person [userId]'s profile opened over what is on screen ([Shell.openPerson], ADR 0144 decision 11): from a current
     * member in a Memory's participants (`Participants.Entry.opensProfile`) or a photo's uploader
     * (`MediaDetails.Uploader.profile`). The shell is a member's; a share's view offers no way in.
     */
    fun openPerson(userId: String) = update { it.openPerson(userId) }

    /**
     * Kept link [node]'s page opened over what is on screen ([Shell.openBookmark], ADR 0150 decision 6): from its row on a
     * level (`Home.level` lists it), a search's hit ([openFound]). No use is recorded: Recent lists applications' objects.
     */
    fun openBookmark(node: NodeAt) = update { it.openBookmark(node) }

    /** Person [userId]'s frame: named by the tenant's directory as read, with the sections of the applications on. */
    fun profile(userId: String): PersonProfile = PersonProfile.of(userId, tenant.people, tenant.applicationsOff())

    private fun opened(ref: ObjectRef, move: (Shell) -> Shell) {
        recent.record(ref, now())
        put { s -> s?.let { it.moved(move(it.shell), Lists(it.lists.spaces, it.lists.nodes, it.lists.objects, it.lists.favorites, it.lists.applications, it.lists.filters, drawer(it.lists.objects, it.lists.favorites), recent.resolve(it.lists.objects))) } }
    }

    /** Every node of [scopeId] this device opens: what the move browser walks. */
    suspend fun scopeNodes(scopeId: String): List<NodeRef> = runCatching { tenant.nodes() }.getOrDefault(emptyList()).filter { it.scopeId == scopeId }

    /** Home's path read anew where [ref] is a folder on it (renamed, moved, trashed): the folder shown is where it is now. */
    private suspend fun relocate(ref: NodeAt) {
        val s = shell ?: return
        if (s.path.none { it.nodeId == ref.nodeId }) return
        // a folder made offline is on the tree its queued create puts it in (#1095): renamed, it is shown under the new name
        val tree = treeWithUnsent(ref.scopeId)
        update { relocated(it, tree, ref.nodeId) }
    }

    // ---- actions on a node ----

    /** Whether [ref] is pinned; null while its space's pin set is not loaded (no heart is offered then). */
    fun isFavorite(ref: NodeAt): Boolean? = tenant.isFavorite(ref.scopeId, ref.nodeId)

    /** Whether [ref] is a guest's identity folder or lies in one. */
    fun inIdentityFolder(ref: NodeAt): Boolean = tenant.inIdentityFolder(ref.scopeId, ref.nodeId)

    /**
     * Whether the pick mode lets [ref] be chosen for what waits in [inbox] (ADR 0138 decisions 1 and 2): its kind's answer
     * (`AppKinds.share`), where this member may put files into it here, takes one of them. Anything else is dimmed.
     */
    fun takesShare(inbox: ShareInbox, ref: NodeAt): Boolean = inbox.offers(
        AppKinds.share(ref.kind),
        ShareAnswer.Place(ref.kind, tenant.applicationsOff(), online, inIdentityFolder(ref)),
    )

    /**
     * The page in [shell] that offers *Add here* for what waits in [inbox] (ADR 0138 decision 3, [AddHere.at]): [page],
     * the open page that takes files ([PageIntake]: a Memory's or a chapter's), or the level shown — a folder or a space's
     * top, which asks as a folder — where this member may put files here. Null anywhere else. Every platform asks this;
     * none branches on kinds.
     */
    fun addHere(inbox: ShareInbox, shell: Shell, page: PageIntake?): AddHere? =
        AddHere.at(inbox, shell, page, ::levelPlace)

    /**
     * The level [shell] shows where a file dropped onto it is uploaded at once (ADR 0138 decision 9, [AddHere.shown]): a
     * folder or a space's top where this member may put files here; null elsewhere, where a drop waits in the pick mode.
     */
    fun dropLevel(shell: Shell): AddHere.Level? = AddHere.shown(shell, ::levelPlace)

    /** Where a level stands for a share: a folder's place (a space's top asks as one, [folder] null). */
    private fun levelPlace(folder: NodeAt?) =
        ShareAnswer.Place(NodeRef.FOLDER, tenant.applicationsOff(), takesFiles, folder?.let(::inIdentityFolder) ?: false)

    /**
     * Whether files put on a level are written: online, or offline where a Depot file is queued (ADR 0153 decision 3) —
     * the same rule as New's Upload files offline ([createsHere]), so the upload gate never refuses what New offers.
     */
    private val takesFiles: Boolean get() = online || queues(me.raddatz.veyq.mobile.offline.Queued.DEPOT_FILE_ADD)

    /**
     * Whether files may be put under [parentId] (null: the space's top) of [scopeId] — the one gate of a drop, a pick's
     * upload and the bridge's upload calls (ADR 0154 decision 1): a space of this tenant, its level asked as a folder
     * ([FolderShare]'s answer: online, Depot on, no guest's identity folder). A host that skipped the gate is refused here.
     */
    fun takesUpload(scopeId: String, parentId: String?): Boolean {
        if (_state.value?.lists?.spaces?.none { it.scopeId == scopeId } != false) return false
        // online into a folder made offline and not sent yet: the upload waits until the folder is sent (#1095)
        if (online && unsentFolder(parentId)) return false
        val answer = AppKinds.share(NodeRef.FOLDER) ?: return false
        val inIdentity = parentId != null && tenant.inIdentityFolder(scopeId, parentId)
        return answer.mayChoose(ShareAnswer.Place(NodeRef.FOLDER, tenant.applicationsOff(), takesFiles, inIdentity))
    }

    /**
     * Whether [row] — a row of the level the shell shows — takes a drop into it (ADR 0154 decision 1): a folder the gate
     * [takesUpload] offers, never a row by its kind alone (a guest's identity folder takes none). Every platform asks this.
     */
    fun dropTarget(row: NodeAt): Boolean = row.kind == NodeRef.FOLDER && takesUpload(row.scopeId, row.nodeId)

    /**
     * Files and folders dropped onto [target] (ADR 0154 decision 1, ADR 0138 decision 9), the platforms' one drop: judged —
     * the level shown ([dropLevel]), a folder's row ([dropTarget]), a page that takes files (its own [PageIntake.dropIntake]:
     * a gallery's rights, a Contributions view's bucket) —,
     * sorted through the target's answer, and uploaded into [depot] (a level's [LevelUploads]) or the page at once; a level's veyq
     * download offered as an import, one per drop. Each file a target does not keep is handed to [left] once no run reads
     * it again; [deletedName] names an imported Pot's erased person. A target that takes none is [DropOutcome.Refused] and
     * keeps nothing.
     */
    suspend fun drop(
        target: DropTarget, items: List<Dropped>, depot: me.raddatz.veyq.mobile.blob.LevelUploads?,
        left: (me.raddatz.veyq.mobile.blob.UploadSource) -> Unit = {}, deletedName: String = "",
    ): DropOutcome = when (target) {
        is DropTarget.Page -> target.drop(items, left)
        is DropTarget.Level -> {
            val level = shell?.let(::dropLevel)
            if (level == null || depot == null) DropOutcome.notHere
            else Drops.ontoLevel(level.scopeId, level.parentId, target.label, items, depot, level.answer, left, deletedName)
        }
        is DropTarget.Row -> {
            val answer = AppKinds.share(NodeRef.FOLDER)
            if (!dropTarget(target.folder) || depot == null || answer == null) DropOutcome.notHere
            else Drops.ontoLevel(target.folder.scopeId, target.folder.nodeId, target.folder.name, items, depot, answer, left, deletedName)
        }
    }

    /**
     * Whether [shell] shows a space's top level on Home, where its share offers "Add files only" ([topLevelShareSheet], the
     * web's level share): no folder, object, application, person's page or Search open, and the tenant's links and drop
     * boxes on. Every platform asks this (the phones through their `AppModel`, the desktops through `ShellShown.topLevelShare`).
     */
    fun offersTopLevelShare(shell: Shell): Boolean {
        val space = shell.space ?: return false
        val rules = tenant.linkRules
        return topLevelShared(shell, space) && rules.on && rules.dropBox
    }

    /**
     * Whether "New" is offered where the shell is: in a space, never in a guest's folder (the web's `folderRights`); offline
     * only where something it offers is queued ([createsHere], ADR 0153 decision 3).
     */
    val canCreateHere: Boolean
        get() {
            val s = shell ?: return false
            val space = s.space ?: return false
            if (!online && createsHere(isHome = true).isEmpty()) return false
            // online in a folder made offline and not sent yet (held, or its send not come yet): a create would name a
            // parent the server does not hold — New waits until the folder is sent (#1095)
            if (online && unsentFolder(s.folder?.nodeId)) return false
            return s.folderAt(space)?.let { !inIdentityFolder(it) } ?: true
        }

    /**
     * What "New" offers where the shell is (`AppKinds.createsWhere`, [isHome] a level of Home): online all of it; offline
     * only what is queued there (ADR 0153 decision 3: a folder, a Memory, files — never a Pot, a folder uploaded whole or a
     * kept link), none where this device keeps no store. Every platform draws this list, never `createsWhere` beside it.
     */
    fun createsHere(isHome: Boolean, bookmarks: Boolean = false): List<AppKinds.Create> {
        val all = AppKinds.createsWhere(isHome, bookmarks)
        if (online) return all
        return all.filter { c -> QUEUED_CREATES[c]?.let { tenant.account.queues(it) } == true }
    }

    /**
     * What the drawer's New offers (ADR 0122 decision 2; owner 2026-11-20: on all four apps): made at the shown space's top
     * wherever the shell is — online a folder, a Pot, a Memory and a kept link, offline only what queues there
     * ([createsHere]); none outside a space.
     */
    fun drawerCreates(): List<AppKinds.Create> = if (shell?.space == null) emptyList() else createsHere(isHome = false, bookmarks = true)

    /**
     * The drawer's sections, in their order ([DrawerSection.shown]): its New where [drawerCreates] offers any, then each
     * list with something in it. Every platform draws this list whole (ADR 0154 decision 2).
     */
    fun drawerSections(lists: Lists? = current?.lists): List<DrawerSection> =
        lists?.let { DrawerSection.shown(it.drawer, it.applications, creates = drawerCreates().isNotEmpty()) }.orEmpty()

    /**
     * Whether the shown level offers the space's trash (the web's `trashOffered`, `FolderRights.nodeTrash`): a level of a
     * space, online (the trash reads and restores), never in a guest's folder.
     */
    fun trashOffered(shell: Shell): Boolean {
        val space = shell.space ?: return false
        if (!online) return false
        return shell.folderAt(space)?.let { !inIdentityFolder(it) } ?: true
    }

    /**
     * A level row's menu (ADR 0154 decision 2, [LevelRow.plan]): the rights its node's header would grant it, on what a row
     * knows — no page read it, so its kind's doors on [AppKinds.Facts]' defaults —, the report where the server takes one,
     * and a kept link's share report where its link reads ([bookmarkReads]).
     * A kind this build registers no page for offers nothing.
     */
    suspend fun rowPlan(row: NodeAt): LevelRow.Plan {
        val rights = rowRights(row, reportOffered()) ?: return LevelRow.Plan(emptyList(), emptyList())
        return LevelRow.plan(rights, own = if (row.kind == AppKinds.BOOKMARK_KIND && bookmarkReads(row)) listOf(HeaderAction.REPORT_SHARED) else emptyList())
    }

    /** What [row]'s header would grant it, [report] whether the server takes reports; null for a kind this build draws no page of. */
    private fun rowRights(row: NodeAt, report: Boolean): NodeHeader.Rights? {
        val kind = AppKinds.header(row.kind) ?: return null
        val doors = AppKinds.doors(row.kind, AppKinds.Facts())
        return NodeHeader.rights(
            row.kind, favoriteKnown = isFavorite(row) != null, shareable = kind.shareable, linksOn = tenant.linkRules.on,
            rename = kind.renames, takesCover = kind.takesCover, coverOffered = doors.coverOffered, copy = doors.copies,
            download = doors.downloads, report = report, inGuestFolder = inIdentityFolder(row), online = online,
        )
    }

    // ---- a level's selection (inbox #1049, the web's `LevelView.vue` Select mode) ----

    /**
     * The place a level's selection belongs to: a level of Home (a space, a folder) — never an application's list, an
     * object, a person, a kept link or Search, which draw no level of their own (the web offers Select only where core
     * draws the rows) —, or null.
     */
    private fun selectionPlace(s: Shell): String? {
        if (s.application != null || s.searching || s.openObject != null || s.person != null || s.bookmark != null) return null
        val space = s.space ?: return null
        return "$space/${s.folder?.nodeId.orEmpty()}"
    }

    /** Every row of the level [s] shows, whatever its filter shows (the selection is the level's, not the filter's). */
    private fun levelRows(s: Shell, lists: Lists): List<NodeRef> =
        s.space?.let { Home.level(lists.nodes, it, s.folder?.nodeId, null) }.orEmpty()

    /** What each row of the level lets a selection do: its own row menu's entries ([LevelSelection.Rights.of]). */
    private fun selectionRights(rows: List<NodeRef>): Map<String, LevelSelection.Rights> {
        val report = reportKnown ?: false
        return rows.associate { r -> r.nodeId to (rowRights(r.nodeAt(), report)?.let { LevelSelection.Rights.of(LevelRow.plan(it)) } ?: LevelSelection.Rights.NONE) }
    }

    /** Whether the level [s] shows offers Select ([LevelSelection.offered]). */
    private fun selectOffered(s: Shell, lists: Lists?): Boolean {
        if (lists == null || selectionPlace(s) == null) return false
        return LevelSelection.offered(selectionRights(levelRows(s, lists)).values)
    }

    /** [next] with its level's selection kept on what the level holds, or cleared where the place changed or offers no Select. */
    private fun reselected(prior: State?, next: State): State {
        val sel = next.selection ?: prior?.selection ?: return next
        val place = selectionPlace(next.shell)
        if (place == null || place != sel.place) return next.cleared()
        val rights = selectionRights(levelRows(next.shell, next.lists))
        if (!LevelSelection.offered(rights.values)) return next.cleared()
        return next.withSelection(LevelSelection.Picking.of(place, rights, sel.selected, next.lists.level(next.shell).map { it.nodeId }))
    }

    private fun State.cleared() = if (selection == null) this else State(shell, lists, offline, gone, pages, null)
    private fun State.withSelection(sel: LevelSelection.Picking?) = State(shell, lists, offline, gone, pages, sel)

    /** The selection changed by [change] (the ids), shown anew; nothing where the level is in no Select mode. */
    private fun changeSelection(change: (LevelSelection.Picking, List<String>) -> Set<String>) = _state.update { s ->
        val sel = s?.selection ?: return@update s
        val shown = s.lists.level(s.shell).map { it.nodeId }
        reselected(null, s.withSelection(sel.copy(selected = change(sel, shown))))
    }

    /**
     * Select turned on ([LevelTool.SELECT]): the level's Select mode, nothing selected yet; the server's answer whether it
     * takes reports is read first, so the bar offers Report where it does.
     */
    suspend fun selectStart() {
        reportOffered()
        _state.update { s ->
            val place = s?.let { selectionPlace(it.shell) } ?: return@update s
            reselected(null, s.withSelection(LevelSelection.Picking(place, emptySet(), Notice(Msg.SELECT_COUNT, 0), emptyList())))
        }
    }

    /** Select turned off (*Cancel*, the tool again): the selection cleared. */
    fun selectCancel() = _state.update { it?.cleared() }

    /** Row [id] selected, or no longer. */
    fun selectToggle(id: String) = changeSelection { sel, _ -> LevelSelection.toggle(sel.selected, id) }

    /** *All*: every row the level shows selected too. */
    fun selectAll() = changeSelection { sel, shown -> LevelSelection.all(sel.selected, shown) }

    /** *None*: every row the level shows no longer selected; what its filter hides stays. */
    fun selectNone() = changeSelection { sel, shown -> LevelSelection.none(sel.selected, shown) }

    /** The selected rows [action] reaches, in the level's order (each row by its own rights); empty without a selection. */
    fun selected(action: SelectionAction): List<NodeAt> {
        val s = current ?: return emptyList()
        val sel = s.selection ?: return emptyList()
        val rows = levelRows(s.shell, s.lists)
        val rights = selectionRights(rows)
        return LevelSelection.reached<NodeRef>(action, rows, { it.nodeId }, sel.selected) { rights[it.nodeId] ?: LevelSelection.Rights.NONE }.map { it.nodeAt() }
    }

    /**
     * The selection's *Move to…* or *Copy to…* ([copying]): one sheet over every selected row the action reaches
     * ([MoveSheet] over a list); null where it reaches none. A Pot's copy is its open model's (none here): it fails as its
     * row's copy without a page does.
     */
    fun selectionMoveSheet(copying: Boolean, deletedName: String, changed: () -> Unit): MoveSheet? {
        val nodes = selected(if (copying) SelectionAction.COPY else SelectionAction.MOVE).takeIf { it.isNotEmpty() } ?: return null
        return MoveSheet(nodes, copying, moveDoor(null, deletedName, changed))
    }

    /** The selection's *Report*: one report of every selected row ([ReportSheet] over a list), the folder on screen offered above the cap. */
    fun selectionReportSheet(): ReportSheet? {
        val nodes = selected(SelectionAction.REPORT).takeIf { it.isNotEmpty() } ?: return null
        val enclosing = shell?.folder?.let { f -> current?.lists?.nodes?.firstOrNull { it.nodeId == f.nodeId }?.nodeAt() }
        return ReportSheet(nodes, enclosing, reportDoor(nodes.first()))
    }

    /** The selection's *Delete*: one question for every selected row it reaches ([SelectionTrash]); null where it reaches none. */
    fun selectionTrash(changed: () -> Unit): SelectionTrash? {
        val nodes = selected(SelectionAction.DELETE).takeIf { it.isNotEmpty() } ?: return null
        return SelectionTrash(nodes, object : SelectionTrash.Door {
            override suspend fun trash(ref: NodeAt) = this@ShellModel.trash(ref)
            override suspend fun undoTrash(node: OpenNode) = this@ShellModel.undoTrash(node)
            override fun changed() = changed()
        })
    }

    /**
     * Whether kept link [row]'s meta reads (ADR 0154 § Consequences, the web's `coreNodeActions.ts`: a row offers its share's
     * report where the link reads; ADR 0155 § The rights and plans' slice, D3): its row then offers *Report shared content*,
     * which a platform runs through `BookmarkPage.reportSharedFromRow`.
     */
    private suspend fun bookmarkReads(row: NodeAt): Boolean = runCatching {
        tenant.node(row.scopeId, row.nodeId)?.let { me.raddatz.veyq.mobile.sharing.BookmarkMeta.open(tenant.account.suite, it) } != null
    }.getOrDefault(false)

    /** Whether the tenant's map services were built: the Settings page offers the maps' consent
     *  ([me.raddatz.veyq.mobile.session.AccountSettings.State.sections]). */
    val mapsOffered: Boolean get() = builtMaps != null

    /**
     * The plan of the level [shell] shows (ADR 0154 decision 2, [LevelPlan]); null where it shows none (an object, a
     * person, a kept link, Search). A level of Home: New where [canCreateHere] ([createsHere]), the trash where
     * [trashOffered], the view switch where it offers more than one view ([LevelPlan.views]: not on an empty guest's
     * identity folder). An application's list: the view switch, unless the Memories map is its tab — its New is the drawer's.
     */
    fun levelPlan(shell: Shell? = this.shell): LevelPlan? {
        val s = shell ?: return null
        if (s.searching || s.openObject != null || s.person != null || s.bookmark != null) return null
        if (s.application != null) {
            return LevelPlan.of(emptyList(), trash = false, views = LevelPlan.views(s.selectedTab?.key == MAP_TAB, identityFolder = false, rows = 1))
        }
        val space = s.space ?: return null
        val creates = if (s == this.shell && canCreateHere) createsHere(isHome = true, bookmarks = true) else emptyList()
        val identity = s.folderAt(space)?.let { inIdentityFolder(it) } ?: false
        val rows = if (identity) current?.lists?.level(s)?.size ?: 0 else 0
        return LevelPlan.of(
            creates, trash = trashOffered(s), views = LevelPlan.views(mapTab = false, identityFolder = identity, rows = rows),
            select = selectOffered(s, current?.lists),
        )
    }

    /** Pins or unpins [ref], then the lists are read anew; false when it was not stored (the heart goes back). */
    suspend fun toggleFavorite(ref: NodeAt): Boolean {
        if (!online) return false
        val now = tenant.isFavorite(ref.scopeId, ref.nodeId) ?: return false
        val stored = runCatching { tenant.setFavorite(ref.scopeId, ref.nodeId, !now) }.getOrDefault(false)
        refresh()
        return stored
    }

    /** Moves [ref] with everything under it to its space's trash; a page of it leaves for its parent. */
    suspend fun trash(ref: NodeAt): Trashed {
        if (queues(me.raddatz.veyq.mobile.offline.Queued.NODE_TRASH)) {
            // offline it waits to be sent (ADR 0153): out of the lists at once, no Undo (a restore never queues)
            if (!me.raddatz.veyq.mobile.offline.QueuedNodes.trash(tenant.account, tenantId, ref.scopeId, ref.nodeId, wallClock())) return Trashed(NodeTrash.Deleted.FAILED, noRoom, null)
            update { afterTrash(it, ref.nodeId) }
            relocate(ref)
            return Trashed(NodeTrash.Deleted.TRASHED, Notice(Msg.OFFLINE_WAITING).living(me.raddatz.veyq.mobile.words.NoticeKind.SAID), null)
        }
        if (!online) return Trashed(NodeTrash.Deleted.FAILED, readOnly, null)
        val before = runCatching { tenant.node(ref.scopeId, ref.nodeId) }.getOrNull()
        val outcome = runCatching { NodeTrash.delete(tenant, ref.scopeId, ref.nodeId) }.getOrDefault(NodeTrash.Deleted.FAILED)
        // moved without the node as it was, nothing can bring it back: said, not offered as an undo
        val said = Notices.trashed(outcome, ref.name).let { if (before == null && it.kind == me.raddatz.veyq.mobile.words.NoticeKind.UNDO) it.living(me.raddatz.veyq.mobile.words.NoticeKind.SAID) else it }
        val r = Trashed(outcome, said, before)
        if (r.gone) update { afterTrash(it, ref.nodeId) }
        refresh()
        if (r.gone) relocate(ref)
        return r
    }

    /** The undo of a trash: [node] back under its name, as it was; the notice of its failure, or null. */
    suspend fun undoTrash(node: OpenNode): Notice? {
        if (!online) return readOnly
        val token = AppKinds.tokenInput(node.kind, node.name) ?: return Notices.trashUndoFailed(node.name)
        val restored = runCatching { NodeTrash.restore(tenant, node, token) }.getOrNull()
        refresh()
        return if (restored == NodeTrash.Restored.RESTORED) null else Notices.trashUndoFailed(node.name)
    }

    /** The trash of [scopeId] (each deleted subtree's root); null when it could not be read. */
    suspend fun trashOf(scopeId: String): List<NodeTrash.Trashed>? = runCatching { NodeTrash.list(tenant, scopeId) }.getOrNull()

    /** Whether the app can bring [row] back: its kind's fold of a name is known here. */
    fun canRestore(row: NodeTrash.Trashed): Boolean = AppKinds.tokenInput(row.kind, row.name) != null

    /** Brings [row] back from the trash, under [newName] after its own was taken; the lists are read anew. */
    suspend fun restoreFromTrash(row: NodeTrash.Trashed, newName: String?): NodeTrash.Restored {
        if (!online) return NodeTrash.Restored.REFUSED
        val token = AppKinds.tokenInput(row.kind, newName?.trim() ?: row.name) ?: return NodeTrash.Restored.REFUSED
        val r = runCatching { NodeTrash.restoreFromTrash(tenant, row, token, newName) }.getOrDefault(NodeTrash.Restored.REFUSED)
        refresh()
        return r
    }

    /** Erases [row] and everything under it now: irreversible. */
    suspend fun purge(row: NodeTrash.Trashed): NodeTrash.Purged =
        if (!online) NodeTrash.Purged.FAILED else runCatching { NodeTrash.purge(tenant, row.scopeId, row.id) }.getOrDefault(NodeTrash.Purged.FAILED)

    /**
     * The header of [node] over this shell ([NodeHeaderModel]), its kind's page knowing [facts] (null: not yet); [closeOpen]
     * closes an open object of a node gone to the trash, [changed] runs after each write that went through.
     */
    fun header(node: NodeAt, facts: me.raddatz.veyq.mobile.AppKinds.Facts?, closeOpen: (NodeAt) -> Unit, changed: () -> Unit): NodeHeaderModel =
        NodeHeaderModel(node, facts, object : NodeHeaderModel.Door {
            override fun isFavorite(ref: NodeAt) = this@ShellModel.isFavorite(ref)
            override fun inIdentityFolder(ref: NodeAt) = this@ShellModel.inIdentityFolder(ref)
            override fun linksOn() = tenant.linkRules.on
            override suspend fun reportOffered() = this@ShellModel.reportOffered()
            override suspend fun toggleFavorite(ref: NodeAt) = this@ShellModel.toggleFavorite(ref)
            override suspend fun trash(ref: NodeAt) = this@ShellModel.trash(ref)
            override suspend fun undoTrash(node: OpenNode) = this@ShellModel.undoTrash(node)
            override suspend fun rename(ref: NodeAt, name: String) = this@ShellModel.rename(ref, name)
            override fun closeOpen(ref: NodeAt) = closeOpen(ref)
            override fun changed() = changed()
            override suspend fun downloadName(ref: NodeAt) = AppKinds.downloadName(tenant, ref)
            override suspend fun cover(ref: NodeAt): NodeCover.Crop? {
                val n = tenant.node(ref.scopeId, ref.nodeId) ?: return null
                return NodeCover.read(NodeMetaWrite.openForWrite(tenant.account.suite, n.scopeId, n.id, n.key, n.wrappedMeta))?.crop
            }
            override suspend fun coverPicture(ref: NodeAt) = NodeCover.openNode(tenant, ref.nodeId)
            override suspend fun setCover(ref: NodeAt, jpeg: ByteArray, crop: NodeCover.Crop) = NodeCover.set(tenant, ref.scopeId, ref.nodeId, jpeg, crop)
            override suspend fun recropCover(ref: NodeAt, crop: NodeCover.Crop) = NodeCover.recrop(tenant, ref.scopeId, ref.nodeId, crop)
            override suspend fun removeCover(ref: NodeAt) = NodeCover.remove(tenant, ref.scopeId, ref.nodeId)
            override fun offline() = tenant.account.offlinePins?.let { me.raddatz.veyq.mobile.offline.OfflineNodes.of(it, tenant) }
            override val online = tenant.account.reach.map { it == Reach.Online }.distinctUntilChanged()
        })

    private var reportKnown: Boolean? = null
    private val reportReading = Mutex()

    /**
     * Whether the tenant's server takes reports (it publishes a moderation key, ADR 0093): asked once per tenant, Report
     * offered only then. A read that failed is asked again by the next header (the duty is the law's).
     */
    suspend fun reportOffered(): Boolean = reportReading.withLock {
        reportKnown ?: runCatching { NodeReport.published(tenant) != null }.getOrNull()?.also { reportKnown = it } ?: false
    }

    /** The trash sheet of [scopeId] over this shell; [changed] after each write that went through. */
    fun trashSheet(scopeId: String, changed: () -> Unit): TrashSheet = TrashSheet(object : TrashSheet.Door {
        override suspend fun list() = trashOf(scopeId)
        override fun canRestore(row: NodeTrash.Trashed) = this@ShellModel.canRestore(row)
        override suspend fun restore(row: NodeTrash.Trashed, newName: String?) = restoreFromTrash(row, newName)
        override suspend fun purge(row: NodeTrash.Trashed) = this@ShellModel.purge(row)
    }, changed)

    /** Renames [ref] to [name] (its placement, then its open links' names); an open page follows it under its new name. */
    suspend fun rename(ref: NodeAt, name: String): Renamed {
        if (queues(me.raddatz.veyq.mobile.offline.Queued.NODE_RENAME)) {
            // offline the rename states the placement the person saw (ADR 0153 decision 4) and waits to be sent
            val seen = runCatching { tenant.node(ref.scopeId, ref.nodeId)?.placementRevision }.getOrNull() ?: 0
            if (!me.raddatz.veyq.mobile.offline.QueuedNodes.rename(tenant.account, tenantId, ref.scopeId, ref.nodeId, seen, name, wallClock())) return Renamed(NodeRename.Result(NodeRename.Outcome.FAILED), noRoom)
            // shown in place at once (#1040): the level's row, an open page's head (the outbox's watch keeps it so)
            renamedOffline = renamesQueued()
            rebuild()
            followRename(ref)
            // a Memory made offline is in no list: its open page heads it under the name its create now carries
            shell?.openObject?.takeIf { it.scopeId == ref.scopeId && it.nodeId == ref.nodeId && it.name != name.trim() }
                ?.let { o -> update { afterRename(it, o.copy(name = name.trim())) } }
            return Renamed(NodeRename.Result(NodeRename.Outcome.RENAMED), Notice(Msg.OFFLINE_WAITING))
        }
        if (!online) return Renamed(NodeRename.Result(NodeRename.Outcome.FAILED), readOnly)
        val r = runCatching { NodeRename.rename(tenant, ref.scopeId, ref.nodeId, name, AppKinds::tokenInput, now()) }
            .getOrDefault(NodeRename.Result(NodeRename.Outcome.FAILED))
        if (r.outcome == NodeRename.Outcome.RENAMED) {
            // a rename made offline and held for the person is decided by this one (its *Open*, `QueuedOpen.Rename`)
            runCatching { me.raddatz.veyq.mobile.offline.QueuedNodes.renameDecided(tenant.account, ref.nodeId) }
            refresh()
            followRename(ref)
        }
        return Renamed(r, Notices.renameRefused(r))
    }

    /** An open page of renamed [ref] (an object's, a kept link's) and the path follow its name as the lists hold it now. */
    private suspend fun followRename(ref: NodeAt) {
        val lists = _state.value?.lists
        val renamed = lists?.objects?.firstOrNull { it.scopeId == ref.scopeId && it.nodeId == ref.nodeId }
        if (renamed != null) update { afterRename(it, renamed) }
        // a kept link's page heads its node under the new name too (#1035)
        val kept = lists?.nodes?.firstOrNull { it.scopeId == ref.scopeId && it.nodeId == ref.nodeId }?.asBookmark()
        if (kept != null) update { afterRename(it, kept) }
        relocate(ref)
    }

    /** Where a node of a kind may be placed: the build's kinds' placement. */
    val placement get() = AppKinds.placement

    /** What a move came to, its notice where it did not go, and where it came from (its undo moves it back). */
    class Moved(val result: NodeMove.Result, val notice: Notice?, val from: String?)

    /** Moves [ref] under [to] (null: the space's root), its name kept; the lists are read anew. */
    suspend fun move(ref: NodeAt, to: String?): Moved {
        if (!online) return Moved(NodeMove.Result(NodeMove.Outcome.FAILED), readOnly, null)
        val from = runCatching { tenant.node(ref.scopeId, ref.nodeId)?.parentId }.getOrNull()
        val r = runCatching { NodeMove.move(tenant, ref.scopeId, ref.nodeId, to, AppKinds::tokenInput, AppKinds.placement) }
            .getOrDefault(NodeMove.Result(NodeMove.Outcome.FAILED))
        refresh()
        if (r.outcome == NodeMove.Outcome.MOVED) relocate(ref)
        return Moved(r, Notices.moveRefused(r.outcome), from)
    }

    /**
     * A new folder [name] in [scopeId] under [parentId] (null: the space's root), as New Folder's dialog answers it
     * ([NameAnswer], ADR 0154 decision 1): an empty name or one a sibling holds is a [NameAnswer.problem] (the dialog asks
     * again); offline it is queued where this device keeps the account's store ([NameAnswer.QUEUED]: it shows at once where
     * it was made, [queuedAt]) and refused otherwise; a refusal and a thrown error are said ([NameAnswer.notice]).
     */
    suspend fun createFolder(scopeId: String, parentId: String?, name: String): NameAnswer {
        val n = name.trim()
        if (queues(me.raddatz.veyq.mobile.offline.Queued.FOLDER_CREATE)) {
            if (n.isEmpty()) return NameAnswer.problem(Notice(Msg.FOLDER_NAME_EMPTY))
            val queued = runCatching { me.raddatz.veyq.mobile.offline.QueuedNodes.folder(tenant.account, tenantId, scopeId, parentId, n, wallClock()) }.getOrNull()
            // known at once as a level its row enters, and as the place what is made in it goes (#1095)
            if (queued != null) madeUnsent = madeQueued()
            return if (queued != null) NameAnswer.QUEUED else NameAnswer.said(noRoom)
        }
        if (!online) return NameAnswer.said(readOnly)
        if (n.isEmpty()) return NameAnswer.problem(Notice(Msg.FOLDER_NAME_EMPTY))
        val r = runCatching { FolderCreate.create(tenant, scopeId, parentId, n) }.getOrNull()
        runCatching { refresh() }
        return when (r) {
            is NodeCreate.Result.Done -> NameAnswer.DONE
            is NodeCreate.Result.Refused ->
                if (r.reason == NodeCreate.Reason.NAME_TAKEN) NameAnswer.problem(Notices.folderRefused(r.reason)) else NameAnswer.said(Notices.folderRefused(r.reason))
            // a thrown error: the core says it, the platform never decides what a failure reads as
            null -> NameAnswer.said(Notices.folderRefused(null))
        }
    }

    /** [createFolder] where the shell is: in its space, under the folder Home shows. */
    suspend fun createFolderHere(name: String): NameAnswer {
        val s = shell
        val space = s?.space ?: return NameAnswer.said(Notices.folderRefused(null))
        return createFolder(space, s.folder?.nodeId, name)
    }

    /** [createFolder] at the shown space's top, wherever the shell is: the drawer's New ([drawerCreates], ADR 0122 decision 2). */
    suspend fun createFolderAtTop(name: String): NameAnswer {
        val space = shell?.space ?: return NameAnswer.said(Notices.folderRefused(null))
        return createFolder(space, null, name)
    }

    /**
     * Copies [ref] with everything under it (named `duplicate`: a name starting `copy` reaches Swift as `doCopy`) to [to] (null: the space's root) in its space; [deletedName] is how a copied
     * Pot names an erased person; the lists are read anew. Why not, or null once copied.
     */
    suspend fun duplicate(ref: NodeAt, to: String?, deletedName: String, uuid: () -> String): Notice? {
        if (!online) return readOnly
        val r = runCatching { SubtreeCopy.duplicate(tenant, ref.scopeId, ref.nodeId, to, AppKinds.copies(deletedName, uuid)) }.getOrNull()
        refresh()
        return Notices.subtreeCopyRefused(r?.outcome)
    }

    /**
     * The move or copy sheet of [node] over this shell ([MoveSheet]): its copy by the kind's way — an object that copies by
     * its open model by [open] (the model its page holds, [OpenCopy]: Expenses' Pot; none: the copy fails), everything else
     * with what is under it —, [deletedName] how a copied
     * Pot names an erased person (each new id a UUID from the suite's randomness); [changed] after each write.
     */
    fun moveSheet(
        node: NodeAt, copying: Boolean, open: OpenCopy?, deletedName: String, changed: () -> Unit,
    ): MoveSheet = MoveSheet(node, copying, moveDoor(open, deletedName, changed))

    private fun moveDoor(open: OpenCopy?, deletedName: String, changed: () -> Unit) = object : MoveSheet.Door {
        val uuid = { NodeCreate.uuid(tenant.account) }
        override suspend fun tree(scopeId: String) = scopeNodes(scopeId)
        override val placement get() = AppKinds.placement
        override fun rootName(scopeId: String) = ShellWords.space(current?.lists?.spaces?.firstOrNull { it.scopeId == scopeId })
        override suspend fun move(ref: NodeAt, to: String?) = this@ShellModel.move(ref, to).result.outcome
        override suspend fun duplicateOpen(ref: NodeAt, to: String?): Notice? =
            if (open == null) Notice(Msg.COPY_FAILED) else open.duplicateOpen(this@ShellModel, ref, to, deletedName, uuid)
        override suspend fun duplicateSubtree(ref: NodeAt, to: String?) = duplicate(ref, to, deletedName, uuid)
        override suspend fun preview(ref: NodeAt) = SubtreeCopy.preview(tenant, ref.scopeId, ref.nodeId, AppKinds.copies(deletedName, uuid))
        override suspend fun noRoom(ref: NodeAt) = StorageFit.noRoomForCopy(tenant, ref.scopeId, ref.nodeId, AppKinds.exports(deletedName))
        override fun changed() = changed()
    }

    /**
     * The share sheet of [node] over this tenant ([ShareSheet]): its links (a drop box's WRITE_ONLY ones), the writes on
     * them — a link's close re-keying what is below it by the build's kinds (`AppKinds.rekeys`), each new id a UUID from the
     * suite's randomness, and the tenant told ([TenantSession.noteRekeyed]) —, a folder's "Add files only"; [changed] after a
     * node was made.
     */
    fun shareSheet(node: NodeAt, changed: () -> Unit): ShareSheet = ShareSheet(node, shareDoor(node, node.nodeId, changed), now)

    /**
     * The share of space [scopeId]'s top level over this tenant ([ShareSheet.topLevel]): "Add files only" alone, its drop
     * box made at the space's top with its first link; [changed] once it is made.
     */
    fun topLevelShareSheet(scopeId: String, changed: () -> Unit): ShareSheet {
        val top = NodeAt(scopeId, "", NodeRef.FOLDER, "")
        return ShareSheet.topLevel(scopeId, shareDoor(top, null, changed), now)
    }

    /** The share sheet's door on [node]; a drop box "Add files only" makes goes under [dropBoxParent] (null: the space's top). */
    private fun shareDoor(node: NodeAt, dropBoxParent: String?, changed: () -> Unit) = object : ShareSheet.Door {
        val uuid = { NodeCreate.uuid(tenant.account) }
        // a drop box lists its upload-only links, a Memory and a chapter every role in one list, any other kind none
        val writeOnly: Boolean? = when (AppKinds.shareShape(node.kind)) {
            ShareSheet.Shape.WRITE_ONLY -> true
            ShareSheet.Shape.UPLOAD_LINK -> null
            null -> false
        }
        override val rules get() = tenant.linkRules
        override suspend fun list() = ShareLinks.list(tenant, node.scopeId, node.nodeId, writeOnly)
        override suspend fun mint(role: String, label: String, password: String, expiresAt: String?) =
            ShareLinks.mint(tenant, node.scopeId, node.nodeId, role, label, password, expiresAt)
        override suspend fun createDropBox(name: String, password: String, expiresAt: String?) =
            AppKinds.createDropBox(tenant, node.scopeId, dropBoxParent, name, password, expiresAt)
        override suspend fun edit(shareId: String, edit: ShareLinkChanges.Edit) =
            ShareLinkChanges.edit(tenant, node.scopeId, node.nodeId, shareId, edit, now())
        override suspend fun renew(shareId: String) =
            ShareLinkChanges.renew(tenant, node.scopeId, node.nodeId, shareId, AppKinds.rekeys, AppKinds::tokenInput, uuid, now())
        override suspend fun switchOff(shareId: String) =
            ShareLinks.switchOff(tenant, node.scopeId, node.nodeId, shareId, AppKinds.rekeys, AppKinds::tokenInput, uuid)
        override suspend fun reactivate(shareId: String) = ShareLinkChanges.reactivate(tenant, node.scopeId, node.nodeId, shareId)
        override suspend fun delete(shareId: String) =
            ShareLinkChanges.delete(tenant, node.scopeId, node.nodeId, shareId, AppKinds.rekeys, AppKinds::tokenInput, uuid, now())
        override fun rekeyed() = tenant.noteRekeyed()
        override fun changed() {
            changed()
            CoroutineScope(Dispatchers.Default).launch { refresh() }
        }
    }

    /** The report of [node] to the tenant's moderators over this tenant ([ReportSheet], core's [NodeReport]). */
    fun reportSheet(node: NodeAt): ReportSheet = ReportSheet(node, reportDoor(node), nameOnly = AppKinds.reportsNameOnly(node.kind))

    private fun reportDoor(node: NodeAt) = object : ReportSheet.Door {
        override val origin get() = tenant.origin
        override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String) =
            // a kept link reports its name only, whatever is asked (ADR 0150 decision 6): its key opens another operator's share
            NodeReport.report(tenant, node.scopeId, node.nodeId, if (AppKinds.reportsNameOnly(node.kind)) NodeReport.Scope.NAME else scope, category, reason, contact)
        override suspend fun sendAll(nodes: List<NodeAt>, category: String, reason: String, contact: String) =
            NodeReport.reportMany(tenant, node.scopeId, nodes.map { it.nodeId }, category, reason, contact)
    }

    /**
     * Node [ref]'s download into [sink]: a file the file itself ([AppKinds.ownDownload]), any other node with everything
     * under it as the web's ZIP (`NodeExport.walk`, `write`; the kinds the build exports, a copied Pot's erased people named
     * [deletedName]): what it left out, or null when there is nothing to download or it did not finish.
     * Natively the sink is a file by its path (`FileSink`, which removes a download that did not finish).
     */
    suspend fun download(ref: NodeAt, deletedName: String, nowMs: Long, sink: (ByteArray) -> Unit): List<NodeExport.Skipped>? =
        downloadAs(ref.kind, own = { AppKinds.ownDownload(tenant, ref, it) == true }, zip = zip@{ out ->
            val walked = NodeExport.walk(tenant, ref.scopeId, ref.nodeId, AppKinds.exports(deletedName)) ?: return@zip null
            NodeExport.write(walked.root, VeyqAccount.MAJOR.toString(), nowMs, out)
            walked.skipped
        }, sink = sink)

    /** Node [nodeId]'s cover as a list row draws it, the host holding the frame [held] (`NodeCover.row`); null: none. */
    suspend fun rowCover(nodeId: String, held: String?): NodeCover.Row? = runCatching { NodeCover.row(tenant, nodeId, held) }.getOrNull()

    /** The account's view choice on this device (ADR 0149 decision 8): every level's and a person's Memories'. */
    val viewModes: ViewModes get() = ViewModes(tenant.account.deviceStore)

    /** How a level is drawn now: the account's choice among a level's views ([ViewMode.LEVEL]). */
    val levelView: ViewMode get() = viewModes.of(tenant.account.userId, ViewMode.LEVEL)

    /** A level's view chosen: kept for every level and a person's Memories. */
    fun chooseView(mode: ViewMode) = viewModes.set(tenant.account.userId, mode)

    // ---- the search (ADR 0146) ----

    /** The search's one-shot requests to a node's page (a photo of another Memory opened in its lightbox). */
    val openRequests = me.raddatz.veyq.mobile.search.OpenRequests()

    /** What the page of the node on screen adds to the search (the open Memory's photos). */
    val searchContributions = me.raddatz.veyq.mobile.search.SearchContributions()

    /**
     * The search over this tenant (ADR 0146 decision 7): one per field, built by a platform beside its shell — [words] the
     * platform's catalog in the UI's language, [appearance] whether its settings page offers the appearance section (the
     * phones) — and [me.raddatz.veyq.mobile.search.SearchModel.dispose]d where the tenant closes (the bridge's
     * `closeTenant`, the phones' switch, a lock, a sign-out). It reads the shell's trees, never a tree of its own; a result
     * opens through this shell. [trace] hears how long each walk of the index took (counts and milliseconds, no name).
     */
    fun searchModel(
        words: me.raddatz.veyq.mobile.search.SearchWords, appearance: Boolean, trace: ((String) -> Unit)? = null,
    ): me.raddatz.veyq.mobile.search.SearchModel =
        me.raddatz.veyq.mobile.search.SearchModel(searchDoor(appearance), AppKinds.search, words, openRequests, searchContributions, now, trace = trace)

    private fun searchDoor(appearance: Boolean) = object : me.raddatz.veyq.mobile.search.SearchModel.Door {
        override suspend fun spaces() = current?.lists?.spaces ?: hidden.visibleSpaces(tenant.spaces())
        override suspend fun <V : Any> fromTree(key: String, derive: suspend (Map<String, List<OpenNode>>) -> V): V = tenant.fromTree(key, derive)
        override suspend fun treeMoved(scopeId: String) = tenant.treeMoved(scopeId).also { if (it) refresh() }
        override fun notRead() = tenant.unreadTrees + tenant.closed.keys
        override fun openMeta(node: OpenNode) =
            runCatching { NodeMetaWrite.openForWrite(tenant.account.suite, node.scopeId, node.id, node.key, node.wrappedMeta) }.getOrNull()
        override fun online() = online
        override fun people() = tenant.people.values
        override suspend fun settings(): List<me.raddatz.veyq.mobile.search.SettingsSection> {
            val account = tenant.account
            val linking = online && runCatching {
                account.call { me.raddatz.veyq.mobile.api.apis.SetupApi(account.server, account.http).getSetupStatus() }
                    .capabilities.any { it.id == me.raddatz.veyq.mobile.session.AccountSettings.DEVICE_LINKING }
            }.getOrDefault(false)
            // the page's own gates: the storage section where a store is kept or why none is said (AccountSettings.Over)
            val storage = account.offline != null || (account.thisDevice.offlineRoot != null && account.trusted == null)
            return me.raddatz.veyq.mobile.search.SettingsSection.offered(
                me.raddatz.veyq.mobile.search.SettingsSection.Gates(addDevice = linking, storage = storage, maps = builtMaps != null, appearance = appearance),
            )
        }
        override fun where(): me.raddatz.veyq.mobile.search.Results.SearchContext {
            val s = shell
            val active = s?.openObject?.scopeId ?: s?.space
            // the node the screen heads (an open object, a kept link's page, the folder Home shows): a result of it is "here"
            val here = s?.headed?.takeIf { it.scopeId == active }?.nodeId
            return me.raddatz.veyq.mobile.search.Results.SearchContext(active, s?.openObject?.nodeId, here)
        }
        override fun applicationsOff() = tenant.applicationsOff()
        override suspend fun scopeDocuments(
            app: me.raddatz.veyq.mobile.search.ApplicationSearch, scopeId: String, tag: String?,
            previous: Map<String, me.raddatz.veyq.mobile.search.ScopeDocument>, nodes: Map<String, OpenNode>, unnamed: (Boolean) -> String,
        ) = app.scopeDocuments(me.raddatz.veyq.mobile.search.ScopeDocumentsContext(tenant, scopeId, tag, previous, nodes, unnamed))
        override fun openNode(scopeId: String, nodeId: String) = openFound(scopeId, nodeId)
        override fun openPerson(userId: String) = this@ShellModel.openPerson(userId)
    }

    /**
     * Node [nodeId] of [scopeId] a search found, opened where it lives (ADR 0146 decision 8): an object in its application in
     * its space (back returns to the search), a kept link as its page, a folder on Home at that folder; a node that is none
     * of these (a chapter) by the nearest object or folder above it ([found]).
     */
    fun openFound(scopeId: String, nodeId: String) {
        val now = shell ?: return
        val (moved, obj) = found(now, current?.lists?.nodes.orEmpty(), scopeId, nodeId)
        if (obj != null) opened(obj) { moved } else update { moved }
    }

    /**
     * This tenant's map services once the platform built them ([mapServices]); null before. What a Memory's place names
     * are looked up through (Memories' `placeNames`, derived from it with [mapsLanguage]), and whether Settings offers the
     * maps' consent ([mapsOffered]).
     */
    @PlatformApi
    @HiddenFromObjC
    var builtMaps: me.raddatz.veyq.mobile.map.MapServices? = null
        private set

    /** The app's language the map services were built with (the geocoder's `accept-language`). */
    @PlatformApi
    @HiddenFromObjC
    var mapsLanguage: () -> String = { "" }
        private set

    /**
     * This tenant's map services on this device ([me.raddatz.veyq.mobile.map.MapServices.of], ADR 0143 D2): one per signed-in
     * tenant in the process, built beside the media uploads by the platform ([recipient] Apple Maps on Apple; [lang] the
     * app's language, the geocoder's `accept-language`) and closed where the tenant closes (a lock, a sign-out, a switch) —
     * its tiles dropped, its geocoder's queue cleared. The shell's place names come from it from then on ([builtMaps]).
     */
    fun mapServices(recipient: me.raddatz.veyq.mobile.map.TileRecipient, lang: () -> String): me.raddatz.veyq.mobile.map.MapServices =
        me.raddatz.veyq.mobile.map.MapServices.of(tenant, recipient).also {
            mapsLanguage = lang
            builtMaps = it
        }

    /**
     * The account's music player over the platform's two decks ([newDeck], ADR 0142 decision 2) and its system controls
     * ([controls], decision 3), its order's randomness the suite's (ADR 0142 decision 1). One per signed-in account in the
     * process, built beside the media uploads and stopped where the tenant closes (a lock, a sign-out, a switch).
     */
    fun musicPlayer(
        newDeck: (me.raddatz.veyq.mobile.audio.Deck.Events) -> me.raddatz.veyq.mobile.audio.Deck,
        controls: me.raddatz.veyq.mobile.audio.SystemControls?,
    ): me.raddatz.veyq.mobile.audio.MusicPlayer =
        me.raddatz.veyq.mobile.audio.MusicPlayer(newDeck, controls, me.raddatz.veyq.mobile.audio.randomIntOf { tenant.account.suite.p.randomBytes(it) })

    companion object {
        /** The bar's tab that shows the Memories map, no list (`Applications.kt`): a level there offers no view switch. */
        private const val MAP_TAB = "memories.map"

        /** What each "New" that is queued offline writes ([createsHere]); one not here is never queued. */
        private val QUEUED_CREATES = mapOf(
            AppKinds.Create.FOLDER to me.raddatz.veyq.mobile.offline.Queued.FOLDER_CREATE,
            AppKinds.Create.MEMORY to me.raddatz.veyq.mobile.offline.Queued.MEMORY_CREATE,
            AppKinds.Create.UPLOAD_FILES to me.raddatz.veyq.mobile.offline.Queued.DEPOT_FILE_ADD,
        )

        /**
         * A node of [kind]'s download into [sink]: its [own] content where the kind downloads itself
         * ([AppKinds.downloadsItself]: a file, nothing left out) — null where it did not finish —, else the [zip] of it.
         */
        suspend fun downloadAs(
            kind: String, own: suspend ((ByteArray) -> Unit) -> Boolean, zip: suspend ((ByteArray) -> Unit) -> List<NodeExport.Skipped>?,
            sink: (ByteArray) -> Unit,
        ): List<NodeExport.Skipped>? =
            if (AppKinds.downloadsItself(kind)) (if (own(sink)) emptyList() else null) else zip(sink)

        /**
         * The shell after the lists were read: [prior] kept (null: the app starts — on [lastUsed], else Home's default
         * space), but a space no longer listed gives way to the default one.
         */
        fun next(prior: Shell?, spaces: List<SpaceRef>, lastUsed: ObjectRef?): Shell {
            val defaultSpace = Home.defaultSpace(spaces)?.scopeId
            return when {
                prior == null -> Shell.start(lastUsed, defaultSpace)
                prior.space != null && spaces.none { it.scopeId == prior.space } -> prior.switchSpace(defaultSpace ?: "")
                else -> prior
            }
        }

        /** Whether [shell] stands on [space]'s top level on Home: no folder, object, application, person's page or Search open. */
        fun topLevelShared(shell: Shell, space: String): Boolean =
            shell.openObject == null && shell.application == null && shell.person == null && shell.bookmark == null && !shell.searching &&
                shell.folderAt(space) == null

        /**
         * Where [shell] goes for node [nodeId] of [scopeId] a search found among [nodes] ([openFound]), and the object it
         * opens (its use to record; null where none opens): an object in its application in its space, back to the search;
         * a kept link as its page (ADR 0150 decision 6); a folder on Home at it; another node (a chapter) by the nearest
         * object or folder above it.
         */
        fun found(shell: Shell, nodes: List<NodeRef>, scopeId: String, nodeId: String): Pair<Shell, ObjectRef?> {
            val inSpace = nodes.filter { it.scopeId == scopeId }
            val byId = inSpace.associateBy { it.nodeId }
            byId[nodeId]?.asBookmark()?.let { return shell.openBookmark(it).copy(space = scopeId) to null }
            val seen = HashSet<String>()
            var n = byId[nodeId]
            while (n != null && seen.add(n.nodeId)) {
                val obj = n.asObject() ?: if (n.nodeId == nodeId) AppKinds.rowPage(n) else null
                if (obj != null) return shell.open(obj).copy(space = scopeId) to obj
                if (n.isFolder) return shell.switchSpace(scopeId).at(inSpace, n.nodeId) to null
                n = n.parentId?.let { byId[it] }
            }
            return shell.switchSpace(scopeId) to null
        }

        /**
         * The shell where folder [nodeId] on Home's path changed: still in [tree] (its space's nodes), the path read anew to
         * where it is now; gone (trashed), Home shows the folder above it.
         */
        fun relocated(shell: Shell, tree: List<NodeRef>, nodeId: String): Shell {
            if (shell.path.none { it.nodeId == nodeId }) return shell
            if (tree.any { it.nodeId == nodeId }) return shell.at(tree, shell.folder?.nodeId)
            val i = shell.path.indexOfFirst { it.nodeId == nodeId }
            return shell.at(tree, if (i > 0) shell.path[i - 1].nodeId else null)
        }

        /**
         * The shell once node [nodeId] went to the trash: an open object of it leaves ([Shell.leave]) — one opened at the
         * top for its application's list, since back does not leave it (its undo brings it back to that list, not open) —,
         * and so does a kept link's page of it (#1035), back to where it was opened from.
         */
        fun afterTrash(shell: Shell, nodeId: String): Shell =
            if (shell.openObject?.nodeId == nodeId || shell.bookmark?.nodeId == nodeId) shell.leave() else shell

        /**
         * The shell once object [renamed] has its new name: an open page of it shows it under that name, where it was —
         * its back and its place among the top-level destinations ([Shell.top]) kept.
         */
        fun afterRename(shell: Shell, renamed: ObjectRef): Shell =
            if (shell.openObject?.let { it.nodeId == renamed.nodeId && it.scopeId == renamed.scopeId } == true) shell.copy(openObject = renamed) else shell

        /** The shell once kept link [renamed] has its new name: its page, where it is open, heads it under that name (#1035). */
        fun afterRename(shell: Shell, renamed: NodeAt): Shell =
            if (shell.bookmark?.let { it.nodeId == renamed.nodeId && it.scopeId == renamed.scopeId } == true) shell.copy(bookmark = renamed) else shell

        /** How long a trash's undo is offered (the web's `TRASH_UNDO_MS`; the platform's [NodeHeaderModel.TRASH_UNDO_MS]). */
        const val TRASH_UNDO_MS = NodeHeaderModel.TRASH_UNDO_MS

        /** The drawer's short lists (ADR 0122): six favorites, eight recent. */
        const val DRAWER_FAVORITES = 6
        const val DRAWER_RECENT = 8
    }
}

/** [this] as core's node actions take it. */
private fun NodeRef.nodeAt() = NodeAt(scopeId, nodeId, kind, name)
