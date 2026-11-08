package me.raddatz.veyq.mobile.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ArrangedList
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.Connections
import me.raddatz.veyq.mobile.navigation.FolderRef
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.Shell
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.navigation.UseKey
import me.raddatz.veyq.mobile.navigation.arrange
import me.raddatz.veyq.mobile.navigation.drawerApplications
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.VeyqAccount

/** One server in the switcher: its account and the tenants the switcher lists there. */
data class ServerEntry(val account: VeyqAccount, val label: String, val tenants: List<TenantRef>)

/** A space on "Manage servers": shown or hidden on this device, and whether it may be hidden. */
data class ManagedSpace(val space: SpaceRef, val hidden: Boolean, val canHide: Boolean)

/** A tenant on "Manage servers". [spaces] is null while they load. */
data class ManagedTenant(val tenant: TenantRef, val hidden: Boolean, val current: Boolean, val spaces: List<ManagedSpace>?)

data class ManagedServer(val account: VeyqAccount, val label: String, val tenants: List<ManagedTenant>)

/** What the screen shows: the sign-in (first, or to add a server), or the shell over the current tenant. */
sealed interface Screen {
    data class SignIn(val working: Boolean = false, val error: String? = null, val adding: Boolean = false) : Screen

    data class Signed(
        val shell: Shell,
        val tenant: TenantRef,
        val server: String,
        val servers: List<ServerEntry>,
        val applications: List<ApplicationId>,
        val filters: List<ApplicationId?>,
        val drawer: ArrangedList,
        val spaces: List<SpaceRef>,
        val nodes: List<NodeRef>,
        val objects: List<ObjectRef>,
        val favorites: List<ObjectRef>,
        val switching: Boolean = false,
        /** "Manage servers" is open over the shell. */
        val managing: List<ManagedServer>? = null,
    ) : Screen {
        /** The chosen application's own list, by the one rule (ADR 0122 decision 5). */
        fun applicationList(app: ApplicationId): ArrangedList =
            arrange(favorites.filter { it.application == app }, emptyList(), objects.filter { it.application == app })

        val space: SpaceRef? get() = spaces.firstOrNull { it.scopeId == shell.space }

        /** Home's level: the shown folder's children in the shown space, under the shown filter. */
        val level: List<NodeRef> get() = shell.space?.let { Home.level(nodes, it, shell.folder?.nodeId, shell.filter) }.orEmpty()
    }
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("veyq-recent", Context.MODE_PRIVATE)
    private val recent = RecentUses(persist = ::persistRecent, initial = loadRecent())
    private val hidden = Hidden(persist = { prefs.edit().putStringSet("hidden", it).apply() }, initial = prefs.getStringSet("hidden", emptySet()).orEmpty())
    private val connections = Connections<VeyqAccount>()
    private val trustStore = PreferencesStore(app)

    /** The current tenant as read, before what this device hides is taken out. */
    private class Raw(val spaces: List<SpaceRef>, val nodes: List<NodeRef>, val favorites: List<ObjectRef>, val off: Set<String>)
    private var raw: Raw? = null

    private val _screen = MutableStateFlow<Screen>(Screen.SignIn())
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    fun signIn(server: String, email: String, password: String, secretKey: String) {
        val adding = (_screen.value as? Screen.SignIn)?.adding == true
        _screen.value = Screen.SignIn(working = true, adding = adding)
        viewModelScope.launch {
            try {
                val account = VeyqAccount.signIn(BouncyCastlePrimitives, origin(server), email, password, secretKey, trustStore) { block -> Network.client(block) }
                val tenant = account.defaultTenant ?: throw SignInFailure(SignInFailure.Reason.NO_TENANT)
                val added = connections.add(account, tenant)
                added.duplicate?.signOut()
                show(added.connection, connections.current!!.second)
            } catch (e: SignInFailure) {
                _screen.value = Screen.SignIn(error = e.reason.name, adding = adding)
            }
        }
    }

    /** The switcher: another tenant of the same server is the same session at another domain; another server is its own. */
    fun switchTo(account: VeyqAccount, tenant: TenantRef) {
        connections.switchTo(account, tenant)
        _screen.update { s -> if (s is Screen.Signed) s.copy(switching = true, managing = null) else s }
        viewModelScope.launch {
            try {
                show(account, tenant)
            } catch (e: SignInFailure) {
                _screen.update { s -> if (s is Screen.Signed) s.copy(switching = false) else s }
            }
        }
    }

    /** "Add server": the sign-in again, which can be left back to the shell. */
    fun addServer() {
        _screen.value = Screen.SignIn(adding = true)
    }

    fun cancelAdd() {
        val (account, tenant) = connections.current ?: return
        viewModelScope.launch { show(account, tenant) }
    }

    // ---- Manage servers ----

    fun openManage() {
        _screen.update { s -> if (s is Screen.Signed) s.copy(managing = managed(emptyMap())) else s }
        viewModelScope.launch {
            val loaded = HashMap<String, List<SpaceRef>>()
            for (c in connections.all) for (t in c.tenants) {
                runCatching { c.open(t).spaces() }.getOrNull()?.let { loaded[c.key + "#" + t.tenantId] = it }
                _screen.update { s -> if (s is Screen.Signed && s.managing != null) s.copy(managing = managed(loaded)) else s }
            }
        }
    }

    fun closeManage() = _screen.update { s -> if (s is Screen.Signed) s.copy(managing = null) else s }

