package me.raddatz.veyq.mobile.android.core

import me.raddatz.veyq.mobile.media.Derivation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.sharing.GuestScreen
import me.raddatz.veyq.mobile.sharing.GuestSessions
import me.raddatz.veyq.mobile.sharing.GuestView
import me.raddatz.veyq.mobile.sharing.OpenLink
import me.raddatz.veyq.mobile.sharing.ReceivedLink

/**
 * A link's guest sessions in the Keystore-wrapped store (ADR 0150 decision 3, `src/mobile/CLAUDE.md` § Nothing on disk):
 * each sealed under the Keystore's secrets key — the one that seals the device's refresh tokens —, its alias the core's
 * hashed name (`GuestNames.session`), never the share's id. A store that refuses throws, as the core's port asks.
 */
class AndroidGuestSessions(private val keys: DeviceKeys) : GuestSessions {
    override fun read(name: String): String? = keys.secret(name)
    override fun write(name: String, value: String) = keys.putSecret(name, value)
    override fun remove(name: String) = keys.deleteSecret(name)
}

/**
 * The app's received links (ADR 0150 decisions 4 and 6): *Open a link*'s field ([asking]) on the sign-in and the unlock
 * screen (owner 2026-11-19: not in the shell), and the one link open as its guest ([screen]), a screen above whatever was on screen — the sign-in or
 * the shell, which stay where they were and are shown again once the link is left. Every guest view of the app — opened
 * here, from a bookmark's page or its *Open on its own page* — is made by [view] over the app's HTTP client, its device
 * store (the terms' and the note's acceptances), the Keystore-wrapped [sessions] and its one upload queue. Nothing of a
 * link is kept here once it is left: the core's model holds the key and zeroes it ([GuestScreen.leave]).
 */
class GuestLinks(
    private val scope: CoroutineScope,
    private val store: DeviceStore,
    private val sessions: GuestSessions,
    private val queue: UploadQueue,
    private val deriver: Derivation.Deriver,
    /** The tenant a signed-in person keeps a link in (*Keep*); null signed out. */
    private val tenant: () -> TenantSession?,
    private val language: () -> String,
    /** The player a guest's Memory plays its music with (#1034): the tenant's, or the app's own signed out. */
    private val music: () -> me.raddatz.veyq.mobile.audio.PageMusic? = { null },
) {
    private val _asking = MutableStateFlow<OpenLink?>(null)

    /** *Open a link*'s field while it is open. */
    val asking: StateFlow<OpenLink?> = _asking.asStateFlow()

    private val _screen = MutableStateFlow<GuestScreen?>(null)

    /** The link open as its guest, above everything; null while none is. */
    val screen: StateFlow<GuestScreen?> = _screen.asStateFlow()

    /** A guest view of [link], [password] handed in memory (a bookmark's): the core's model over this device's doors. */
    fun view(link: ReceivedLink.ShareLink, password: String? = null): GuestView = GuestView.open(
        BouncyCastlePrimitives, link, { block -> Network.client(block) }, store, sessions, language, password,
        queue, deriver, System::currentTimeMillis, music = music(),
    )

    /** Whether the link the field opened asked for its report (`&report`). */
    private var report = false

    /** *Open a link* asked: its field, empty. */
    fun ask() {
        report = false
        _asking.value = OpenLink { link, asked ->
            report = asked
            view(link)
        }
    }

    /**
     * A text another app shared (inbox #1015, ADR 0150 § S): *Open a link*'s field over whatever shows, its link checked
     * ([OpenLink.received]) and opened at once where it opens; where not, the field stays with its reason (and, for a
     * moderator's review, *Open in the browser*) — what a paste of the same text would have shown.
     */
    fun receive(text: String) {
        ask()
        _asking.value?.received(text)
        if (_asking.value?.state?.value?.canOpen == true) openAsked()
    }

    fun cancelAsk() {
        _asking.value = null
    }

    /** The field's link opened as its guest (the field closes; the text, which holds the key, is not kept). */
    fun openAsked() {
        val field = _asking.value ?: return
        val view = field.open() ?: return
        _asking.value = null
        show(GuestScreen(view, tenant(), reportAsked = report))
    }

    /** [screen] shown above everything and loaded; a link open before is let go. */
    fun show(screen: GuestScreen) {
        _screen.value?.leave()
        _screen.value = screen
        scope.launch(Dispatchers.IO) { screen.load() }
    }

    /** The open link let go (its question answered, or the person signed out under it): the screen goes. */
    fun closed(screen: GuestScreen) {
        if (!screen.state.value.left) screen.leave()
        if (_screen.value === screen) _screen.value = null
    }
}
