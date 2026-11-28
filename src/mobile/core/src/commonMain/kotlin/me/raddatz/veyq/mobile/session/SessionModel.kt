package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.account.KitImport
import me.raddatz.veyq.mobile.account.KitPictures
import me.raddatz.veyq.mobile.crypto.EmergencyKit
import me.raddatz.veyq.mobile.crypto.PairingGrant
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Connections
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.qr.QrDecoder
import me.raddatz.veyq.mobile.qr.QrFrame
import me.raddatz.veyq.mobile.qr.QrScan
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * The app's way in, for every platform (ADR 0123 decision 8, ADR 0161): where it opens — every account the person stays
 * signed in to resumes after one authentication; with none, the most recent password-trusted account asks for its
 * password; with no trusted account, the sign-in —, the full sign-in with this device's password trust after it, the
 * offer to stay signed in that follows a sign-in ([Offer]), the unlock, the switcher, a server added, a sign-out and
 * what becomes current after it, and "Manage servers"' list with each server's trust and its change.
 *
 * A UI observes [gate] (or [watch]) and draws the sign-in, the unlock or — once [Gate.Signed] — its shell over the
 * tenant an action returned [Opened]; it holds no account logic of its own. [A] is the account, [T] an opened tenant:
 * [accounts] builds the model over the real ones, a test over its own.
 *
 * An Emergency Kit read at the sign-in ([readKit], [takeKit]) fills its email and server into [Gate.SignIn] and leaves
 * its Secret Key **here**, in the model alone (en.account.key-model § The Emergency Kit): the state, the bridge and every
 * UI know only that a kit's key is held ([Gate.SignIn.kit]). The key is held exactly as long as the gate shows a kit —
 * [clearKit], a new kit, a typed key, Cancel and every other way out of the sign-in, and a sign-in that succeeded end
 * it, its bytes zeroed; a sign-in that failed keeps it.
 */
