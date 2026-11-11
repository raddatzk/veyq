@file:OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.expenses.PotModel
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeHeaderModel
import me.raddatz.veyq.mobile.navigation.TrashSheet
import me.raddatz.veyq.mobile.navigation.MoveSheet
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.words.Notices
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.navigation.ShellWords
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.navigation.UseKey
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The models one process holds, and what a tenant opened becomes: its shell and, per Pot opened, a Pot model. [A] is the
 * account and [T] an opened tenant, as in [SessionModel] — the real ones in [live], a test's own in a test.
 */
internal class Composition<A : Connection, T : Any>(
    val session: SessionModel<A, T>,
    /** What this device hides, which the switcher's tenants leave out ([ShellShown.servers]). */
    val hidden: Hidden,
    /**
     * The files the host names by their paths (ADR 0128 decision 3); a test's composition that writes none has
     * [DeviceFiles.None].
     */
    val files: DeviceFiles = DeviceFiles.None,
    /** The models over a tenant just opened; null where there are none (a test's tenant that is no `TenantSession`). */
    val tenant: (T) -> Tenant?,
)

/**
 * What a tenant opened offers the bridge: its shell, a Pot model per Pot opened (both null for a test's tenant, as they
 * take only a `TenantSession`), the signed-in member's user id ([me]) and the members' pictures the core holds
 * ([pictures], `AvatarsModel.state`), read when [loadPictures] runs (`AvatarsModel.load`: when is the core's rule).
 */
internal class Tenant(
    val shell: ShellModel?,
    val pot: (() -> PotModel)?,
    val me: String?,
    val pictures: StateFlow<Map<String, ByteArray>>,
    val loadPictures: suspend () -> Unit,
    /**
     * A node's header over this tenant (`ShellModel.header`), whose `closeOpen` the bridge hands in (it closes the open
     * Pots of a node gone to the trash); a test's tenant plays one over a door of its own.
     */
    val header: ((node: NodeAt, facts: AppKinds.Facts?, closeOpen: (NodeAt) -> Unit) -> NodeHeaderModel)? = shell?.let { s ->
        { node, facts, closeOpen -> s.header(node, facts, closeOpen) {} }
    },
    /** A space's trash sheet (`ShellModel.trashSheet`). */
    val trash: ((scopeId: String) -> TrashSheet)? = shell?.let { s -> { scopeId -> s.trashSheet(scopeId) {} } },
    /** A new folder where the shell is (`ShellModel.createFolderHere`): why not, or null. */
    val createFolder: (suspend (name: String) -> Notice?)? = shell?.let { s -> { name -> s.createFolderHere(name) } },
    /** A node's move or copy sheet (`ShellModel.moveSheet`), a Pot's copy by the open Pot model handed in. */
    val move: ((node: NodeAt, copying: Boolean, pot: PotModel?, deletedName: String) -> MoveSheet)? = shell?.let { s ->
        { node, copying, pot, deletedName -> s.moveSheet(node, copying, pot, deletedName) {} }
    },
    /** A node with everything under it into a sink (`ShellModel.download`): what it left out, or null. */
    val download: (suspend (node: NodeAt, deletedName: String, sink: (ByteArray) -> Unit) -> List<NodeExport.Skipped>?)? = shell?.let { s ->
        { node, deletedName, sink -> s.download(node, deletedName, Clock.System.now().toEpochMilliseconds(), sink) }
    },
)

/** A header the host opened, with the most buttons its bar holds. */
internal class HeldHeader(val model: NodeHeaderModel, val maxButtons: Int)

/**
 * A file on the device the host named by its path, as its save dialog gave it (ADR 0128 decision 3): the core opens and
 * writes it, and nothing of its content crosses the bridge. Natively core's `FileSink`; a JVM test plays one.
 */
internal interface DeviceFiles {
    /**
     * Writes the file at [path] with what [produce] hands its sink; it stays only when [produce] answers true, and one
     * that throws or answers false leaves nothing at [path]. Throws when [path] does not open.
     */
    suspend fun write(path: String, produce: suspend (sink: (ByteArray) -> Unit) -> Boolean): Boolean

