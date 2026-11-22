package me.raddatz.veyq.mobile.android.core

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.AndroidDevice
import me.raddatz.veyq.mobile.android.AndroidMusicControls
import me.raddatz.veyq.mobile.android.BackgroundWork
import me.raddatz.veyq.mobile.android.MediaDeck
import me.raddatz.veyq.mobile.android.OfflineWork
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.UploadWork
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ArrangedList
import me.raddatz.veyq.mobile.navigation.BarTab
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
import me.raddatz.veyq.mobile.session.LivePoll
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.addressChange
import me.raddatz.veyq.mobile.session.erasure
import me.raddatz.veyq.mobile.session.passwordChange
import me.raddatz.veyq.mobile.session.rotation.keyRotation

/** One tenant row of the switcher (core's `ShellWords.SwitcherEntry`) and the account it switches to. */
data class ServerEntry(val account: VeyqAccount, val entry: me.raddatz.veyq.mobile.navigation.ShellWords.SwitcherEntry)

/** "Manage servers"' rows are core's (`SessionModel.managed`). */
typealias ManagedSpace = me.raddatz.veyq.mobile.session.SessionModel.ManagedSpace
typealias ManagedTenant = me.raddatz.veyq.mobile.session.SessionModel.ManagedTenant
typealias ManagedServer = me.raddatz.veyq.mobile.session.SessionModel.ManagedServer<VeyqAccount>

/** What the screen shows: the sign-in (first, or to add a server), or the shell over the current tenant. */
sealed interface Screen {
    /** The remembered accounts resuming at start. */
    data object Starting : Screen

    /** The full sign-in, with the Secret Key; [server] and [email] filled in where a trusted device's record knew them. */
    data class SignIn(
        val working: Boolean = false,
        val error: me.raddatz.veyq.mobile.words.Msg? = null,
        val adding: Boolean = false,
        val server: String? = null,
        val email: String? = null,
        /** An Emergency Kit read: its Secret Key held by the core, the row says so; null where the key is typed. */
        val kit: me.raddatz.veyq.mobile.session.SessionModel.KitShown? = null,
    ) : Screen

    /** An account this device trusts: the password alone (DEVICE), or the person's authentication (DEVICE_KEY). */
    data class Unlock(val record: TrustedDevice, val working: Boolean = false, val error: me.raddatz.veyq.mobile.words.Msg? = null) : Screen

    data class Signed(
        val shell: Shell,
        val tenant: TenantRef,
        val server: String,
        /** The switcher's tenant rows (core's `ShellWords.switcher`), each with the account it switches to. */
        val servers: List<ServerEntry>,
        val applications: List<ApplicationId>,
        val filters: List<ApplicationId?>,
        val drawer: ArrangedList,
        /** The drawer's sections with something in them (core's `Lists.drawerSections`): an empty one is not drawn. */
        val drawerSections: List<me.raddatz.veyq.mobile.navigation.DrawerSection> = emptyList(),
        val spaces: List<SpaceRef>,
        val nodes: List<NodeRef>,
        val objects: List<ObjectRef>,
        val favorites: List<ObjectRef>,
        /** What this device opened (core's `Lists.uses`): an application's list shows its Recent from it. */
        val uses: List<me.raddatz.veyq.mobile.navigation.Use> = emptyList(),
        val switching: Boolean = false,
        /** "Manage servers" is open over the shell. */
        val managing: List<ManagedServer>? = null,
        /** The shell's banner while the server is out of reach (core's `ShellModel.State.banner`); null online. */
        val banner: me.raddatz.veyq.mobile.words.Notice? = null,
        /** Items made available offline that left this device (core's `State.gone`), said until [AppModel.goneSeen]. */
        val gone: List<me.raddatz.veyq.mobile.words.Notice> = emptyList(),
    ) : Screen {
        /** Whether the account reaches its server: offline every write is taken away (ADR 0140 decision 7). */
        val online: Boolean get() = banner == null

        /** The chosen application's own list, by the one rule (ADR 0122 decision 5). */
        fun applicationList(app: ApplicationId): ArrangedList = lists.applicationList(app)

        val space: SpaceRef? get() = lists.space(shell)

        /** Home's level: the shown folder's children in the shown space, under the shown filter. */
        val level: List<NodeRef> get() = lists.level(shell)
        /** Home's level in its groups, the folders apart (core's `Home.groups`). */
        val levelGroups: List<me.raddatz.veyq.mobile.navigation.Home.Group> get() = lists.groups(shell)

        /** The lists as core's shell holds them, for its rules. */
        private val lists: me.raddatz.veyq.mobile.composition.ShellModel.Lists
            get() = me.raddatz.veyq.mobile.composition.ShellModel.Lists(spaces, nodes, objects, favorites, applications, filters, drawer, uses)
    }
}

class AppModel(app: Application) : AndroidViewModel(app) {
    init {
        // a copy sent to another app in an earlier run has been read by now (or never will be)
        clearSentCopies()
        // a shared HEIC's JPEG copy an earlier process never placed (ShareIn's `no_backup/share-in/`)
        ShareIn.sweep(app)
        // a viewed file's decrypted copy that builds before core's file page left in the cache (`view-…`)
        app.cacheDir.listFiles { f -> f.name.startsWith("view-") }?.forEach { runCatching { it.delete() } }
    }

    private val prefs = app.getSharedPreferences("veyq-recent", Context.MODE_PRIVATE)
    private val recent = RecentUses(persist = ::persistRecent, initial = loadRecent())
    private val hidden = Hidden(persist = { prefs.edit().putStringSet("hidden", it).apply() }, initial = prefs.getStringSet("hidden", emptySet()).orEmpty())
    /** The device's keys in the Keystore: its wraps, its unlock keys, its sealed secrets (a link's guest sessions among them). */
    private val deviceKeys = AndroidDevice.keys(app)
    /** This installation, the process's one: the background sync's worker reads the same ([AndroidDevice]). */
    private val device = AndroidDevice.of(app)

    /** The way in (core's `SessionModel`): the start, the sign-in, the unlock, the switcher, the sign-out. */
    private val session = me.raddatz.veyq.mobile.session.SessionModel.accounts(
        BouncyCastlePrimitives, device, { block -> Network.client(block) }, hidden, recent,
    )

    /** The shell over the current tenant (core's `ShellModel`): its lists, where the person is, the actions on a node. */
    internal var shellModel: me.raddatz.veyq.mobile.composition.ShellModel? = null
        private set
    private var shellWatch: kotlinx.coroutines.Job? = null

    private val _searchModel = MutableStateFlow<me.raddatz.veyq.mobile.search.SearchModel?>(null)

    /**
     * The search over the current tenant (core's `SearchModel`, ADR 0146 decision 7): built with the shell, its words
     * Android's catalog, disposed where the tenant closes — a switch, a lock, a sign-out. Null while no tenant is open.
     */
    val searchModel: StateFlow<me.raddatz.veyq.mobile.search.SearchModel?> = _searchModel.asStateFlow()

    private val _settingsAt = MutableStateFlow<String?>(null)

    /** The section the Settings page scrolls to once (a settings result's, `SettingsSection.id`); null: its top. */
    val settingsAt: StateFlow<String?> = _settingsAt.asStateFlow()

    /** The last settings result the page was opened for (core's `SearchShown.openSettingsCount`, counted per model). */
    private var settingsOpenedCount = 0

    private fun closeSearch() {
        _searchModel.value?.dispose()
        _searchModel.value = null
        settingsOpenedCount = 0
    }

    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _offer = MutableStateFlow<me.raddatz.veyq.mobile.session.SessionModel.Offer?>(null)

    /** The offer to stay signed in a sign-in left open (core's `Gate.Signed.offer`), drawn over the shell; null once answered. */
    val offer: StateFlow<me.raddatz.veyq.mobile.session.SessionModel.Offer?> = _offer.asStateFlow()

    // ---- the music (ADR 0142): declared above the gate's collector in `init`, which may stop it at once ----

    /** The system's media controls, the media session and its foreground service, one for the app ([AndroidMusicControls]). */
    private val musicControls by lazy { AndroidMusicControls(getApplication()) }

    private val _music = MutableStateFlow<me.raddatz.veyq.mobile.audio.MusicPlayer?>(null)

    /**
     * The open account's one music player (core's `MusicPlayer`, ADR 0142 decision 1), its decks Android's [MediaDeck]s:
     * built when a tenant opens, stopped where the tenant's models close — a sign-out, a switch, the session locked or gone.
     * Null while no tenant is open. The shell's bar draws it; every gallery plays through it.
     */
    val music: StateFlow<me.raddatz.veyq.mobile.audio.MusicPlayer?> = _music.asStateFlow()

    private fun stopMusic() {
        _music.value?.stop()
        _music.value = null
    }

    // ---- the map services (ADR 0143 D2): one per open tenant, closed where the tenant's models close ----

    private val _maps = MutableStateFlow<me.raddatz.veyq.mobile.map.MapServices?>(null)

    /**
     * The open tenant's map services on this device (core's `MapServices`, built by the shell beside its uploads): what
     * its server serves, this device's consent, the tiles and the geocoder. Closed — its tiles dropped, its geocoder's
     * queue cleared — at a lock, a sign-out and a tenant switch; null while no tenant is open.
     */
    val maps: StateFlow<me.raddatz.veyq.mobile.map.MapServices?> = _maps.asStateFlow()

    private fun closeMaps() {
        _maps.value?.close()
        _maps.value = null
    }


    // ---- what each application holds here (ADR 0154 decision 3), declared above `init`: the gate's collector asks them ----

    /**
     * Each registered application's [Holding] (the registry's `holding`), built once, in the order they registered: the
     * core UI asks them at each moment of the tenant's life and in the flows an application answers for its own kinds,
     * and names none of them.
     */
    private val holdings: Map<HoldingKey<*>, Holding> by lazy {
        Registry.applications().mapNotNull { it.holding }.associateWith { it.make(this) }
    }

    /** The holding an application registered under [key]: how its UI reaches what it holds here. */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Holding> held(key: HoldingKey<T>): T = holdings[key] as? T ?: error("no holding registered under this key")

    private fun eachHolding(act: (Holding) -> Unit) = holdings.values.forEach(act)

    init {
        viewModelScope.launch { session.gate.collect(::gateChanged) }
        // the shown account ended on its own and core opened the next one (core's `SessionModel.followed`): the shell follows it
        viewModelScope.launch {
            session.followed.collect { opened ->
                if (opened != null && shellModel?.tenant !== opened.session) {
                    trustLog("the shown account ended: the shell follows the next one")
                    show(opened)
                }
            }
        }
        // a pin's fetch keeps the process once the app is left: WorkManager's worker, a dataSync foreground service
        OfflineWork.watch(app, viewModelScope)
        // an upload goes on with the app left (ADR 0145 decision 1)
        UploadWork.watch(app, viewModelScope)
        // the background sync scheduled while an account at the login level has it on (ADR 0153 decision 8)
        BackgroundWork.watch(app, viewModelScope)
    }

