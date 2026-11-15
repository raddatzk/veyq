package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.PairingApi
import me.raddatz.veyq.mobile.api.apis.SetupApi
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import me.raddatz.veyq.mobile.api.models.PairingAnswerRequest
import me.raddatz.veyq.mobile.api.models.PairingCollectRequest
import me.raddatz.veyq.mobile.api.models.PairingDelegateRequest
import me.raddatz.veyq.mobile.api.models.PairingOpenRequest
import me.raddatz.veyq.mobile.api.models.PairingSealRequest
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.IdentityPublic
import me.raddatz.veyq.mobile.crypto.Pairing
import me.raddatz.veyq.mobile.crypto.PairingGrant
import me.raddatz.veyq.mobile.crypto.PairingMarker
import me.raddatz.veyq.mobile.crypto.PairingMarks
import me.raddatz.veyq.mobile.crypto.PairingPayload
import me.raddatz.veyq.mobile.crypto.PairingRefusal
import me.raddatz.veyq.mobile.crypto.PairingRefused
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.qr.QrDecoder
import me.raddatz.veyq.mobile.qr.QrEncoder
import me.raddatz.veyq.mobile.qr.QrFrame
import me.raddatz.veyq.mobile.qr.QrMatrix
import me.raddatz.veyq.mobile.qr.QrPng
import me.raddatz.veyq.mobile.qr.QrScan
import kotlin.concurrent.Volatile

/**
 * One device-linking ceremony (ADR 0059; `cr-app-device-linking` D4), in one of its four roles — the web's
 * `AddDevicePanel` (both trusted arms), `JoinDeviceCard` and `SignInWithCodeCard` (the new device's), call for call:
 *
 * | | Add a new device | Sign in with a QR code |
 * |---|---|---|
 * | trusted | [Role.SHOW_FOR_NEW_DEVICE]: shows a `veyqpair:` code, compares the number, confirms | [Role.SCAN_SIGN_IN_CODE]: scans a `veyqsignin:` code, shows six digits, confirms |
 * | new | [Role.SCAN_FROM_TRUSTED]: scans the trusted code, shows the number | [Role.SHOW_FOR_SIGN_IN]: shows a `veyqsignin:` code, takes the six digits typed |
 *
 * A UI observes [state] (or [watch]), draws the code with [matrix] or [png] — the code's text never leaves the core —,
 * feeds the camera into [scan], and calls [confirm] (the slide's end), [enterCode] (once) or [close]. It holds no rule.
 *
 * **The relay sees only the handle (§11).** The trusted device seals only to an answer whose MAC verifies under the
 * channel key, and the new device opens only an envelope whose MAC verifies; a refused MAC ends the ceremony
 * ([Refusal.REFUSED]) and is never retried in place (§6, uc.account.onboard-device E2) — starting over mints a new
 * secret. **The QR never carries a server (§4):** each side talks only to the [Relay] it was built over — the trusted
 * device's own server, the new device's sign-in form's.
 *
 * **Collecting spends the channel once it holds the grant**, whoever asks: the trusted device polls only until the
 * answer lands and never again (the web's `pairingAnswerPolling`); only the new device polls for the envelope.
 */
