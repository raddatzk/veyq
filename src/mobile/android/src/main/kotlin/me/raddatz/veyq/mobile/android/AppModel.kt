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
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ArrangedList
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.Shell
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.UseKey
import me.raddatz.veyq.mobile.navigation.arrange
import me.raddatz.veyq.mobile.navigation.availableApplications
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.VeyqSession

/** What the screen shows: the sign-in, or the shell over a signed-in session. */
sealed interface Screen {
    data class SignIn(val working: Boolean = false, val error: String? = null) : Screen

    data class Signed(
        val shell: Shell,
        val applications: List<ApplicationId>,
        val drawer: ArrangedList,
        val spaces: List<SpaceRef>,
        val objects: List<ObjectRef>,
        val favorites: List<ObjectRef>,
    ) : Screen {
        /** The chosen application's own list, by the one rule (ADR 0122 decision 4). */
        fun applicationList(app: ApplicationId): ArrangedList =
            arrange(favorites.filter { it.application == app }, emptyList(), objects.filter { it.application == app })
    }
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("veyq-recent", Context.MODE_PRIVATE)
    private val recent = RecentUses(persist = ::persistRecent, initial = loadRecent())
    private var session: VeyqSession? = null

    private val _screen = MutableStateFlow<Screen>(Screen.SignIn())
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    fun signIn(server: String, email: String, password: String, secretKey: String) {
        _screen.value = Screen.SignIn(working = true)
        viewModelScope.launch {
            try {
                val s = VeyqSession.signIn(BouncyCastlePrimitives, origin(server), email, password, secretKey) { block -> Network.client(block) }
                session = s
                val objects = s.objects()
                val favorites = s.favorites()
                val signed = Screen.Signed(
                    shell = Shell.start(recent.lastUsed(objects)),
                    applications = availableApplications(s.applicationsOff()).map { it.id },
                    drawer = drawerList(objects, favorites),
                    spaces = s.spaces(),
                    objects = objects,
                    favorites = favorites,
                )
                _screen.value = signed
            } catch (e: SignInFailure) {
                _screen.value = Screen.SignIn(error = e.reason.name)
            }
        }
    }

    fun signOut() {
        session?.let { s -> viewModelScope.launch { s.signOut() } }
        session = null
        recent.clear()
        _screen.value = Screen.SignIn()
    }

    fun choose(app: ApplicationId?) = updateShell { it.choose(app) }

    fun open(ref: ObjectRef) {
        recent.record(ref, System.currentTimeMillis())
        _screen.update { s -> if (s is Screen.Signed) s.copy(shell = s.shell.open(ref), drawer = drawerList(s.objects, s.favorites)) else s }
    }

    fun select(tab: BarTab) = updateShell { it.select(tab) }
    fun close() = updateShell { it.close() }
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