    private fun gateChanged(g: me.raddatz.veyq.mobile.session.SessionModel.Gate) {
        // the session locked, signed out or starting again: the tenant's music stops with it (ADR 0142 decision 1)
        if (g !is me.raddatz.veyq.mobile.session.SessionModel.Gate.Signed) {
            stopMusic()
            closeMaps()
            // locked or gone: the open dialogs, the profile's section and the tenant's tree go (ADR 0144 decision 12)
            eachHolding { it.locked() }
            closeSearch()
            // a kept link shown in place goes with the tenant; a link open on its own page is a guest's and stays
            openBookmark?.close()
            openBookmark = null
            tenantSession?.close()
        }
        _offer.value = (g as? me.raddatz.veyq.mobile.session.SessionModel.Gate.Signed)?.offer
        trustLog("gate ${g::class.simpleName}" + when (g) {
            is me.raddatz.veyq.mobile.session.SessionModel.Gate.Signed -> ", offer: ${g.offer?.levels}"
            is me.raddatz.veyq.mobile.session.SessionModel.Gate.Unlock -> ", ${g.record.method}, level: ${g.record.unlockLevel}"
            else -> ""
        })
        when (g) {
            is me.raddatz.veyq.mobile.session.SessionModel.Gate.SignIn -> _screen.value = Screen.SignIn(g.working, g.error, g.adding, g.server, g.email, g.kit)
            is me.raddatz.veyq.mobile.session.SessionModel.Gate.Unlock -> _screen.value = Screen.Unlock(g.record, g.working, g.error)
            is me.raddatz.veyq.mobile.session.SessionModel.Gate.Signed -> {
                shellModel?.state?.value?.let(::publish)
            }
            else -> _screen.value = Screen.Starting
        }
    }

    /** The activity a prompt for the unlock key is shown over, while one is resumed. */
    fun attach(activity: android.app.Activity?) {
        AndroidDevice.host = activity?.let { java.lang.ref.WeakReference(it) }
    }

    /**
     * Where the app opens (ADR 0123 decision 8): every account the person stays signed in to resumes after one
     * authentication; with none, the most recent password-trusted account asks for its password; with no trusted account,
     * the sign-in. Once per process, from the first resumed activity (the prompt needs one).
     */
    fun start() {
        viewModelScope.launch { session.start()?.let { show(it) } }
    }

    /** The debug build's unlock (`devlogin.py --unlock`): the password for the unlock screen, now or once it shows. */
    fun devUnlock(password: String) {
        viewModelScope.launch { session.devUnlock(password)?.let { show(it) } }
    }

    /**
     * The debug build's kit sign-in (`devlogin.py --kit`): the password waits for the Emergency Kit the sign-in holds —
     * picked or scanned by the pass — and then signs in as the form's button does (its email and server, an empty Secret
     * Key: the core's held one). Logged to `veyq-kit`, never the email, the key or the password.
     */
    fun devSignInWithKit(password: String) {
        viewModelScope.launch {
            val s = kotlinx.coroutines.withTimeoutOrNull(300_000) {
                screen.first { it is Screen.SignIn && it.kit != null && !it.working } as Screen.SignIn
            }
            if (s == null) {
                android.util.Log.i("veyq-kit", "dev kit sign-in: no kit held within 300 s")
                return@launch
            }
            val kit = s.kit!!
            android.util.Log.i("veyq-kit", "dev kit sign-in: kit held, server from ${kit.serverFrom}; signing in")
            signIn(s.server ?: kit.server.orEmpty(), s.email ?: kit.email, password, "")
        }
    }

    /**
     * The full sign-in, which holds the password trust; the core's offer to stay signed in follows it ([offer]). [then]
     * runs once it opened — the debug build's `--remember` answers the offer there, as a person would.
     */
    fun signIn(server: String, email: String, password: String, secretKey: String, then: (suspend () -> Unit)? = null) {
        viewModelScope.launch {
            val opened = session.signIn(server, email, password, secretKey) ?: return@launch
            show(opened)
            then?.invoke()
        }
    }

    /**
     * The Emergency Kit file the person picked ([uri], `OpenDocument`): read into memory ([KitFile], never a copy on
     * disk) and handed to the core, which finds the kit and holds its Secret Key ([AndroidKitPictures] draws what it asks
     * for); [typedServer] is the form's server as typed now. What it found shows on the sign-in's gate.
     */
    fun readKit(uri: android.net.Uri, typedServer: String) {
        viewModelScope.launch {
            val bytes = KitFile.read(getApplication<Application>().contentResolver, uri)
            AndroidKitPictures(bytes).use { pictures -> session.readKit(bytes, pictures, typedServer) }
        }
    }

    /** The Secret Key row's way back to typing: the kit's held key ends. */
    fun clearKit() = session.clearKit()

    /** Why the sign-in's Secret Key field, as typed, holds no key (core's `SessionModel.secretKeyProblem`): a typo is said. */
    fun secretKeyProblem(text: String) = session.secretKeyProblem(text)

    private val _kitScan = MutableStateFlow<me.raddatz.veyq.mobile.qr.QrScan<*>?>(null)

    /**
     * The sign-in's kit scan on screen ([KitScanner]), or null. This model's, as the device link is: a rotation recreates
     * the activity and drops what a composable remembers, and closed the scanner with it (#937).
     */
    val kitScan: StateFlow<me.raddatz.veyq.mobile.qr.QrScan<*>?> = _kitScan.asStateFlow()

    /**
     * Opens the sign-in's kit scan for [KitScanner]'s camera: core's scan with the kit's parser as its `accepts`, the found
     * kit taken by core (the gate fills the form); [typedServer] is the form's server as typed now. Each decode's time is
     * logged, never a code's text.
     */
    fun scanKit(typedServer: String) {
        closeKitScan()
        _kitScan.value = session.scanKit(typedServer, me.raddatz.veyq.mobile.qr.QrFrames.timedDecode(::qrDecodeTiming))
    }

    /** The kit scan ends (its scanner closed, a kit found, no camera): nothing is decoded after it. */
    fun closeKitScan() {
        _kitScan.value?.close()
        _kitScan.value = null
    }

    override fun onCleared() {
        closeKitScan()
        closeFlowKitScan()
        super.onCleared()
    }

    // ---- device linking (ADR 0059) ----

    private val _link = MutableStateFlow<me.raddatz.veyq.mobile.session.DeviceLink?>(null)

    /** The ceremony on screen ([DeviceLinkScreen]), or null. */
    val link: StateFlow<me.raddatz.veyq.mobile.session.DeviceLink?> = _link.asStateFlow()
    private var linkWatch: kotlinx.coroutines.Job? = null

    /** How the link on screen was opened, for "Start over": the same role, the same server. */
    private var linkAgain: (() -> me.raddatz.veyq.mobile.session.DeviceLink?)? = null

    /** Settings' "Show a code" (*add a new device*, the trusted side): this account's code for the new device to scan. */
    fun showForNewDevice() {
        val account = session.signedTo?.account ?: return
        openLink { me.raddatz.veyq.mobile.session.DeviceLink.showForNewDevice(account) }
    }

    /** Settings' "Scan a code" (*sign in with a QR code*, the trusted side): the new device's code, scanned here. */
    fun scanSignInCode() {
        val account = session.signedTo?.account ?: return
        openLink { me.raddatz.veyq.mobile.session.DeviceLink.scanSignInCode(account) }
    }

    /** The sign-in's "Scan a code from another device", over the form's [server] as typed now (nothing in a scan changes it). */
    fun scanFromTrusted(server: String) =
        openLink { session.scanFromTrusted(server, me.raddatz.veyq.mobile.qr.QrFrames.timedDecode(::qrDecodeTiming)) }

    /** The sign-in's "Sign in with a QR code", over the form's [server] as typed now. */
    fun showForSignIn(server: String) = openLink { session.showForSignIn(server) }

    /** "Start over": a new ceremony of the same role — a new secret, never the old one retried (ADR 0059 §6). */
    fun linkAgain() {
        val again = linkAgain ?: return
        openLink(again)
    }

    /** The ceremony's screen went: the link ends (its polls stop, its scan closes, nothing more is sent). */
    fun closeLink() {
        linkWatch?.cancel()
        linkWatch = null
        _link.value?.close()
        _link.value = null
    }

    private fun openLink(make: () -> me.raddatz.veyq.mobile.session.DeviceLink?) {
        closeLink()
        val link = make() ?: return
        linkAgain = make
        _link.value = link
        // its steps to logcat (`veyq-link`): the step, the refusal and the hint — never a code, a number or a name
        val newDevice = link.role == me.raddatz.veyq.mobile.session.DeviceLink.Role.SCAN_FROM_TRUSTED ||
            link.role == me.raddatz.veyq.mobile.session.DeviceLink.Role.SHOW_FOR_SIGN_IN
        // the new device signed in: the shell opens over the account the grant made, its offer over it — awaited apart from
        // the watch, which a close ends: a close while the grant is spent ends the screen, not the sign-in (ADR 0136's Consequences)
        if (newDevice) viewModelScope.launch { session.signedInBy(link)?.let { show(it) } }
        linkWatch = viewModelScope.launch {
            link.state.collect { s ->
                android.util.Log.i("veyq-link", "${link.role}: ${s.step}" + (s.refusal?.let { ", $it" } ?: "") + (s.hint?.let { ", hint $it" } ?: ""))
                if (newDevice && s.step == me.raddatz.veyq.mobile.session.DeviceLink.Step.DONE) closeLink() // cancels this watch: last
            }
        }
    }

    // ---- staying signed in (ADR 0123 decision 8) ----

    private val _trustWorking = MutableStateFlow(false)

    /** A change of this device's trust is under way (the offer's answer, Manage's): its controls wait. */
    val trustWorking: StateFlow<Boolean> = _trustWorking.asStateFlow()

    /**
     * The offer's answer: staying signed in at [level], or "Not now" (null). A cancelled prompt leaves the offer standing
     * (the core's rule); a level this device could not keep ends it, said in the shell.
     */
    fun answerOffer(level: me.raddatz.veyq.mobile.session.StaySignedIn?) {
        if (level == null) {
            session.notNow()
            trustLog("offer answered: NotNow")
            return
        }
        viewModelScope.launch {
            val change = trusting { session.stay(level) }
            trustLog("offer answered: $level -> $change")
            if (change == me.raddatz.veyq.mobile.session.TrustChange.FAILED) trustFailed()
        }
    }

    /** "Manage servers": [account] staying signed in at [level], replacing the trust it had; [done] gets the outcome. */
    fun stayAt(account: VeyqAccount, level: me.raddatz.veyq.mobile.session.StaySignedIn, done: (me.raddatz.veyq.mobile.session.TrustChange) -> Unit = {}) {
        viewModelScope.launch {
            val change = trusting { session.stay(account, level) }
            trustLog("manage stay signed in $level: $change")
            refreshManaged()
            if (change == me.raddatz.veyq.mobile.session.TrustChange.FAILED) trustFailed()
            done(change)
        }
    }

    /** "Manage servers"' way back to the password trust for [account] with [password]; [done] gets the outcome. */
    fun backToPassword(account: VeyqAccount, password: String, done: (me.raddatz.veyq.mobile.session.TrustChange) -> Unit) {
        viewModelScope.launch {
            val change = trusting { session.trustWithPassword(account, password) }
            trustLog("manage back to the password: $change")
            refreshManaged()
            if (change == me.raddatz.veyq.mobile.session.TrustChange.FAILED) trustFailed()
            done(change)
        }
    }

    private suspend fun <R> trusting(block: suspend () -> R): R {
        _trustWorking.value = true
        try {
            return block()
        } finally {
            _trustWorking.value = false
        }
    }

    /** The device trust's steps in logcat (`veyq-trust`), for a pass to read before it looks: never a key or a password. */
    private fun trustLog(line: String) {
        android.util.Log.i("veyq-trust", line)
    }

