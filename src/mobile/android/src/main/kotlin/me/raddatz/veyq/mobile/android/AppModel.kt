package me.raddatz.veyq.mobile.android

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.expenses.Amounts
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.PotView
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

/** One server in the switcher: its account and the tenants the switcher lists there. */
data class ServerEntry(val account: VeyqAccount, val label: String, val tenants: List<TenantRef>)

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
        val servers: List<ServerEntry>,
        val applications: List<ApplicationId>,
        val filters: List<ApplicationId?>,
        val drawer: ArrangedList,
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
        private val lists: me.raddatz.veyq.mobile.navigation.ShellModel.Lists
            get() = me.raddatz.veyq.mobile.navigation.ShellModel.Lists(spaces, nodes, objects, favorites, applications, filters, drawer, uses)
    }
}

class AppModel(app: Application) : AndroidViewModel(app) {
    init {
        // a copy sent to another app in an earlier run has been read by now (or never will be)
        clearSentCopies()
        // a viewed file's decrypted copy that builds before core's file page left in the cache (`view-…`)
        app.cacheDir.listFiles { f -> f.name.startsWith("view-") }?.forEach { runCatching { it.delete() } }
    }

    private val prefs = app.getSharedPreferences("veyq-recent", Context.MODE_PRIVATE)
    private val recent = RecentUses(persist = ::persistRecent, initial = loadRecent())
    private val hidden = Hidden(persist = { prefs.edit().putStringSet("hidden", it).apply() }, initial = prefs.getStringSet("hidden", emptySet()).orEmpty())
    private var host: java.lang.ref.WeakReference<android.app.Activity>? = null
    private val device = ThisDevice(
        PreferencesStore(app),
        KeystoreDeviceKeys(app) { host?.get() },
        android.os.Build.MODEL,
        BouncyCastlePrimitives,
        // ADR 0140 decision 3: app-private, credential-encrypted, left out of Auto Backup by the directory itself
        offlineRoot = java.io.File(app.noBackupFilesDir, "offline").path,
        offlineLimit = me.raddatz.veyq.mobile.offline.AccountOffline.PHONE_LIMIT,
    )

    /** The way in (core's `SessionModel`): the start, the sign-in, the unlock, the switcher, the sign-out. */
    private val session = me.raddatz.veyq.mobile.session.SessionModel.accounts(
        BouncyCastlePrimitives, device, { block -> Network.client(block) }, hidden, recent,
    )

    /** The shell over the current tenant (core's `ShellModel`): its lists, where the person is, the actions on a node. */
    private var shellModel: me.raddatz.veyq.mobile.navigation.ShellModel? = null
    private var shellWatch: kotlinx.coroutines.Job? = null

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
        openMusic?.close()
        openMusic = null
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

    // ---- a Memory's curation (ADR 0144), declared above `init`: its collector closes them at the first gate ----

    private val _participants = MutableStateFlow<me.raddatz.veyq.mobile.memories.ParticipantsPicker?>(null)

    /** The open gallery's participants picker (core's `ParticipantsPicker`); null while closed. */
    val participants: StateFlow<me.raddatz.veyq.mobile.memories.ParticipantsPicker?> = _participants.asStateFlow()

    private val _banner = MutableStateFlow<me.raddatz.veyq.mobile.memories.banner.BannerEditor?>(null)

    /** The open gallery's banner dialog (core's `BannerEditor`); null while closed. */
    val banner: StateFlow<me.raddatz.veyq.mobile.memories.banner.BannerEditor?> = _banner.asStateFlow()

    /** The dialog's running save or removal: Cancel cancels it, the old banner staying as it was. */
    private var bannerJob: kotlinx.coroutines.Job? = null

    /** The file picked last in the banner's dialog, read where it lies (closed with the dialog). */
    private var bannerSource: me.raddatz.veyq.mobile.blob.UploadSource? = null

    /** The profile on screen's Memories section, kept here so a rotation keeps its List | Map choice. */
    private var personSection: me.raddatz.veyq.mobile.memories.people.PersonMemories? = null

    init {
        viewModelScope.launch { session.gate.collect(::gateChanged) }
        // a pin's fetch keeps the process once the app is left: WorkManager's worker, a dataSync foreground service
        OfflineWork.watch(app, viewModelScope)
    }