class SessionModel<A : Connection, T : Any>(
    private val door: Door<A, T>,
    private val hidden: Hidden,
    /** The share extension (ADR 0138): it resumes and unlocks, but never signs in — the app does. */
    private val signInElsewhere: Boolean = false,
    /** The viewer's language (the platform's words'), in which the terms gate shows a tenant's terms ([Gate.Signed.terms]). */
    private val language: () -> String = { "en" },
) {
    /** What the model does with an account, the platform's suite and HTTP client behind it. */
    interface Door<A : Connection, T : Any> {
        /** The process started: what a crash left half-done on this device is finished (an offline store's wipe). */
        fun started() {}

        /**
         * The terms of the tenant [session] opened (uc.account.accept-terms, [MemberTerms.read]), or null where they did not
         * read — the gate then fails open, as the web's (ADR 0035 § The apps' gate).
         */
        suspend fun terms(session: T): MemberTerms.Read? = null

        /** The member's acceptance of the pair [revision], [operatorRevision] in [session]'s tenant ([MemberTerms.accept]). */
        suspend fun acceptTerms(session: T, revision: Int, operatorRevision: Int): MemberTerms.Answer = MemberTerms.Answer.FAILED

        /** The platform's suite: what a kit's read checks its Secret Key with ([KitImport], [EmergencyKit]). */
        val primitives: Primitives

        /** The accounts this device trusts, most recent first. */
        fun trusted(): List<TrustedDevice>
        suspend fun signIn(server: String, email: String, password: String, secretKey: String): A

        /** The same sign-in with the Secret Key's 32 bytes (a kit's): [secretKey] is this call's, which zeroes it. */
        suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): A
        suspend fun unlockOnDevice(record: TrustedDevice, password: String): A
        suspend fun resume(record: TrustedDevice): A

        /** This device trusted with [account] by the password after a sign-in (DEVICE); never fails the sign-in. */
        suspend fun trust(account: A, password: String)

        /**
         * Staying signed in with [account] at [level] (a DEVICE_KEY wrap), replacing how this device trusted it. Never
         * signs out: anything short of [TrustChange.DONE] leaves the trust it had.
         */
        suspend fun stay(account: A, level: StaySignedIn): TrustChange

        /** Back to the password trust for [account] with [password], which is checked first ([TrustChange.WRONG_PASSWORD]). */
        suspend fun trustWithPassword(account: A, password: String): TrustChange
        fun defaultTenant(account: A): TenantRef?

        /** How this device trusts [account] now, if it does. */
        fun trustedBy(account: A): TrustedDevice?
        suspend fun open(account: A, tenant: TenantRef): T

        /**
         * [session] became the tenant shown (a sign-in, an unlock, a resume, a switch) — not one read only for its spaces
         * ([loadSpaces]): what runs in the background while a tenant is open starts here (the re-key's resume).
         */
        fun opened(session: T) {}
        suspend fun spaces(session: T): List<SpaceRef>
        suspend fun signOut(account: A)

        /**
         * [account] ends as the duplicate of a connection of the same account this model holds already ([Connections.add]
         * keeps that one): its server session and keys only — the record, the wrap copy and the offline store stay with the
         * account, which stays on this device (ADR 0140 decision 11).
         */
        suspend fun retire(account: A) = signOut(account)

        /** Why [account] ended on its own (a reconnect found this device's trust gone, [Reach.Ended]); null: it never does. */
        fun ends(account: A): kotlinx.coroutines.flow.Flow<SignInFailure.Reason>? = null
        suspend fun forgetDevice(account: A)

        /**
         * [record] forgotten on this device with no account opened (the unlock's *Stop trusting this device*, inbox #1052):
         * its keys, the record and its kept wrap, its offline store and its "Not now" — nothing sent.
         */
        suspend fun forgetRecord(record: TrustedDevice): Unit = throw UnsupportedOperationException("this door forgets no record")

        /** How many writes made offline [record]'s account keeps on this device ([OfflineStores.waiting]); 0 without a store. */
        fun waiting(record: TrustedDevice): Int = 0

        /** The levels staying signed in can take on this device now ([DeviceKeys.staySignedIn]). */
        fun staySignedIn(): Set<StaySignedIn>

        /** What this device is ([ThisDevice.form]): a TV starts at its picker and opens one account at a time (ADR 0159 decision 4). */
        val form: DeviceForm get() = DeviceForm.PHONE

        /** Whether this device can keep an account behind a PIN ([ThisDevice.offersPin], ADR 0160 decision 1). */
        fun offersPin(): Boolean = false

        /** [record]'s account opened by [pin] (ADR 0160): throws [PinRefused] on a wrong one. */
        suspend fun unlockWithPin(record: TrustedDevice, pin: String): A = throw UnsupportedOperationException("this door keeps no PIN")

        /** [account] kept on this device behind [pin] (a DEVICE_PIN wrap), replacing how it was trusted. */
        suspend fun stayWithPin(account: A, pin: String): TrustChange = TrustChange.FAILED

        /** Whether the person answered [account]'s offer with "Not now" on this device ([ThisDevice.declined]). */
        fun declined(account: A): Boolean
        fun setDeclined(account: A, declined: Boolean)

        /** The pairing relay of the sign-in form's [server] (an origin), for a link from the sign-in (ADR 0059). */
        fun relay(server: String): DeviceLink.Relay = throw UnsupportedOperationException("this door links no device")

        /** The sign-in from a link's [grant] on [server] ([VeyqAccount.linked]); enrols no trust. */
        suspend fun linked(server: String, grant: PairingGrant): A = throw UnsupportedOperationException("this door links no device")

        /**
         * The login alone on [server] (uc.account.sessions E3, [VeyqAccount.signInLocked]): no key material read, nothing
         * unlocked and nothing kept on this device. Fails as [signIn] does on a wrong password.
         */
        suspend fun signInLocked(server: String, email: String, password: String): LockedSession =
            throw UnsupportedOperationException("this door signs in no locked session")

        /** The recovery link asked for [email] on [server] (uc.account.recover-access step 1, [VeyqAccount.requestRecovery]). */
        suspend fun requestRecovery(server: String, email: String): RecoveryAsked =
            throw UnsupportedOperationException("this door asks for no recovery link")
    }

    /**
     * A session signed in without unlocking (uc.account.sessions E3): what it can do needs a session only. Its tokens live
     * in this object's memory alone — never in the device's store — and end with [drop].
     */
    interface LockedSession {
        /** Every session family of the account but this one revoked (`revokeOtherSessionFamilies`); throws when it was not. */
        suspend fun revokeOthers()

        /** This session signed out (`logout`); throws when the server did not confirm it. */
        suspend fun logout()

        /** The tokens forgotten and the HTTP client closed; nothing sent. */
        fun drop()
    }

    /** What asking for a recovery link came to (the web's `RecoverView.askForLink`). */
    enum class RecoveryAsked {
        /** Answered 202: a link is on its way if the address is a member here — the same answer either way (ADR 0004). */
        SENT,

        /** `503 mail_unavailable`: this tenant sends no mail, so no link was minted (ADR 0055 §4). */
        MAIL_UNAVAILABLE,

        /** Anything else: not reached, refused, rate-limited. */
        FAILED,
    }

    /**
     * The offer to stay signed in after a sign-in (ADR 0123 decision 8): the levels this device's store offers ([StaySignedIn], in that
     * order) for the account of connection [connection]. Answered by [stay] or [notNow]; the shell is open beneath it.
     *
     * [linked]: the sign-in was a link's (ADR 0059 §10) — this device holds no password trust, so the offer is its only
     * way to stay signed in, and it is made even with no level: then (and on "Not now") the Master Key is held for this
     * run only, and the next start needs the password and Secret Key or the Emergency Kit — what the offer says.
     */
    @Serializable
    data class Offer(
        val connection: String,
        val levels: List<StaySignedIn>,
        val linked: Boolean = false,
        /**
         * A TV's offer (ADR 0160 decision 1): the PIN, asked twice ([stayWithPin]), the recommended way to stay. Beside it
         * a TV's [levels] is [StaySignedIn.LOGIN] alone where its store keeps that level — staying signed in without a
         * PIN (owner 2026-11-26, ADR 0160 § Staying signed in without a PIN), a deliberate step the platform confirms with
         * `tv_stay_no_pin_warning` before it calls [stay]; never the default, never offered first.
         */
        val pin: Boolean = false,
    )

    /** Where the way in stands; serializable, since the native bridge sends it as it is (ADR 0123's Consequences, the bridge). */
    @Serializable
    sealed class Gate {
        /** The remembered accounts resuming at start. */
        @Serializable @SerialName("Starting")
        object Starting : Gate()

        /** The share extension with no account it can open: the app signs in (ADR 0138). */
        @Serializable @SerialName("NeedsApp")
        object NeedsApp : Gate()

        /**
         * The full sign-in, with the Secret Key; [server] and [email] filled in where a trusted device's record knew them,
         * or a kit read ([kit]) — whose Secret Key the model holds, never this state. [notice] is what the way here left to
         * say that is no error (every device signed out, [signOutEverywhere]). [keepsNothing]: reached by the locked
         * sign-in's way back ([lockedBack]) — the sign-in then enrols no trust and makes no offer (uc.account.sessions E3),
         * and the form says so (`locked_back_hint`).
         */
        @Serializable @SerialName("SignIn")
        data class SignIn(
            val working: Boolean = false,
            @Serializable(with = MsgKey::class) val error: Msg? = null,
            val adding: Boolean = false,
            val server: String? = null,
            val email: String? = null,
            val kit: KitShown? = null,
            @Serializable(with = MsgKey::class) val notice: Msg? = null,
            val keepsNothing: Boolean = false,
        ) : Gate()

        /**
         * Signed in to [server] as [email] without unlocking (uc.account.sessions E3, [signInLocked]): no list, one action —
         * every device signed out, this one included ([signOutEverywhere]) — and the way back to the sign-in ([lockedBack]);
         * [LockedPlan] names them. The session is the model's, in memory only.
         */
        @Serializable @SerialName("Locked")
        data class Locked(
            val server: String,
            val email: String,
            val adding: Boolean = false,
            val working: Boolean = false,
            @Serializable(with = MsgKey::class) val error: Msg? = null,
        ) : Gate()

        /**
         * *Reset your password* (uc.account.recover-access steps 1–2, [forgotPassword]): the link asked for [email] on
         * [server] ([requestRecovery]); [sent] once the server took the ask. The mailed link opens the web's page, which
         * finishes the reset (ADR 0148 decision 6, ADR 0150 § O). [RecoverPlan] names its entries.
         */
        @Serializable @SerialName("Recover")
        data class Recover(
            val server: String? = null,
            val email: String? = null,
            val adding: Boolean = false,
            val working: Boolean = false,
            val sent: Boolean = false,
            @Serializable(with = MsgKey::class) val error: Msg? = null,
        ) : Gate()

        /** An account this device trusts: the password alone (DEVICE), or the person's authentication (DEVICE_KEY). */
        @Serializable @SerialName("Unlock")
        data class Unlock(
            val record: TrustedDevice,
            val working: Boolean = false,
            @Serializable(with = MsgKey::class) val error: Msg? = null,
            /** A TV's PIN unlock after a wrong PIN (ADR 0160 decision 3): how many tries remain before the account leaves this TV. */
            val attemptsLeft: Int? = null,
        ) : Gate() {
            /** Whether this unlock asks a TV's PIN ([unlockWithPin]) rather than a password or the person's authentication. */
            val asksPin: Boolean get() = record.method == TrustedDevice.Method.DEVICE_PIN
        }

        /**
         * A TV's account picker (ADR 0159 decision 4): each account kept on it under the name it was given here, the most
         * recently used first; picking one asks its PIN, or opens it where it is kept without one ([pick]); *Add someone* is
         * the QR sign-in ([addServer]). [error]: why
         * the picker shows again (a wrap gone, the tenth wrong PIN). No account is open while it shows.
         */
        @Serializable @SerialName("Picker")
        data class Picker(val tiles: List<PickerTile>, @Serializable(with = MsgKey::class) val error: Msg? = null) : Gate()

        /**
         * Signed in, the shell over tenant [tenantId] of connection [connectionKey] — while [switching], still the one
         * shown, the switch to another under way. [offer] is the offer to stay signed in a sign-in left open, shown over
         * the shell until it is answered.
         *
         * [terms]: the tenant's terms gate (uc.account.accept-terms, ADR 0035 § The apps' gate) — the tenant has terms
         * whose current pair this member has not accepted. While it stands a platform draws it **in place of** the shell
         * (no list, no switcher): its two ways out are [acceptTerms] and the sign-out ([signOut]). The tenant stays open
         * beneath it, as the web's scope stays entered beneath its `/terms` route; the offer waits behind it ([offer] is
         * null until the terms are accepted).
         */
        @Serializable @SerialName("Signed")
        data class Signed(
            val connectionKey: String,
            val tenantId: String,
            val switching: Boolean = false,
            val offer: Offer? = null,
            val terms: TermsGate? = null,
        ) : Gate()
    }

    /**
     * The terms gate (the web's `TermsAcceptView`): the terms [shown] in the viewer's language, the accept under way
     * ([working]), and why it shows again ([error]: the accept failed, `terms_accept_failed`; or the pair moved while it
     * was shown, `terms_revision_changed`, and [shown] is the newer one).
     */
    @Serializable
    data class TermsGate(
        val shown: MemberTerms.TermsShown,
        val working: Boolean = false,
        @Serializable(with = MsgKey::class) val error: Msg? = null,
    )

    /**
     * What a sign-in shows of the kit whose Secret Key the model holds: the Secret Key row reads "from your Emergency Kit"
     * with a way to clear it ([clearKit]). [server] is the kit's (null where neither its token nor its first address field
     * gave one) and [serverFrom] where it came from; [typedServer] is the different server the form held before the kit
     * replaced it — the notice that the kit was made at another address (uc.account.unlock), information, never a
     * refusal; null where there was none or it was the same.
     */
    @Serializable
    data class KitShown(val email: String, val server: String?, val serverFrom: KitImport.ServerFrom, val typedServer: String? = null)

    /** One account on a TV's picker: its record's key ([TrustedDevice.key], `host#userId`) as [account], the name given it here, and the server's host. */
    @Serializable
    data class PickerTile(val account: String, val label: String, val host: String)

    /** A tenant opened: the shell goes over [session]. */
    class Opened<A : Any, T : Any>(val account: A, val tenant: TenantRef, val session: T)

    private val _gate = MutableStateFlow<Gate>(Gate.Starting)
    val gate: StateFlow<Gate> = _gate.asStateFlow()
    val current: Gate get() = _gate.value

    /** The Secret Key control's logic (ADR 0151 decision 1): the kit [Gate.SignIn.kit] shows, its key held only while it shows one. */
    private val intake = me.raddatz.veyq.mobile.account.KeyIntake(door.primitives)

    /** The gate shown next; a gate without a kit ends the held one (leaving the sign-in, Cancel, a typed key, a success). */
    private fun show(gate: Gate) {
        if ((gate as? Gate.SignIn)?.kit == null) dropKey()
        _gate.value = gate
    }

    private fun dropKey() = intake.clear()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (Gate) -> Unit): Watch = Watch(scope.launch { gate.collect { listener(it) } })

    private val connections = Connections<A>()

    /**
     * The levels staying signed in can take on this device now (ADR 0123 decision 8): what an offer and "Manage servers" offer, none where
     * nothing can be kept. Asked of the device's store each time.
     */
    val staySignedIn: Set<StaySignedIn> get() = door.staySignedIn()

    /** The servers signed in to. */
    val accounts: List<A> get() = connections.all

    /** The account and tenant the shell is over, if any. */
    val signedTo: Opened<A, T>? get() = opened

    private val _followed = MutableStateFlow<Opened<A, T>?>(null)

    /**
     * What the shell follows that no call of the platform opened (ADR 0154 decision 1): the shown account ended on its own
     * ([Reach.Ended], a reconnect found this device's trust gone) and the next one opened in its place — the platforms
     * observe it ([watchFollowed]) and show it where their shell is over another; none compares accounts itself. Each end
     * is a new value; null until one. Where no account is left the [gate] says so (the unlock or the sign-in).
     */
    val followed: StateFlow<Opened<A, T>?> = _followed.asStateFlow()

    /** [followed] for a platform that observes by callback (Swift, the bridge): each opened account the shell follows. */
    fun watchFollowed(listener: (Opened<A, T>) -> Unit): Watch = Watch(scope.launch { followed.collect { o -> o?.let(listener) } })

    private var opened: Opened<A, T>? = null
    private var started = false
    private var pendingUnlock: String? = null

    /** The offer a sign-in left open ([Gate.Signed.offer]), null once answered. */
    private var offer: Offer? = null

    /** The start, once per process: what opened, or null for the sign-in or the unlock [gate] now shows. */
    suspend fun start(): Opened<A, T>? {
        if (started) return null
        started = true
        door.started()
        val records = door.trusted()
        if (records.isEmpty()) {
            show(if (signInElsewhere) Gate.NeedsApp else Gate.SignIn())
            return null
        }
        // a TV resumes nothing: each account waits behind its own PIN on the picker (ADR 0159 decision 4)
        if (tv) return picker()
        var stopped: TrustedDevice? = null
        for (r in records.filter { it.method == TrustedDevice.Method.DEVICE_KEY }) {
            try {
                add(door.resume(r))
            } catch (e: SignInFailure) {
                // The person did not confirm: nothing more is asked of them this start.
                if (e.reason == SignInFailure.Reason.DEVICE_AUTH_CANCELLED) { stopped = r; break }
            } catch (e: Exception) {
                // a server that answers nothing this start: the next account
            }
        }
        val current = connections.current
        return if (current != null) openOrLock(current.first, current.second) else locked(stopped)
    }

    /**
     * The full sign-in, then this device trusted with the account by the password (DEVICE), then — where the device
     * offers a level and the person never said "Not now" for this account here — the offer to stay signed in
     * ([Gate.Signed.offer], ADR 0123 decision 8). A trust that cannot be enrolled leaves the sign-in standing: the next start then asks for
     * the Secret Key again.
     *
     * An empty [secretKey] signs in with the held kit's key, where one is held; a typed one wins over it and ends it. A
     * pasted kit token in its place is read as that kit's key — its email and server not applied, the person is typing
     * them (uc.account.unlock step 1).
     */
    suspend fun signIn(server: String, email: String, password: String, secretKey: String): Opened<A, T>? {
        started = true // a sign-in (the dev login among them) decides where the app is; the start does not run after it
        val adding = (current as? Gate.SignIn)?.adding == true
        // the locked sign-in's way back: nothing kept at any tier (uc.account.sessions E3) — no trust, no offer
        val keepsNothing = (current as? Gate.SignIn)?.keepsNothing == true
        val typed = secretKey.isNotBlank()
        val kit = if (typed) null else (current as? Gate.SignIn)?.kit
        // this call's copy of the key, zeroed by the door: the held one stays for a retry until the sign-in succeeds
        val key = if (typed) EmergencyKit.read(door.primitives, secretKey)?.secretKey else intake.heldCopy()
        show(Gate.SignIn(working = true, adding = adding, server = server, email = email, kit = kit, keepsNothing = keepsNothing))
        // an account this device trusts with the password, typed without a Secret Key: the password is enough, as on the
        // web (`sign-in` § On a device you have used before) — the unlock the start shows only for the most recent one
        val trusted = if (key == null && !keepsNothing) passwordTrusted(server, email) else null
        return try {
            val account = when {
                trusted != null -> door.unlockOnDevice(trusted, password)
                key != null -> door.signInWithKey(origin(server), email, password, key)
                else -> door.signIn(origin(server), email, password, secretKey)
            }
            if (!keepsNothing && trusted == null) door.trust(account, password)
            add(account)
            offer = if (keepsNothing) null else offerFor(account)
            val (held, tenant) = connections.current!!
            openTenant(held, tenant)
        } catch (e: Exception) {
            show(Gate.SignIn(error = words(reasonOf(e)), adding = adding, server = server, email = email, kit = kit, keepsNothing = keepsNothing))
            null
        } finally {
            key?.fill(0)
        }
    }

    /**
     * The record by which this device trusts [email] on [server] with the password (DEVICE), if it does: a sign-in typed
     * for it needs no Secret Key (inbox #1425). Several accounts of one server each keep their own record; the unlock the
     * start shows names the most recent, and *Use another account* reaches the others here.
     */
    fun passwordTrusted(server: String, email: String): TrustedDevice? {
        if (server.isBlank() || email.isBlank()) return null
        val host = runCatching { io.ktor.http.Url(origin(server)).host }.getOrNull() ?: return null
        return door.trusted().firstOrNull {
            it.method == TrustedDevice.Method.DEVICE && it.email.equals(email.trim(), ignoreCase = true) &&
                runCatching { io.ktor.http.Url(it.server).host }.getOrNull().equals(host, ignoreCase = true)
        }
    }

    /**
     * An Emergency Kit file the person picked, read at the sign-in ([KitImport.read]: [pictures] draws its pages, the
     * core decides) and taken as [takeKit] takes it; [typedServer] is the form's server as typed now. [bytes] are zeroed
     * once read. False — the gate saying the kit could not be read, a kit held before kept — where none was found, and
     * outside the sign-in.
     */
    suspend fun readKit(bytes: ByteArray, pictures: KitPictures, typedServer: String? = null): Boolean {
        val state = current as? Gate.SignIn
        if (state == null) {
            bytes.fill(0)
            return false
        }
        show(state.copy(working = true, error = null))
        val found = try {
            KitImport.read(door.primitives, bytes, pictures)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
        val now = current as? Gate.SignIn
        if (now == null) { // the sign-in was left while the file was read
            found?.kit?.wipe()
            return false
        }
        if (found == null) {
            show(now.copy(working = false, error = Msg.SIGNIN_KIT_UNREADABLE))
            return false
        }
        return takeKit(found, typedServer)
    }

    /**
     * Why the sign-in's Secret Key field, as typed, holds no key ([me.raddatz.veyq.mobile.account.KeyIntake.problem]):
     * [me.raddatz.veyq.mobile.crypto.SecretKeyProblem.TYPO] is said under the field, the rest says nothing until the
     * sign-in refuses it. Asked on every edit; nothing of [text] is kept.
     */
    fun secretKeyProblem(text: String): me.raddatz.veyq.mobile.crypto.SecretKeyProblem? = intake.problem(text)

    /**
     * A kit read (a file's, [readKit], or a scanned code's): its email and server into the sign-in's form — a kit with no
     * server keeps the form's —, its Secret Key held by this model in place of any held before. The model owns
     * [found]'s kit from here and zeroes its key when it ends. [typedServer] is the form's server as typed now (else the
     * gate's): a different one the kit's replaced is said ([KitShown.typedServer]). False outside the sign-in.
     */
    fun takeKit(found: KitImport.Found, typedServer: String? = null): Boolean {
        val kit = found.kit
        val state = current as? Gate.SignIn
        if (state == null) {
            kit.wipe()
            return false
        }
        val before = typedServer?.takeIf { it.isNotBlank() } ?: state.server?.takeIf { it.isNotBlank() }
        val other = before?.takeIf { kit.server != null && origin(it).lowercase() != kit.server }?.let { origin(it) }
        intake.take(found, before)
        show(
            state.copy(
                working = false,
                error = null,
                email = kit.email,
                server = kit.server ?: before,
                kit = KitShown(kit.email, kit.server, found.serverFrom, other),
            ),
        )
        return true
    }

    /** The Secret Key row's clear: the held kit's key ends, zeroed; the form keeps the email and server it filled. */
    fun clearKit() {
        val state = current as? Gate.SignIn ?: return
        show(state.copy(kit = null))
    }

    /**
     * The sign-in's "Scan Emergency Kit": a QR scan (the camera's view feeds it) whose `accepts` is the kit's parser,
     * [EmergencyKit.read] — a code that is not a v3 kit is skipped and the scan goes on, as the web's scanner skips one —
     * and whose kit is taken here as a file's is ([takeKit]): email and server into the form, its Secret Key held by this
     * model. A QR carries no form, so its server is the token's or none. The code's text and the key never leave the
     * core: the scan's `Found` says only where the server came from. [typedServer] is the form's server as typed when the
     * scan opened. A code read after the camera's view closed the scan is still taken (the person held up their kit).
     */
    fun scanKit(
        typedServer: String? = null,
        decode: suspend (QrFrame) -> String? = { QrDecoder.decode(it) },
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): QrScan<KitImport.ServerFrom> = intake.scan(typedServer, decode, scope) { takeKit(it, typedServer) }



    /**
     * The sign-in's "Use another device" (*add a new device*, ADR 0059): a scan of the trusted device's code, the link
     * over the form's [server] — nothing in a scan can change it (§4). Its grant is spent into the account here
     * ([Door.linked]) and the shell opens over it with the linked offer ([Offer.linked]). The gate stays the sign-in (a
     * held kit with it) until then. Null outside the sign-in, and in the share extension.
     */
    fun scanFromTrusted(
        server: String,
        decode: suspend (QrFrame) -> String? = { QrDecoder.decode(it) },
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): DeviceLink? {
        if (signInElsewhere || current !is Gate.SignIn) return null
        val o = origin(server)
        return DeviceLink.scanFromTrusted(door.primitives, door.relay(o), { linkedIn(o, it) }, decode, scope)
    }

    /** The sign-in's "Sign in with a QR code" (ADR 0059): this device shows its code over the form's [server]; as [scanFromTrusted]. */
    fun showForSignIn(
        server: String,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): DeviceLink? {
        if (signInElsewhere || current !is Gate.SignIn) return null
        val o = origin(server)
        return DeviceLink.showForSignIn(door.primitives, door.relay(o), { linkedIn(o, it) }, scope)
    }

    /**
     * The account [link] (a new device's, from [scanFromTrusted] or [showForSignIn]) signed in, once its tail did — also
     * when its screen closed meanwhile ([DeviceLink.signedIn]) —, or null where it ended without. The shell opens on this,
     * never on the link's [DeviceLink.Step.DONE] (ADR 0161's Consequences).
     */
    suspend fun signedInBy(link: DeviceLink): Opened<A, T>? = if (link.signedIn()) signedTo else null

    /** A link's tail (the app's sign-in tail): the account from the grant, no password trust, the linked offer, the shell. */
    private suspend fun linkedIn(server: String, grant: PairingGrant) {
        started = true
        val account = door.linked(server, grant)
        add(account)
        offer = linkedOffer(account)
        val (held, tenant) = connections.current!!
        openTenant(held, tenant)
    }

    /** The offer after a link: every level the device has, even none (the run-only case); none where it stays signed in already. */
    private fun linkedOffer(account: A): Offer? {
        if (signInElsewhere) return null
        if (door.trustedBy(account)?.method == TrustedDevice.Method.DEVICE_KEY) return null
        // a TV's PIN, where its store has the hardware factor (ADR 0160 decision 1), and beside it the login level where its
        // store keeps one — staying without a PIN, chosen deliberately (owner 2026-11-26); neither: nothing is kept
        if (tv) return Offer(account.key, listOf(StaySignedIn.LOGIN).filter { it in door.staySignedIn() }, linked = true, pin = door.offersPin())
        return Offer(account.key, StaySignedIn.entries.filter { it in door.staySignedIn() }, linked = true)
    }

    /**
     * The trusted account's unlock: its password (DEVICE), or the person's authentication again (DEVICE_KEY). A password
     * unlock is a sign-in like the full one: the offer follows it where it would follow that one.
     */
    suspend fun unlock(password: String): Opened<A, T>? {
        val state = current as? Gate.Unlock ?: return null
        val record = state.record
        show(state.copy(working = true, error = null))
        return try {
            val account = when (record.method) {
                TrustedDevice.Method.DEVICE -> door.unlockOnDevice(record, password)
                TrustedDevice.Method.DEVICE_KEY -> door.resume(record)
                TrustedDevice.Method.DEVICE_PIN -> door.unlockWithPin(record, password)
            }
            add(account)
            if (record.method == TrustedDevice.Method.DEVICE) offer = offerFor(account)
            val (held, tenant) = connections.current!!
            openTenant(held, tenant)
        } catch (e: Exception) {
            val reason = reasonOf(e)
            show(when (reason) {
                SignInFailure.Reason.DEVICE_NOT_TRUSTED, SignInFailure.Reason.SESSION_ENDED ->
                    Gate.SignIn(error = words(reason), server = record.server, email = record.email)
                SignInFailure.Reason.DEVICE_AUTH_CANCELLED -> state.copy(working = false, error = null)
                else -> state.copy(working = false, error = words(reason))
            })
            null
        }
    }

    /** The debug build's unlock (`devlogin.py --unlock`): the password for the unlock screen, now or once it shows. */
    suspend fun devUnlock(password: String): Opened<A, T>? {
        if (current is Gate.Unlock) return unlock(password)
        pendingUnlock = password
        return null
    }

    /**
     * How many writes made offline the account the unlock shows keeps on this device: what the ask before
     * [forgetUnlocked] names, which deletes them (ADR 0153 decision 2, `QueuedShown.forgetAsk`). 0 outside the unlock.
     */
    fun waitingOnUnlock(): Int = (current as? Gate.Unlock)?.let { door.waiting(it.record) } ?: 0

    /**
     * The unlock's *Stop trusting this device* (inbox #1052, owner 2026-11-22): the account the unlock shows forgotten on
     * this device as "Manage servers"' stop forgets it ([Door.forgetRecord]) — the only way off an account erased
     * elsewhere, which this device cannot tell from a wrong password (anti-enumeration) —, then where the start would go
     * without it: the next account this device trusts, or the sign-in. Nothing is sent. False outside the unlock, while it
     * works, in the share extension (the app keeps this device's trust), and where the account is held as a connection
     * (its own stop is Manage servers'). The caller asked first, always (`QueuedShown.forgetAsk` over [waitingOnUnlock]).
     */
    suspend fun forgetUnlocked(): Boolean {
        val state = current as? Gate.Unlock ?: return false
        if (state.working || signInElsewhere || connections.all.any { it.key == state.record.key }) return false
        try {
            door.forgetRecord(state.record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("veyq: the unlock's record was not forgotten: $e")
        }
        locked()
        return true
    }

    /** The session [signInLocked] holds while [Gate.Locked] shows; null otherwise. */
    private var lockedSession: LockedSession? = null

    /**
     * The sign-in's *I don't have my Secret Key with me* (uc.account.sessions E3, the web's `LoginView.enterLocked`): the
     * login alone ([Door.signInLocked]) — no unlock attempted, nothing enrolled, nothing kept on this device — and then
     * [Gate.Locked]. A held kit's key ends here (the locked state needs none). A login that fails stays on the sign-in with
     * why, as a sign-in's does. Nothing outside the sign-in, nor with the server, the email or the password blank.
     */
    suspend fun signInLocked(server: String, email: String, password: String) {
        val state = current as? Gate.SignIn ?: return
        if (state.working || server.isBlank() || email.isBlank() || password.isEmpty()) return
        show(state.copy(working = true, error = null, notice = null))
        try {
            val session = door.signInLocked(origin(server), email.trim(), password)
            lockedSession?.drop()
            lockedSession = session
            show(Gate.Locked(server = server, email = email.trim(), adding = state.adding))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show(state.copy(working = false, error = words(reasonOf(e)), notice = null))
        }
    }

    /**
     * The locked state's one action (the web's `LockedSignOut.signOutEverywhere`; the platform asked first with
     * `locked_confirm`): every other session family revoked first — the urgent half, and the one that needs the session —
     * then this one signed out. A revoke that fails stops there and says so (nothing was cut off; the session stays for a
     * retry). A sign-out of this device that fails says so too and keeps the session, so the action can be run again (the
     * revoke is idempotent). Once both went through, the sign-in says every device was signed out.
     */
    suspend fun signOutEverywhere() {
        val state = current as? Gate.Locked ?: return
        val session = lockedSession ?: return
        if (state.working) return
        show(state.copy(working = true, error = null))
        try {
            session.revokeOthers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show(state.copy(working = false, error = Msg.ACCOUNT_SETTINGS_REVOKE_FAILED))
            return
        }
        try {
            session.logout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show(state.copy(working = false, error = Msg.LOCKED_DONE_STILL_HERE))
            return
        }
        session.drop()
        lockedSession = null
        show(Gate.SignIn(adding = state.adding, server = state.server, email = state.email, notice = Msg.LOCKED_DONE))
    }

    /**
     * The locked state's way back (*I have my Secret Key after all*): the sign-in again with the Secret Key, keeping
     * nothing on this device ([Gate.SignIn.keepsNothing]). Unlike the web, which unlocks over the session it holds, the app
     * signs in anew — its sign-in is one login and unlock — so the locked session is signed out here first (best effort;
     * its tokens are forgotten either way).
     */
    suspend fun lockedBack() {
        val state = current as? Gate.Locked ?: return
        if (state.working) return
        endLocked()
        show(Gate.SignIn(adding = state.adding, server = state.server, email = state.email, keepsNothing = true))
    }

    /** The locked session signed out (best effort) and forgotten. */
    private suspend fun endLocked() {
        val session = lockedSession ?: return
        lockedSession = null
        try {
            session.logout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("veyq: the locked session was not signed out: $e")
        } finally {
            session.drop()
        }
    }

    /**
     * The sign-in's *Forgot your password?* (uc.account.recover-access step 1): *Reset your password* with the form's
     * [server] and [email]. A held kit's key ends (the request needs none). Nothing outside the sign-in.
     */
    fun forgotPassword(server: String?, email: String?) {
        val state = current as? Gate.SignIn ?: return
        if (state.working) return
        show(Gate.Recover(server = server?.takeIf { it.isNotBlank() }, email = email?.trim()?.takeIf { it.isNotBlank() }, adding = state.adding))
    }

    /**
     * The link asked for (the web's `requestRecovery`, [Door.requestRecovery]) at [server] — the request's host names the
     * tenant whose relay mails it (ADR 0054 §4). The same answer for a known and an unknown address ([Gate.Recover.sent]);
     * a tenant that sends no mail says so; anything else asks for a retry. Nothing with the server or the email blank.
     */
    suspend fun requestRecovery(server: String, email: String) {
        val state = current as? Gate.Recover ?: return
        if (state.working || state.sent || server.isBlank() || email.isBlank()) return
        show(state.copy(server = server, email = email.trim(), working = true, error = null))
        val asked = try {
            door.requestRecovery(origin(server), email.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecoveryAsked.FAILED
        }
        val now = current as? Gate.Recover ?: return // left while it was asked
        show(when (asked) {
            RecoveryAsked.SENT -> now.copy(working = false, sent = true)
            RecoveryAsked.MAIL_UNAVAILABLE -> now.copy(working = false, error = Msg.RECOVERY_MAIL_UNAVAILABLE)
            RecoveryAsked.FAILED -> now.copy(working = false, error = Msg.RECOVERY_REQUEST_FAILED)
        })
    }

    /** *Reset your password*'s Back: the sign-in again, its server and email kept. */
    fun backToSignIn() {
        val state = current as? Gate.Recover ?: return
        show(Gate.SignIn(adding = state.adding, server = state.server, email = state.email))
    }

    /**
     * A recovery finished in this app ([RecoveryFinish.signIn]): the sign-in with its [server] and [email] filled, as the
     * web's page routes to `/login` — from any gate before a shell (the sign-in, *Reset your password*, the unlock, the
     * picker); an open shell is left as it is (its own account's sessions, if it was this one, end at their next refresh).
     */
    fun recovered(server: String, email: String) {
        val adding = when (val state = current) {
            is Gate.SignIn -> if (state.working) return else state.adding
            is Gate.Recover -> state.adding
            is Gate.Unlock, is Gate.Picker, is Gate.Locked -> false
            else -> return
        }
        show(Gate.SignIn(adding = adding, server = server, email = email))
    }

    /** From the unlock to the full sign-in, for another account. */
    fun otherAccount() {
        show(if (signInElsewhere) Gate.NeedsApp else Gate.SignIn())
    }

    /** "Add server": the sign-in again, which [cancelAdd] leaves back to the shell. */
    fun addServer() {
        show(Gate.SignIn(adding = true))
    }

    fun cancelAdd() {
        val o = opened ?: run { if (tv) showPicker(); return }
        show(signed(o.account.key, o.tenant.tenantId))
    }

    // ---- a TV (ADR 0159 decision 4, ADR 0160)

    private val tv: Boolean get() = door.form == DeviceForm.TV

    /**
     * The picker's tiles: every account this TV keeps — behind a PIN, or without one (a DEVICE_KEY record, owner
     * 2026-11-26) —, the most recently used first.
     */
    private fun tiles(): List<PickerTile> = door.trusted().filter { it.keptOnTv }
        .map { PickerTile(it.key, it.pickerLabel, io.ktor.http.Url(it.server).host) }

    private val TrustedDevice.keptOnTv: Boolean
        get() = method == TrustedDevice.Method.DEVICE_PIN || method == TrustedDevice.Method.DEVICE_KEY

    private fun showPicker(error: Msg? = null) {
        val tiles = tiles()
        show(if (tiles.isEmpty()) Gate.SignIn(error = error) else Gate.Picker(tiles, error))
    }

    private fun picker(error: Msg? = null): Opened<A, T>? {
        showPicker(error)
        return null
    }

    /**
     * A TV's picker: the account [key] names. One kept behind a PIN shows its PIN unlock ([unlockWithPin]) and returns
     * null; one kept without a PIN (owner 2026-11-26) opens at once — a resume, whatever was open closed first, the gate an
     * unlock at work meanwhile — and returns what opened. A kept session gone (refused, the wrap removed elsewhere) is the
     * picker again without it, saying so; any other failure the picker with why. Null where the picker shows no such tile.
     */
    suspend fun pick(key: String): Opened<A, T>? {
        if (current !is Gate.Picker) return null
        val record = door.trusted().firstOrNull { it.key == key && it.keptOnTv } ?: return null
        if (record.method == TrustedDevice.Method.DEVICE_PIN) {
            show(Gate.Unlock(record))
            return null
        }
        show(Gate.Unlock(record, working = true))
        closeAll()
        return try {
            add(door.resume(record))
            val (held, tenant) = connections.current!!
            openTenant(held, tenant)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (val reason = reasonOf(e)) {
                SignInFailure.Reason.DEVICE_NOT_TRUSTED, SignInFailure.Reason.SESSION_ENDED -> picker(Msg.TV_PIN_GONE)
                else -> picker(words(reason))
            }
        }
    }

    /** From a PIN unlock back to the picker, nothing opened. */
    fun backToPicker() {
        if ((current as? Gate.Unlock)?.asksPin == true && current.let { !(it as Gate.Unlock).working }) showPicker()
    }

    /**
     * The PIN unlock the gate shows (ADR 0160 decision 3): one account at a time on a TV, so whatever is open closes first;
     * a wrong PIN stays on the unlock with the attempts left; a wrap gone (the tenth wrong PIN, or removed elsewhere) back
     * to the picker, which no longer lists it.
     */
    suspend fun unlockWithPin(pin: String): Opened<A, T>? {
        val state = current as? Gate.Unlock ?: return null
        if (!state.asksPin || state.working) return null
        show(state.copy(working = true, error = null))
        closeAll()
        return try {
            add(door.unlockWithPin(state.record, pin))
            val (held, tenant) = connections.current!!
            openTenant(held, tenant)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PinRefused) {
            show(state.copy(working = false, error = Msg.TV_PIN_WRONG, attemptsLeft = e.attemptsLeft))
            null
        } catch (e: Exception) {
            val reason = reasonOf(e)
            when (reason) {
                SignInFailure.Reason.DEVICE_NOT_TRUSTED, SignInFailure.Reason.SESSION_ENDED -> picker(Msg.TV_PIN_GONE)
                else -> { show(state.copy(working = false, error = words(reason))); null }
            }
        }
    }

    /**
     * A TV locks (ADR 0160 decision 6): *Lock*, the switcher, a return from the background, the idle lock ([IdleLock]) —
     * every open account closed (its Master Key zeroed, its session kept for the next unlock) and the picker shown. Nothing
     * on another form.
     */
    suspend fun lock() {
        if (!tv) return
        closeAll()
        showPicker()
    }

    /** Every held connection retired: the keys zeroed, the records and the kept sessions stay. */
    private suspend fun closeAll() {
        offer = null
        opened = null
        for (a in connections.all.toList()) {
            watchedEnds.remove(a.key)?.cancel()
            connections.remove(a)
            runCatching { door.retire(a) }
        }
    }

    /**
     * The offer's PIN on a TV ([Offer.pin]): the account kept behind [pin] (ADR 0160 decision 1). Anything short of
     * [TrustChange.DONE] leaves the offer standing.
     */
    suspend fun stayWithPin(pin: String): TrustChange? {
        val o = offer?.takeIf { it.pin } ?: return null
        val account = connections.all.firstOrNull { it.key == o.connection } ?: return null.also { endOffer() }
        val change = door.stayWithPin(account, pin)
        if (change == TrustChange.DONE) endOffer()
        return change
    }

    /** The offer's answer: stay signed in at [level] ([stay] for the offer's account); null without an offer. */
    suspend fun stay(level: StaySignedIn): TrustChange? {
        val o = offer ?: return null
        val account = connections.all.firstOrNull { it.key == o.connection } ?: return null.also { endOffer() }
        return stay(account, level)
    }

    /**
     * Staying signed in with [account] at [level], from the offer or "Manage servers" (ADR 0123 decision 8): the device's DEVICE_KEY wrap
     * for it at that level, replacing the trust it had (the other level's wrap and, unused, its key). A level the device
     * does not offer is [TrustChange.FAILED] without asking anyone; the level it has already is [TrustChange.DONE]. A
     * prompt the person cancelled ([TrustChange.CANCELLED]) leaves an offer standing; any other answer ends it, and a
     * level taken forgets an earlier "Not now".
     */
    suspend fun stay(account: A, level: StaySignedIn): TrustChange {
        val now = door.trustedBy(account)
        val change = when {
            level !in door.staySignedIn() -> TrustChange.FAILED
            now?.method == TrustedDevice.Method.DEVICE_KEY && now.unlockLevel == level -> TrustChange.DONE
            else -> door.stay(account, level)
        }
        if (change == TrustChange.DONE) door.setDeclined(account, false)
        if (offer?.connection == account.key && change != TrustChange.CANCELLED) endOffer()
        return change
    }

    /** The offer's "Not now": the password trust stays, and this account is not offered again on this device (ADR 0123 decision 8).
     *  A linked offer's is not remembered: that device has no password trust to stay with. False without an offer. */
    fun notNow(): Boolean {
        val o = offer ?: return false
        if (!o.linked) connections.all.firstOrNull { it.key == o.connection }?.let { door.setDeclined(it, true) }
        endOffer()
        return true
    }

    /**
     * "Manage servers"' way back from staying signed in to the password trust for [account] (ADR 0123 decision 8): a DEVICE wrap under
     * [password], which is checked first, replacing the DEVICE_KEY one. Already password-trusted, nothing changes.
     */
    suspend fun trustWithPassword(account: A, password: String): TrustChange {
        if (door.trustedBy(account)?.method == TrustedDevice.Method.DEVICE) return TrustChange.DONE
        return door.trustWithPassword(account, password)
    }

    /** The offer for [account] after its sign-in, or none: the share extension never offers, nor a device without a
     *  level, nor an account staying signed in already or whose offer the person declined here. */
    private fun offerFor(account: A): Offer? {
        if (signInElsewhere || door.declined(account)) return null
        if (door.trustedBy(account)?.method == TrustedDevice.Method.DEVICE_KEY) return null
        val levels = door.staySignedIn()
        return if (levels.isEmpty()) null else Offer(account.key, StaySignedIn.entries.filter { it in levels })
    }

    private fun endOffer() {
        offer = null
        (current as? Gate.Signed)?.let { show(it.copy(offer = null)) }
    }

    /** The terms gate standing over the tenant [opened] holds, by that tenant's [spacesKey]; null where none stands. */
    private var terms: Pair<String, TermsGate>? = null

    /** The shell's gate, with the terms gate of that tenant standing, else the offer. */
    private fun signed(connectionKey: String, tenantId: String, switching: Boolean = false): Gate.Signed {
        val gate = terms?.takeIf { it.first == "$connectionKey#$tenantId" }?.second
        return Gate.Signed(connectionKey, tenantId, switching, offer.takeIf { gate == null }, gate)
    }

    /** The terms gate over the open tenant set to [gate] (null: passed) and shown. */
    private fun showTerms(o: Opened<A, T>, gate: TermsGate?) {
        val key = spacesKey(o.account, o.tenant)
        terms = gate?.let { key to it }
        if (opened === o) show(signed(o.account.key, o.tenant.tenantId))
    }

    /** The tenant's terms read for [o]: the gate where they are stale, none where they are not or did not read (fail-open). */
    private suspend fun readTerms(o: Opened<A, T>): TermsGate? {
        val read = try {
            door.terms(o.session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return read?.takeIf { it.stale }?.let { TermsGate(it.shown(language())) }
    }

    /**
     * The terms gate's *I agree* (uc.account.accept-terms step 3, the web's `TermsAcceptView.accept`): the pair it shows
     * recorded by the server, and the shell shown. A pair that moved meanwhile (`409 terms_revision_changed`) is read anew
     * and shown with `terms_revision_changed` — or the gate passes where the newer read says it is accepted already; any
     * other failure keeps the gate with `terms_accept_failed`. True once the gate passed; false where none stands.
     */
    suspend fun acceptTerms(): Boolean {
        val o = opened ?: return false
        val gate = terms?.takeIf { it.first == spacesKey(o.account, o.tenant) }?.second ?: return false
        if (gate.working) return false
        showTerms(o, gate.copy(working = true, error = null))
        val answer = try {
            door.acceptTerms(o.session, gate.shown.revision, gate.shown.operatorRevision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MemberTerms.Answer.FAILED
        }
        if (opened !== o) return false // signed out or switched meanwhile: that gate is gone with its tenant
        when (answer) {
            MemberTerms.Answer.ACCEPTED -> showTerms(o, null)
            MemberTerms.Answer.FAILED -> showTerms(o, gate.copy(working = false, error = Msg.TERMS_ACCEPT_FAILED))
            MemberTerms.Answer.CHANGED -> {
                val read = try {
                    door.terms(o.session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (opened !== o) return false
                when {
                    read == null -> showTerms(o, gate.copy(working = false, error = Msg.TERMS_ACCEPT_FAILED))
                    !read.stale -> showTerms(o, null)
                    else -> showTerms(o, TermsGate(read.shown(language()), error = Msg.TERMS_REVISION_CHANGED))
                }
            }
        }
        return terms == null
    }

    /**
     * The switcher: another tenant of the same server is the same session at another domain; another server is its own.
     * The shell shown stays, [Gate.Signed.switching], until the other opened; one that does not open leaves it where it was.
     */
    suspend fun switchTo(account: A, tenant: TenantRef): Opened<A, T>? {
        val was = opened
        val before = connections.current
        connections.switchTo(account, tenant)
        if (was != null) show(signed(was.account.key, was.tenant.tenantId, switching = true))
        return try {
            openTenant(account, tenant)
        } catch (e: Exception) {
            before?.let { connections.switchTo(it.first, it.second) }
            if (was != null) show(signed(was.account.key, was.tenant.tenantId))
            null
        }
    }

    /** What a sign-out left: [next] when the account was current and another one opened in its place. */
    class SignedOut<A : Any, T : Any>(val wasCurrent: Boolean, val next: Opened<A, T>?)

    /**
     * Signs out of one server; if it was current, another one held becomes current, else the unlock of an account this
     * device still trusts with the password, or the sign-in — and the device forgets what was hidden once no account is
     * left on it (what was opened is each account's, wiped with its store, inbox #1423). Staying signed in ends with the sign-out; the password trust stays (as on the web).
     */
    suspend fun signOut(account: A): SignedOut<A, T> {
        val wasCurrent = connections.current?.first == account
        watchedEnds.remove(account.key)?.cancel()
        connections.remove(account)
        if (offer?.connection == account.key) offer = null
        if (wasCurrent) opened = null
        runCatching { door.signOut(account) }
        val next = connections.current
        return when {
            next == null -> {
                hidden.clear()
                SignedOut(wasCurrent, locked())
            }
            wasCurrent -> SignedOut(true, openOrLock(next.first, next.second))
            else -> SignedOut(false, null)
        }
    }

    /**
     * Ends this device's trust in [account]: the next sign-in on this device asks for the Secret Key again, and offers to
     * stay signed in again — an earlier "Not now" ends with the trust it was said for (ADR 0123 decision 8).
     */
    suspend fun forgetDevice(account: A) {
        runCatching { door.forgetDevice(account) }
        door.setDeclined(account, false)
    }

    /** A space on "Manage servers". */
    class ManagedSpace(val space: SpaceRef, val hidden: Boolean, val canHide: Boolean)

    /** A tenant on "Manage servers"; [spaces] is null while they load. */
    class ManagedTenant(val tenant: TenantRef, val hidden: Boolean, val current: Boolean, val spaces: List<ManagedSpace>?) {
        /** Its switch: on while it is shown — the current one always is, and cannot be hidden. */
        val shown: Boolean get() = !hidden || current
        val switchable: Boolean get() = !current

        /** The line under its name: "Shown now" for the current one, else its domain. */
        val subtitle: me.raddatz.veyq.mobile.words.Piece
            get() = if (current) me.raddatz.veyq.mobile.words.Piece.Said(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.MANAGE_CURRENT))
            else me.raddatz.veyq.mobile.words.Piece.Plain(tenant.domain)
    }

    /**
     * A server on "Manage servers": its account, its tenants, how this device trusts it ([trusted], null when it does not;
     * [level] the level a DEVICE_KEY trust is at), and the levels it may change to ([levels], the device's: [stay] for
     * one, [trustWithPassword] back to the password trust).
     */
    class ManagedServer<A : Any>(
        val account: A,
        val label: String,
        val tenants: List<ManagedTenant>,
        val trusted: TrustedDevice.Method?,
        val level: StaySignedIn? = null,
        val levels: List<StaySignedIn> = emptyList(),
    )

    /** Key of a tenant's spaces in [managed]'s `loaded`. */
    fun spacesKey(account: A, tenant: TenantRef): String = account.key + "#" + tenant.tenantId

    /** Every tenant's spaces [loadSpaces] read so far, by [spacesKey]. */
    var loadedSpaces: Map<String, List<SpaceRef>> = emptyMap()
        private set

    /** "Manage servers": every server, each tenant with what this device hides, and its spaces as far as they read. */
    fun managed(): List<ManagedServer<A>> = managed(loadedSpaces)

    internal fun managed(loaded: Map<String, List<SpaceRef>>): List<ManagedServer<A>> {
        val now = connections.current
        val levels = StaySignedIn.entries.filter { it in door.staySignedIn() }
        return connections.all.map { c ->
            val trust = door.trustedBy(c)
            ManagedServer(c, c.label, trusted = trust?.method, level = trust?.unlockLevel, levels = levels, tenants = c.tenants.map { t ->
                val spaces = loaded[spacesKey(c, t)]
                ManagedTenant(
                    tenant = t,
                    hidden = hidden.isTenantHidden(c.key, t.tenantId),
                    current = now?.first == c && now.second == t,
                    spaces = spaces?.map { ManagedSpace(it, hidden.isSpaceHidden(it.scopeId), hidden.canHideSpace(it.scopeId, spaces)) },
                )
            })
        }
    }

    /** Every tenant's spaces read anew for "Manage servers", [each] told as soon as one more read ([loadedSpaces]). */
    suspend fun loadSpaces(each: () -> Unit) {
        loadedSpaces = emptyMap()
        for (c in connections.all) for (t in c.tenants) {
            runCatching { door.spaces(door.open(c, t)) }.getOrNull()?.let { loadedSpaces = loadedSpaces + (spacesKey(c, t) to it); each() }
        }
    }

    private suspend fun add(account: A) {
        val tenant = door.defaultTenant(account) ?: throw SignInFailure(SignInFailure.Reason.NO_TENANT)
        // the duplicate leaves its connection only: the account stays on this device, under the connection held
        connections.add(account, tenant).duplicate?.let { d -> watchedEnds.remove(d.key)?.cancel(); runCatching { door.retire(d) } }
        val ends = door.ends(account) ?: return
        watchedEnds[account.key] = scope.launch { ends.first().let { ended(account, it) } }
    }

    /** The watch of each held account's own end ([Door.ends]), by connection key. */
    private val watchedEnds = HashMap<String, kotlinx.coroutines.Job>()

    /**
     * [account] ended on its own (a reconnect found this device's trust gone: its store is wiped already): it leaves the
     * switcher, and — where it was current — the next account opens, else the unlock or the sign-in says [reason].
     */
    private suspend fun ended(account: A, reason: SignInFailure.Reason) {
        if (connections.all.none { it === account }) return
        watchedEnds.remove(account.key)
        val wasCurrent = connections.current?.first == account
        connections.remove(account)
        if (offer?.connection == account.key) offer = null
        if (wasCurrent) opened = null
        runCatching { door.retire(account) }
        val next = connections.current
        when {
            next == null -> locked(error = words(reason))
            wasCurrent -> openOrLock(next.first, next.second)?.let { _followed.value = it }
        }
    }

    private suspend fun openTenant(account: A, tenant: TenantRef): Opened<A, T> {
        val o = Opened(account, tenant, door.open(account, tenant))
        // the terms gate is decided before the shell is shown, so no list shows ahead of it (uc.account.accept-terms)
        val gate = readTerms(o)
        door.opened(o.session)
        opened = o
        terms = gate?.let { spacesKey(account, tenant) to it }
        show(signed(account.key, tenant.tenantId))
        return o
    }

    /** The tenant opened, or — when it does not — the unlock or the sign-in with why. */
    private suspend fun openOrLock(account: A, tenant: TenantRef): Opened<A, T>? = try {
        openTenant(account, tenant)
    } catch (e: Exception) {
        connections.remove(account)
        locked(error = words(reasonOf(e)))
    }

    /** The unlock for [preferred], else the most recent account this device trusts, else the sign-in. */
    private suspend fun locked(preferred: TrustedDevice? = null, error: Msg? = null): Opened<A, T>? {
        if (tv) return picker(error)
        val record = preferred ?: door.trusted().firstOrNull()
        show(when {
            record != null -> Gate.Unlock(record, error = error)
            signInElsewhere -> Gate.NeedsApp
            else -> Gate.SignIn(error = error)
        })
        val pending = pendingUnlock ?: return null
        pendingUnlock = null
        return if (record != null) unlock(pending) else null
    }

    companion object {
        /** A typed server: a bare host gets https, except a dev `*.localhost` host, which the dev backend serves on http. */
        fun origin(typed: String): String {
            val t = typed.trim().trimEnd('/')
            if ("://" in t) return t
            val host = t.substringBefore(':').substringBefore('/')
            return if (host == "localhost" || host.endsWith(".localhost")) "http://$t" else "https://$t"
        }

        /** What a failed sign-in or unlock says. */
        fun words(reason: SignInFailure.Reason): Msg = when (reason) {
            SignInFailure.Reason.UNREACHABLE -> Msg.SIGNIN_ERROR_UNREACHABLE
            SignInFailure.Reason.INCOMPATIBLE -> Msg.SIGNIN_ERROR_INCOMPATIBLE
            SignInFailure.Reason.CREDENTIALS -> Msg.SIGNIN_ERROR_CREDENTIALS
            SignInFailure.Reason.SECRET_KEY -> Msg.SIGNIN_ERROR_SECRET_KEY
            SignInFailure.Reason.NO_TENANT -> Msg.SIGNIN_ERROR_NO_TENANT
            SignInFailure.Reason.DEVICE_NOT_TRUSTED -> Msg.SIGNIN_ERROR_DEVICE_NOT_TRUSTED
            SignInFailure.Reason.SESSION_ENDED -> Msg.SIGNIN_ERROR_SESSION_ENDED
            SignInFailure.Reason.RATE_LIMITED -> Msg.SIGNIN_ERROR_RATE_LIMITED
            SignInFailure.Reason.PROTOCOL, SignInFailure.Reason.DEVICE_AUTH_CANCELLED -> Msg.SIGNIN_ERROR_OTHER
        }

        private fun reasonOf(e: Exception): SignInFailure.Reason = (e as? SignInFailure)?.reason ?: SignInFailure.Reason.PROTOCOL

        /** The model over real accounts: [primitives] the platform's suite, [client] its HTTP client. */
        fun accounts(
            primitives: Primitives,
            device: ThisDevice,
            client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
            hidden: Hidden,
            signInElsewhere: Boolean = false,
            /** The platform's words' language: the terms gate's ([Gate.Signed.terms]). */
            language: () -> String = { "en" },
        ): SessionModel<VeyqAccount, TenantSession> = SessionModel(AccountDoor(primitives, device, client), hidden, signInElsewhere, language)
    }
}

/** [SessionModel.Door] over [VeyqAccount]. */
class AccountDoor(
    override val primitives: Primitives,
    private val device: ThisDevice,
    private val client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
) : SessionModel.Door<VeyqAccount, TenantSession> {
    override fun trusted(): List<TrustedDevice> = device.trusted()

    /** A wipe of an offline store a crash interrupted is finished before any account opens (ADR 0140 decision 11). */
    override fun started() {
        device.offline?.let { o -> runCatching { o.finishWipes() }.onFailure { println("veyq: an offline store's wipe did not finish: $it") } }
    }

    override suspend fun signIn(server: String, email: String, password: String, secretKey: String): VeyqAccount =
        VeyqAccount.signIn(primitives, server, email, password, secretKey, device, client)

    override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): VeyqAccount =
        VeyqAccount.signInWithKey(primitives, server, email, password, secretKey, device, client)

    override suspend fun unlockOnDevice(record: TrustedDevice, password: String): VeyqAccount =
        VeyqAccount.unlockOnDevice(primitives, record, password, device, client)

    override suspend fun resume(record: TrustedDevice): VeyqAccount = VeyqAccount.resume(primitives, record, device, client)

    /** The join of a received [invite] (`TenantJoin`), its cold sign-ins through this door's device and client. */
    fun join(invite: me.raddatz.veyq.mobile.sharing.ReceivedLink.InviteLink): TenantJoin = TenantJoin.of(primitives, device, client, invite)

    /** The finish of a received recovery [link] (`RecoveryFinish`), over this door's client. */
    fun recovery(link: me.raddatz.veyq.mobile.sharing.ReceivedLink.RecoveryLink): RecoveryFinish = RecoveryFinish.of(primitives, client, link)

    override suspend fun trust(account: VeyqAccount, password: String) {
        runCatching { account.trustThisDevice(password, level = null) }
            .onFailure { if (it is CancellationException) throw it; println("veyq: this device could not be trusted with the account: $it") }
    }

    override suspend fun stay(account: VeyqAccount, level: StaySignedIn): TrustChange = changed("staying signed in") {
        if (account.trustThisDevice(null, level) == VeyqAccount.TrustOutcome.REMEMBERED) TrustChange.DONE else TrustChange.FAILED
    }

    override suspend fun trustWithPassword(account: VeyqAccount, password: String): TrustChange = changed("the password trust") {
        when {
            !account.isPassword(password) -> TrustChange.WRONG_PASSWORD
            account.trustThisDevice(password, level = null) == VeyqAccount.TrustOutcome.TRUSTED -> TrustChange.DONE
            else -> TrustChange.FAILED
        }
    }

    /** A change of trust's outcome: a cancelled prompt is [TrustChange.CANCELLED], any other failure logged and FAILED. */
    private suspend fun changed(what: String, block: suspend () -> TrustChange): TrustChange = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceAuthFailed) {
        if (e.cancelled) TrustChange.CANCELLED else TrustChange.FAILED.also { println("veyq: $what was not enrolled: $e") }
    } catch (e: Exception) {
        println("veyq: $what was not enrolled: $e")
        TrustChange.FAILED
    }

    override fun defaultTenant(account: VeyqAccount): TenantRef? = account.defaultTenant
    override fun trustedBy(account: VeyqAccount): TrustedDevice? = account.trusted
    /** A tenant opened re-keys by the build's kinds ([AppKinds.rules]): a write pulls its path forward, the resume finishes a scope. */
    override suspend fun open(account: VeyqAccount, tenant: TenantRef): TenantSession =
        account.open(tenant).also { it.rekeyRules = me.raddatz.veyq.mobile.AppKinds.rules }

    /**
     * The tenant shown: the scopes served as re-keying are finished in the background ([TenantSession.resumeRekeys]), the
     * task queue's jobs run while it stays open (`TaskQueue`), and the account's pins synced by the build's kinds ([AppKinds.pins], ADR 0140 decision 9) — at each open, online.
     */
    override fun opened(session: TenantSession) {
        session.resumeRekeys()
        session.tasks.start()
        session.account.offlinePins?.start(session, me.raddatz.veyq.mobile.AppKinds.pins)
    }
    override suspend fun spaces(session: TenantSession): List<SpaceRef> = session.spaces()
    override suspend fun terms(session: TenantSession): MemberTerms.Read? = MemberTerms.read(session)
    override suspend fun acceptTerms(session: TenantSession, revision: Int, operatorRevision: Int): MemberTerms.Answer =
        MemberTerms.accept(session, revision, operatorRevision)
    override suspend fun signOut(account: VeyqAccount) = account.signOut()
    override suspend fun retire(account: VeyqAccount) = account.retire()
    override fun ends(account: VeyqAccount) = account.reach.mapNotNull { (it as? Reach.Ended)?.reason }
    override suspend fun forgetDevice(account: VeyqAccount) = account.forgetThisDevice()
    override suspend fun forgetRecord(record: TrustedDevice) = device.forgetLocked(record)
    override fun waiting(record: TrustedDevice): Int = device.offline?.waiting(record.key) ?: 0
    override fun staySignedIn(): Set<StaySignedIn> = device.keys.staySignedIn
    override val form: DeviceForm get() = device.form
    override fun offersPin(): Boolean = device.offersPin
    override suspend fun unlockWithPin(record: TrustedDevice, pin: String): VeyqAccount =
        VeyqAccount.unlockWithPin(primitives, record, pin, device, client)
    override suspend fun stayWithPin(account: VeyqAccount, pin: String): TrustChange = changed("the PIN") {
        if (account.trustWithPin(pin) == VeyqAccount.TrustOutcome.REMEMBERED) TrustChange.DONE else TrustChange.FAILED
    }
    override fun declined(account: VeyqAccount): Boolean = device.declined(account.key)
    override fun setDeclined(account: VeyqAccount, declined: Boolean) = device.setDeclined(account.key, declined)
    override fun relay(server: String): DeviceLink.Relay = HttpRelay.of(server, client)
    override suspend fun linked(server: String, grant: PairingGrant): VeyqAccount = VeyqAccount.linked(primitives, server, grant, device, client)
    override suspend fun signInLocked(server: String, email: String, password: String): SessionModel.LockedSession =
        VeyqAccount.signInLocked(primitives, server, email, password, client)
    override suspend fun requestRecovery(server: String, email: String): SessionModel.RecoveryAsked =
        VeyqAccount.requestRecovery(server, email, client)
}

/** What a change of this device's trust came to (ADR 0123 decision 8): [DONE]; the person [CANCELLED] the prompt (an offer stays); the
 *  password was not the account's; or it [FAILED] otherwise — the trust the account had stands in every case but DONE. */
enum class TrustChange { DONE, CANCELLED, WRONG_PASSWORD, FAILED }
