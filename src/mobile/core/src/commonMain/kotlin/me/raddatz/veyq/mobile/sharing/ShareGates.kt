package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.raddatz.veyq.mobile.api.apis.SharingApi
import me.raddatz.veyq.mobile.api.models.CreateShareIdentityRequest
import me.raddatz.veyq.mobile.api.models.ShareGuestNotice
import me.raddatz.veyq.mobile.api.models.ShareTerms
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.session.DeviceStore

/**
 * The gates every link passes and the guest behind them (ADR 0150 decision 2; the web's `shareGates.ts`, its order and
 * its rules):
 *
 *   the instance -> password -> the share's tenant's terms -> the note's acceptance -> content,
 *
 * each decided on a full resolve ([load]) **before** the state is [State.Ready], so no content is shown ahead of a gate.
 * The instance comes first because the app is a foreign client to every instance (decision 2, [ShareInstance]).
 *
 * **The key** (decision 3): `K_share` comes from [link] into this model and its head, and nowhere else — no store, no
 * request (a write link's `X-Share-Write` carries the token derived from it), no state this model publishes ([State]
 * names the root and its kind only). [close] zeroes it.
 *
 * **The accountless terms gate** (uc.sharing.share-accept, ADR 0035 § 3): what was accepted is kept on this device only, in
 * [store] under [GuestNames.terms] (per tenant of the share's instance, the pairs of revisions, the latest two); the server
 * records nothing. Operational, not enforcement, and it fails open: terms that do not read are no terms.
 * **The note's gate** (ADR 0036 point 2): sealed inside the note, so only an opened note asks; remembered in [store] under
 * [GuestNames.note] (by the digest of its plaintext: an edited note asks again) and for this visit in memory.
 * **The guest's session** (a write link's identity) through [sessions] under [GuestNames.session], never in [store].
 */