class DeviceLink private constructor(
    val role: Role,
    private val relay: Relay,
    private val pairing: Pairing,
    /** The trusted device's half: its account's grant and calls. Null on the new device. */
    private val granter: Granter?,
    /** The new device's tail: the grant spent into a signed-in account (the app's sign-in tail). Null on the trusted device. */
    private val receive: (suspend (PairingGrant) -> Unit)?,
    decode: suspend (QrFrame) -> String?,
    parent: CoroutineScope,
) {
    enum class Role { SHOW_FOR_NEW_DEVICE, SCAN_SIGN_IN_CODE, SCAN_FROM_TRUSTED, SHOW_FOR_SIGN_IN }

    enum class Step {
        /** The instance is asked whether it links devices, and — where this side shows the code — the channel opened. */
        OPENING,

        /** This side's code is on screen ([matrix], [png]). [Role.SHOW_FOR_SIGN_IN]: the six digits are typed here. */
        SHOWING,

        /** The camera reads ([scan]). */
        SCANNING,

        /** [Role.SCAN_SIGN_IN_CODE]: [Shown.code] is on screen for the new device to type; its answer is awaited. */
        CODE,

        /** The new device answered and waits for the grant ([Role.SCAN_FROM_TRUSTED]: [Shown.number] on screen). */
        WAITING,

        /** The trusted device: the answer verified; it is named and the slide confirms ([confirm]). */
        CONFIRMING,

        /** The trusted device: the session delegated and the grant sealed. */
        SEALING,

        /** The new device: the grant opened, the sign-in's tail running. */
        FINISHING,

        /** Trusted: sealed — the new device finishes. New: signed in. */
        DONE,

        /** Ended: [Shown.refusal] says why, or none where it was closed. */
        ENDED,
    }

    /** Why a ceremony ended. */
    enum class Refusal {
        /** The instance does not link devices (`core.device-linking` off). */
        OFF,

        /** The channel is unknown, expired or spent — every dead channel alike (uc.account.onboard-device E1). */
        GONE,

        /** A MAC did not verify: "this code was not answered by your device" (§11, E2). Never retried in place. */
        REFUSED,

        /** A step did not come through (the server unreachable, the session ended mid-grant, the sign-in's tail failed). */
        FAILED,
    }

    /** What a UI may add to the state's words. */
    enum class Hint {
        /** The camera saw the other entry's code: the other direction is the other entry (said with its name). */
        OTHER_DIRECTION,

        /** No answer after the first window, or a channel the server does not know: both devices must use the same server. */
        SAME_SERVER,
    }

    /**
     * What the screen shows: never the secret, the channel key, the grant or a token. [number] is the five digits both
     * screens compare in *add a new device* (the new device's from the start, the trusted device's once the answer
     * verified); [code] the six digits the trusted device shows in *sign in with a QR code*; [label] the name the new
     * device was given, once it is linked.
     */
    @Serializable
    data class Shown(
        val role: Role,
        val step: Step,
        val number: String? = null,
        val code: String? = null,
        val label: String? = null,
        val refusal: Refusal? = null,
        val hint: Hint? = null,
    )

    /** What the channel relays now: each value present once its step happened. */
    class Collected(val answer: String?, val envelope: String?)

    /** A dead channel (`404 pairing_channel_invalid`): unknown, expired, spent, not this account's — indistinguishable. */
    class Gone : Exception("pairing_channel_invalid")

    /** `422 capability_disabled`: the instance does not link devices. */
    class Off : Exception("capability_disabled")

    /** The server's pairing verbs, unauthenticated: what both sides read and the new device writes. */
    interface Relay {
        /** Whether the instance lists `core.device-linking`. */
        suspend fun capable(): Boolean
        suspend fun open(handle: String)
        suspend fun answer(handle: String, answer: String)
        suspend fun collect(handle: String): Collected

        /** The link is done with the relay. */
        fun close() {}
    }

    /** The trusted, unlocked account's half: what it grants and the two verbs that reach an account. */
    interface Granter {
        val relay: Relay
        val email: String

        /** A copy of the Master Key, the link's own: it is zeroed once sealed. */
        fun masterKey(): ByteArray

        /** This device's trust-state marks for the account (ADR 0028 Decision 7), or null where none could be read. */
        suspend fun marks(): PairingMarks?
        suspend fun delegate(handle: String): String
        suspend fun seal(handle: String, envelope: String)
    }

    private val _state = MutableStateFlow(Shown(role, Step.OPENING))
    val state: StateFlow<Shown> = _state.asStateFlow()
    val current: Shown get() = _state.value

    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (Shown) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    /** The code this side shows (its marker and secret), or null where it shows none: never leaves the core as text. */
    @Volatile
    private var payload: String? = null

    /** The channel's secret's two derivations. */
    @Volatile
    private var handle: String? = null
    @Volatile
    private var channelKey: ByteArray? = null

    /** The new device's single-use transport key (§2): minted once, its private half never leaves this link. */
    private val transport: Pair<IdentityPrivate, IdentityPublic>? =
        if (role == Role.SCAN_FROM_TRUSTED || role == Role.SHOW_FOR_SIGN_IN) pairing.mintTransportKey() else null

    /** The trusted device's: the key the grant is sealed to, the one whose number was compared or whose proof held. */
    @Volatile
    private var sealTo: IdentityPublic? = null

    /** [Role.SCAN_SIGN_IN_CODE]: the six digits this device drew (never derived from a key: the QR's author holds those). */
    @Volatile
    private var drawn: String? = null

    /** [Role.SCAN_SIGN_IN_CODE], [Role.SCAN_FROM_TRUSTED]: the camera's scan, the link's own (ADR 0141 decision 3). */
    val scan: QrScan<Unit>? =
        if (role == Role.SCAN_SIGN_IN_CODE || role == Role.SCAN_FROM_TRUSTED) QrScan(accepts = ::scanned, decode = decode, scope = scope) else null

    private val marker: PairingMarker = when (role) {
        Role.SHOW_FOR_NEW_DEVICE, Role.SCAN_FROM_TRUSTED -> PairingMarker.ADD_DEVICE
        Role.SCAN_SIGN_IN_CODE, Role.SHOW_FOR_SIGN_IN -> PairingMarker.SIGN_IN
    }

    init {
        scope.launch { begin() }
        scope.launch {
            delay(CHANNEL_MS)
            // the server's channel is gone by now; a link still waiting on the other device ends as it would
            if (current.step in WAITING_ON_OTHER || current.step == Step.SCANNING || current.step == Step.CONFIRMING) end(Refusal.GONE)
        }
    }

    private suspend fun begin() {
        val capable = try {
            relay.capable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return end(Refusal.FAILED)
        }
        if (!capable) return end(Refusal.OFF)
        when (role) {
            Role.SHOW_FOR_NEW_DEVICE, Role.SHOW_FOR_SIGN_IN -> {
                val secret = pairing.mintSecret()
                try {
                    derive(secret)
                    if (!step { relay.open(handle!!) }) return
                    payload = PairingPayload.of(secret, marker)
                } finally {
                    secret.fill(0)
                }
                move(Step.OPENING) { it.copy(step = Step.SHOWING) }
                if (role == Role.SHOW_FOR_NEW_DEVICE) awaitAnswer() else hintLater(Step.SHOWING)
            }
            Role.SCAN_SIGN_IN_CODE, Role.SCAN_FROM_TRUSTED -> move(Step.OPENING) { it.copy(step = Step.SCANNING) }
        }
    }

    private fun derive(secret: ByteArray) {
        handle = b64(pairing.handle(secret))
        channelKey = pairing.channelKey(secret)
    }

    /**
     * The scan's parser: a code of this direction is taken (and the link goes on with it), one of the other direction is
     * skipped with [Hint.OTHER_DIRECTION] — so a mis-scan never burns the write-once answer slot of the real ceremony —,
     * anything else skipped silently.
     */
    private fun scanned(text: String): Unit? {
        if (current.step != Step.SCANNING) return null
        val secret = PairingPayload.parse(text, marker)
        if (secret == null) {
            val other = if (marker == PairingMarker.ADD_DEVICE) PairingMarker.SIGN_IN else PairingMarker.ADD_DEVICE
            PairingPayload.parse(text, other)?.let { it.fill(0); _state.update { s -> s.copy(hint = Hint.OTHER_DIRECTION) } }
            return null
        }
        derive(secret)
        secret.fill(0)
        if (role == Role.SCAN_SIGN_IN_CODE) {
            val code = pairing.drawCode()
            drawn = code
            if (!advance(Step.SCANNING) { it.copy(step = Step.CODE, code = code, hint = null) }) return null
            scope.launch { awaitAnswer() }
        } else {
            val pub = transport!!.second
            val number = pairing.checkNumber(pub)
            val answer = withKey { pairing.transportAnswer(it, pub) } ?: return null
            if (!advance(Step.SCANNING) { it.copy(step = Step.WAITING, number = number, hint = null) }) return null
            scope.launch { answerThenAwait(answer) }
        }
        return Unit
    }

    /** The QR of this side's code, or null where it shows none (yet). */
    fun matrix(): QrMatrix? = payload?.let { QrEncoder.encode(it) }

    /** This side's code as a PNG about [px] pixels wide (a whole number of pixels per module), or null where it shows none. */
    fun png(px: Int): ByteArray? {
        val m = matrix() ?: return null
        // the drawn grid's side, quiet zone included: the image stays within [px] (the bridge's `drawnCode` rule)
        val scale = maxOf(1, minOf(px, QrPng.MAX_SIDE) / m.side)
        return QrPng.write(m, scale)
    }

    /**
     * [Role.SHOW_FOR_SIGN_IN]: the six digits the trusted device shows, sent once as the channel's one answer (key and
     * proof, §6). False — nothing sent — for anything but six digits, outside [Step.SHOWING], or after the one send.
     */
    fun enterCode(code: String): Boolean {
        if (role != Role.SHOW_FOR_SIGN_IN || !SIX_DIGITS.matches(code)) return false
        // the one send is the one move out of SHOWING, made atomically before the post: a second call — or one racing
        // this — finds the state moved and sends nothing (the server's answer slot is write-once)
        if (current.step != Step.SHOWING) return false
        val answer = withKey { pairing.signInAnswer(it, transport!!.second, code) } ?: return false
        if (!advance(Step.SHOWING) { it.copy(step = Step.WAITING, hint = null) }) return false
        scope.launch { answerThenAwait(answer) }
        return true
    }

    /**
     * The trusted device's slide reached its end (§7): the session delegated, then the grant sealed to the key whose
     * number was compared or whose proof held, naming the new device [label]. False outside [Step.CONFIRMING], for a
     * blank name, and for every call but the one that moved the link on: a double tap delegates once (the server allows
     * one delegation; a second's `404` would end the ceremony mid-seal). Declining is [close]: nothing delegated, nothing
     * sealed, the channel left to expire.
     */
    fun confirm(label: String): Boolean {
        val name = label.trim()
        val g = granter ?: return false
        if (name.isEmpty()) return false
        val pub = sealTo ?: return false
        // the key copied before the move: a close after it zeroes the link's key, never this call's copy
        val key = channelKey?.copyOf() ?: return false
        if (!advance(Step.CONFIRMING) { it.copy(step = Step.SEALING) }) {
            key.fill(0)
            return false
        }
        scope.launch {
            try {
                val h = handle!!
                val token = try {
                    g.delegate(h)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch end(refusalOf(e))
                }
                val envelope = try {
                    val grant = PairingGrant(g.email, g.masterKey(), token, name, runCatching { g.marks() }.getOrNull())
                    try {
                        pairing.sealGrant(key, pub, grant)
                    } finally {
                        grant.wipe()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch end(Refusal.FAILED)
                }
                if (!step { g.seal(h, envelope) }) return@launch
                move(Step.SEALING) { it.copy(step = Step.DONE) }
                // terminal here: this device cannot see the new one arrive without spending its grant (see [awaitAnswer])
                finish()
            } finally {
                key.fill(0)
            }
        }
        return true
    }

    /** Ends the ceremony: the polls stop, the scan closes, nothing more is sent. Called when its screen goes. */
    fun close() {
        _state.update { if (it.step == Step.DONE || it.step == Step.ENDED) it else it.copy(step = Step.ENDED) }
        finish()
    }

    private fun finish() {
        scan?.close()
        channelKey?.fill(0)
        transport?.first?.let { it.kemSeed.fill(0); it.signSeed.fill(0) }
        payload = null
        job.cancel()
        relay.close()
    }

    /** The trusted device's poll for the answer — and only until it lands: never again after (the collect would spend the grant). */
    private suspend fun awaitAnswer() {
        hintLater(if (role == Role.SHOW_FOR_NEW_DEVICE) Step.SHOWING else Step.CODE)
        val answer = poll { it.answer } ?: return
        try {
            sealTo = withKey { key ->
                if (role == Role.SHOW_FOR_NEW_DEVICE) pairing.readTransportAnswer(key, answer)
                else pairing.readSignInAnswer(key, drawn!!, answer)
            } ?: return
        } catch (e: PairingRefused) {
            sealTo = null
            return end(if (e.reason == PairingRefusal.ANSWER_REFUSED) Refusal.REFUSED else Refusal.GONE)
        }
        val number = if (role == Role.SHOW_FOR_NEW_DEVICE) pairing.checkNumber(sealTo!!) else null
        _state.update { if (it.step in WAITING_ON_OTHER) it.copy(step = Step.CONFIRMING, number = number ?: it.number, hint = null) else it }
    }

    /** The new device's one answer, then its poll for the grant, then the sign-in's tail. */
    private suspend fun answerThenAwait(answer: String) {
        try {
            relay.answer(handle!!, answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Gone) {
            // a channel this server does not know: expired, spent — or opened on another server
            return end(Refusal.GONE, Hint.SAME_SERVER)
        } catch (e: Exception) {
            return end(refusalOf(e))
        }
        hintLater(Step.WAITING)
        val envelope = poll { it.envelope } ?: return
        // the key and the transport key copied before the move: a close after it zeroes the link's, never these
        val key = channelKey?.copyOf() ?: return
        val t = transport!!.first
        val priv = IdentityPrivate(t.kemSeed.copyOf(), t.signSeed.copyOf())
        val grant = try {
            // a close between the poll and here (the state moved to ENDED) opens nothing and spends nothing
            if (!advance(Step.WAITING) { it.copy(step = Step.FINISHING, hint = null) }) return
            pairing.openGrant(key, priv, envelope)
        } catch (e: PairingRefused) {
            return end(if (e.reason == PairingRefusal.ENVELOPE_REFUSED) Refusal.REFUSED else Refusal.FAILED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a MAC that verified over a seal that does not open: no grant, and no state left in FINISHING
            return end(Refusal.FAILED)
        } finally {
            key.fill(0)
            priv.kemSeed.fill(0)
            priv.signSeed.fill(0)
        }
        val label = grant.label
        try {
            // the token is spent here: the tail runs to its end even when the screen closes meanwhile, so a spent token's
            // successor is always kept (a close in FINISHING ends the screen, not the sign-in)
            withContext(NonCancellable) { receive!!(grant) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return end(if ((e as? SignInFailure)?.reason == SignInFailure.Reason.SESSION_ENDED) Refusal.GONE else Refusal.FAILED)
        } finally {
            grant.wipe()
        }
        move(Step.FINISHING) { it.copy(step = Step.DONE, label = label) }
        finish()
    }

    /**
     * Polls the channel every [POLL_MS] until [take] finds what is awaited; null when the channel is gone (the link has
     * ended) or the instance stopped linking. A failure that is not an answer (the network on one tick) is left alone:
     * the next tick catches up.
     */
    private suspend fun poll(take: (Collected) -> String?): String? {
        while (true) {
            try {
                take(relay.collect(handle!!))?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Gone) {
                // the trusted device that scanned asks a server that never had the channel: another server, or expired
                end(Refusal.GONE, if (role == Role.SCAN_SIGN_IN_CODE && current.step == Step.CODE) Hint.SAME_SERVER else current.hint)
                return null
            } catch (e: Off) {
                end(Refusal.OFF)
                return null
            } catch (e: Exception) {
                // one dropped tick is not a dead channel
            }
            delay(POLL_MS)
        }
    }

    /** After the first window in [step] without the other device, the hint that both must use the same server. */
    private fun hintLater(step: Step) {
        scope.launch {
            delay(HINT_AFTER_MS)
            _state.update { if (it.step == step && it.hint == null) it.copy(hint = Hint.SAME_SERVER) else it }
        }
    }

    /** One call whose failure ends the link; true when it came through. */
    private suspend fun step(call: suspend () -> Unit): Boolean = try {
        call()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        end(refusalOf(e))
        false
    }

    private fun refusalOf(e: Exception): Refusal = when (e) {
        is Gone -> Refusal.GONE
        is Off -> Refusal.OFF
        else -> Refusal.FAILED
    }

    /**
     * The one move out of [from], atomic: true for the caller that made it, false where the link was elsewhere or another
     * call moved it first — what makes a send, a confirmation and the grant's opening happen once.
     */
    private fun advance(from: Step, next: (Shown) -> Shown): Boolean {
        while (true) {
            val before = _state.value
            if (before.step != from) return false
            if (_state.compareAndSet(before, next(before))) return true
        }
    }

    /** [use] over a copy of the channel key, zeroed after; null where the link holds none. A close meanwhile zeroes the
     *  link's key, never the copy in use. */
    private inline fun <R> withKey(use: (ByteArray) -> R): R? {
        val key = channelKey?.copyOf() ?: return null
        try {
            return use(key)
        } finally {
            key.fill(0)
        }
    }

    /** The secrets this link holds (the channel key, the transport key's seeds), for a test that they end zeroed. */
    internal fun heldSecrets(): List<ByteArray> = listOfNotNull(channelKey, transport?.first?.kemSeed, transport?.first?.signSeed)

    /** To the next state, only from [from]: a link closed or ended meanwhile stays where it is. */
    private fun move(from: Step, next: (Shown) -> Shown) = _state.update { if (it.step == from) next(it) else it }

    private fun end(refusal: Refusal, hint: Hint? = current.hint) {
        _state.update { if (it.step == Step.DONE || it.step == Step.ENDED) it else it.copy(step = Step.ENDED, refusal = refusal, hint = hint) }
        finish()
    }

    companion object {
        /** The web's poll interval for both loops. */
        const val POLL_MS = 2_000L

        /** The server's channel lifetime (`veyq.pairing.channel.ttl`): a link still waiting then ends as gone. */
        const val CHANNEL_MS = 10L * 60 * 1000

        /** The first window: past it, a link waiting on the other device says both must use the same server. */
        const val HINT_AFTER_MS = 30_000L

        private val SIX_DIGITS = Regex("^[0-9]{6}$")
        private val WAITING_ON_OTHER = setOf(Step.SHOWING, Step.CODE, Step.WAITING)

        /** The trusted device, *add a new device*: it shows the code, the new device scans. */
        fun showForNewDevice(
            p: Primitives,
            granter: Granter,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ) = DeviceLink(Role.SHOW_FOR_NEW_DEVICE, granter.relay, Pairing(p), granter, null, { null }, scope)

        /** The trusted device, *sign in with a QR code*: it scans the new device's code. */
        fun scanSignInCode(
            p: Primitives,
            granter: Granter,
            decode: suspend (QrFrame) -> String? = { QrDecoder.decode(it) },
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ) = DeviceLink(Role.SCAN_SIGN_IN_CODE, granter.relay, Pairing(p), granter, null, decode, scope)

        /** The new device, *add a new device*, over the sign-in form's server ([relay]); [receive] is the sign-in's tail. */
        fun scanFromTrusted(
            p: Primitives,
            relay: Relay,
            receive: suspend (PairingGrant) -> Unit,
            decode: suspend (QrFrame) -> String? = { QrDecoder.decode(it) },
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ) = DeviceLink(Role.SCAN_FROM_TRUSTED, relay, Pairing(p), null, receive, decode, scope)

        /** The new device, *sign in with a QR code*, over the sign-in form's server ([relay]); [receive] is the sign-in's tail. */
        fun showForSignIn(
            p: Primitives,
            relay: Relay,
            receive: suspend (PairingGrant) -> Unit,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ) = DeviceLink(Role.SHOW_FOR_SIGN_IN, relay, Pairing(p), null, receive, { null }, scope)

        /** The trusted device's *add a new device* over a signed-in [account]. */
        fun showForNewDevice(account: VeyqAccount): DeviceLink = showForNewDevice(account.primitives, AccountGranter(account))

        /** The trusted device's *sign in with a QR code* over a signed-in [account]; [decode] reads its camera's frames. */
        fun scanSignInCode(
            account: VeyqAccount,
            decode: suspend (QrFrame) -> String? = { QrDecoder.decode(it) },
        ): DeviceLink = scanSignInCode(account.primitives, AccountGranter(account), decode)
    }
}

/**
 * [DeviceLink.Relay] over the generated client on [base]: `404` is [DeviceLink.Gone], `422 capability_disabled`
 * [DeviceLink.Off], any other refusal an exception. [owns] closes [http] with the link (a new device's own client).
 */
class HttpRelay(private val base: String, private val http: HttpClient, private val owns: Boolean) : DeviceLink.Relay {
    private val api get() = PairingApi(base, http)

    override suspend fun capable(): Boolean {
        val r = SetupApi(base, http).getSetupStatus()
        if (!r.success) throw IllegalStateException("setup ${r.status}")
        return r.body().capabilities.any { it.id == AccountSettings.DEVICE_LINKING }
    }

    override suspend fun open(handle: String) = done(api.openPairingChannel(PairingOpenRequest(handle)))
    override suspend fun answer(handle: String, answer: String) = done(api.answerPairingChannel(PairingAnswerRequest(handle, answer)))
    override suspend fun collect(handle: String): DeviceLink.Collected {
        val r = api.collectPairingChannel(PairingCollectRequest(handle))
        refused(r)
        return r.body().let { DeviceLink.Collected(it.answer, it.envelope) }
    }

    override fun close() {
        if (owns) http.close()
    }

    companion object {
        /** A relay for the new device: the platform's client with JSON, no bearer, closed with the link. */
        fun of(server: String, client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient): HttpRelay =
            HttpRelay(server.trimEnd('/'), client {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            }, owns = true)

        internal suspend fun done(r: HttpResponse<Unit>) = refused(r)

        internal suspend fun refused(r: HttpResponse<*>) {
            if (r.success) return
            if (r.status == 404) throw DeviceLink.Gone()
            if (r.status == 422 && code(r) == "capability_disabled") throw DeviceLink.Off()
            throw IllegalStateException("pairing ${r.status}")
        }

        private suspend fun code(r: HttpResponse<*>): String? = runCatching {
            (Json.parseToJsonElement(r.response.bodyAsText()) as? JsonObject)?.get("code")?.jsonPrimitive?.content
        }.getOrNull()
    }
}

/** [DeviceLink.Granter] over a signed-in [VeyqAccount]: its own server, its bearer refreshed once on a `401`. */
internal class AccountGranter(private val account: VeyqAccount) : DeviceLink.Granter {
    override val relay: DeviceLink.Relay = HttpRelay(account.server, account.http, owns = false)
    override val email: String get() = account.email
    override fun masterKey(): ByteArray = account.masterKey.copyOf()
    override suspend fun marks(): PairingMarks = account.linkMarks()
    override suspend fun delegate(handle: String): String {
        val r = account.respond { PairingApi(account.server, account.http).delegatePairingSession(PairingDelegateRequest(handle)) }
        HttpRelay.refused(r)
        return r.body().refreshToken
    }
    override suspend fun seal(handle: String, envelope: String) =
        HttpRelay.done(account.respond { PairingApi(account.server, account.http).sealPairingChannel(PairingSealRequest(handle, envelope)) })
}
