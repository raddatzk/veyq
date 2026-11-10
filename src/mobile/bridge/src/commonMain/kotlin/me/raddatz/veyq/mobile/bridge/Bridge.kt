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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.concurrent.atomics.AtomicLong
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
    /** The models over a tenant just opened; null where there are none (a test's tenant that is no `TenantSession`). */
    val tenant: (T) -> Tenant?,
)

internal class Tenant(val shell: ShellModel, val pot: () -> PotModel)

/** The tenant open now: its models, and the account and tenant the session opened them for. */
private class Open<A : Connection>(val models: Tenant, val account: A, val tenant: TenantRef)

/**
 * The dispatch (CR D6): each [Call] is one line onto a model's own function, each [Watched] one model's state flow, and
 * the registry of what is open — the tenant's models, the Pots by id, the watches by id. Nothing here decides anything
 * the person sees: a rule found here is a defect to move into the core (ADR 0127 decision 1).
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
    private val watches = Registry<Job>()

    /** One call, answered once through [answer] on a core thread — a request that is no [Call] too. */
    fun call(request: String, answer: (String) -> Unit) {
        scope.launch {
            val a = try {
                val call = runCatching { wire.decodeFromString(Call.serializer(), request) }.getOrNull()
                if (call == null) Answer.Refused(Answer.Why.NOT_A_CALL) else dispatch(call)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Answer.Failed(e::class.simpleName ?: "Exception")
            }
            answer(wire.encodeToString(Answer.serializer(), a))
        }
    }

    private suspend fun dispatch(call: Call): Answer {
        val session = parts.await().session
        val shell = tenant.value?.models?.shell
        return when (call) {
            Call.SessionStart -> opened(session.start())
            is Call.SessionSignIn -> opened(session.signIn(call.server, call.email, call.password, call.secretKey, remember = false))
            is Call.SessionSwitchTo -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val to = account.tenants.firstOrNull { it.tenantId == call.tenantId } ?: return Answer.Refused(Answer.Why.NO_SUCH_TENANT)
                opened(session.switchTo(account, to))
            }
            is Call.SessionSignOut -> {
                val account = session.accounts.firstOrNull { it.key == call.connection } ?: return Answer.Refused(Answer.Why.NO_SUCH_CONNECTION)
                val out = session.signOut(account)
                if (out.wasCurrent) closeTenant()
                opened(out.next)
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
            is Call.PotOpen -> tenant.value?.models?.let { t ->
                val id = ids.incrementAndFetch()
                val pot = t.pot()
                pots.put(id, pot)
                scope.launch { pot.open(call.ref) }
                Answer.PotOpened(id)
            } ?: noTenant
            is Call.PotRefresh -> pots[call.pot]?.refresh().done(Answer.Why.NO_SUCH_POT)
            is Call.PotClose -> pots.remove(call.pot)?.close().done(Answer.Why.NO_SUCH_POT)
        }
    }

    private val noTenant = Answer.Refused(Answer.Why.NO_TENANT_OPEN)

    private fun Unit?.done(why: Answer.Why = Answer.Why.NO_TENANT_OPEN): Answer = if (this == null) Answer.Refused(why) else Answer.Done

    /** A tenant the session opened becomes the open one: its shell read and shown, every Pot of the one before closed. */
    private suspend fun opened(o: SessionModel.Opened<A, T>?): Answer {
        if (o == null) return Answer.Done
        val t = parts.await().tenant(o.session) ?: return Answer.Done
        closeTenant()
        tenant.value = Open(t, o.account, o.tenant)
        t.shell.load(openLastUsed = true)
        return Answer.Done
    }

    private fun closeTenant() {
        tenant.value = null
        pots.removeAll().forEach { it.close() }
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
        Watched.Shell -> {
            val parts = parts.await()
            tenant.flatMapLatest { open ->
                open?.models?.shell?.state?.map { s -> Update.Shell(s, s?.let { shown(parts, open, it) }) } ?: flowOf(Update.Shell(null))
            }
        }
        is Watched.Pot -> (pots[w.pot]?.state ?: flowOf(null)).map { Update.Pot(w.pot, it) }
    }

    /** What the shell shows beside [s] (CR D9, [ShellShown]): each field one core function's answer. */
    private fun shown(parts: Composition<A, T>, open: Open<A>, s: ShellModel.State): ShellShown {
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
            val device = ThisDevice(store, NoDeviceKeys, config.device, primitives)
            Composition(SessionModel.accounts(primitives, device, client, hidden, recent), hidden) { t ->
                Tenant(ShellModel(t, hidden, recent, now)) { PotModel(t, now) }
            }
        }

        /** The device store's names for what the bridge itself keeps there (the others are the core's). */
        const val RECENT = "veyq.recent"
        const val HIDDEN = "veyq.hidden"
    }
}