class ShareGates(
    /** The link as received: its key lives here until [close]. */
    val link: ReceivedLink.ShareLink,
    private val client: GuestClient,
    private val store: DeviceStore,
    private val sessions: GuestSessions,
    /** The viewer's language, for the terms and the note. */
    private val locale: () -> String,
    /** A password handed in memory (a bookmark's, ADR 0120 decision 5): entered once when the link asks for one. */
    private var handedPassword: String? = null,
    /** Whether a head's root kind has a guest door here (`AppKinds.guestDoor`): one without shows the unavailable state. */
    private val renders: (ShareHead) -> Boolean = { it.rootKind != null },
) {
    sealed class State {
        object Loading : State()
        /** The app does not open this instance's links (decision 8): with *Open in the browser*. */
        data class Refused(val refusal: ShareInstance.Refusal) : State()
        /** The password form; [wrong]: the last one was refused. */
        data class Locked(val wrong: Boolean) : State()
        /** The tenant's terms to accept: the space's text, then the operator's section and the record's pointer. */
        data class Terms(val markdown: String, val operatorSection: String, val record: String) : State()
        /** The note to accept before the share opens. */
        data class Note(val markdown: String) : State()
        /** Behind the gates: the root's name, the role and the root's kind; the note as a banner when it asked nothing. */
        data class Ready(val name: String, val role: String, val rootKind: String?, val note: String) : State()
        /** Revoked, expired, re-keyed away, a key that opens nothing, a kind not shown here; [unreachable]: no answer. */
        data class Unavailable(val unreachable: Boolean) : State()
    }

    private val names = GuestNames(client.suite.p)
    private val kShare: ByteArray = link.key()
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Deriving the password's token (Argon2id) and resolving again. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var pwSalt: String = ""
    /** The password that opened the link, in memory only: what *Keep* offers to keep with it (a bookmark's, sealed). */
    internal var enteredPassword: String? = null
        private set
    private var pwToken: String? = null
    /** The opened head behind the gates (internal: it holds `K_share`); null until [load] passed the password. */
    @PlatformApi
    @HiddenFromObjC
    var head: ShareHead? = null
        private set
    private var terms: ShareTerms? = null
    private val acceptedNotes = HashSet<String>()
    private var closed = false

    /** The guest's session of this link on this device (a write link's identity), as the platform's store has it. */
    private val _guest = MutableStateFlow(readSession())
    val guest: StateFlow<GuestSession?> = _guest.asStateFlow()

    /** The password token the gated reads present (internal: the guest doors' calls carry it). */
    internal val passwordToken: String? get() = pwToken

    /** `X-Share-Write`: the token derived from `K_share` (ADR 0076) that proves the guest holds the whole link. */
    internal fun writeToken(): String = b64(client.suite.shareWriteToken(kShare, link.shareId))

    /** The guest's client, for its kind's door. */
    internal val guestClient: GuestClient get() = client

    /** Read the instance, resolve, and decide the gates (in that order). */
    suspend fun load() {
        check(!closed) { "closed" }
        if (_state.value !is State.Locked) {
            val refusal = ShareInstance.decide(ShareInstance.read(client))
            if (refusal != null) {
                _state.value = State.Refused(refusal)
                return
            }
        }
        val resolved = try {
            ShareResolve.resolve(client, link.shareId, kShare, pwToken)
        } catch (e: ShareRefused.PasswordRequired) {
            pwSalt = e.pwSalt
            _state.value = State.Locked(wrong = false)
            handedPassword?.let { handed ->
                handedPassword = null // entered once, as the reader would
                submitPassword(handed)
            }
            return
        } catch (e: ShareRefused.PasswordIncorrect) {
            _state.value = State.Locked(wrong = true)
            return
        } catch (e: ShareRefused.Unavailable) {
            _state.value = State.Unavailable(e.unreachable)
            return
        }
        if (!renders(resolved)) {
            resolved.kShare.fill(0)
            _state.value = State.Unavailable(false)
            return
        }
        // the terms, then the note — both decided before the content is offered
        terms = ShareResolve.terms(client, link.shareId)
        // the head before is kept, not zeroed: the page a poll's flip left open still holds it until the view rebuilds
        head?.let { retired += it }
        head = resolved
        advance()
    }

    /** Heads a [reresolve] replaced: still held by the pages opened before it, zeroed at [close]. */
    private val retired = mutableListOf<ShareHead>()

    /**
     * The link resolved anew behind the gates it passed (a guest's write shown, the tree as it stands now; the web's
     * `reresolve`): the open head replaced, the one before kept for the pages that still hold it until [close]. False where
     * the gates are not passed or it no longer resolves (the state is then left as it was; the next [load] tells why).
     */
    internal suspend fun reresolve(): Boolean {
        if (closed || _state.value !is State.Ready) return false
        val resolved = try {
            ShareResolve.resolve(client, link.shareId, kShare, pwToken)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        head?.let { retired += it }
        head = resolved
        return true
    }

    /** What one tick of the live poll found ([poll]). */
    internal enum class Polled {
        /** A `304` (or a `200` that serves exactly what is open): nothing read, nothing changed. */
        SAME,
        /** The link serves something else now: [head] is the new one (the one before kept until [close]). */
        MOVED,
        /** The link is gone, its password changed or its root went: [state] left [State.Ready] (the page gives way to it). */
        FLIPPED,
    }

    /**
     * One tick of the live poll (the web's `shareGates.poll`, inbox #1017): the link re-resolved CONDITIONALLY on the tag of
     * the open head ([ShareResolve.poll]) — a `304` costs nothing. A link GONE flips the state as a [load] would: the
     * non-enumerable `404` to [State.Unavailable], a changed password to the password form ([State.Locked], wrong where
     * the old token is refused), a root no door renders (trashed from under the link) to [State.Unavailable]. A failure
     * that says nothing about the link — the network, a `429`, a `5xx` — is thrown to the poll (it backs off) and never
     * reaches the state. The terms and the note are decided at [load], as the web's are: a poll re-gates neither, it only
     * carries the new name and banner. Outside [State.Ready], or closed, it asks nothing ([Polled.SAME]).
     */
    internal suspend fun poll(): Polled {
        if (closed || _state.value !is State.Ready) return Polled.SAME
        val current = head ?: return Polled.SAME
        val polled = try {
            ShareResolve.poll(client, link.shareId, kShare, pwToken, current.etag)
        } catch (e: ShareRefused.PasswordRequired) {
            if (closed || head !== current) return Polled.SAME
            pwSalt = e.pwSalt
            _state.value = State.Locked(wrong = false)
            // a password handed in memory and not entered yet (a bookmark's): entered once, as the reader would
            handedPassword?.let { handed ->
                handedPassword = null
                submitPassword(handed)
            }
            return Polled.FLIPPED
        } catch (e: ShareRefused.PasswordIncorrect) {
            if (closed || head !== current) return Polled.SAME
            _state.value = State.Locked(wrong = true)
            return Polled.FLIPPED
        } catch (e: ShareRefused.Unavailable) {
            if (closed || head !== current) return Polled.SAME
            _state.value = State.Unavailable(e.unreachable)
            return Polled.FLIPPED
        } ?: return Polled.SAME
        if (closed || head !== current) {
            polled.kShare.fill(0) // a load or a re-resolve ran meanwhile: what this read saw may predate it
            return Polled.SAME
        }
        if (!renders(polled)) {
            polled.kShare.fill(0)
            _state.value = State.Unavailable(false)
            return Polled.FLIPPED
        }
        // a server that names no tag answers every poll in full: the served answer compared, so only a change moves the page
        if (polled.share == current.share && polled.etag == current.etag) {
            polled.kShare.fill(0)
            return Polled.SAME
        }
        retired += current
        head = polled
        if (polled.share == current.share) return Polled.SAME // the tag alone moved: kept for the next poll
        _state.value = ready(polled)
        return Polled.MOVED
    }

    /** The guest entered the password: its token derived here, then the link resolved again with it. */
    suspend fun submitPassword(password: String) {
        if (password.isEmpty() || _busy.value) return
        _busy.value = true
        try {
            pwToken = ShareResolve.passwordToken(client, password, pwSalt)
            load()
            if (_state.value !is State.Locked) enteredPassword = password
        } finally {
            _busy.value = false
        }
    }

    /** The terms accepted: kept on this device for the tenant (the latest two pairs), the gate passed. No server call. */
    fun acceptTerms() {
        if (_state.value !is State.Terms) return // only the gate on screen is accepted
        val t = terms ?: return
        val key = pairKey(t)
        val name = names.terms(link.origin, t.tenantId)
        val kept = accepted(name).filter { it != key && PAIR.matches(it) }
        runCatching { store.write(name, (kept + key).takeLast(KEEP).joinToString(" ")) }
        acceptedPairs += key
        advance()
    }

    /** The note accepted: remembered on this device (if it can) and for this visit. No server call. */
    fun acceptNote() {
        if (_state.value !is State.Note) return // a note is never accepted ahead of the terms
        val digest = head?.noteAcceptance ?: return
        runCatching { store.write(names.note(link.origin, link.shareId, digest), "1") }
        acceptedNotes += digest
        advance()
    }

    private val acceptedPairs = HashSet<String>()

    private fun accepted(name: String): List<String> = runCatching { store.read(name) }.getOrNull().orEmpty().split(' ').filter { it.isNotEmpty() }

    private fun pairKey(t: ShareTerms) = "${t.termsRevision}:${t.operatorSection.revision}"

    /** The next gate the open head asks for, or the content. */
    private fun advance() {
        val h = head ?: return
        val t = terms
        val lang = locale()
        if (t != null && t.termsRevision > 0) {
            val key = pairKey(t)
            if (key !in acceptedPairs && key !in accepted(names.terms(link.origin, t.tenantId))) {
                _state.value = State.Terms(
                    ShareResolve.pickLocaleText(ShareResolve.localeMapOf(t.terms?.de, t.terms?.en), lang),
                    ShareResolve.pickLocaleText(ShareResolve.localeMapOf(t.operatorSection.text.de, t.operatorSection.text.en), lang),
                    ShareResolve.pickLocaleText(ShareResolve.localeMapOf(t.operatorSection.record.de, t.operatorSection.record.en), lang),
                )
                return
            }
        }
        val digest = h.noteAcceptance
        if (digest != null && digest !in acceptedNotes && runCatching { store.read(names.note(link.origin, link.shareId, digest)) }.getOrNull() != "1") {
            _state.value = State.Note(ShareResolve.pickLocaleText(h.noteByLocale, lang))
            return
        }
        _state.value = ready(h)
    }

    /** The content's state over head [h]: its name, role and root kind, the note as the banner where it asks nothing. */
    private fun ready(h: ShareHead): State.Ready {
        // a note that asked to be accepted is not repeated as the page's banner: the guest got past its gate
        val banner = if (h.noteAcceptance != null) "" else ShareResolve.pickLocaleText(h.noteByLocale, locale())
        return State.Ready(h.name, h.role, h.rootKind, banner)
    }

    // --- the guest's session (a write link's identity) ------------------------------------------------------------------

    private fun sessionName() = names.session(link.origin, link.shareId)

    private fun readSession(): GuestSession? = GuestSessionJson.decode(runCatching { sessions.read(sessionName()) }.getOrNull())

    /** Save the guest's [session] for this link: the platform's credential store, never [store]. Throws where it refuses. */
    internal fun saveSession(session: GuestSession) {
        sessions.write(sessionName(), GuestSessionJson.encode(session))
        _guest.value = session
    }

    /** *Not you?*: forget this device's session of the link; the next visitor names itself afresh. */
    fun forgetGuest() {
        runCatching { sessions.remove(sessionName()) }
        _guest.value = null
    }

    /**
     * Name the guest of a READ_WRITE link (the web's `nameShareGuest`): its name sealed under the share root's key, bound to
     * a client-minted identity id, an anonymous identity created with `X-Share-Write` and the password token, and saved as
     * the link's one session. A `stale_epoch` / `node_rekey_pending` refusal resolves the link again and names it once more.
     */
    suspend fun nameGuest(displayName: String): GuestSession {
        val h = head ?: error("share_not_ready")
        val created = try {
            createIdentity(h, displayName)
        } catch (e: EpochRefused) {
            load()
            createIdentity(head ?: error("share_not_ready"), displayName)
        }
        val session = created.copy(displayName = displayName)
        saveSession(session)
        return session
    }

    private class EpochRefused : Exception("epoch")

    private suspend fun createIdentity(h: ShareHead, displayName: String): GuestSession {
        val rootKey = GuestTree.openRoot(client.suite, h)
        try {
            val identityId = client.uuid()
            val sealed = client.suite.sealField(GuestTree.identityName(h.scopeId, h.shareId, identityId), rootKey, utf8(displayName))
            val r = SharingApi(client.origin, client.http).createShareIdentity(
                h.shareId, CreateShareIdentityRequest(nodeEpoch = h.rootEpoch, identityId = identityId, sealedDisplayName = b64(sealed)),
                pwToken, writeToken(),
            )
            if (r.status == 409) throw EpochRefused()
            check(r.success) { "share_identity_refused ${r.status}" }
            return GuestSession(identityId, r.body().contributorToken)
        } finally {
            rootKey.fill(0)
        }
    }

    /** The measures on this guest's uploads (ADR 0093 §§ 7, 12), or null where none were taken or it did not read. */
    suspend fun guestNotice(): ShareGuestNotice? {
        val token = _guest.value?.contributorToken ?: return null
        return runCatching {
            val r = SharingApi(client.origin, client.http).getShareGuestNotice(link.shareId, token)
            if (r.success) r.body() else null
        }.getOrNull()
    }

    /**
     * The open link let go: its key zeroed, its client closed, the guest's session no longer held here (the credential
     * store's entry stays, ADR 0150 decision 3). A closed model loads nothing.
     */
    fun close() {
        if (closed) return
        closed = true
        _guest.value = null
        kShare.fill(0)
        head?.kShare?.fill(0)
        retired.forEach { it.kShare.fill(0) }
        retired.clear()
        head = null
        enteredPassword = null
        client.close()
    }

    private companion object {
        val PAIR = Regex("^\\d+:\\d+$")
        const val KEEP = 2
    }
}