    private fun gateChanged(g: me.raddatz.veyq.mobile.session.SessionModel.Gate) {
        // the session locked, signed out or starting again: the tenant's music stops with it (ADR 0142 decision 1)
        if (g !is me.raddatz.veyq.mobile.session.SessionModel.Gate.Signed) {
            stopMusic()
            closeMaps()
            // locked or gone: the open dialogs, the profile's section and the tenant's tree go (ADR 0144 decision 12)
            closeCuration()
            personSection = null
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
                followEnded(g)
            }
            else -> _screen.value = Screen.Starting
        }
    }

    /**
     * The shown account ended on its own ([me.raddatz.veyq.mobile.session.Reach.Ended]: a reconnect found this device's
     * trust gone): core took it out of the switcher and opened the next one, which no call of this model asked for — the
     * shell follows it here. A switch or a sign-out this model made shows its own opened account.
     */
    private fun followEnded(g: me.raddatz.veyq.mobile.session.SessionModel.Gate.Signed) {
        if (g.switching) return
        val opened = session.signedTo ?: return
        val shown = shellModel?.tenant ?: return
        if (shown === opened.session || session.accounts.any { it === shown.account }) return
        trustLog("the shown account ended: the shell follows the next one")
        viewModelScope.launch { if (shellModel?.tenant !== opened.session) show(opened) }
    }

    /** The activity a prompt for the unlock key is shown over, while one is resumed. */
    fun attach(activity: android.app.Activity?) {
        host = activity?.let { java.lang.ref.WeakReference(it) }
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

    private fun trustFailed() = notify(Notice(getApplication<Application>().getString(R.string.trust_failed)))

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
    fun signOut(account: VeyqAccount) {
        if (session.signedTo?.account == account) uploads.clear()
        viewModelScope.launch { signedOut(session.signOut(account)) }
    }

    /** What a sign-out left: another account opened, nothing (the sign-in or the unlock follows), or Manage read anew. */
    private suspend fun signedOut(out: me.raddatz.veyq.mobile.session.SessionModel.SignedOut<VeyqAccount, me.raddatz.veyq.mobile.session.TenantSession>) {
        when {
            out.next != null -> show(out.next!!)
            out.wasCurrent -> {
                shellWatch?.cancel()
                shellModel = null
                stopMusic()
                _depot.value?.close()
                _depot.value = null
                closeCuration()
                openGallery?.close()
                openGallery = null
                media?.close()
                media = null
                personSection = null
                tenantSession?.close()
            }
            else -> refreshManaged()
        }
    }

    /** Ends this device's trust in [account]: the next sign-in on this device asks for the Secret Key again. */
    fun forgetDevice(account: VeyqAccount) {
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
        override fun signOut(familyId: String) = onSettings { it.signOut(familyId) }
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
        override fun stopTrusting() = onSettings { m ->
            m.stopTrusting()
            trustLog("settings stop trusting")
            null
        }
        override fun signOutHere() = onSettings { it.signOutHere() }
        override fun setOfflineLimit(amount: String, unit: me.raddatz.veyq.mobile.offline.LimitUnit) = onSettings { it.setOfflineLimit(amount, unit); null }
        override fun turnOfflineLimitOff() = onSettings { it.turnOfflineLimitOff(); null }
        override fun clearOfflineCache() = onSettings { it.clearOfflineCache(); null }
        override fun unpinOffline(node: String) = onSettings { it.unpinOffline(node); null }
        override fun askRemoveAllOffline() { settingsModel?.askRemoveAllOffline() }
        override fun cancelRemoveAllOffline() { settingsModel?.cancelRemoveAllOffline() }
        override fun removeAllOffline() = onSettings { it.removeAllOffline(); null }
        override fun linkShow() = showForNewDevice()
        override fun linkScan() = scanSignInCode()
    }

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
        personSection = null
        closeCuration()
        rows = me.raddatz.veyq.mobile.RowDetails.Read.EMPTY
        openFile?.close()
        openFile = null
        openMusic?.close()
        openMusic = null
        openTracks?.close()
        openTracks = null
        openGallery?.close()
        openGallery = null
        val pots = me.raddatz.veyq.mobile.expenses.PotModel(t, System::currentTimeMillis)
        potWatch?.cancel()
        potModel = pots
        potWatch = viewModelScope.launch { pots.state.collect { _pot.value = it } }
        val model = me.raddatz.veyq.mobile.navigation.ShellModel(t, hidden, recent, System::currentTimeMillis)
        shellWatch?.cancel()
        shellModel = model
        // another tenant's music stops; the same tenant opened again plays on
        if (switched || _music.value == null) {
            stopMusic()
            _music.value = model.musicPlayer({ events -> MediaDeck(getApplication(), events) }, musicControls)
        }
        // the map services follow the shell: built over this tenant, the place names the shell looks up come from them
        closeMaps()
        _maps.value = model.mapServices(me.raddatz.veyq.mobile.map.TileRecipient.TILE_HOST) { appLanguage() }
        _depot.value?.close()
        _depot.value = model.depotUploads(uploads) { viewModelScope.launch { refreshLists(t) } }
        // Android reads a pick in place: a file the queue is done with leaves nothing to remove
        val madeMedia = model.mediaUploads(uploads, deriver, left = {}) { viewModelScope.launch { refreshLists(t) } }
        // a new tenant's queue was emptied; the same tenant opened again carries its uploads on
        media?.let { if (switched) it.close() else it.handOver(madeMedia) }
        media = madeMedia
        startPending = true
        model.load(openLastUsed = shareInbox.waiting.value == null)
        shellWatch = viewModelScope.launch { model.state.collect { it?.let(::publish) } }
        readRowDetails(t)
        watchAvatars(t)
    }

    // ---- what another app shared (ADR 0138) ----

    /** Files shared to veyq, from their arrival to where they went (core's `ShareInbox`). */
    val shareInbox = me.raddatz.veyq.mobile.expenses.ShareInbox()

    /** Files waiting for the Pot they go to (the pick mode); null when none waits. */
    val incoming: StateFlow<me.raddatz.veyq.mobile.expenses.ShareInbox.Incoming?> = shareInbox.waiting

    /**
     * Files arrive: the pick mode starts at the space's Home, never in the object last open, so the person chooses where
     * they go (ADR 0138 decision 1) — the start does the same while the session is still opening.
     */
    fun receiveShare(incoming: me.raddatz.veyq.mobile.expenses.ShareInbox.Incoming) {
        updateShell { Shell.start(null, it.space) }
        shareInbox.receive(incoming)
    }

    fun cancelShare() = shareInbox.cancel()

    /** The waiting files, taken by the Pot opened as their place: its new expense's receipts. */
    fun takeShare(): List<PotSession.PendingReceipt>? = shareInbox.take()

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
            closeOpen = { ref -> _pot.value?.ref?.takeIf { it.nodeId == ref.nodeId }?.let { potModel?.close(it) } },
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
    private suspend fun refreshLists(t: me.raddatz.veyq.mobile.session.TenantSession, people: Boolean = false) {
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
    fun shareSheet(node: me.raddatz.veyq.mobile.navigation.NodeAt): me.raddatz.veyq.mobile.navigation.ShareSheet? {
        val m = shellModel ?: return null
        return m.shareSheet(node) { viewModelScope.launch { readRowDetails(m.tenant) } }
    }

    /** The share of space [scopeId]'s top level (core's `ShareSheet.topLevel`: "Add files only" alone, a drop box at the top). */
    fun topLevelShareSheet(scopeId: String): me.raddatz.veyq.mobile.navigation.ShareSheet? {
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
        return m.moveSheet(node, copying, potModel, getApplication<Application>().getString(R.string.pot_deleted_person)) {
            viewModelScope.launch { readRowDetails(m.tenant) }
        }
    }

    // ---- the space's trash (core's NodeTrash) ----

    /** The trash sheet of [scopeId] (core's `TrashSheet`); the lists are read anew after each write that went through. */
    fun trashSheet(scopeId: String): me.raddatz.veyq.mobile.navigation.TrashSheet? {
        val m = shellModel ?: return null
        return m.trashSheet(scopeId) { viewModelScope.launch { readRowDetails(m.tenant) } }
    }

    // ---- an open Pot (Expenses) ----

    /** The tenant the shell shows, opened: where an object's own view reads from. */
    private var tenantSession: me.raddatz.veyq.mobile.session.TenantSession? = null
    /** The Pot open in the app (core's `PotModel`): its views, its live poll, its writes. */
    private var potModel: me.raddatz.veyq.mobile.expenses.PotModel? = null
    private var potWatch: kotlinx.coroutines.Job? = null
    private val potSession: PotSession? get() = potModel?.session

    private val _pot = MutableStateFlow<me.raddatz.veyq.mobile.expenses.PotModel.State?>(null)

    /** What each object's row says beside its name, by node id (an application's answer: Expenses' `PotRows`). */
    private val _rowDetails = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val rowDetails: StateFlow<Map<String, List<String>>> = _rowDetails.asStateFlow()

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
    private var rows = me.raddatz.veyq.mobile.RowDetails.Read.EMPTY

    private suspend fun readRowDetails(t: me.raddatz.veyq.mobile.session.TenantSession) {
        val read = me.raddatz.veyq.mobile.RowDetails.read(t, rows)
        if (tenantSession !== t) return
        rows = read
        // the file on screen reads itself anew: a replaced blob is shown
        openFile?.reload()
        val app = getApplication<Application>()
        // a Memory's row says its date as its second line (ADR 0143 D5)
        val shell = shellModel
        val dates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { shell?.memoryDates().orEmpty() }.mapNotNull { (id, d) -> app.memoryDateText(d)?.let { id to it } }.toMap()
        if (tenantSession !== t) return
        _rowDetails.value = read.details.mapValues { (_, facts) -> facts.map { app.piece(it) } } +
            dates.mapValues { (id, d) -> read.details[id].orEmpty().map { app.piece(it) } + d }
        readCovers(t, read.covers)
    }

    // ---- what the shell says (a snackbar), and an upload under way ----

    /**
     * A notice the shell shows once: its text and, where offered, an action (Depot's "Replace anyway"); [done] once it is
     * gone, its action taken or not.
     */
    class Notice(val text: String, val action: String? = null, val run: (suspend () -> Unit)? = null, val done: (() -> Unit)? = null)

    private val _notices = kotlinx.coroutines.channels.Channel<Notice>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val notices: kotlinx.coroutines.flow.Flow<Notice> = _notices.receiveAsFlow()

    fun notify(n: Notice) {
        _notices.trySend(n)
    }

    /**
     * The app's one upload queue (core's `UploadQueue`, the web's `core/blob/queue.ts`): every application's uploads, one
     * after another, retried once; the shell's tray shows it ([UploadTray]). It outlives a screen, not the account: a
     * sign-out or a switch of tenant empties it.
     */
    val uploads = me.raddatz.veyq.mobile.blob.UploadQueue()

    /**
     * The uploads as the shell shows them (core's `DepotUploads` over [uploads], one per open tenant): the tray draws its
     * state, Depot's uploads go through it, and what it says ([sayUploads]) is the shell's snackbar.
     */
    private val _depot = MutableStateFlow<me.raddatz.veyq.mobile.depot.DepotUploads?>(null)
    val depot: StateFlow<me.raddatz.veyq.mobile.depot.DepotUploads?> = _depot.asStateFlow()

    /** What the uploads say, each once, as the shell's snackbar: its action takes the offer, else it is seen. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sayUploads = viewModelScope.launch {
        _depot.flatMapLatest { d -> d?.state?.map { d to it.said } ?: kotlinx.coroutines.flow.flowOf(null) }
            .distinctUntilChanged { a, b -> a?.second?.id == b?.second?.id }
            .collect { pair ->
                val (d, said) = pair ?: return@collect
                said ?: return@collect
                val app = getApplication<Application>()
                notify(Notice(said.notices.joinToString("\n") { app.text(it) }, said.action?.let { app.words(it) }, run = { d.take(said) }) { d.seen(said.id) })
            }
    }

    /**
     * Queues the picked [uris] as new files under the level shown ([label] its name; core's `DepotUploads.upload`): a name a
     * file already holds offers its replace.
     */
    fun uploadPicked(scopeId: String, parentId: String?, label: String, uris: List<android.net.Uri>) {
        val d = _depot.value ?: return
        viewModelScope.launch {
            val sources = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uris.mapNotNull { picked(it) }.map { source(it) } }
            d.upload(scopeId, parentId, label, sources)
        }
    }

    /** The header's "Replace…": the picked document replaces the content [file] showed when Replace was chosen. */
    fun replaceFile(file: me.raddatz.veyq.mobile.depot.FileModel, uri: android.net.Uri) {
        val d = _depot.value ?: return
        viewModelScope.launch {
            val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { picked(uri) } ?: return@launch
            d.replace(file, source(p))
        }
    }

    /**
     * Depot's "Upload folder…": the directory the system's picker granted ([tree], `OpenDocumentTree`) walked through its
     * documents provider, each entry handed to core's `DepotUploads.uploadFolder`, which keeps, prepares, queues and says.
     */
    fun uploadFolderPicked(scopeId: String, parentId: String?, tree: android.net.Uri) {
        val d = _depot.value ?: return
        viewModelScope.launch {
            val walk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { treeWalk(tree) }
            d.uploadFolder(scopeId, parentId, walk.shown, walk.path, walk.found)
        }
    }

    /** What the documents provider lists of a picked directory: its shown name (if it gives one), its id, every entry below. */
    private class TreeWalk(val shown: String?, val path: String, val found: List<me.raddatz.veyq.mobile.depot.FolderUpload.Found>)

    /** The directory [tree] names walked: each entry by its path there (`/`-separated) and what the provider says it is. */
    private fun treeWalk(tree: android.net.Uri): TreeWalk {
        val resolver = getApplication<Application>().contentResolver
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val shown = runCatching {
            resolver.query(
                android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val out = ArrayList<me.raddatz.veyq.mobile.depot.FolderUpload.Found>()
        fun walk(docId: String, path: String) {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_SIZE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val rows = ArrayList<Array<Any?>>()
            val listed = runCatching {
                resolver.query(children, cols, null, null, null)?.use { q ->
                    while (q.moveToNext()) rows += arrayOf(q.getString(0), q.getString(1), q.getString(2), if (q.isNull(3)) 0L else q.getLong(3), if (q.isNull(4)) null else q.getLong(4))
                }
            }.getOrNull()
            // a directory the provider does not list is said, never silently skipped
            if (listed == null) { out += me.raddatz.veyq.mobile.depot.FolderUpload.Found(path.ifEmpty { shown ?: rootId }, me.raddatz.veyq.mobile.depot.FolderUpload.Kind.UNREADABLE); return }
            for (row in rows) {
                val id = row[0] as String
                val name = row[1] as String
                val mime = row[2] as String?
                val at = if (path.isEmpty()) name else "$path/$name"
                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    out += me.raddatz.veyq.mobile.depot.FolderUpload.Found(at, me.raddatz.veyq.mobile.depot.FolderUpload.Kind.DIRECTORY)
                    walk(id, at)
                    continue
                }
                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
                out += me.raddatz.veyq.mobile.depot.FolderUpload.Found(at, me.raddatz.veyq.mobile.depot.FolderUpload.Kind.FILE, me.raddatz.veyq.mobile.blob.UploadSource.of(name, row[3] as Long, mime ?: "application/octet-stream", row[4] as Long?) { from, length ->
                    // opened per frame: the upload asks one frame after another and never holds the file
                    val pfd = resolver.openFileDescriptor(uri, "r") ?: throw java.io.IOException("unreadable")
                    pfd.use { d ->
                        java.io.FileInputStream(d.fileDescriptor).channel.use { ch ->
                            val buf = java.nio.ByteBuffer.allocate(length)
                            while (buf.hasRemaining()) if (ch.read(buf, from + buf.position()) < 0) break
                            buf.array().copyOf(buf.position())
                        }
                    }
                })
            }
        }
        walk(rootId, "")
        return TreeWalk(shown, rootId, out)
    }

    // ---- Depot's files (the application's `depot/`) ----

    /** The file page on screen (core's `FileModel`): one at a time, read anew whenever the lists are. */
    private var openFile: me.raddatz.veyq.mobile.depot.FileModel? = null

    /** The page of file [node] (core's `FileModel` over the shell; Android has a share sheet, so it sends a copy). */
    fun fileModel(node: me.raddatz.veyq.mobile.navigation.NodeAt): me.raddatz.veyq.mobile.depot.FileModel? {
        openFile?.takeIf { it.node == node }?.let { return it }
        val m = shellModel ?: return null
        openFile?.close()
        return m.fileModel(node, shares = true).also { openFile = it }
    }

    /** The Memory or chapter page on screen (core's `GalleryModel`): one at a time, read anew whenever the lists are. */
    private var openGallery: me.raddatz.veyq.mobile.memories.GalleryModel? = null

    /** The page of Memory or chapter [node] (core's `GalleryModel` over the shell, its uploads through [media]). */
    fun galleryModel(node: me.raddatz.veyq.mobile.navigation.NodeAt): me.raddatz.veyq.mobile.memories.GalleryModel? {
        openGallery?.takeIf { it.node == node }?.let { return it }
        val m = shellModel ?: return null
        val uploads = media ?: return null
        openMusic?.close()
        openMusic = null
        openTracks?.close()
        openTracks = null
        closeCuration()
        openGallery?.close()
        val app = getApplication<Application>()
        val music = _music.value?.let { p -> me.raddatz.veyq.mobile.memories.GalleryModel.Music(p) { n -> app.words(me.raddatz.veyq.mobile.words.Msg.MUSIC_UNTITLED, n) } }
        return m.galleryModel(node, uploads, music).also { openGallery = it }
    }

    /** The gallery page went: its lightbox and any video's reader close with it, and its Music view (the music plays on). */
    fun closeGallery(gallery: me.raddatz.veyq.mobile.memories.GalleryModel) {
        if (openGallery !== gallery) return
        openMusic?.close()
        openMusic = null
        openTracks?.close()
        openTracks = null
        closeCuration()
        gallery.close()
        openGallery = null
    }

    // ---- a Memory's curation (ADR 0144): its participants picker and its banner's dialog, held here so a rotation keeps them ----


    /** [gallery]'s participants picker opened over the scope's current members, where its rights offer it. */
    fun openParticipants(gallery: me.raddatz.veyq.mobile.memories.GalleryModel) {
        val picker = gallery.participantsPicker() ?: return
        _participants.value = picker
        viewModelScope.launch { picker.load() }
    }

    /** The members read again (*Try again*). */
    fun reloadParticipants() {
        val picker = _participants.value ?: return
        viewModelScope.launch { picker.load() }
    }

    /** The pick saved (core's merge); the picker closes once it landed. */
    fun saveParticipants() {
        val picker = _participants.value ?: return
        viewModelScope.launch { if (picker.save().let { it == me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Written || it == me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Unchanged }) closeParticipants(picker) }
    }

    fun closeParticipants(picker: me.raddatz.veyq.mobile.memories.ParticipantsPicker? = _participants.value) {
        if (_participants.value === picker) _participants.value = null
    }


    /** A banner's pictures decoded on this device: an image as the cover's JPEG, a video's first frame ([MediaDerive]). */
    private val bannerPictures = object : me.raddatz.veyq.mobile.memories.banner.BannerWrite.Pictures {
        override suspend fun image(source: me.raddatz.veyq.mobile.blob.UploadSource): ByteArray? =
            (source as? ContentSource)?.uri?.let { u -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MediaDerive.bannerImage(getApplication(), u) } }

        override suspend fun poster(source: me.raddatz.veyq.mobile.blob.UploadSource): ByteArray? =
            (source as? ContentSource)?.uri?.let { u -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MediaDerive.bannerPoster(getApplication(), u) } }
    }

    /** [gallery]'s banner dialog opened, where whoever looks may set its banner online. */
    fun openBanner(gallery: me.raddatz.veyq.mobile.memories.GalleryModel) {
        if (openGallery !== gallery) return
        _banner.value = gallery.bannerEditor(bannerPictures)
    }

    /** The file the photo picker handed over ([uri]) taken as the banner: refused or decoded for the previews (core's). */
    fun pickBanner(uri: android.net.Uri) {
        val editor = _banner.value ?: return
        viewModelScope.launch {
            // the media store's original where full media access was given, as an upload reads it: the equal-file match needs its bytes
            val source = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pickedMedium(uri)?.let(::source) } ?: return@launch
            bannerSource?.close()
            bannerSource = source
            editor.pick(source)
        }
    }

    /** The picked file made the banner, or [remove]d: one run at a time, cancelled by [cancelBanner]. */
    fun saveBanner(remove: Boolean = false) {
        val editor = _banner.value ?: return
        if (bannerJob?.isActive == true) return
        bannerJob = viewModelScope.launch { if (remove) editor.remove() else editor.save() }
    }

    fun cancelBanner() {
        bannerJob?.cancel()
        bannerJob = null
    }

    fun closeBanner() {
        cancelBanner()
        _banner.value = null
        bannerSource?.close()
        bannerSource = null
    }

    /** The open gallery's dialogs closed with it (a switch, a lock, a sign-out, another gallery). */
    private fun closeCuration() {
        closeParticipants()
        closeBanner()
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
        personSection = null
        shellModel?.openPerson(userId)
    }

    /** Person [userId]'s frame as the tenant's directory names them; null while no tenant is open. */
    fun profile(userId: String): me.raddatz.veyq.mobile.navigation.PersonProfile? = shellModel?.profile(userId)


    /** Memories' section of person [userId]'s profile (core's `PersonMemories`), read on its first ask. */
    fun personMemories(userId: String): me.raddatz.veyq.mobile.memories.people.PersonMemories? {
        personSection?.takeIf { it.userId == userId }?.let { return it }
        val m = shellModel ?: return null
        return m.personMemories(userId).also { section ->
            personSection = section
            // the index unseals each Memory's meta: off the main thread (src/mobile/CLAUDE.md § Traps)
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) { section.load() }
        }
    }

    /** *Try again* on the section. */
    fun retryPerson(section: me.raddatz.veyq.mobile.memories.people.PersonMemories) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) { section.load() }
    }

    // ---- a Memory's date, place and Map view (ADR 0143 D5, D8) ----

    /** The app's language, as the geocoder's `accept-language` (the web's reader's): the per-app one Android applies. */
    private fun appLanguage(): String =
        getApplication<Application>().resources.configuration.locales.get(0)?.language?.takeIf { it.isNotEmpty() } ?: java.util.Locale.getDefault().language

    /** [gallery]'s date editor (core's `DateEditor`), where whoever looks may edit its meta online; null otherwise. */
    fun dateEditor(gallery: me.raddatz.veyq.mobile.memories.GalleryModel): me.raddatz.veyq.mobile.memories.place.DateEditor? = shellModel?.dateEditor(gallery)

    /** [gallery]'s place editor (core's `PlaceEditor` over the map services), where whoever looks may edit its meta online. */
    fun placeEditor(gallery: me.raddatz.veyq.mobile.memories.GalleryModel): me.raddatz.veyq.mobile.memories.place.PlaceEditor? = shellModel?.placeEditor(gallery)

    /** The Memories bar's Map tab's pins over [memories] (its All tab's set; core's `MemoriesMap`). */
    suspend fun memoriesMap(memories: List<me.raddatz.veyq.mobile.navigation.ObjectRef>): me.raddatz.veyq.mobile.memories.place.MemoriesMap? {
        val m = shellModel ?: return null
        // each pin's meta opened off the main thread
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            m.memoriesMap(memories.map { me.raddatz.veyq.mobile.navigation.NodeRef(it.scopeId, it.nodeId, null, it.kind, it.name) })
        }
    }

    /** The open gallery's Map view's tracks (core's `TrackList`): one at a time, closed with its gallery. */
    private var openTracks: me.raddatz.veyq.mobile.memories.tracks.TrackList? = null

    /** [gallery]'s Map view's tracks, its picked GPX files queued through [media]. */
    fun trackList(gallery: me.raddatz.veyq.mobile.memories.GalleryModel): me.raddatz.veyq.mobile.memories.tracks.TrackList? {
        openTracks?.takeIf { it.gallery === gallery }?.let { return it }
        val m = shellModel ?: return null
        val uploads = media ?: return null
        openTracks?.close()
        return m.trackList(gallery, uploads, left = {}).also { openTracks = it }
    }

    /** The GPX files the system's picker granted ([uris]) offered to [tracks] (core's checks; the accepted ones' labels asked there). */
    fun addTracks(tracks: me.raddatz.veyq.mobile.memories.tracks.TrackList, uris: List<android.net.Uri>) {
        viewModelScope.launch {
            val sources = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uris.mapNotNull { picked(it) }.map { source(it) } }
            tracks.add(sources)
        }
    }

    /** Track [mediaId] of [tracks] saved to the document the person made at [uri] (the gallery's save); a document it did not fill goes again. */
    fun saveGpx(tracks: me.raddatz.veyq.mobile.memories.tracks.TrackList, mediaId: String, uri: android.net.Uri): me.raddatz.veyq.mobile.depot.FileSave? {
        val resolver = getApplication<Application>().contentResolver
        val save = tracks.save(mediaId) { produce ->
            val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolver.openOutputStream(uri) } ?: return@save false
            out.use { o -> produce { o.write(it) } }
        } ?: return null
        viewModelScope.launch {
            val ended = save.state.first { it.end != null }
            if (ended.end != me.raddatz.veyq.mobile.depot.FileSave.End.SAVED) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
            }
        }
        return save
    }

    /** The open gallery's Music view (core's `MusicModel`, ADR 0142 decision 8); one at a time, closed with its gallery. */
    private var openMusic: me.raddatz.veyq.mobile.memories.music.MusicModel? = null

    /** [gallery]'s Music view: its tracks, the picked files checked with Android's [musicMeasure], queued through [media]. */
    fun musicModel(gallery: me.raddatz.veyq.mobile.memories.GalleryModel): me.raddatz.veyq.mobile.memories.music.MusicModel? {
        openMusic?.takeIf { it.gallery === gallery }?.let { return it }
        val m = shellModel ?: return null
        val uploads = media ?: return null
        openMusic?.close()
        return m.musicModel(gallery, uploads, musicMeasure, left = {}).also { openMusic = it }
    }

    /**
     * A picked file's length as Android's player would play it (core's `MusicUpload.Measure`, ADR 0142 decision 7):
     * `MediaMetadataRetriever` over the document; null where it cannot read it, and the file is refused as unplayable.
     */
    private val musicMeasure = object : me.raddatz.veyq.mobile.memories.music.MusicUpload.Measure {
        override fun durationMs(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, done: (Long?) -> Unit) {
            val uri = (source as? ContentSource)?.uri ?: return done(null)
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ms = runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(getApplication(), uri)
                        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally {
                        r.release()
                    }
                }.getOrNull()
                done(ms)
            }
        }
    }

    /** The audio files the system's picker granted ([uris]) added to [music]'s node as tracks (core's checks, its refusals said there). */
    fun addMusic(music: me.raddatz.veyq.mobile.memories.music.MusicModel, uris: List<android.net.Uri>) {
        viewModelScope.launch {
            val sources = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uris.mapNotNull { picked(it) }.map { source(it) } }
            music.add(sources)
        }
    }

    /**
     * Track [mediaId] of [music] saved to the document the person made at [uri] (core's `FileSave`, as a lightbox's
     * original is): a document it did not fill goes again. The save, whose end the view says; null where it is not offered.
     */
    fun saveTrack(music: me.raddatz.veyq.mobile.memories.music.MusicModel, mediaId: String, uri: android.net.Uri): me.raddatz.veyq.mobile.depot.FileSave? {
        val resolver = getApplication<Application>().contentResolver
        val save = music.save(mediaId) { produce ->
            val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolver.openOutputStream(uri) } ?: return@save false
            out.use { o -> produce { o.write(it) } }
        } ?: return null
        viewModelScope.launch {
            val ended = save.state.first { it.end != null }
            if (ended.end != me.raddatz.veyq.mobile.depot.FileSave.End.SAVED) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
            }
        }
        return save
    }

    /**
     * [gallery]'s delete, Undo or purge (core's `GalleryModel.trash`/`undo`/`purge`, what each says lands in its state) run
     * in the model's scope: a request the person started finishes though the lightbox or the snackbar that asked went.
     */
    fun galleryAct(act: suspend () -> Unit) {
        viewModelScope.launch { act() }
    }

    /** Decoded tiers by blob id, the most recent kept (a gallery asks for every tile as it scrolls past). */
    private val mediaPictures = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 4
    }

    /** Blob [blobId]'s bytes as [read] hands them, decoded as a picture and kept; null where it does not read or is no picture. */
    suspend fun mediaPicture(blobId: String, read: suspend () -> ByteArray?): androidx.compose.ui.graphics.ImageBitmap? {
        mediaPictures.get(blobId)?.let { return it }
        val picture = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bytes = read() ?: return@runCatching null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } ?: return null
        mediaPictures.put(blobId, picture)
        return picture
    }

    /**
     * The original [box] shows saved to the document the person made at [uri] (core's `FileSave`, a frame at a time); the
     * save outlives the lightbox, and half an original is no download: a document it did not fill goes again.
     */
    fun saveMedium(box: me.raddatz.veyq.mobile.memories.Lightbox, uri: android.net.Uri) {
        val resolver = getApplication<Application>().contentResolver
        val save = box.save { produce ->
            val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolver.openOutputStream(uri) } ?: return@save false
            out.use { o -> produce { o.write(it) } }
        } ?: return
        viewModelScope.launch {
            val ended = save.state.first { it.end != null }
            if (ended.end != me.raddatz.veyq.mobile.depot.FileSave.End.SAVED) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
            }
        }
    }

    /** Memories' uploads of the open tenant (core's `MediaUploads` over [uploads]): Android reads each pick in place. */
    private var media: me.raddatz.veyq.mobile.memories.MediaUploads? = null

    /**
     * Queues the photos and videos the system's picker granted ([uris]) into [gallery]'s Memory or chapter (core's
     * `MediaUploads`: each one's tiers derived here ([MediaDerive]) while the one before it is on the wire, its bytes read at
     * any offset; the core reads the gallery anew as each is in, the lists at the batch's end).
     */
    fun uploadMedia(gallery: me.raddatz.veyq.mobile.memories.GalleryModel, uris: List<android.net.Uri>) {
        viewModelScope.launch {
            val sources = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uris.mapNotNull { pickedMedium(it) }.map { source(it) } }
            gallery.upload(sources)
        }
    }

    /** Android's tiers of a picked medium ([MediaDerive]), for core's upload job. */
    private val deriver = object : me.raddatz.veyq.mobile.memories.MediaUpload.Deriver {
        override fun derive(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, done: (me.raddatz.veyq.mobile.memories.MediaUpload.Derived) -> Unit) {
            val uri = (source as? ContentSource)?.uri
            done(uri?.let { MediaDerive.of(getApplication(), it, type) } ?: me.raddatz.veyq.mobile.memories.MediaUpload.Derived(null, null, null, null))
        }
    }

    /**
     * A photo or video the system's picker handed over ([uri]), read as it was taken where the person gave the app full
     * media access: the media store's own item behind it — its location kept (`setRequireOriginal`), its own name — else
     * (no such access, only some photos allowed, an item the store does not hold) the picker's copy as it is.
     */
    private fun pickedMedium(uri: android.net.Uri): Picked? {
        val picked = picked(uri) ?: return null
        val app = getApplication<Application>()
        fun granted(p: String) = app.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val full = if (android.os.Build.VERSION.SDK_INT >= 33) granted(android.Manifest.permission.READ_MEDIA_IMAGES) && granted(android.Manifest.permission.READ_MEDIA_VIDEO)
        else granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (!full || !granted(android.Manifest.permission.ACCESS_MEDIA_LOCATION)) return picked
        return runCatching {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return picked
            val video = picked.type.startsWith("video/")
            val base = if (video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val item = android.content.ContentUris.withAppendedId(base, id)
            val resolver = app.contentResolver
            val cols = arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.SIZE, android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
            val listed = resolver.query(item, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else Triple(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2) * 1000)
            } ?: return picked
            val original = android.provider.MediaStore.setRequireOriginal(item)
            resolver.openFileDescriptor(original, "r")?.close() ?: return picked
            Picked(original, listed.first ?: picked.name, listed.second.takeIf { it > 0 } ?: picked.size, picked.type, listed.third ?: picked.modifiedAt)
        }.getOrDefault(picked)
    }

    /** Whether the app asked for full media access before (once: a no is the person's, and the picker works without it). */
    fun mediaAccessAsked(): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences("veyq.memories", android.content.Context.MODE_PRIVATE)
        val asked = prefs.getBoolean("mediaAccessAsked", false)
        if (!asked) prefs.edit().putBoolean("mediaAccessAsked", true).apply()
        return asked
    }

    /** Drop box [ref]'s page (core's `DropBoxPage`: its guests' folders read anew with the lists, a delete asked first). */
    fun dropBoxPage(ref: me.raddatz.veyq.mobile.navigation.NodeAt): me.raddatz.veyq.mobile.depot.DropBoxPage? = shellModel?.dropBoxPage(ref)

    /**
     * The file a person is about to send a copy of to another app ("Send a copy…"), while the shell asks them first: the copy
     * leaves veyq decrypted. The data are theirs; the app says what happens and never forbids it.
     */
    private val _sending = MutableStateFlow<me.raddatz.veyq.mobile.navigation.NodeAt?>(null)
    val sending: StateFlow<me.raddatz.veyq.mobile.navigation.NodeAt?> = _sending.asStateFlow()

    fun askToSend(ref: me.raddatz.veyq.mobile.navigation.NodeAt?) {
        _sending.value = ref
    }

    /** Where the copies sent to another app wait to be read: cleared before the next one and when the app starts. */
    private val sharedDir get() = java.io.File(getApplication<Application>().cacheDir, "shared")

    fun clearSentCopies() {
        runCatching { sharedDir.deleteRecursively() }
    }

    /**
     * A decrypted copy of file [ref] under its own name (core's `downloadName`), in the app's shared cache, and the content
     * URI another app reads it through (the FileProvider, read permission granted to the one it is sent to); null when it
     * could not be written.
     */
    suspend fun copyForSending(ref: me.raddatz.veyq.mobile.navigation.NodeAt): Pair<android.net.Uri, String>? {
        val header = fileModel(ref)?.current?.header?.takeIf { it.facts.hasContent } ?: return null
        val app = getApplication<Application>()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            clearSentCopies()
            val dir = java.io.File(sharedDir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
            val out = java.io.File(dir, header.downloadName)
            // the file itself (core's download of a file), the one decrypted copy the person asked for; swept at the next start
            val ok = out.outputStream().let { downloadNode(ref, it) } != null
            if (!ok) {
                dir.deleteRecursively()
                return@withContext null
            }
            androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", out) to header.mediaType
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
     * are read anew. Why not, in the web's words, or null once created.
     */
    suspend fun createFolder(name: String): String? {
        val app = getApplication<Application>()
        val m = shellModel ?: return app.text(me.raddatz.veyq.mobile.words.Notices.folderRefused(null))
        return m.createFolderHere(name)?.let { app.text(it) }.also { readRowDetails(m.tenant) }
    }

    /**
     * A Memory [name] made in space [scopeId] under [parentId] (null: the space's top) — New Memory, core's
     * `ShellModel.createMemory` (the web's `MemoryNodeDialog`: checked against the loaded tree, then sent) —, opened once
     * made (the web's), its maker listed among its participants where [wasThere] (ADR 0144 decision 2). Why not, in the
     * dialog's words, or null.
     */
    suspend fun createMemory(scopeId: String, parentId: String?, name: String, wasThere: Boolean = false): String? {
        val app = getApplication<Application>()
        val m = shellModel ?: return app.text(me.raddatz.veyq.mobile.memories.MemoryCreate.refused(null))
        return opened(m.createMemory(scopeId, parentId, name, wasThere)).also { readRowDetails(m.tenant) }
    }

    /** Whether New Memory in space [scopeId] offers *I was there* (a tenant or group space, ADR 0144 decision 2). */
    fun offersWasThere(scopeId: String): Boolean = shellModel?.offersWasThere(scopeId) == true

    /** A chapter [name] made in [gallery]'s Memory (core's `GalleryModel.createChapter`), opened once made; why not, or null. */
    suspend fun createChapter(gallery: me.raddatz.veyq.mobile.memories.GalleryModel, name: String): String? {
        // core reads the lists anew once it is made (`ShellModel.createMemoryNode`)
        val made = gallery.createChapter(name)
        shellModel?.let { readRowDetails(it.tenant) }
        return opened(made)
    }

    /** What a Memory's or a chapter's create came to: the node made opened, or why not in words. */
    private fun opened(made: me.raddatz.veyq.mobile.memories.MemoryCreate.Made): String? {
        made.notice?.let { return getApplication<Application>().text(it) }
        val n = made.node ?: return getApplication<Application>().text(me.raddatz.veyq.mobile.memories.MemoryCreate.refused(null))
        me.raddatz.veyq.mobile.memories.GalleryModel.page(n)?.let(::open)
        return null
    }

    /** What the drawer's "New" asked Home to create ("folder" or "pot"), until Home opens its dialog. */
    private val _newRequest = MutableStateFlow<String?>(null)
    val newRequest: StateFlow<String?> = _newRequest.asStateFlow()

    /** The drawer's "New": Home, where its dialog for [kind] opens. */
    fun requestNew(kind: String) {
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
    val pot: StateFlow<me.raddatz.veyq.mobile.expenses.PotModel.State?> = _pot.asStateFlow()

    /** Opens and reads the Pot [ref] unless it is the one shown. */
    fun loadPot(ref: ObjectRef) {
        val pots = potModel ?: return
        viewModelScope.launch { pots.open(ref) }
    }

    /** The open Pot's live poll, for as long as the caller runs it (the Pot's screen while it is started). */
    suspend fun livePot() {
        potModel?.live()
    }

    /** Reads the open Pot again now (pull to refresh); [done] when it settled, moved or not. */
    fun refreshPot(done: () -> Unit) {
        val pots = potModel ?: return done()
        viewModelScope.launch {
            pots.refresh()
            done()
        }
    }

    /** Records settle-up [rows] as paid, dated the core's today (core's `PotModel.record`); a refusal is the Pot's message. */
    fun recordSettleUp(rows: List<me.raddatz.veyq.mobile.expenses.PotView.SettleRow>) {
        val pots = potModel ?: return
        viewModelScope.launch { pots.record(rows) }
    }

    /** Whether deleted entry [entryId] may be restored now, by the core's clock. */
    fun restorable(entryId: String): Boolean = potModel?.restorable(entryId) == true

    /** Restores deleted entry [entryId] (core's `PotModel.restore`, its window the core's); [done] once it was. */
    fun restoreEntry(entryId: String, done: () -> Unit) {
        val pots = potModel ?: return
        viewModelScope.launch { if (pots.restore(entryId)) done() }
    }

    /** The open Pot's settings and people (core's `PotSettings`); null while none is read. */
    fun potSettings(): me.raddatz.veyq.mobile.expenses.PotSettings? = potModel?.settings()

    /**
     * One change of the open Pot's settings or people ([op], a `PotSettings` action); [done] once it was stored. A new base
     * currency is said on the Pot's row at once.
     */
    fun settingsWrite(op: suspend () -> Boolean, done: () -> Unit = {}) {
        val pots = potModel ?: return
        val ref = _pot.value?.ref ?: return
        viewModelScope.launch {
            val stored = op()
            pots.rowDetails()?.let { facts -> _rowDetails.update { it + (ref.nodeId to facts) } }
            if (stored) done()
        }
    }

    /** "New pot" (core's `PotCreateSheet`) at the shell's place now, in the person's language's currency; null before the shell is read. */
    fun potCreateSheet(): me.raddatz.veyq.mobile.expenses.PotCreateSheet? {
        val shell = shellModel ?: return null
        return potModel?.createSheet(shell, java.util.Locale.getDefault().language)
    }

    /** "New pot" made one: the new Pot's row says its facts. */
    fun potCreated() {
        val shell = shellModel ?: return
        viewModelScope.launch { readRowDetails(shell.tenant) }
    }

    /**
     * The entry editor of [entryId] in the open Pot (null: a new entry), core's [me.raddatz.veyq.mobile.expenses.EntryEditor]
     * over the Pot's model; [pending] what another app shared as its receipts. Null when the Pot cannot take one.
     */
    fun entryEditor(entryId: String?, separator: Char, pending: List<PotSession.PendingReceipt> = emptyList()): me.raddatz.veyq.mobile.expenses.EntryEditor? =
        potModel?.editor(entryId, separator, pending)

    /** A receipt of the open Pot opened to its bytes, or null (blocked, or it did not read). */
    /**
     * Downloads the open Pot [ref] into [out] as the web's ZIP (uc.platform.export-import, ADR 0082): its settings, people and
     * live entries, each receipt, and a CSV of the entries. False when it did not complete; [out] is closed either way.
     */
    suspend fun downloadPot(ref: ObjectRef, out: java.io.OutputStream): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val session = potSession?.takeIf { _pot.value?.ref == ref }
        val deleted = getApplication<Application>().getString(R.string.pot_deleted_person)
        val done = session != null && runCatching {
            out.buffered(1 shl 16).let { b ->
                me.raddatz.veyq.mobile.expenses.PotExport.download(session, deleted, System.currentTimeMillis()) { b.write(it) }.also { b.flush() }
            } != null
        }.getOrDefault(false)
        runCatching { out.close() }
        done
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

    suspend fun openReceipt(receiptId: String): ByteArray? = potModel?.openReceipt(receiptId)


    /** Forgets the open Pot's last refusal (a dialog closed). */
    fun clearPotMessage() {
        potModel?.clearMessage()
    }

    /** The shell's lists from what was read, less what this device hides; on [start], the shell where the app opens. */
    /** Whether the next publish is the shell's first over a tenant ("Manage servers" closes then). */
    private var startPending = false

    /** The screen over [state], the core shell's, with this device's servers and the tenant shown. */
    private fun publish(state: me.raddatz.veyq.mobile.navigation.ShellModel.State) {
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
            servers = session.accounts.map { c -> ServerEntry(c, c.label, hidden.visibleTenants(c.key, c.tenants, if (c == account) tenant else null)) },
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

    fun select(tab: BarTab) = updateShell { it.select(tab) }

    /** Whether "New" is offered where the shell is (core's `ShellModel.canCreateHere`). */
    val canCreateHere: Boolean get() = shellModel?.canCreateHere == true

    /** The shell's notices of items no longer available offline were shown: core does not say them again. */
    fun goneSeen() {
        val m = shellModel ?: return
        viewModelScope.launch { m.goneSeen() }
    }
    fun search(on: Boolean) = updateShell { it.search(on) }

    private fun updateShell(f: (Shell) -> Shell) {
        shellModel?.update(f)
    }

    private fun persistRecent(keys: List<UseKey>) {
        prefs.edit().putString("uses", keys.joinToString("\n") { it.encode() }).apply()
    }

    private fun loadRecent(): List<UseKey> = prefs.getString("uses", "").orEmpty().lines().mapNotNull(UseKey::decode)
}