    fun setTenantHidden(account: VeyqAccount, tenant: TenantRef, value: Boolean) {
        hidden.setTenantHidden(account.key, tenant.tenantId, value)
        refreshManaged()
        rebuild()
    }

    fun setSpaceHidden(scopeId: String, value: Boolean) {
        hidden.setSpaceHidden(scopeId, value)
        refreshManaged()
        rebuild()
    }

    /** Signs out of one server; if it was current, another one held becomes current, else the sign-in shows. */
    fun signOut(account: VeyqAccount) {
        val wasCurrent = connections.current?.first == account
        connections.remove(account)
        viewModelScope.launch { account.signOut() }
        val next = connections.current
        when {
            next == null -> {
                recent.clear() // the device forgets what was opened once no account is left on it
                hidden.clear()
                raw = null
                _screen.value = Screen.SignIn()
            }
            wasCurrent -> viewModelScope.launch { show(next.first, next.second) }
            else -> refreshManaged()
        }
    }

    private var managedSpaces: Map<String, List<SpaceRef>> = emptyMap()

    private fun managed(loaded: Map<String, List<SpaceRef>>): List<ManagedServer> {
        managedSpaces = loaded
        val current = connections.current
        return connections.all.map { c ->
            ManagedServer(c, c.label, c.tenants.map { t ->
                val spaces = loaded[c.key + "#" + t.tenantId]
                ManagedTenant(
                    tenant = t,
                    hidden = hidden.isTenantHidden(c.key, t.tenantId),
                    current = current?.first == c && current.second == t,
                    spaces = spaces?.map { ManagedSpace(it, hidden.isSpaceHidden(it.scopeId), hidden.canHideSpace(it.scopeId, spaces)) },
                )
            })
        }
    }

    private fun refreshManaged() = _screen.update { s -> if (s is Screen.Signed && s.managing != null) s.copy(managing = managed(managedSpaces)) else s }

    // ---- the current tenant ----

    private suspend fun show(account: VeyqAccount, tenant: TenantRef) {
        val t = account.open(tenant)
        raw = Raw(t.spaces(), t.nodes(), t.favorites(), t.applicationsOff())
        rebuild(start = true)
    }

    /** The shell's lists from what was read, less what this device hides; on [start], the shell where the app opens. */
    private fun rebuild(start: Boolean = false) {
        val r = raw ?: return
        val (account, tenant) = connections.current ?: return
        val spaces = hidden.visibleSpaces(r.spaces)
        val nodes = hidden.visibleNodes(r.nodes, r.spaces)
        val objects = nodes.mapNotNull { it.asObject() }
        val favorites = hidden.visibleObjects(r.favorites, r.spaces)
        val prior = _screen.value as? Screen.Signed
        val defaultSpace = Home.defaultSpace(spaces)?.scopeId
        val shell = when {
            start || prior == null -> Shell.start(recent.lastUsed(objects), defaultSpace)
            prior.shell.space != null && spaces.none { it.scopeId == prior.shell.space } -> prior.shell.switchSpace(defaultSpace ?: "")
            else -> prior.shell
        }
        _screen.value = Screen.Signed(
            shell = shell,
            tenant = tenant,
            server = account.label,
            servers = connections.all.map { c -> ServerEntry(c, c.label, hidden.visibleTenants(c.key, c.tenants, if (c == account) tenant else null)) },
            applications = drawerApplications(r.off).map { it.id },
            filters = Home.filters(r.off),
            drawer = drawerList(objects, favorites),
            spaces = spaces,
            nodes = nodes,
            objects = objects,
            favorites = favorites,
            managing = if (start) null else prior?.managing,
        )
    }

    fun choose(app: ApplicationId?) = updateShell { it.choose(app) }
    fun switchSpace(scopeId: String) = updateShell { it.switchSpace(scopeId) }
    fun enter(folder: FolderRef) = updateShell { it.enter(folder) }
    fun filter(app: ApplicationId?) = updateShell { it.filter(app) }
    fun goBack() = updateShell { it.goBack() }

    fun open(ref: ObjectRef) {
        recent.record(ref, System.currentTimeMillis())
        _screen.update { s -> if (s is Screen.Signed) s.copy(shell = s.shell.open(ref), drawer = drawerList(s.objects, s.favorites)) else s }
    }

    fun select(tab: BarTab) = updateShell { it.select(tab) }
    fun search(on: Boolean) = updateShell { it.search(on) }

    /** A typed server: a bare host gets https, except a dev `*.localhost` host, which the dev backend serves on http. */
    private fun origin(typed: String): String {
        val t = typed.trim().trimEnd('/')
        if ("://" in t) return t
        val host = t.substringBefore(':').substringBefore('/')
        return if (host == "localhost" || host.endsWith(".localhost")) "http://$t" else "https://$t"
    }

    private fun updateShell(f: (Shell) -> Shell) = _screen.update { s -> if (s is Screen.Signed) s.copy(shell = f(s.shell)) else s }

    private fun drawerList(objects: List<ObjectRef>, favorites: List<ObjectRef>) =
        arrange(favorites, recent.resolve(objects), favoritesLimit = 6, recentLimit = 8)

    private fun persistRecent(keys: List<UseKey>) {
        prefs.edit().putString("uses", keys.joinToString("\n") { "${it.scopeId}\t${it.nodeId}\t${it.lastUsedEpochMs}" }).apply()
    }

    private fun loadRecent(): List<UseKey> = prefs.getString("uses", "").orEmpty().lines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size == 3) p[2].toLongOrNull()?.let { UseKey(p[0], p[1], it) } else null
    }
}