    /** No file is written: a test's composition that has none to offer. */
    object None : DeviceFiles {
        override suspend fun write(path: String, produce: suspend (sink: (ByteArray) -> Unit) -> Boolean) =
            throw UnsupportedOperationException("this composition writes no files")
    }
}

/** The tenant open now: its models, and the account and tenant the session opened them for. */
private class Open<A : Connection>(val models: Tenant, val account: A, val tenant: TenantRef)

/**
 * The dispatch (ADR 0123's Consequences, the bridge): each [Call] is one line onto a model's own function, each
 * [Watched] one model's state flow, and the registry of what is open — the tenant's models, the Pots by id, the watches
 * by id. Nothing here decides anything the person sees: a rule found here is a defect to move into the core (ADR 0127
 * decision 1).
 *
 * Every answer and update is delivered on a core thread ([Dispatchers.Default]); [compose] runs there too, so a host
 * request it makes (the store's first read) never waits on the thread that started the core.
 */
internal class Bridge<A : Connection, T : Any>(compose: () -> Composition<A, T>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val parts = scope.async { compose() }
    private val tenant = MutableStateFlow<Open<A>?>(null)
    private val ids = AtomicLong(0)
    private val pots = Registry<PotModel>()
    private val readers = Registry<RangeReader>()
    private val watches = Registry<Job>()
    private val headers = Registry<HeldHeader>()
    private val trashes = Registry<TrashSheet>()
    private val moves = Registry<MoveSheet>()

    /** "Manage servers" while it is open, null while closed; its spaces read by [managing]'s job. */
    private val manage = MutableStateFlow<List<ManagedShown>?>(null)
    private val managing = AtomicReference<Job?>(null)

    /** Turned by each tenant hidden or shown, so the shell's watch reads the switcher's tenants anew. */
    private val hiddenTurned = MutableStateFlow(0L)

    /**
     * One call, answered once through [answer] on a core thread — a request that is no [Call] too. The payload beside
     * the JSON is the bytes of an [Answer.Bytes] (never null for one, empty included) and null for every other answer.
     */
    fun call(request: String, answer: (json: String, payload: ByteArray?) -> Unit) {
        scope.launch {
            var payload: ByteArray? = null
            val a = try {
                val call = runCatching { wire.decodeFromString(Call.serializer(), request) }.getOrNull()
                if (call == null) Answer.Refused(Answer.Why.NOT_A_CALL) else dispatch(call) { payload = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Answer.Failed(e::class.simpleName ?: "Exception")
            }
            answer(wire.encodeToString(Answer.serializer(), a), if (a is Answer.Bytes) payload ?: ByteArray(0) else null)
        }
    }

    /** One line per call; a call whose answer is [Answer.Bytes] hands its bytes to [payload] first. */
    private suspend fun dispatch(call: Call, payload: (ByteArray) -> Unit): Answer {
        val parts = parts.await()
        val session = parts.session
        val shell = tenant.value?.models?.shell
        return when (call) {
            Call.SessionStart -> opened(session.start())
            is Call.SessionSignIn -> opened(session.signIn(call.server, call.email, call.password, call.secretKey)).also { managed(parts) }
            is Call.SessionStaySignedIn -> (session.stay(call.level)?.answer() ?: Answer.Refused(Answer.Why.NO_OFFER)).also { managed(parts) }
            Call.SessionNotNow -> if (session.notNow()) Answer.Done else Answer.Refused(Answer.Why.NO_OFFER)
            is Call.SessionUnlock -> opened(session.unlock(call.password))
            Call.SessionOtherAccount -> Answer.Done.also { session.otherAccount() }
            Call.SessionAddServer -> Answer.Done.also { session.addServer() }
            Call.SessionCancelAdd -> Answer.Done.also { session.cancelAdd() }
            is Call.SessionForgetDevice -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                session.forgetDevice(account)
                managed(parts)
                Answer.Done
            }
            Call.ManageOpen -> {
                manage.value = shownManaged(parts)
                managing.exchange(scope.launch { session.loadSpaces { managed(parts) } })?.cancel()
                Answer.Done
            }
            Call.ManageClose -> {
                managing.exchange(null)?.cancel()
                manage.value = null
                Answer.Done
            }
            is Call.ManageHideTenant -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                if (account.tenants.none { it.tenantId == call.tenantId }) return Answer.Refused(Answer.Why.NO_SUCH_TENANT)
                parts.hidden.setTenantHidden(account.key, call.tenantId, call.hidden)
                hiddenTurned.value++ // the switcher's tenants are read beside the shell's state, which did not change
                managed(parts)
                Answer.Done
            }
            is Call.ManageStaySignedIn -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                session.stay(account, call.level).answer().also { managed(parts) }
            }
            is Call.ManagePasswordTrust -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                session.trustWithPassword(account, call.password).answer().also { managed(parts) }
            }
            is Call.ManageHideSpace -> {
                parts.hidden.setSpaceHidden(call.scopeId, call.hidden)
                shell?.rebuild()
                managed(parts)
                Answer.Done
            }
            is Call.SessionSwitchTo -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val to = account.tenants.firstOrNull { it.tenantId == call.tenantId } ?: return Answer.Refused(Answer.Why.NO_SUCH_TENANT)
                opened(session.switchTo(account, to)).also { managed(parts) }
            }
            is Call.SessionSignOut -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val out = session.signOut(account)
                if (out.wasCurrent) closeTenant()
                opened(out.next).also { managed(parts) }
            }
            is Call.ShellChoose -> shell?.choose(call.application).done()
            is Call.ShellSwitchSpace -> shell?.switchSpace(call.scopeId).done()
            is Call.ShellEnter -> shell?.enter(call.folder).done()
            is Call.ShellFilter -> shell?.filter(call.application).done()
            Call.ShellBack -> shell?.goBack().done()
            is Call.ShellSelect -> shell?.select(call.tab).done()
            is Call.ShellOpen -> shell?.open(call.ref).done()
            is Call.ShellOpenAtTop -> shell?.openAtTop(call.ref).done()
            is Call.ShellSearch -> shell?.search(call.on).done()
            Call.ShellRefresh -> shell?.refresh().done()
            Call.HomeGroups -> shell?.current?.let { Answer.Groups(it.lists.groups(it.shell)) } ?: noTenant
            is Call.SearchQuery -> shell?.current?.let { Answer.Hits(ShellWords.search(it.lists.objects, call.query)) } ?: noTenant
            is Call.PotOpen -> tenant.value?.models?.pot?.let { make ->
                val id = ids.incrementAndFetch()
                val pot = make()
                pots.put(id, pot)
                scope.launch { pot.open(call.ref) }
                Answer.PotOpened(id)
            } ?: noTenant
            is Call.PotRefresh -> pots[call.pot]?.refresh().done(Answer.Why.NO_SUCH_POT)
            is Call.PotClose -> pots.remove(call.pot)?.close().done(Answer.Why.NO_SUCH_POT)
            is Call.PotDownload -> pots[call.pot]?.let { pot ->
                val written = parts.files.write(call.path) { sink -> pot.download(call.deletedName, sink) != null }
                if (written) Answer.Done else Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
            } ?: Answer.Refused(Answer.Why.NO_SUCH_POT)
            is Call.PotArchiveName -> pots[call.pot]?.let { pot ->
                pot.archiveName()?.let(Answer::Name) ?: Answer.Refused(Answer.Why.NOTHING_TO_DOWNLOAD)
            } ?: Answer.Refused(Answer.Why.NO_SUCH_POT)
            is Call.AvatarPicture -> tenant.value?.models?.let { t ->
                val picture = t.pictures.value[call.userId] ?: return Answer.Refused(Answer.Why.NO_PICTURE)
                payload(picture)
                Answer.Bytes(Sniff.image(picture)?.mime)
            } ?: noTenant
            is Call.ReaderRead -> readers[call.reader]?.let { r ->
                payload(r.read(call.position, call.length))
                Answer.Bytes(null)
            } ?: Answer.Refused(Answer.Why.NO_SUCH_READER)
            is Call.ReaderClose -> readers.remove(call.reader)?.let { Answer.Done } ?: Answer.Refused(Answer.Why.NO_SUCH_READER)
            is Call.HeaderOpen -> tenant.value?.models?.header?.let { make ->
                val id = ids.incrementAndFetch()
                headers.put(id, HeldHeader(make(call.node, call.facts, ::closeOpen), call.maxButtons))
                Answer.HeaderOpened(id)
            } ?: noTenant
            is Call.HeaderFacts -> headers[call.header]?.model?.update(call.facts).done(Answer.Why.NO_SUCH_HEADER)
            is Call.HeaderClose -> headers.remove(call.header)?.let { Answer.Done } ?: Answer.Refused(Answer.Why.NO_SUCH_HEADER)
            is Call.HeaderPin -> headers[call.header]?.let { Answer.Said(it.model.toggleFavorite()) } ?: noHeader
            is Call.HeaderTrash -> headers[call.header]?.let { h -> h.model.trash().let { Answer.Trashed(it, h.model.current.undo != null) } } ?: noHeader
            is Call.HeaderUndo -> headers[call.header]?.let { Answer.Said(it.model.undoTrash()) } ?: noHeader
            is Call.HeaderRename -> headers[call.header]?.let { it.model.rename(call.name).let { r -> Answer.Renamed(r.problem, r.notice) } } ?: noHeader
            is Call.HeaderDownload -> headers[call.header]?.let { h ->
                val download = tenant.value?.models?.download ?: return noTenant
                var leftOut: List<NodeExport.Skipped>? = null
                runCatching { parts.files.write(call.path) { sink -> download(h.model.node, call.deletedName, sink).also { leftOut = it } != null } }
                Answer.Downloaded(Notices.downloaded(h.model.node.name, leftOut), Notices.downloadLeftOut(leftOut))
            } ?: noHeader
            is Call.MoveOpen -> tenant.value?.models?.move?.let { make ->
                val id = ids.incrementAndFetch()
                val sheet = make(call.node, call.copy, pots.values().firstOrNull { it.current?.ref?.nodeId == call.node.nodeId }, call.deletedName)
                moves.put(id, sheet)
                scope.launch { sheet.load() }
                Answer.MoveOpened(id)
            } ?: noTenant
            is Call.MoveClose -> moves.remove(call.move)?.let { Answer.Done } ?: noMove
            is Call.MoveGo -> moves[call.move]?.go(call.at).done(Answer.Why.NO_SUCH_MOVE)
            is Call.MoveUp -> moves[call.move]?.up().done(Answer.Why.NO_SUCH_MOVE)
            is Call.MoveHere -> moves[call.move]?.let { sent(it.choose()) } ?: noMove
            is Call.MoveConfirm -> moves[call.move]?.let { sent(it.confirm()) } ?: noMove
            is Call.MoveCancelConfirm -> moves[call.move]?.cancelConfirm().done(Answer.Why.NO_SUCH_MOVE)
            is Call.MoveUndo -> moves[call.move]?.let { Answer.Said(it.undo()) } ?: noMove
            is Call.ShellCreateFolder -> tenant.value?.models?.createFolder?.let { Answer.Said(it(call.name)) } ?: noTenant
            is Call.TrashOpen -> tenant.value?.models?.trash?.let { make ->
                val id = ids.incrementAndFetch()
                val sheet = make(call.scopeId)
                trashes.put(id, sheet)
                scope.launch { sheet.load() }
                Answer.TrashOpened(id)
            } ?: noTenant
            is Call.TrashClose -> trashes.remove(call.trash)?.let { Answer.Done } ?: noTrash
            is Call.TrashRestore -> onRow(call.trash, call.row) { sheet, row -> sheet.restore(row) }
            is Call.TrashRetry -> onRow(call.trash, call.row) { sheet, row -> sheet.retry(row) }
            is Call.TrashPurge -> onRow(call.trash, call.row) { sheet, row -> sheet.purge(row) }
            is Call.TrashType -> trashes[call.trash]?.type(call.name).done(Answer.Why.NO_SUCH_TRASH)
            is Call.TrashCancelRename -> trashes[call.trash]?.cancelRename().done(Answer.Why.NO_SUCH_TRASH)
        }
    }

    /**
     * Holds [reader] for the host until [Call.ReaderClose] or the tenant closes, answered as [Answer.Reader] (ADR 0128
     * decision 2): the door of every call that opens a blob for reading in ranges (Depot's file, Memories' medium;
     * their CRs), and of the test's reader until then. Refused while no tenant is open.
     */
    internal fun openReader(reader: RangeReader, mediaType: String?): Answer {
        if (tenant.value == null) return noTenant
        val id = ids.incrementAndFetch()
        readers.put(id, reader)
        return Answer.Reader(id, reader.size, mediaType)
    }

    private val noTenant = Answer.Refused(Answer.Why.NO_TENANT_OPEN)
    private val noHeader = Answer.Refused(Answer.Why.NO_SUCH_HEADER)
    private val noTrash = Answer.Refused(Answer.Why.NO_SUCH_TRASH)
    private val noMove = Answer.Refused(Answer.Why.NO_SUCH_MOVE)

    /** A move sheet's state as a write's answer ([Answer.MoveSent]). */
    private fun sent(s: MoveSheet.State) = Answer.MoveSent(s.done, s.undo != null, s.confirming, s.problem)

    /** [act] on the row of trash sheet [trash] listed as [rowId] (`TrashSheet.row`). */
    private suspend fun onRow(trash: Long, rowId: String, act: suspend (TrashSheet, me.raddatz.veyq.mobile.session.NodeTrash.Trashed) -> Unit): Answer {
        val sheet = trashes[trash] ?: return noTrash
        val row = sheet.row(rowId) ?: return Answer.Refused(Answer.Why.NO_SUCH_ROW)
        act(sheet, row)
        return Answer.Done
    }

    /** The open Pots of a node gone to the trash close (a header's `closeOpen`). */
    private fun closeOpen(ref: NodeAt) {
        pots.values().filter { it.current?.ref?.nodeId == ref.nodeId }.forEach { it.close() }
    }

    /** "Manage servers" as the core has it now, when it is open (after a call that changed the servers or their trust). */
    private fun managed(parts: Composition<A, T>) {
        if (manage.value != null) manage.value = shownManaged(parts)
    }

    /** `SessionModel.managed`, each field the core's own ([ManagedShown]). */
    private fun shownManaged(parts: Composition<A, T>): List<ManagedShown> = parts.session.managed().map { m ->
        ManagedShown(m.account.key, m.label, m.trusted, m.level, m.levels, m.tenants.map { t ->
            ManagedShown.Tenant(t.tenant, t.hidden, t.current, t.shown, t.switchable, t.subtitle, t.spaces?.map { s ->
                ManagedShown.Space(s.space, ShellWords.space(s.space), s.hidden, s.canHide)
            })
        })
    }

    private fun Unit?.done(why: Answer.Why = Answer.Why.NO_TENANT_OPEN): Answer = if (this == null) Answer.Refused(why) else Answer.Done

    /** A change of trust's outcome as the call's answer. */
    private fun TrustChange.answer(): Answer = when (this) {
        TrustChange.DONE -> Answer.Done
        TrustChange.CANCELLED -> Answer.Refused(Answer.Why.TRUST_CANCELLED)
        TrustChange.WRONG_PASSWORD -> Answer.Refused(Answer.Why.WRONG_PASSWORD)
        TrustChange.FAILED -> Answer.Refused(Answer.Why.TRUST_FAILED)
    }

    /**
     * A tenant the session opened becomes the open one: its shell read and shown, its pictures read in the background
     * (the letters show meanwhile), every Pot and reader of the one before closed.
     */
    private suspend fun opened(o: SessionModel.Opened<A, T>?): Answer {
        if (o == null) return Answer.Done
        val t = parts.await().tenant(o.session) ?: return Answer.Done
        closeTenant()
        tenant.value = Open(t, o.account, o.tenant)
        scope.launch { runCatching { t.loadPictures() } }
        t.shell?.load(openLastUsed = true)
        return Answer.Done
    }

    private fun closeTenant() {
        tenant.value = null
        pots.removeAll().forEach { it.close() }
        readers.removeAll()
        headers.removeAll()
        trashes.removeAll()
        moves.removeAll()
    }

    /**
     * Observes what [request] names: [update] gets the state now and each change after, on a core thread, until
     * [unwatch]. Returns the watch's id, or 0 for a request that is no [Watched] (nothing is delivered then).
     */
    fun watch(request: String, update: (String) -> Unit): Long {
        val watched = runCatching { wire.decodeFromString(Watched.serializer(), request) }.getOrNull() ?: return 0
        val id = ids.incrementAndFetch()
        val job = scope.launch {
            updates(watched).collect { update(wire.encodeToString(Update.serializer(), it)) }
        }
        watches.put(id, job)
        return id
    }

    private suspend fun updates(w: Watched): Flow<Update> = when (w) {
        Watched.Session -> parts.await().session.gate.map { Update.Session(it) }
        Watched.Manage -> manage.map { Update.Manage(it) }
        Watched.Shell -> {
            val parts = parts.await()
            tenant.flatMapLatest { open ->
                val m = open?.models
                m?.shell?.state?.let { state ->
                    combine(state, m.pictures, hiddenTurned) { s, pictures, _ -> Update.Shell(s, s?.let { shown(parts, open, it, pictures.keys) }) }
                } ?: flowOf(Update.Shell(null))
            }
        }
        is Watched.Header -> headers[w.header]?.let { h -> h.model.state.map { Update.Header(w.header, shownHeader(h)) } }
            ?: flowOf(Update.Header(w.header, null))
        is Watched.Trash -> trashes[w.trash]?.let { t -> t.state.map { Update.Trash(w.trash, shownTrash(t, it)) } }
            ?: flowOf(Update.Trash(w.trash, null))
        is Watched.Move -> moves[w.move]?.let { m -> m.state.map { Update.Move(w.move, shownMove(m, it)) } }
            ?: flowOf(Update.Move(w.move, null))
        is Watched.Pot -> {
            val pictures = tenant.value?.models?.pictures ?: flowOf(emptyMap())
            (pots[w.pot]?.state ?: flowOf(null)).combine(pictures) { s, p -> Update.Pot(w.pot, s, if (s == null) emptyList() else p.keys.sorted()) }
        }
    }

    /**
     * What the shell shows beside [s] ([ShellShown]; ADR 0123's Consequences, the bridge): each field one core
     * function's answer.
     */
    private fun shown(parts: Composition<A, T>, open: Open<A>, s: ShellModel.State, pictures: Set<String>): ShellShown {
        val shell = s.shell
        val lists = s.lists
        val space = lists.space(shell)
        return ShellShown(
            tenant = open.tenant,
            server = open.account.label,
            connection = open.account.key,
            servers = parts.session.accounts.map { c ->
                ShellShown.ShownServer(c.key, c.label, parts.hidden.visibleTenants(c.key, c.tenants, if (c == open.account) open.tenant else null))
            },
            space = space,
            spaceName = ShellWords.space(space),
            switcherSubtitle = ShellWords.switcherSubtitle(space, open.tenant.name, open.account.label),
            spaces = lists.spaces.map { ShellShown.ShownSpace(it, ShellWords.space(it)) },
            applicationWords = ApplicationId.entries.map { ShellShown.ShownApplication(it, AppKinds.applicationWord(it)) },
            groups = lists.groups(shell),
            list = shell.application?.let(lists::applicationList),
            tabs = shell.barTabs.map { ShellShown.ShownTab(it, AppKinds.tabWord(it, shell.openObject?.kind)) },
            selectedTab = shell.selectedTab,
            canGoBack = shell.canGoBack,
            isHome = shell.isHome,
            drawerOpensAtStart = shell.drawerOpensAtStart,
            creates = AppKinds.drawerCreates.map { ShellShown.ShownCreate(it, it.word) },
            canCreateHere = open.models.shell?.canCreateHere == true,
            headed = shell.openObject?.at ?: shell.space?.let(shell::folderAt),
            me = open.models.me,
            pictures = pictures.sorted(),
        )
    }

    /** A header as the host draws it ([HeaderShown]), each field the model's own answer. */
    private fun shownHeader(h: HeldHeader): HeaderShown {
        val m = h.model
        val s = m.current
        fun actions(ids: List<String>) = ids.map { HeaderShown.PlanAction(it, m.label(it, hasCover = false)) }
        return HeaderShown(
            node = m.node,
            pinned = s.pinned,
            plan = m.plan(h.maxButtons)?.let { p -> HeaderShown.Plan(actions(p.buttons), actions(p.menu), actions(p.last)) },
            deleteQuestion = m.deleteQuestion(),
            undo = s.undo,
            gone = s.gone,
            archiveName = NodeExport.fileName(m.node.name),
        )
    }

    /** A move or copy sheet as the host draws it ([MoveShown]), each field the sheet's own answer. */
    private fun shownMove(m: MoveSheet, s: MoveSheet.State): MoveShown = MoveShown(
        node = m.node, copying = m.copying, title = m.title, hint = m.hint, submit = m.submit, loaded = s.loaded,
        trail = s.trail.map { MoveShown.MoveCrumb(it.nodeId, it.name) },
        places = s.places.map { MoveShown.MovePlace(it.node.nodeId, it.node.kind, it.node.name, it.blocked?.let(NodeMove::placeWord)) },
        empty = m.empty.takeIf { s.loaded && s.places.isEmpty() },
        here = m.hereWord(s), problem = s.problem, noRoom = s.noRoom, losses = s.losses.map { Notice(it) }, leftOut = s.leftOut,
        busy = s.busy, canChoose = s.canChoose, confirming = s.confirming, confirmQuestion = m.confirmQuestion, done = s.done, undo = s.undo,
    )

    /** A trash sheet as the host draws it ([TrashShown]), each field the sheet's own answer. */
    private fun shownTrash(sheet: TrashSheet, s: TrashSheet.State): TrashShown = TrashShown(
        rows = s.rows?.map { TrashShown.Row(it.id, it.kind, it.name, it.deletedAt, it.purgeAfter, sheet.canRestore(it), s.retryable(it)) },
        failed = s.failed, busy = s.busy, errorRow = s.errorRow, error = s.error, renaming = s.renaming, retryName = s.retryName,
    )

    fun unwatch(id: Long) {
        watches.remove(id)?.cancel()
    }

    /** Ends everything: the watches, the calls under way, the models. */
    fun shutdown() {
        scope.cancel()
    }

    companion object {
        /** The bridge over the real accounts: [primitives] the platform's suite, [client] its HTTP client, [store] the host's device store. */
        fun live(
            primitives: Primitives,
            client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
            config: Config,
            store: DeviceStore,
            /** The device's keys over [store]: the platform's (`platformDeviceKeys`), a test's own on the JVM. */
            keys: (DeviceStore) -> DeviceKeys,
            files: DeviceFiles,
            now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        ): Bridge<VeyqAccount, TenantSession> = Bridge {
            val recent = RecentUses(
                persist = { keys -> store.write(RECENT, keys.joinToString("\n") { it.encode() }) },
                initial = store.read(RECENT)?.lines()?.mapNotNull(UseKey::decode).orEmpty(),
            )
            val hidden = Hidden(
                persist = { ids -> store.write(HIDDEN, ids.joinToString("\n")) },
                initial = store.read(HIDDEN)?.lines()?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
            )
            val device = ThisDevice(store, keys(store), config.device, primitives)
            Composition(SessionModel.accounts(primitives, device, client, hidden, recent), hidden, files) { t ->
                Tenant(ShellModel(t, hidden, recent, now), { PotModel(t, now) }, t.account.userId, t.pictures.state, t.pictures::load)
            }
        }

        /** The device store's names for what the bridge itself keeps there (the others are the core's). */
        const val RECENT = "veyq.recent"
        const val HIDDEN = "veyq.hidden"
    }
}