    private fun trustFailed() = said(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.TRUST_FAILED))

    /** The debug build's `--unlock` password, for "Manage servers"' password field once (never typed by a pass). */
    private var devPasswordTrust: String? = null

    fun devFillPasswordTrust(password: String) {
        devPasswordTrust = password
    }

    /** The debug password for "Manage servers"' field, once; null in a release build and after it was taken. */
    fun takeDevPasswordTrust(): String? = devPasswordTrust.also { devPasswordTrust = null }

    /** The trusted account's unlock: its password (DEVICE), or the person's authentication again (DEVICE_KEY). */
    fun unlock(password: String) {
        viewModelScope.launch { session.unlock(password)?.let { show(it) } }
    }

    /** From the unlock to the full sign-in, for another account. */
    fun otherAccount() = session.otherAccount()

    /**
     * The unlock's *Stop trusting this device* (inbox #1052): core's ask first, always (owner 2026-11-22), naming the writes
     * made offline that wait in the same question (`QueuedShown.forgetAsk`), then the account forgotten here; the gate
     * shows the next account or the sign-in.
     */
    fun forgetUnlocked() {
        val go = { viewModelScope.launch { if (session.forgetUnlocked()) trustLog("unlock stop trusting") }; Unit }
        _lossAsk.value = LossAsk(me.raddatz.veyq.mobile.composition.QueuedShown.forgetAsk(session.waitingOnUnlock()), go, {})
    }

    /** The switcher: another tenant of the same server is the same session at another domain; another server is its own. */
    fun switchTo(account: VeyqAccount, tenant: TenantRef) {
        _screen.update { s -> if (s is Screen.Signed) s.copy(managing = null) else s }
        viewModelScope.launch { session.switchTo(account, tenant)?.let { show(it) } }
    }

    /** "Add server": the sign-in again, which can be left back to the shell. */
    fun addServer() = session.addServer()

    fun cancelAdd() = session.cancelAdd()

    // ---- Manage servers ----

    fun openManage() {
        viewModelScope.launch { session.loadSpaces { refreshManaged() } }
        _screen.update { s -> if (s is Screen.Signed) s.copy(managing = session.managed()) else s }
        trustLog("manage servers: " + session.managed().joinToString { "${it.trusted}/${it.level}" })
    }

    fun closeManage() = _screen.update { s -> if (s is Screen.Signed) s.copy(managing = null) else s }

    fun setTenantHidden(account: VeyqAccount, tenant: TenantRef, value: Boolean) {
        hidden.setTenantHidden(account.key, tenant.tenantId, value)
        refreshManaged()
        shellModel?.state?.value?.let(::publish)
    }

    fun setSpaceHidden(scopeId: String, value: Boolean) {
        hidden.setSpaceHidden(scopeId, value)
        refreshManaged()
        shellModel?.rebuild()
    }

    /**
     * Signs out of one server; if it was current, another one held becomes current, else the unlock of an account this
     * device still trusts with the password, or the sign-in. Staying signed in ends with the sign-out; the password trust
     * stays (as on the web).
     */
    fun signOut(account: VeyqAccount) = beforeLoss(account, me.raddatz.veyq.mobile.composition.QueuedLoss.SIGN_OUT) {
        if (session.signedTo?.account == account) uploads.clear()
        viewModelScope.launch { signedOut(session.signOut(account)) }
    }

    /** What a sign-out left: another account opened, nothing (the sign-in or the unlock follows), or Manage read anew. */
    private suspend fun signedOut(out: me.raddatz.veyq.mobile.session.SessionModel.SignedOut<VeyqAccount, me.raddatz.veyq.mobile.session.TenantSession>) {
        when {
            out.next != null -> show(out.next!!)
            out.wasCurrent -> {
                closeAccountFlow()
                shellWatch?.cancel()
                shellModel = null
                _shell.value = null
                eachHolding { it.signedOut() }
                stopMusic()
                closeSearch()
                tenantSession?.close()
            }
            else -> refreshManaged()
        }
    }

    /** Ends this device's trust in [account]: the next sign-in on this device asks for the Secret Key again. */
    fun forgetDevice(account: VeyqAccount) = beforeLoss(account, me.raddatz.veyq.mobile.composition.QueuedLoss.STOP_TRUSTING) {
        viewModelScope.launch {
            session.forgetDevice(account)
            trustLog("manage stop trusting")
            refreshManaged()
        }
    }

    // ---- the account's Settings (uc.account.sessions) ----

    /** The signed-in account's Settings while its page is open: core's screen model, drawn by [SettingsScreen]. */
    private var settingsModel: me.raddatz.veyq.mobile.session.AccountSettings<VeyqAccount, me.raddatz.veyq.mobile.session.TenantSession>? = null
    private var settingsWatch: kotlinx.coroutines.Job? = null
    private val _settings = MutableStateFlow<me.raddatz.veyq.mobile.session.AccountSettings.State?>(null)

    /** The open Settings page's state; null while it is closed. */
    val settings: StateFlow<me.raddatz.veyq.mobile.session.AccountSettings.State?> = _settings.asStateFlow()

    /** The drawer's Settings: the current account's page, read anew as it opens. */
    fun openSettings() {
        val account = session.signedTo?.account ?: return
        val m = me.raddatz.veyq.mobile.session.AccountSettings.of(session, account, separator())
        settingsWatch?.cancel()
        settingsModel = m
        settingsWatch = viewModelScope.launch {
            // "Storage on this device" read anew at each change of the pins while the page is open (core's)
            launch { m.followStorage() }
            m.state.collect { _settings.value = it }
        }
        viewModelScope.launch { m.refresh() }
    }

    fun closeSettings() {
        _settingsAt.value = null
        settingsWatch?.cancel()
        settingsWatch = null
        settingsModel = null
        _settings.value = null
    }

    /** Runs [block] on the open page's model; a sign-out it answers closes the page and is followed as Manage's is. */
    private fun onSettings(block: suspend (me.raddatz.veyq.mobile.session.AccountSettings<VeyqAccount, me.raddatz.veyq.mobile.session.TenantSession>) -> me.raddatz.veyq.mobile.session.SessionModel.SignedOut<VeyqAccount, me.raddatz.veyq.mobile.session.TenantSession>?) {
        val m = settingsModel ?: return
        viewModelScope.launch {
            val out = block(m) ?: return@launch
            trustLog("settings: signed out here")
            if (out.wasCurrent) uploads.clear()
            closeSettings()
            signedOut(out)
        }
    }

    /** The page's actions, each a call of core's model (the page decides nothing). */
    val settingsActions: SettingsActions = object : SettingsActions {
        override fun close() = closeSettings()
        override fun rename(label: String) = onSettings { it.rename(label); null }
        override fun signOut(familyId: String) {
            // this device's own family is a sign-out here: asked first while writes made offline wait (ADR 0153 decision 2)
            if (settingsModel?.current?.families?.firstOrNull { it.id == familyId }?.current == true) {
                beforeLoss(session.signedTo?.account, me.raddatz.veyq.mobile.composition.QueuedLoss.SIGN_OUT) { onSettings { it.signOut(familyId) } }
            } else {
                onSettings { it.signOut(familyId) }
            }
        }
        override fun signOutOthers() = onSettings { it.signOutOthers(); null }
        override fun signOutAndRemoveKey(familyId: String) = onSettings { it.signOutAndRemoveKey(familyId); null }
        override fun removeSavedKey(deviceId: String) = onSettings { it.removeSavedKey(deviceId); null }
        override fun removeEveryOtherSavedKey() = onSettings { it.removeEveryOtherSavedKey(); null }
        override fun stay(level: me.raddatz.veyq.mobile.session.StaySignedIn, done: (me.raddatz.veyq.mobile.session.TrustChange) -> Unit) = onSettings { m ->
            val change = m.stay(level)
            trustLog("settings stay signed in $level: $change")
            done(change)
            null
        }
        override fun backToPassword(password: String, done: (me.raddatz.veyq.mobile.session.TrustChange) -> Unit) = onSettings { m ->
            val change = m.trustWithPassword(password)
            trustLog("settings back to the password: $change")
            done(change)
            null
        }
        override fun stopTrusting() = beforeLoss(session.signedTo?.account, me.raddatz.veyq.mobile.composition.QueuedLoss.STOP_TRUSTING) {
            onSettings { m ->
                m.stopTrusting()
                trustLog("settings stop trusting")
                null
            }
        }
        override fun signOutHere() = beforeLoss(session.signedTo?.account, me.raddatz.veyq.mobile.composition.QueuedLoss.SIGN_OUT) { onSettings { it.signOutHere() } }
        override fun setOfflineLimit(amount: String, unit: me.raddatz.veyq.mobile.offline.LimitUnit) = onSettings { it.setOfflineLimit(amount, unit); null }
        override fun turnOfflineLimitOff() = onSettings { it.turnOfflineLimitOff(); null }
        override fun clearOfflineCache() = onSettings { it.clearOfflineCache(); null }
        override fun unpinOffline(node: String) = onSettings { it.unpinOffline(node); null }
        override fun setBackgroundSync(on: Boolean) = onSettings { it.setBackgroundSync(on); null }
        override fun askRemoveAllOffline() { settingsModel?.askRemoveAllOffline() }
        override fun cancelRemoveAllOffline() { settingsModel?.cancelRemoveAllOffline() }
        override fun removeAllOffline() = beforeLoss(session.signedTo?.account, me.raddatz.veyq.mobile.composition.QueuedLoss.REMOVE_ALL, cancel = ::cancelRemoveAllOffline) {
            onSettings { it.removeAllOffline(); null }
        }
        override fun linkShow() = showForNewDevice()
        override fun linkScan() = scanSignInCode()
        override fun renameFamily(familyId: String, label: String) = onSettings { it.renameFamily(familyId, label); null }
        override fun openProfile() = this@AppModel.openProfile()
        override fun openPasswordChange() = this@AppModel.openPasswordChange()
        override fun openAddressChange() = this@AppModel.openAddressChange()
        override fun openKeyRotation() = this@AppModel.openKeyRotation()
        override fun openErasure() = this@AppModel.openErasure()
    }

    // ---- the account's own flows (ADR 0148): the profile, the password, the address, the keys, the erasure ----

    /** One of the account's flows open over the page it was started from: the core's model, drawn by [AccountFlowDialog]. */
    sealed interface AccountFlow {
        class Profile(val model: me.raddatz.veyq.mobile.session.ProfileModel) : AccountFlow
        class Password(val model: me.raddatz.veyq.mobile.session.PasswordChange) : AccountFlow
        class Address(val model: me.raddatz.veyq.mobile.session.AddressChange) : AccountFlow
        class Rotation(val model: me.raddatz.veyq.mobile.session.rotation.KeyRotation) : AccountFlow
        class Erasure(val model: me.raddatz.veyq.mobile.session.AccountErasure) : AccountFlow
    }

    private val _accountFlow = MutableStateFlow<AccountFlow?>(null)

    /** The account flow open now; null while none is. */
    val accountFlow: StateFlow<AccountFlow?> = _accountFlow.asStateFlow()

    private val _ended = MutableStateFlow<me.raddatz.veyq.mobile.words.Msg?>(null)

    /** What the sign-in says once after the account was erased here ([me.raddatz.veyq.mobile.words.Msg.ERASURE_ENDED]). */
    val ended: StateFlow<me.raddatz.veyq.mobile.words.Msg?> = _ended.asStateFlow()

    fun endedSeen() {
        _ended.value = null
    }

    private fun openFlow(flow: AccountFlow) {
        closeAccountFlow()
        _accountFlow.value = flow
    }

    /** Closes the open flow; its model drops every secret it held (the profile its tenant key copy, a kit its key). */
    fun closeAccountFlow() {
        closeFlowKitScan()
        when (val f = _accountFlow.value) {
            is AccountFlow.Profile -> f.model.close()
            is AccountFlow.Password -> f.model.close()
            is AccountFlow.Address -> f.model.close()
            is AccountFlow.Rotation -> f.model.close()
            else -> Unit
        }
        _accountFlow.value = null
    }

    /**
     * A flow's Secret Key section took a kit file ([uri], `OpenDocument`; ADR 0151 decision 4): read into memory as the
     * sign-in's is and handed to the flow's [intake], which holds its key until the call or the flow ends.
     */
    fun readFlowKit(intake: me.raddatz.veyq.mobile.account.KeyIntake, uri: android.net.Uri) {
        viewModelScope.launch {
            val bytes = KitFile.read(getApplication<Application>().contentResolver, uri)
            AndroidKitPictures(bytes).use { pictures -> intake.read(bytes, pictures) }
        }
    }

    private val _flowKitScan = MutableStateFlow<me.raddatz.veyq.mobile.qr.QrScan<*>?>(null)

    /** A flow's kit scan on screen ([KitScanner]), or null: this model's, so a rotation keeps it (#937), as the sign-in's. */
    val flowKitScan: StateFlow<me.raddatz.veyq.mobile.qr.QrScan<*>?> = _flowKitScan.asStateFlow()

    /** Opens a flow's kit scan: the core's, the kit found taken by the flow's [intake]. */
    fun scanFlowKit(intake: me.raddatz.veyq.mobile.account.KeyIntake) {
        closeFlowKitScan()
        _flowKitScan.value = intake.scan(decode = me.raddatz.veyq.mobile.qr.QrFrames.timedDecode(::qrDecodeTiming))
    }

    private val _flowNoCamera = MutableStateFlow(false)

    /** A flow's scan found no camera: its entry is dropped for the rest of this run (an emulator names a camera it lacks). */
    val flowNoCamera: StateFlow<Boolean> = _flowNoCamera.asStateFlow()

    fun flowCameraMissing() {
        _flowNoCamera.value = true
    }

    fun closeFlowKitScan() {
        _flowKitScan.value?.close()
        _flowKitScan.value = null
    }

    /** The new password's field was left: the flow's breached-password check runs (ADR 0151 decision 5). */
    fun checkBreach(check: suspend () -> Unit) {
        viewModelScope.launch { check() }
    }

    /** *Your profile* (Settings) or *Edit profile* (one's own person page): the core's model over the open tenant. */
    fun openProfile() {
        val t = tenantSession ?: return
        val m = t.profile(AndroidWebpEncoder)
        openFlow(AccountFlow.Profile(m))
        viewModelScope.launch { m.load() }
    }

    fun saveProfile(m: me.raddatz.veyq.mobile.session.ProfileModel) {
        viewModelScope.launch {
            m.save()
            // the shell's people were read anew by the core: the names on screen follow
            shellModel?.rebuild()
        }
    }

    /** The framed picture's pixels handed to the core, which encodes, pads and seals it with the profile's save. */
    fun choosePicture(m: me.raddatz.veyq.mobile.session.ProfileModel, rgba: ByteArray) {
        viewModelScope.launch { m.choosePicture(rgba) }
    }

    /**
     * The part of [image] that [crop] frames, drawn to the core's square ([me.raddatz.veyq.mobile.session.ProfileModel.EDGE]
     * px a side) as straight RGBA, 4 bytes a pixel (the host's render of `HostRequest.PictureSquare`); null where it fails.
     */
    suspend fun avatarPixels(image: androidx.compose.ui.graphics.ImageBitmap, crop: me.raddatz.veyq.mobile.session.NodeCover.Crop): ByteArray? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val edge = me.raddatz.veyq.mobile.session.ProfileModel.EDGE
                val whole = image.asAndroidBitmap()
                val (x, y, w, h) = me.raddatz.veyq.mobile.session.CoverFrame.pixels(crop, whole.width, whole.height).toList()
                val square = android.graphics.Bitmap.createBitmap(edge, edge, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    android.graphics.Canvas(square).drawBitmap(
                        whole, android.graphics.Rect(x, y, x + w, y + h), android.graphics.Rect(0, 0, edge, edge),
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                    )
                    // ARGB_8888 holds its pixels as R, G, B, A bytes: the buffer is the core's RGBA
                    val out = java.nio.ByteBuffer.allocate(edge * edge * 4)
                    square.copyPixelsToBuffer(out)
                    out.array()
                } finally {
                    square.recycle()
                }
            }.getOrNull()
        }

    fun openPasswordChange() {
        val account = session.signedTo?.account ?: return
        openFlow(AccountFlow.Password(account.passwordChange()))
    }

    /** The password changed with [current] and the Secret Key field's text [secretKey] (the flow's intake reads it); the saved key's row is read anew after. */
    fun changePassword(m: me.raddatz.veyq.mobile.session.PasswordChange, current: String, next: String, confirm: String, secretKey: String) {
        viewModelScope.launch {
            m.run(current, next, confirm, secretKey)
            trustLog("password change: done=${m.current.done} refusal=${m.current.refusal?.key}")
            settingsModel?.refresh()
        }
    }

    fun openAddressChange() {
        val signed = session.signedTo ?: return
        openFlow(AccountFlow.Address(signed.account.addressChange(signed.tenant)))
    }

    fun requestAddressChange(m: me.raddatz.veyq.mobile.session.AddressChange, newEmail: String, password: String, secretKey: String) {
        viewModelScope.launch {
            m.request(newEmail, password, secretKey)
            trustLog("address change: sent=${m.current.sentTo != null} refusal=${m.current.refusal?.key}")
        }
    }

    /** A new kit with the account's address after the cold proof; [made] gets its step (null: refused, said by the model). */
    fun addressKit(m: me.raddatz.veyq.mobile.session.AddressChange, password: String, secretKey: String, made: (me.raddatz.veyq.mobile.account.KitStep?) -> Unit) {
        viewModelScope.launch { made(m.kit(password, secretKey)) }
    }

    fun openKeyRotation() {
        val account = session.signedTo?.account ?: return
        val m = account.keyRotation(open = tenantSession?.takeIf { it.account === account })
        openFlow(AccountFlow.Rotation(m))
        viewModelScope.launch { m.open() }
    }

    fun proveRotation(m: me.raddatz.veyq.mobile.session.rotation.KeyRotation, password: String, secretKey: String, newPassword: String, confirm: String) {
        viewModelScope.launch {
            m.prove(password, secretKey, newPassword, confirm)
            trustLog("rotation gate: step=${m.current.step} refusal=${m.current.refusal?.key}")
        }
    }

    /** The danger step: the new keys published; once done the tenant is opened anew on them (its keys were the old ones). */
    fun runRotation(m: me.raddatz.veyq.mobile.session.rotation.KeyRotation) {
        viewModelScope.launch {
            m.run()
            trustLog("rotation: step=${m.current.step} done=${m.current.done} refusal=${m.current.refusal?.key}")
            if (!m.current.done) return@launch
            val signed = session.signedTo ?: return@launch
            session.switchTo(signed.account, signed.tenant)?.let { show(it) }
            settingsModel?.refresh()
        }
    }

    fun openErasure() {
        val account = session.signedTo?.account ?: return
        val m = account.erasure()
        openFlow(AccountFlow.Erasure(m))
        viewModelScope.launch { m.load() }
    }

    /** Erases the account with [password]; once erased the device forgot it (the core) and it leaves as a sign-out does. */
    fun erase(m: me.raddatz.veyq.mobile.session.AccountErasure, password: String) {
        val account = session.signedTo?.account ?: return
        viewModelScope.launch {
            m.erase(password)
            trustLog("erasure: erased=${m.current.erased} refusal=${m.current.refusal?.key}")
            if (!m.current.erased) return@launch
            uploads.clear()
            closeAccountFlow()
            closeSettings()
            _ended.value = me.raddatz.veyq.mobile.words.Msg.ERASURE_ENDED
            signedOut(session.signOut(account))
        }
    }

    /** The person's language for a kit's words (the app's, as Android applies it) and their local date. */
    fun kitLanguage(): String = appLanguage()

    /** The open tenant's safety numbers (core's `PersonTrust`); null while no tenant is open. */
    fun personTrust(): me.raddatz.veyq.mobile.session.PersonTrust? = tenantSession?.personTrust

    /** The signed-in account's own user id, for one's own person page. */
    fun ownUserId(): String? = tenantSession?.account?.userId

    /** A scan of a safety code (`veyqid:`), the text held for the compare; any other code is skipped by the core. */
    fun safetyScan(): me.raddatz.veyq.mobile.qr.QrScan<String> = me.raddatz.veyq.mobile.qr.QrScan(
        accepts = { text -> text.takeIf { me.raddatz.veyq.mobile.qr.QrSchemes.of(it) == "veyqid" } },
        decode = me.raddatz.veyq.mobile.qr.QrFrames.timedDecode(::qrDecodeTiming),
    )

    /** A space's name as the shell lists it, for the rotation's per-space status; null for one this device has not opened. */
    private fun refreshManaged() = _screen.update { s ->
        if (s is Screen.Signed && s.managing != null) s.copy(managing = session.managed().also { m ->
            trustLog("manage servers: " + m.joinToString { "${it.trusted}/${it.level}" })
        }) else s
    }

    // ---- the current tenant ----

    private suspend fun show(opened: me.raddatz.veyq.mobile.session.SessionModel.Opened<VeyqAccount, me.raddatz.veyq.mobile.session.TenantSession>) {
        val account = opened.account
        val tenant = opened.tenant
        val t = opened.session
        // the re-key's rules and its background resume are the core's (`AccountDoor.open`/`opened`)
        val switched = tenantSession?.let { was -> was.account !== account || was.tenant.tenantId != tenant.tenantId } ?: true
        if (switched) uploads.clear()
        // the tenant shown before closes: its tree and what was derived from it (a person's index) dropped now (ADR 0144 decision 12)
        tenantSession?.takeIf { it !== t }?.close()
        tenantSession = t
        // what each application held open of the tenant before closes (its pages, its dialogs, a profile's section)
        eachHolding { it.leave() }
        rows = me.raddatz.veyq.mobile.composition.RowDetails.Read.EMPTY
        val model = me.raddatz.veyq.mobile.composition.ShellModel(t, hidden, recent, System::currentTimeMillis)
        shellWatch?.cancel()
        shellModel = model
        _shell.value = model
        _levelView.value = model.levelView
        closeSearch()
        val app = getApplication<Application>()
        _searchModel.value = model.searchModel({ msg -> app.words(msg) }, appearance = true) { android.util.Log.i("veyq-search", it) }
        // another tenant's music stops; the same tenant opened again plays on
        if (switched || _music.value == null) {
            stopMusic()
            _music.value = model.musicPlayer({ events -> MediaDeck(getApplication(), events) }, musicControls)
        }
        // the map services follow the shell: built over this tenant, the place names the shell looks up come from them
        closeMaps()
        _maps.value = model.mapServices(me.raddatz.veyq.mobile.map.TileRecipient.TILE_HOST) { appLanguage() }
        // each application builds its models over the new shell (its uploads, an open Pot's model)
        eachHolding { it.shown(model, t, switched) }
        startPending = true
        model.load(openLastUsed = shareInbox.waiting.value == null)
        shellWatch = viewModelScope.launch { model.state.collect { it?.let(::publish) } }
        readRowDetails(t)
        watchAvatars(t)
    }

    // ---- what another app shared (ADR 0138) ----

    /** Files shared to veyq, from their arrival to where they went (core's `ShareInbox`); one let go is closed, a copy removed. */
    val shareInbox = me.raddatz.veyq.mobile.navigation.ShareInbox(ShareIn::release)

    /** Files waiting for the place they go (the pick mode); null when none waits. */
    val incoming: StateFlow<me.raddatz.veyq.mobile.navigation.ShareInbox.Incoming?> = shareInbox.waiting

    /** Whether the pick mode lets [ref] be chosen for what waits (core's answer of its kind, and this member's right there). */
    fun takesShare(ref: ObjectRef): Boolean = shellModel?.takesShare(shareInbox, ref.at) ?: false


    /**
     * The page on screen that answers *Add here* itself (a Memory's or a chapter's, which its application sets while it
     * is composed); null on any other page, where the level shown answers.
     */
    private val _addHerePage = MutableStateFlow<AddHerePage?>(null)
    internal val addHerePage: StateFlow<AddHerePage?> = _addHerePage.asStateFlow()

    /** [page] is on screen ([shown]) or gone. */
    internal fun addHerePage(page: AddHerePage, shown: Boolean) {
        if (shown) _addHerePage.value = page else _addHerePage.compareAndSet(page, null)
    }

    /**
     * What *Add here* puts the waiting files into on [shell] (core's `ShellModel.addHere`): [page]'s place, the open
     * Memory's or chapter's, or the level shown — a folder or a space's top — where this member may put files; null elsewhere.
     */
    internal fun addHereTarget(shell: Shell, page: AddHerePage?): me.raddatz.veyq.mobile.composition.AddHere? =
        if (page != null) page.at(shell) else shellModel?.addHere(shareInbox, shell, null)

    /**
     * *Add here* on [target]: what its place takes queued there — a gallery's photos and videos, a level's files into
     * Depot's queue under [label] —, the share ended; what was left out said. The place's application answers it.
     */
    fun addHere(target: me.raddatz.veyq.mobile.composition.AddHere, label: String): me.raddatz.veyq.mobile.words.Notice? =
        holdings.values.firstNotNullOfOrNull { it.addHere(target, label) }?.notice

    /**
     * Files dropped onto [target] (ADR 0138 decision 9, ADR 0154 decision 1): core's one drop judges the target, sorts and
     * uploads (`ShellModel.drop`), and says what it did not take. A level that takes none leaves the files waiting in the pick
     * mode, as a share's do (the open Pot among the places, [receiveDrop]); a gallery's or a row's refusal is said.
     */
    fun drop(target: me.raddatz.veyq.mobile.composition.DropTarget, drop: ShareIn.Drop) {
        val m = shellModel
        if (m == null) {
            if (target is me.raddatz.veyq.mobile.composition.DropTarget.Level) receiveDrop(drop.incoming) else drop.release()
            return
        }
        viewModelScope.launch {
            // the target's application answers (a gallery's Memories, a level's or a folder row's Depot)
            val outcome = holdings.values.firstNotNullOfOrNull { it.drop(m, target, drop.items) }
                ?: m.drop(target, drop.items, null, ShareIn::release)
            if (outcome is me.raddatz.veyq.mobile.composition.DropOutcome.Refused && target is me.raddatz.veyq.mobile.composition.DropTarget.Level) {
                receiveDrop(drop.incoming)
                return@launch
            }
            if (outcome is me.raddatz.veyq.mobile.composition.DropOutcome.Refused) drop.release()
            // the phones' Depot offers no import (core's `DepotUploads` built so, owner 2026-11-19): never offered here
            if (outcome is me.raddatz.veyq.mobile.composition.DropOutcome.Offered) outcome.offer.uploadAsFile()
            outcome.notices.forEach(::said)
        }
    }

    /** Whether [row] of the level shown takes a drop into it (core's `ShellModel.dropTarget`), never by its kind. */
    fun dropTarget(row: me.raddatz.veyq.mobile.navigation.NodeAt): Boolean = shellModel?.dropTarget(row) ?: false

    /**
     * Files arrive: the pick mode starts at the space's Home, never in the object last open, so the person chooses where
     * they go (ADR 0138 decision 1) — the start does the same while the session is still opening.
     */
    fun receiveShare(incoming: me.raddatz.veyq.mobile.navigation.ShareInbox.Incoming) {
        updateShell { Shell.start(null, it.space) }
        shareInbox.receive(incoming)
    }

    /**
     * Files dropped onto the app (ADR 0138 decision 9): an open object that takes them at once — its application says so
     * (an open Pot opens its new expense with them) — gets them; anywhere else they wait in the pick mode, as a share's do
     * (a Memory's gallery takes its own drop, [drop]).
     */
    fun receiveDrop(incoming: me.raddatz.veyq.mobile.navigation.ShareInbox.Incoming) {
        val open = (screen.value as? Screen.Signed)?.shell?.openObject
        if (open != null && holdings.values.any { it.takesDrop(open, incoming) }) {
            shareInbox.receive(incoming)
        } else {
            receiveShare(incoming)
        }
    }

    fun cancelShare() = shareInbox.cancel()


    /** The new expense's editor closed unsaved: the files wait again, for this Pot or another (from Home). */
    fun returnShare() {
        if (shareInbox.giveBack() != null) updateShell { Shell.start(null, it.space) }
    }

    /** The new expense the shared files went into was saved: the share is done. */
    fun shareSaved() = shareInbox.saved()

    // ---- core's actions on a node ----

    /**
     * The header of [node] (core's `NodeHeaderModel` over the shell), its kind's page knowing [facts]: an open Pot of the
     * node closes once it went to the trash, and each write reads the rows' details anew.
     */
    fun header(node: me.raddatz.veyq.mobile.navigation.NodeAt, facts: me.raddatz.veyq.mobile.AppKinds.Facts?): me.raddatz.veyq.mobile.navigation.NodeHeaderModel? {
        val m = shellModel ?: return null
        return m.header(
            node, facts,
            closeOpen = { ref -> eachHolding { it.trashed(ref) } },
            changed = { viewModelScope.launch { readRowDetails(m.tenant) } },
        )
    }

    /**
     * A pull to refresh on a list or a Memory's page (#953): the lists read anew with each row's facts, which an open
     * Memory's page follows (its revisions); [done] when it ended, read or not.
     */
    fun refreshLists(done: () -> Unit) {
        val t = tenantSession ?: return done()
        viewModelScope.launch {
            // a person's pull: the members too, a picture replaced since among them (core's)
            runCatching { refreshLists(t, people = true) }
            done()
        }
    }

    /** The lists read anew (core's shell), with each row's facts; with [people] the members too (a person's refresh). */
    internal suspend fun refreshLists(t: me.raddatz.veyq.mobile.session.TenantSession, people: Boolean = false) {
        readRowDetails(t)
        shellModel?.refresh(people)
    }

    // ---- a report (core's ReportSheet) ----

    /** The report of [node] to the tenant's moderators (core's `ReportSheet`: the form's rules, the send, the privacy URL). */
    fun reportSheet(node: me.raddatz.veyq.mobile.navigation.NodeAt): me.raddatz.veyq.mobile.navigation.ReportSheet? = shellModel?.reportSheet(node)

    // ---- guest links (core's ShareLinks) ----

    /** Whether the shell stands where the space's top level offers its share, "Add files only" (core's `offersTopLevelShare`). */
    fun offersTopLevelShare(shell: me.raddatz.veyq.mobile.navigation.Shell): Boolean = shellModel?.offersTopLevelShare(shell) == true

    /** The tenant's link rules (its capability set): what the share dialog offers and requires. */
    fun linkRules(): me.raddatz.veyq.mobile.session.ShareLinks.Rules =
        tenantSession?.linkRules ?: me.raddatz.veyq.mobile.session.ShareLinks.Rules.UNKNOWN

    /**
     * The share sheet of [node] (core's `ShareSheet` over the shell: its links, the form, mint, edit, renew, switch off and
     * on, delete, a folder's "Add files only"; a link's close tells the tenant, whose open Pot reads again); the rows'
     * details are read anew after a drop box was made.
     */
    fun shareSheet(node: me.raddatz.veyq.mobile.navigation.NodeAt): me.raddatz.veyq.mobile.composition.ShareSheet? {
        val m = shellModel ?: return null
        return m.shareSheet(node) { viewModelScope.launch { readRowDetails(m.tenant) } }
    }

    /** The share of space [scopeId]'s top level (core's `ShareSheet.topLevel`: "Add files only" alone, a drop box at the top). */
    fun topLevelShareSheet(scopeId: String): me.raddatz.veyq.mobile.composition.ShareSheet? {
        val m = shellModel ?: return null
        return m.topLevelShareSheet(scopeId) { viewModelScope.launch { readRowDetails(m.tenant) } }
    }

    // ---- the move and copy sheet (core's MoveSheet) ----

    /**
     * The move or copy sheet of [node] (core's `MoveSheet` over the shell: its browser, the guest folder's confirm, the
     * kind's copy — a Pot by the open Pot —, the storage check, the move's undo); each write reads the rows' details anew.
     */
    fun moveSheet(node: me.raddatz.veyq.mobile.navigation.NodeAt, copying: Boolean): me.raddatz.veyq.mobile.navigation.MoveSheet? {
        val m = shellModel ?: return null
        val changed = { viewModelScope.launch { readRowDetails(m.tenant) }; Unit }
        // a kind whose copy is its application's (a Pot's, by the open Pot) gets its sheet from that application
        return holdings.values.firstNotNullOfOrNull { it.moveSheet(m, node, copying, changed) }
            ?: m.moveSheet(node, copying, null, getApplication<Application>().getString(R.string.pot_deleted_person), changed)
    }

    // ---- the space's trash (core's NodeTrash) ----

    /** The trash sheet of [scopeId] (core's `TrashSheet`); the lists are read anew after each write that went through. */
    fun trashSheet(scopeId: String): me.raddatz.veyq.mobile.navigation.TrashSheet? {
        val m = shellModel ?: return null
        return m.trashSheet(scopeId) { viewModelScope.launch { readRowDetails(m.tenant) } }
    }

    // ---- the open tenant ----

    /** The tenant the shell shows, opened: where an object's own view reads from. */
    internal var tenantSession: me.raddatz.veyq.mobile.session.TenantSession? = null
        private set

    /** What each object's row says beside its name, by node id (an application's answer: Expenses' `PotRows`). */
    private val _rowDetails = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val rowDetails: StateFlow<Map<String, List<String>>> = _rowDetails.asStateFlow()

    /** Node [nodeId]'s row says [facts] now (its application read them anew, a Pot's new base currency). */
    internal fun rowDetail(nodeId: String, facts: List<String>) {
        _rowDetails.update { it + (nodeId to facts) }
    }

    /** The members' pictures this device opened, by user id (core's `AvatarsModel`): the letter for everyone else. */
    private val _avatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val avatars: StateFlow<Map<String, ByteArray>> = _avatars.asStateFlow()
    private var avatarsWatch: kotlinx.coroutines.Job? = null

    /** Shows the tenant's pictures as core reads them (when is core's rule); the letters show meanwhile. */
    private fun watchAvatars(t: me.raddatz.veyq.mobile.session.TenantSession) {
        avatarsWatch?.cancel()
        _avatars.value = emptyMap()
        avatarsWatch = viewModelScope.launch { t.pictures.state.collect { _avatars.value = it } }
        viewModelScope.launch { t.pictures.load() }
    }

    /** What the rows read last (core's `RowDetails`): a part that does not read next time keeps these. */
    private var rows = me.raddatz.veyq.mobile.composition.RowDetails.Read.EMPTY

    internal suspend fun readRowDetails(t: me.raddatz.veyq.mobile.session.TenantSession) {
        val read = me.raddatz.veyq.mobile.composition.RowDetails.read(t, rows)
        if (tenantSession !== t) return
        rows = read
        // a page on screen reads itself anew (a file: a replaced blob is shown)
        eachHolding { it.listsRead() }
        val app = getApplication<Application>()
        // an application's row says one more line of its own (a Memory's date, ADR 0143 D5)
        val shell = shellModel
        val dates = if (shell == null) emptyMap() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            holdings.values.fold(emptyMap<String, String>()) { all, h -> all + h.rowLines(shell) }
        }
        if (tenantSession !== t) return
        _rowDetails.value = read.details.mapValues { (_, facts) -> facts.map { app.piece(it) } } +
            dates.mapValues { (id, d) -> read.details[id].orEmpty().map { app.piece(it) } + d }
        readCovers(t, read.covers)
    }

    // ---- what the shell says (a snackbar), and an upload under way ----

    /**
     * A notice the shell shows once: its text, how long it lives (core's [kind]) and, where offered, an action (Depot's
     * "Replace anyway"); [done] once it is gone, its action taken or not.
     */
    class Notice(
        val text: String, val kind: me.raddatz.veyq.mobile.words.NoticeKind, val action: String? = null,
        val run: (suspend () -> Unit)? = null, val done: (() -> Unit)? = null,
    )

    private val _notices = kotlinx.coroutines.channels.Channel<Notice>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val notices: kotlinx.coroutines.flow.Flow<Notice> = _notices.receiveAsFlow()

    fun notify(n: Notice) {
        _notices.trySend(n)
    }

    /** Core's [notice] said by the shell, in the words of the app's language. */
    fun said(notice: me.raddatz.veyq.mobile.words.Notice) = notify(Notice(getApplication<Application>().text(notice), notice.kind))

    // ---- the writes made offline (ADR 0153): core's outbox, its tray, its marks and its notices; the app only draws ----

    /** The shell's model as it changes: the outbox's flows follow the tenant shown. */
    private val _shell = MutableStateFlow<me.raddatz.veyq.mobile.composition.ShellModel?>(null)

    /** Under the offline banner: how many writes made offline wait (core's `ShellModel.queuedBanner`); null while none does. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val queuedBanner: StateFlow<me.raddatz.veyq.mobile.words.Notice?> = _shell
        .flatMapLatest { it?.queuedBanner ?: kotlinx.coroutines.flow.flowOf(null) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /** The level shown's nodes made offline and not sent yet (core's `ShellModel.queuedHere`), each with its mark. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val queuedHere: StateFlow<List<me.raddatz.veyq.mobile.composition.QueuedItem>> = _shell
        .flatMapLatest { it?.queuedHere ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /** Every write made offline as the uploads tray lists it (core's `QueuedTray.items`), oldest first. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val queuedItems: StateFlow<List<me.raddatz.veyq.mobile.composition.QueuedItem>> = _shell
        .flatMapLatest { it?.queuedTray()?.items ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    init {
        // after a reconnect that sent writes made offline: how many went and how many need the person (core's notices)
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _shell.flatMapLatest { it?.queuedSent ?: kotlinx.coroutines.flow.emptyFlow() }.collect { it.forEach(::said) }
        }
    }

    /** What the drawer's "New" offers (core's `ShellModel.drawerCreates`: offline only what is queued; none outside a space). */
    fun drawerCreates(): List<me.raddatz.veyq.mobile.AppKinds.Create> = shellModel?.drawerCreates().orEmpty()

    /** A level row's menu (core's `ShellModel.rowPlan`): rename and move first, report and the delete last. */
    suspend fun rowPlan(node: me.raddatz.veyq.mobile.navigation.NodeAt): me.raddatz.veyq.mobile.navigation.LevelRow.Plan =
        shellModel?.rowPlan(node) ?: me.raddatz.veyq.mobile.navigation.LevelRow.Plan(emptyList(), emptyList())

    /** The plan of the level [shell] shows (core's `ShellModel.levelPlan`: its New and its tools); null where it shows none. */
    fun levelPlan(shell: me.raddatz.veyq.mobile.navigation.Shell): me.raddatz.veyq.mobile.navigation.LevelPlan? = shellModel?.levelPlan(shell)

    /** The tray's *Discard* of write [id] (core's `QueuedTray.discard`, with what waits for it). */
    fun discardQueued(id: String) {
        val m = shellModel ?: return
        viewModelScope.launch { m.queuedTray().discard(id) }
    }

    /** The name a refused write's file [index] is saved under (core's `QueuedTray.copyName`), for the system's save dialog. */
    suspend fun queuedCopyName(id: String, index: Int): String? = shellModel?.queuedTray()?.copyName(id, index)?.first

    /**
     * The tray's *Save a copy* (core's `QueuedTray.saveCopy`): refused write [id]'s file [index] written as plaintext only into
     * [uri], the document the person chose in the system's save dialog; what it began removed where it did not complete.
     */
    fun saveQueuedCopy(id: String, index: Int, uri: android.net.Uri) {
        val m = shellModel ?: return
        viewModelScope.launch {
            val tray = m.queuedTray()
            val name = tray.copyName(id, index)?.first
            val resolver = getApplication<Application>().contentResolver
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { resolver.openOutputStream(uri)?.use { out -> tray.saveCopy(id, index) { out.write(it) } } ?: false }.getOrDefault(false)
            }
            if (!saved) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
            said(me.raddatz.veyq.mobile.composition.QueuedShown.copySaved(name, saved))
        }
    }

    /** A held Pot entry's *Open*: the open Pot shows its held editor ([write] on entry [entry] of Pot [pot]). */
    class HeldEntry(val pot: String, val entry: String, val write: String)

    private val _heldEntry = MutableStateFlow<HeldEntry?>(null)
    val heldEntry: StateFlow<HeldEntry?> = _heldEntry.asStateFlow()

    fun heldEntrySeen() {
        _heldEntry.value = null
    }

    /** A held rename's *Open*: the rename dialog with the person's name in it (core's `QueuedOpen.Rename`). */
    private val _heldRename = MutableStateFlow<me.raddatz.veyq.mobile.composition.QueuedOpen.Rename?>(null)
    val heldRename: StateFlow<me.raddatz.veyq.mobile.composition.QueuedOpen.Rename?> = _heldRename.asStateFlow()

    fun heldRenameSeen() {
        _heldRename.value = null
    }

    /**
     * The tray's *Open* of held write [id] (core's `QueuedTray.opens`, ADR 0153 decision 4): where core says it is decided —
     * the Pot's editor over the person's values, the node's rename dialog, or the page it was made on.
     */
    fun openQueued(id: String) {
        val m = shellModel ?: return
        viewModelScope.launch {
            when (val o = m.queuedTray().opens(id)) {
                is me.raddatz.veyq.mobile.composition.QueuedOpen.Entry -> {
                    m.openFound(o.scope, o.pot)
                    _heldEntry.value = HeldEntry(o.pot, o.entry, o.write)
                }
                is me.raddatz.veyq.mobile.composition.QueuedOpen.Rename -> _heldRename.value = o
                is me.raddatz.veyq.mobile.composition.QueuedOpen.Place -> o.node?.let { m.openFound(o.scope, it) } ?: switchSpace(o.scope)
                null -> {}
            }
        }
    }


    /** A held rename decided in its dialog (core's rename of the node, which drops the held one once it lands). */
    suspend fun renameHeld(open: me.raddatz.veyq.mobile.composition.QueuedOpen.Rename, name: String): me.raddatz.veyq.mobile.navigation.NameAnswer {
        val m = shellModel ?: return me.raddatz.veyq.mobile.navigation.NameAnswer.DONE
        val node = m.state.value?.lists?.nodes?.firstOrNull { it.scopeId == open.scope && it.nodeId == open.node }
            ?: return me.raddatz.veyq.mobile.navigation.NameAnswer.DONE
        val header = header(me.raddatz.veyq.mobile.navigation.NodeAt(node.scopeId, node.nodeId, node.kind, node.name), null)
            ?: return me.raddatz.veyq.mobile.navigation.NameAnswer.DONE
        return header.rename(name)
    }

    /** The ask before a loss of writes made offline (core's `QueuedAsk`), what its confirm runs and what its cancel does. */
    class LossAsk(val ask: me.raddatz.veyq.mobile.composition.QueuedAsk, val run: () -> Unit, val cancel: () -> Unit)

    private val _lossAsk = MutableStateFlow<LossAsk?>(null)
    val lossAsk: StateFlow<LossAsk?> = _lossAsk.asStateFlow()

    /**
     * Before [loss] deletes the writes made offline [account] keeps (ADR 0153 decision 2, owner 2026-11-19): core's ask while
     * any wait (`QueuedTray.ask`), [run] once confirmed, [cancel] otherwise; with none waiting [run] at once.
     */
    private fun beforeLoss(account: VeyqAccount?, loss: me.raddatz.veyq.mobile.composition.QueuedLoss, cancel: () -> Unit = {}, run: () -> Unit) {
        if (account == null) return run()
        viewModelScope.launch {
            val ask = runCatching { me.raddatz.veyq.mobile.composition.QueuedTray(account).ask(loss) }.getOrNull()
            if (ask == null) run() else _lossAsk.value = LossAsk(ask, run, cancel)
        }
    }

    /** The loss's ask answered: its confirm ([confirmed]) or its cancel. */
    fun answerLoss(confirmed: Boolean) {
        val a = _lossAsk.value ?: return
        _lossAsk.value = null
        if (confirmed) a.run() else a.cancel()
    }

    /**
     * The app's one upload queue (core's `UploadQueue`, the web's `core/blob/queue.ts`): every application's uploads, one
     * after another, retried once; the shell's tray shows it ([UploadTray]). It outlives a screen, not the account: a
     * sign-out or a switch of tenant empties it.
     */
    val uploads = me.raddatz.veyq.mobile.blob.UploadQueue()

    /** The shell's uploads into a level (the tray, New's *Upload files* and *Upload folder*), as an application keeps them. */
    internal val levelUploads: LevelUploads? get() = holdings.values.firstNotNullOfOrNull { it.levelUploads }

    /** Queues the picked [uris] as new files under the level shown ([label] its name): a name a file already holds offers its replace. */
    fun uploadPicked(scopeId: String, parentId: String?, label: String, uris: List<android.net.Uri>) {
        levelUploads?.upload(scopeId, parentId, label, uris)
    }

    /** *Upload folder…*: the directory the system's picker granted ([tree]) uploaded whole under the level shown. */
    fun uploadFolderPicked(scopeId: String, parentId: String?, tree: android.net.Uri) {
        levelUploads?.uploadFolder(scopeId, parentId, tree)
    }





    /** Whether the tenant's server takes reports at all (it publishes a moderation key). */
    suspend fun reportOffered(): Boolean = shellModel?.reportOffered() == true

    // ---- the view mode (ADR 0149 decision 8) ----

    private val _levelView = MutableStateFlow(me.raddatz.veyq.mobile.navigation.ViewMode.FIRST)

    /** How every level is drawn now: the account's one choice on this device (core's `ShellModel.levelView`). */
    val levelView: StateFlow<me.raddatz.veyq.mobile.navigation.ViewMode> = _levelView.asStateFlow()

    /** A level's view chosen: kept for every level and a person's Memories. */
    fun chooseLevelView(mode: me.raddatz.veyq.mobile.navigation.ViewMode) {
        val m = shellModel ?: return
        m.chooseView(mode)
        _levelView.value = m.levelView
    }

    /** The account's view chosen elsewhere (a person's Memories): every level follows it. */
    internal fun followLevelView() {
        shellModel?.let { _levelView.value = it.levelView }
    }



    // ---- a person's profile (ADR 0144 decisions 11–13) ----

    /**
     * Person [userId]'s profile opened over what is on screen (core's `Shell.openPerson`). A profile opened anew reads its
     * section anew and starts on the list (ADR 0144 decision 13: the choice is not stored), as Apple's and the bridge's
     * do; only a rotation, or the way back from a Memory opened from it, keeps the section held below. Measured
     * 2026-11-18 (ADR 0144's Android pass): held across opens, the same person's profile reopened on the Map, its rows
     * as first read.
     */
    fun openPerson(userId: String) {
        eachHolding { it.personOpened() }
        shellModel?.openPerson(userId)
    }

    /** Person [userId]'s frame as the tenant's directory names them; null while no tenant is open. */
    fun profile(userId: String): me.raddatz.veyq.mobile.navigation.PersonProfile? = shellModel?.profile(userId)



    // ---- a Memory's date, place and Map view (ADR 0143 D5, D8) ----

    /** The app's language, as the geocoder's `accept-language` (the web's reader's): the per-app one Android applies. */
    internal fun appLanguage(): String =
        getApplication<Application>().resources.configuration.locales.get(0)?.language?.takeIf { it.isNotEmpty() } ?: java.util.Locale.getDefault().language



    /**
     * The file a person is about to send a copy of to another app ("Send a copy…"), while the shell asks them first: the copy
     * leaves veyq decrypted. The data are theirs; the app says what happens and never forbids it.
     */
    private val _sending = MutableStateFlow<SendCopy?>(null)
    internal val sending: StateFlow<SendCopy?> = _sending.asStateFlow()

    internal fun askToSend(copy: SendCopy?) {
        _sending.value = copy
    }

    /** Where the copies sent to another app wait to be read: cleared before the next one and when the app starts. */
    private val sharedDir get() = java.io.File(getApplication<Application>().cacheDir, "shared")

    fun clearSentCopies() {
        runCatching { sharedDir.deleteRecursively() }
    }

    /**
     * A decrypted copy of [send]'s file under its own name (its application's, core's `downloadName`), in the app's shared
     * cache, and the content URI another app reads it through (the FileProvider, read permission granted to the one it is
     * sent to); null when it has no content or could not be written.
     */
    internal suspend fun copyForSending(send: SendCopy): Pair<android.net.Uri, String>? {
        val ref = send.node
        val (downloadName, mediaType) = send.named() ?: return null
        val app = getApplication<Application>()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            clearSentCopies()
            val dir = java.io.File(sharedDir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
            val out = java.io.File(dir, downloadName)
            // the file itself (core's download of a file), the one decrypted copy the person asked for; swept at the next start
            val ok = out.outputStream().let { downloadNode(ref, it) } != null
            if (!ok) {
                dir.deleteRecursively()
                return@withContext null
            }
            androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", out) to mediaType
        }
    }

    /** A picked document as an upload reads it: its name, size, type and modified time from the system's listing. */
    class Picked(val uri: android.net.Uri, val name: String, val size: Long, val type: String, val modifiedAt: Long?)

    fun picked(uri: android.net.Uri): Picked? {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                val size = c.getLong(c.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE))
                val modified = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                Picked(uri, name, size, resolver.getType(uri) ?: "application/octet-stream", modified)
            }
        }.getOrNull()
    }

    /**
     * A picked document read for an upload (core's `UploadSource`): its descriptor opened on the first read, read at any
     * offset through its channel, and closed when the job's run ends; a retry opens it again.
     */
    class ContentSource(private val resolver: android.content.ContentResolver, val uri: android.net.Uri, name: String, size: Long, type: String, modifiedAt: Long?) :
        me.raddatz.veyq.mobile.blob.UploadSource(name, size, type, modifiedAt) {
        private var open: Pair<android.os.ParcelFileDescriptor, java.nio.channels.FileChannel>? = null

        @Synchronized
        private fun channel(): java.nio.channels.FileChannel = (open ?: run {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw java.io.IOException("unreadable")
            (pfd to java.io.FileInputStream(pfd.fileDescriptor).channel).also { open = it }
        }).second

        override suspend fun read(from: Long, length: Int): ByteArray {
            val ch = channel()
            val buf = java.nio.ByteBuffer.allocate(length)
            while (buf.hasRemaining()) if (ch.read(buf, from + buf.position()) < 0) break
            return buf.array().copyOf(buf.position())
        }

        @Synchronized
        override fun close() {
            open?.let { (pfd, ch) -> runCatching { ch.close() }; runCatching { pfd.close() } }
            open = null
        }
    }

    fun source(p: Picked): me.raddatz.veyq.mobile.blob.UploadSource = ContentSource(getApplication<Application>().contentResolver, p.uri, p.name, p.size, p.type, p.modifiedAt)

    /** Why [item] did not go up, in its application's words (the tray's line under it); null while it may still. */
    fun uploadProblem(item: me.raddatz.veyq.mobile.blob.UploadQueue.Item): String? =
        me.raddatz.veyq.mobile.words.Notices.uploadProblem(item)?.let { getApplication<Application>().text(it) }

    /** Each listed node's cover as a small picture of its framed part, by node id (core's covers, uc.platform.node-cover):
     *  opened once per blob and crop (the pair's key), dropped when the node names another or none. */
    private val _covers = MutableStateFlow<Map<String, Pair<String, androidx.compose.ui.graphics.ImageBitmap>>>(emptyMap())
    val covers: StateFlow<Map<String, Pair<String, androidx.compose.ui.graphics.ImageBitmap>>> = _covers.asStateFlow()

    private fun readCovers(t: me.raddatz.veyq.mobile.session.TenantSession, named: Map<String, String>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val changes = me.raddatz.veyq.mobile.session.NodeCover.changes(named, _covers.value.mapValues { it.value.first })
            val have = _covers.value.filterKeys { it in changes.keep }
            val fresh = changes.open.mapNotNull { (id, key) ->
                val opened = runCatching { me.raddatz.veyq.mobile.session.NodeCover.openFramed(t, id) }.getOrNull() ?: return@mapNotNull null
                thumbnail(opened.bytes, opened.crop)?.let { id to (key to it) }
            }
            if (tenantSession === t) _covers.value = have + fresh
        }
    }

    /** A cover's framed part decoded at a list's size (a row draws it a few dozen pixels wide, a header a few hundred). */
    private fun thumbnail(bytes: ByteArray, crop: me.raddatz.veyq.mobile.session.NodeCover.Crop): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= me.raddatz.veyq.mobile.session.NodeCover.THUMBNAIL_EDGE) sample *= 2
        val whole = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        val (x, y, w, h) = me.raddatz.veyq.mobile.session.CoverFrame.pixels(crop, whole.width, whole.height).toList()
        android.graphics.Bitmap.createBitmap(whole, x, y, w, h)
    }.getOrNull()?.asImageBitmap()

    /** A picture decoded for the cover sheet's frame: its long edge at most the core's (`NodeCover.encodedSize`). */
    private fun picture(source: android.graphics.ImageDecoder.Source): android.graphics.Bitmap =
        android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = me.raddatz.veyq.mobile.session.NodeCover.encodedSize(info.size.width, info.size.height).let { it[0] to it[1] }
            if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** The picture at [uri], for the cover sheet to frame before it is set; null when it does not read as an image. */
    suspend fun coverPicture(uri: android.net.Uri): androidx.compose.ui.graphics.ImageBitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { picture(android.graphics.ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)).asImageBitmap() }.getOrNull()
    }

    /** [ref]'s cover as it is stored — the whole picture and its crop — for the cover sheet to frame anew; null: none. */
    suspend fun coverSource(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Pair<androidx.compose.ui.graphics.ImageBitmap, me.raddatz.veyq.mobile.session.NodeCover.Crop>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val t = tenantSession ?: return@withContext null
            val opened = runCatching { me.raddatz.veyq.mobile.session.NodeCover.openFramed(t, ref.nodeId) }.getOrNull() ?: return@withContext null
            runCatching { picture(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(opened.bytes))).asImageBitmap() }.getOrNull()?.let { it to opened.crop }
        }

    /** [ref]'s cover as its page's head shows it: the framed part of the stored picture, at up to the cover's long edge; null: none. */
    suspend fun coverHero(ref: me.raddatz.veyq.mobile.navigation.NodeAt): androidx.compose.ui.graphics.ImageBitmap? {
        val (image, crop) = coverSource(ref) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val whole = image.asAndroidBitmap()
                val (x, y, w, h) = me.raddatz.veyq.mobile.session.CoverFrame.pixels(crop, whole.width, whole.height).toList()
                android.graphics.Bitmap.createBitmap(whole, x, y, w, h).asImageBitmap()
            }.getOrNull()
        }
    }

    /** Moves the crop of [ref]'s cover, its picture kept (the web's `keep`); false when it did not land. */
    suspend fun recropCover(ref: me.raddatz.veyq.mobile.navigation.NodeAt, crop: me.raddatz.veyq.mobile.session.NodeCover.Crop): Boolean {
        val t = tenantSession ?: return false
        val done = runCatching { me.raddatz.veyq.mobile.session.NodeCover.recrop(t, ref.scopeId, ref.nodeId, crop) }
            .getOrNull() == me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE
        refreshLists(t)
        return done
    }

    /**
     * Sets the picture at [uri] as the cover of [ref], framed by [crop]: decoded on the device and made the web's cover JPEG
     * (core's `NodeCover.LONG_EDGE` and `QUALITY`, the web's `COVER_SPEC`) — the whole picture, the crop stored beside it as the web stores it —,
     * uploaded and attached (core's `NodeCover`); false when it did not land.
     */
    suspend fun setCover(
        ref: me.raddatz.veyq.mobile.navigation.NodeAt, uri: android.net.Uri,
        crop: me.raddatz.veyq.mobile.session.NodeCover.Crop = me.raddatz.veyq.mobile.session.NodeCover.Crop.FULL,
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t = tenantSession ?: return@withContext false
        val jpeg = runCatching {
            val bitmap = picture(android.graphics.ImageDecoder.createSource(getApplication<Application>().contentResolver, uri))
            java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, me.raddatz.veyq.mobile.session.NodeCover.QUALITY_PERCENT, it) }.toByteArray()
        }.getOrNull() ?: return@withContext false
        val done = runCatching { me.raddatz.veyq.mobile.session.NodeCover.set(t, ref.scopeId, ref.nodeId, jpeg, crop) }
            .getOrNull() == me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE
        refreshLists(t)
        done
    }

    /**
     * Creates a folder named [name] where the shell is (core's `createFolderHere`, the web's `FolderNameDialog`): the lists
     * are read anew. Core's typed answer: a problem keeps the dialog open, a notice closes it and is said.
     */
    suspend fun createFolder(name: String): me.raddatz.veyq.mobile.navigation.NameAnswer {
        val m = shellModel ?: return me.raddatz.veyq.mobile.navigation.NameAnswer.said(me.raddatz.veyq.mobile.words.Notices.folderRefused(null))
        return m.createFolderHere(name).also { readRowDetails(m.tenant) }
    }


    /** What the drawer's "New" asked Home to create (an entry of `ShellModel.drawerCreates`), until Home opens its dialog. */
    private val _newRequest = MutableStateFlow<me.raddatz.veyq.mobile.AppKinds.Create?>(null)
    val newRequest: StateFlow<me.raddatz.veyq.mobile.AppKinds.Create?> = _newRequest.asStateFlow()

    /** The drawer's "New": Home, where its dialog for [kind] opens. */
    fun requestNew(kind: me.raddatz.veyq.mobile.AppKinds.Create) {
        choose(null)
        _newRequest.value = kind
    }

    fun newRequestTaken() {
        _newRequest.value = null
    }

    /** Removes the cover of [ref]; false when it did not land. */
    suspend fun removeCover(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Boolean {
        val t = tenantSession ?: return false
        val done = runCatching { me.raddatz.veyq.mobile.session.NodeCover.remove(t, ref.scopeId, ref.nodeId) }
            .getOrNull() == me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE
        refreshLists(t)
        return done
    }

    /**
     * Writes node [ref]'s download into [out] (core's `ShellModel.download`: a file the file itself, any other node with
     * everything under it as the web's ZIP); what it left out, or null when it did not finish. [out] is closed either way.
     */
    suspend fun downloadNode(ref: me.raddatz.veyq.mobile.navigation.NodeAt, out: java.io.OutputStream): List<me.raddatz.veyq.mobile.export.NodeExport.Skipped>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val m = shellModel ?: return@withContext null
        val deleted = getApplication<Application>().getString(R.string.pot_deleted_person)
        val skipped = runCatching {
            out.buffered(1 shl 16).let { b -> m.download(ref, deleted, System.currentTimeMillis()) { b.write(it) }.also { b.flush() } }
        }.getOrNull()
        runCatching { out.close() }
        skipped
    }


    /** The shell's lists from what was read, less what this device hides; on [start], the shell where the app opens. */
    /** Whether the next publish is the shell's first over a tenant ("Manage servers" closes then). */
    private var startPending = false

    /** The screen over [state], the core shell's, with this device's servers and the tenant shown. */
    private fun publish(state: me.raddatz.veyq.mobile.composition.ShellModel.State) {
        val gate = session.current as? me.raddatz.veyq.mobile.session.SessionModel.Gate.Signed ?: return
        val signed = session.signedTo ?: return
        if (shellModel?.tenant !== signed.session) return // a switch under way: the shell shown stays until the other one read
        val account = signed.account
        val tenant = signed.tenant
        val prior = _screen.value as? Screen.Signed
        val start = startPending
        startPending = false
        val l = state.lists
        _screen.value = Screen.Signed(
            shell = state.shell,
            tenant = tenant,
            server = account.label,
            servers = me.raddatz.veyq.mobile.navigation.ShellWords.switcher(session.accounts, hidden, account, tenant).mapNotNull { e ->
                session.accounts.firstOrNull { it.key == e.connection }?.let { ServerEntry(it, e) }
            },
            drawerSections = shellModel?.drawerSections(l).orEmpty(),
            applications = l.applications,
            filters = l.filters,
            drawer = l.drawer,
            spaces = l.spaces,
            nodes = l.nodes,
            objects = l.objects,
            favorites = l.favorites,
            uses = l.uses,
            switching = gate.switching,
            managing = if (start) null else prior?.managing,
            banner = state.banner,
            gone = state.gone,
        )
    }

    fun choose(app: ApplicationId?) = updateShell { it.choose(app) }
    fun switchSpace(scopeId: String) = updateShell { it.switchSpace(scopeId) }
    fun enter(folder: FolderRef) = updateShell { it.enter(folder) }
    fun filter(app: ApplicationId?) = updateShell { it.filter(app) }
    fun goBack() = updateShell { it.goBack() }

    /** Kept link [node]'s page opened over what is on screen (core's `ShellModel.openBookmark`, #1032). */
    fun openBookmark(node: me.raddatz.veyq.mobile.navigation.NodeAt) {
        shellModel?.openBookmark(node)
    }

    fun open(ref: ObjectRef) {
        shellModel?.open(ref)
    }

    /** [ref] chosen from the sidebar an expanded window keeps open: a top-level destination, no back (`Shell.openAtTop`). */
    fun openAtTop(ref: ObjectRef) {
        shellModel?.openAtTop(ref)
    }

    /** [ref] chosen from the list beside the open object (a wide window): it takes the open one's place. */
    fun openBeside(ref: ObjectRef) {
        shellModel?.openBeside(ref)
    }

    fun select(tab: BarTab) {
        // leaving Search for a place of the bar leaves the field: its text and chips go, nothing is revalidated
        if (shellModel?.shell?.searching == true) _searchModel.value?.close()
        updateShell { it.select(tab) }
    }

    /** Whether "New" is offered where the shell is (core's `ShellModel.canCreateHere`). */
    val canCreateHere: Boolean get() = shellModel?.canCreateHere == true

    /** The shell's notices of items no longer available offline were shown: core does not say them again. */
    fun goneSeen() {
        val m = shellModel ?: return
        viewModelScope.launch { m.goneSeen() }
    }
    /** Search opened from the bar (a fresh field) or left (its text and chips go); back from a result returns to it as it was. */
    fun search(on: Boolean) {
        _searchModel.value?.close()
        updateShell { it.search(on) }
    }

    /**
     * Core's state after a settings result was chosen (`SearchShown.openSettings`, `openSettingsCount`): the Settings page
     * opens scrolled to its section once per choice — the same result chosen twice opens it twice, and Search drawn again
     * after the page (the same state) opens nothing.
     */
    fun openSettingsFromSearch(section: String?, count: Int) {
        if (section == null || count <= settingsOpenedCount) return
        settingsOpenedCount = count
        _settingsAt.value = section
        openSettings()
    }

    /** The page scrolled to [settingsAt]. */
    fun settingsAtSeen() {
        _settingsAt.value = null
    }

    private fun updateShell(f: (Shell) -> Shell) {
        shellModel?.update(f)
    }

    private fun persistRecent(keys: List<UseKey>) {
        prefs.edit().putString("uses", keys.joinToString("\n") { it.encode() }).apply()
    }

    private fun loadRecent(): List<UseKey> = prefs.getString("uses", "").orEmpty().lines().mapNotNull(UseKey::decode)

    // ---- a received link opened as its guest, and a kept one (ADR 0150) ----

    /**
     * *Open a link* and the link open as its guest (ADR 0150 decisions 3–5): a screen above the sign-in or the shell, its
     * guest's session in the Keystore-wrapped store, its uploads in the app's one queue.
     */
    /**
     * A link guest's photos' and videos' tiers: the application's that derives them ([Holding.deriver]), else none —
     * the upload then goes without tiers, as a platform that derives none sends it.
     */
    private val guestDeriver = object : me.raddatz.veyq.mobile.media.Derivation.Deriver {
        override fun derive(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, done: (me.raddatz.veyq.mobile.media.Derivation.Derived) -> Unit) {
            val d = holdings.values.firstNotNullOfOrNull { it.deriver }
            if (d != null) d.derive(source, type, done) else done(me.raddatz.veyq.mobile.media.Derivation.Derived(null, null, null, null))
        }
    }

    val guests: GuestLinks by lazy {
        GuestLinks(viewModelScope, PreferencesStore(getApplication()), AndroidGuestSessions(deviceKeys), uploads, guestDeriver, { shellModel?.tenant }, ::appLanguage, ::guestMusic)
    }

    /**
     * The player a guest's Memory plays its music with (#1034; the member's, ADR 0142 decision 1): the tenant's one, or —
     * signed out — one made for the links, which the music bar draws as it draws the tenant's.
     */
    private fun guestMusic(): me.raddatz.veyq.mobile.audio.PageMusic {
        val app = getApplication<Application>()
        val player = _music.value ?: me.raddatz.veyq.mobile.audio.MusicPlayer(
            { events -> MediaDeck(app, events) }, musicControls,
            me.raddatz.veyq.mobile.audio.randomIntOf { n -> ByteArray(n).also { java.security.SecureRandom().nextBytes(it) } },
        ).also { _music.value = it }
        return me.raddatz.veyq.mobile.audio.PageMusic(player) { n -> app.words(me.raddatz.veyq.mobile.words.Msg.MUSIC_UNTITLED, n) }
    }

    /**
     * A READ_WRITE guest's files picked for the folder on screen ([level], #1034; the web's level upload): each its own file
     * made as the guest, on the app's one queue; the level reads the link anew as each settles.
     */
    fun guestLevelUpload(level: me.raddatz.veyq.mobile.sharing.GuestLevel, uris: List<android.net.Uri>) {
        viewModelScope.launch {
            val sources = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uris.mapNotNull { picked(it)?.let(::source) } }
            // a guest not named yet is asked its name first (core's `uploadAsking`); declined, nothing is queued
            if (level.uploadAsking(sources) == 0) sources.forEach { it.close() }
        }
    }

    /** The bookmark page on screen (core's `BookmarkPage`): one at a time; an in-place link let go with it. */
    private var openBookmark: me.raddatz.veyq.mobile.sharing.BookmarkPage? = null

    /** The page of bookmark [node], loading (its meta opened, the share shown in place where the tenant embeds). */
    fun bookmarkPage(node: me.raddatz.veyq.mobile.navigation.NodeAt): me.raddatz.veyq.mobile.sharing.BookmarkPage? {
        // by its node, not its name: a rename heads the page anew (#1035) without opening the link again
        openBookmark?.takeIf { it.node.scopeId == node.scopeId && it.node.nodeId == node.nodeId }?.let { return it }
        val t = shellModel?.tenant ?: return null
        openBookmark?.close()
        val page = me.raddatz.veyq.mobile.sharing.BookmarkPage.of(t, node) { link, password -> guests.view(link, password) }
        openBookmark = page
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { page.load() }
        return page
    }

    /** The bookmark's page left: its in-place link let go. */
    fun closeBookmark(page: me.raddatz.veyq.mobile.sharing.BookmarkPage) {
        if (openBookmark !== page) return
        page.close()
        openBookmark = null
    }

    /** *Open on its own page*: the kept link's screen above the shell, its password handed in memory, *Keep* offered. */
    fun bookmarkOwnPage(page: me.raddatz.veyq.mobile.sharing.BookmarkPage) {
        page.openOwnPage(shellModel?.tenant)?.let(guests::show)
    }

    /** A kept link's row's *Report shared content* (core's `BookmarkPage.reportSharedFromRow`): its meta read, the link's screen with its report open. */
    fun rowReportShared(node: me.raddatz.veyq.mobile.navigation.NodeAt) {
        val t = shellModel?.tenant ?: return
        val page = me.raddatz.veyq.mobile.sharing.BookmarkPage.of(t, node) { link, password -> guests.view(link, password) }
        viewModelScope.launch {
            val screen = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { page.reportSharedFromRow() }
            screen?.let(guests::show)
        }
    }

    /** *Report shared content*: the kept link's screen with its report to the share's own operator open. */
    fun bookmarkReportShared(page: me.raddatz.veyq.mobile.sharing.BookmarkPage) {
        page.reportShared()?.let(guests::show)
    }

    /** New → Bookmark (core's `BookmarkCreate`) at [parentId] of space [scopeId] (null: its top); null before the shell is read. */
    fun bookmarkCreate(scopeId: String, parentId: String?): me.raddatz.veyq.mobile.sharing.BookmarkCreate? {
        val t = shellModel?.tenant ?: return null
        val kind = (_screen.value as? Screen.Signed)?.spaces?.firstOrNull { it.scopeId == scopeId }?.kind
        return me.raddatz.veyq.mobile.sharing.BookmarkCreate(me.raddatz.veyq.mobile.sharing.BookmarkCreate.into(t, scopeId, parentId), groupSpace = kind != "PERSONAL")
    }


    /** A bookmark was made (New or a link's *Keep*): the lists read anew, so its row shows. */
    fun bookmarkMade() = refreshLists {}
}
