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
)

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
            is Call.SessionSignIn -> opened(session.signIn(call.server, call.email, call.password, call.secretKey, call.remember)).also { managed(parts) }
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

    /** "Manage servers" as the core has it now, when it is open (after a call that changed the servers or their trust). */
    private fun managed(parts: Composition<A, T>) {
        if (manage.value != null) manage.value = shownManaged(parts)
    }

    /** `SessionModel.managed`, each field the core's own ([ManagedShown]). */
    private fun shownManaged(parts: Composition<A, T>): List<ManagedShown> = parts.session.managed().map { m ->
        ManagedShown(m.account.key, m.label, m.trusted, m.tenants.map { t ->
            ManagedShown.Tenant(t.tenant, t.hidden, t.current, t.shown, t.switchable, t.subtitle, t.spaces?.map { s ->
                ManagedShown.Space(s.space, ShellWords.space(s.space), s.hidden, s.canHide)
            })
        })
    }

    private fun Unit?.done(why: Answer.Why = Answer.Why.NO_TENANT_OPEN): Answer = if (this == null) Answer.Refused(why) else Answer.Done

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
        Watched.Session -> parts.await().session.let { session ->
            // asked of the store only beside a sign-in: it may reach the platform (D-Bus, cryptngc)
            session.gate.map { Update.Session(it, if (it is SessionModel.Gate.SignIn) session.staySignedIn else null) }
        }
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
            creates = AppKinds.drawerCreates.map { it.word },
            me = open.models.me,
            pictures = pictures.sorted(),
        )
    }

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
