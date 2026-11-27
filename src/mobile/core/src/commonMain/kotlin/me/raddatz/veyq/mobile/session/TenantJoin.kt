package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.account.BreachCheck
import me.raddatz.veyq.mobile.account.KitRecord
import me.raddatz.veyq.mobile.account.KitStep
import me.raddatz.veyq.mobile.api.apis.AccountEmailApi
import me.raddatz.veyq.mobile.api.apis.SetupApi
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import me.raddatz.veyq.mobile.api.models.AcceptInviteRequest
import me.raddatz.veyq.mobile.api.models.AcceptOwnershipRequest
import me.raddatz.veyq.mobile.api.models.AcceptPreviewRequest
import me.raddatz.veyq.mobile.api.models.AccountRegistration
import me.raddatz.veyq.mobile.api.models.InvitePreview
import me.raddatz.veyq.mobile.api.models.InviteRole
import me.raddatz.veyq.mobile.api.models.InviteSelfGrant
import me.raddatz.veyq.mobile.api.models.KeyChain
import me.raddatz.veyq.mobile.api.models.MasterKeyWrap
import me.raddatz.veyq.mobile.api.models.RegisterAndAcceptRequest
import me.raddatz.veyq.mobile.api.models.ServedMemberInvite
import me.raddatz.veyq.mobile.crypto.AccountProofs
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.Invites
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.OpenRefused
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey
import me.raddatz.veyq.mobile.words.Notice
import kotlin.time.Instant

/**
 * Joining a space from an invite (uc.admin.create-tenant Slice B, uc.spaces.add-member; ADR 0111 decisions 1, 3, 19, 20,
 * 23; ADR 0129), the port of the web's `TenantJoinView.vue` over `tenantInvite.ts` and `accountBirth.ts`. The invite's link
 * (`<origin>/tenant/accept#<token>[.<sealed>[.<code>]]`) reaches it through *Open a link* ([InviteLink]); nothing of the
 * fragment is sent but the token, and only to read the invite and to accept it.
 *
 * The phases are the web's: the invite read ([Phase.LOADING]) — or why not ([Phase.INVALID], [Phase.EXPIRED],
 * [Phase.REDEEMED]); a member invite's code ([Phase.CODE], skipped where the link carries it): the link's sealed part opened
 * and the invite checked ([Invites.checkInvite]) before anything is sent; then a new account ([Phase.REGISTER] → its
 * Emergency Kit [Phase.KIT]) or an existing one ([Phase.SIGNIN]), and [Phase.DONE]. A MEMBER grants itself the tenant key
 * its invite holds and writes its join record; an OWNER invite cold-births the TENANT scope; a handover makes a member an
 * owner. When the inviting owner secured their account between the check and the accept (`409 invite_changed`), or a
 * rotation moved the tenant (`409 stale_epoch`), the invite is checked again and accepted once more without the code.
 *
 * The accepted tenant key is held (ADR 0111 decision 23) by an account signed in here for the accept (an existing account)
 * or right after it (a new one), which is then let go ([VeyqAccount.retire]); *Open the space* signs the person in through
 * the app's own sign-in ([openSpace]) with what this flow holds in memory. The password, the Secret Key, the code and every key
 * stay in this object, never in its [state]; [close] zeroes them.
 */
class TenantJoin internal constructor(
    private val primitives: Primitives,
    /** The server the invite is on: the link's origin. */
    val origin: String,
    private val fragment: Invites.Fragment,
    private val client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
    /** A cold sign-in for the accept and the key's hold: the account door's `signInWithKey`. */
    private val signIn: suspend (server: String, email: String, password: String, secretKey: ByteArray) -> VeyqAccount,
) {
    enum class Phase { LOADING, INVALID, EXPIRED, REDEEMED, CODE, REGISTER, KIT, SIGNIN, DONE }

    enum class Role { OWNER, MEMBER }

    @Serializable
    data class State(
        val phase: Phase = Phase.LOADING,
        val tenantName: String = "",
        val domain: String = "",
        val role: Role = Role.OWNER,
        /** An owner invite into a tenant the caller is a member of: accepting makes them its owner (ADR 0101 decision 9). */
        val handover: Boolean = false,
        /** A link invite (ADR 0129 decision 2): bound to nobody, its invitee types an address. */
        val linkInvite: Boolean = false,
        /** The address the invite names; empty for a link invite. */
        val email: String = "",
        /** Whether the link's sealed part asks for a code or a password the owner chose. */
        val codeMode: Invites.Mode = Invites.Mode.CODE,
        val busy: Boolean = false,
        @Serializable(with = MsgKey::class) val error: Msg? = null,
        /** The breached-password check of a new account's password. */
        val breach: BreachCheck.BreachState = BreachCheck.BreachState(),
        /** The address a link registration's confirmation mail went to, once it went (ADR 0129 decision 4). */
        val confirmationSentTo: String? = null,
        /** *Open the space* is offered: the join is done and this flow still holds the sign-in. */
        val canOpen: Boolean = false,
    ) {

        /** The invitation's line: to own, to join, or to take over. */
        val intro: Notice = Notice(pick(Msg.JOIN_INVITED_TO, Msg.JOIN_INVITED_TO_MEMBER, Msg.JOIN_INVITED_TO_HANDOVER), tenantName, domain)

        /** The register step's subline. */
        val registerSub: Notice =
            if (linkInvite) Notice(Msg.JOIN_REGISTER_SUB_LINK) else Notice(pick(Msg.JOIN_REGISTER_SUB, Msg.JOIN_REGISTER_SUB_MEMBER, Msg.JOIN_REGISTER_SUB), email)

        /** The sign-in step's subline. */
        val signinSub: Notice =
            if (linkInvite) Notice(Msg.JOIN_SIGNIN_SUB_LINK) else Notice(pick(Msg.JOIN_SIGNIN_SUB, Msg.JOIN_SIGNIN_SUB_MEMBER, Msg.JOIN_SIGNIN_SUB_HANDOVER), email)

        /** The accept button's word. */
        @Serializable(with = MsgKey::class) val joinWord: Msg = pick(Msg.JOIN_JOIN, Msg.JOIN_JOIN_MEMBER, Msg.JOIN_JOIN_HANDOVER)

        /** The done step's line and note. */
        val done: Notice = Notice(pick(Msg.JOIN_DONE, Msg.JOIN_DONE_MEMBER, Msg.JOIN_DONE_HANDOVER), tenantName)
        @Serializable(with = MsgKey::class) val doneNote: Msg = pick(Msg.JOIN_DONE_NOTE, Msg.JOIN_DONE_NOTE_MEMBER, Msg.JOIN_DONE_NOTE_HANDOVER)

        /** A handover keeps the member's own handle: no username asked. */
        val asksUsername: Boolean = !handover

        /** The address field: a link invite's, typed; else the invite's, shown. */
        val asksEmail: Boolean = linkInvite

        /** A link invite's invitee may switch between a new account and one they have (ADR 0129 decision 2). */
        val offersSwitch: Boolean = linkInvite

        /** The code step's line: a code or a password. */
        @Serializable(with = MsgKey::class) val codeSub: Msg = if (codeMode == Invites.Mode.CODE) Msg.JOIN_CODE_SUB else Msg.JOIN_PASSWORD_SUB

        @Serializable(with = MsgKey::class) val codeLabel: Msg = if (codeMode == Invites.Mode.CODE) Msg.JOIN_CODE else Msg.JOIN_INVITE_PASSWORD

        // a function, not a field: the variant is no part of the wire
        private fun pick(owner: Msg, member: Msg, handover: Msg) = when {
            this.handover -> handover
            role == Role.MEMBER -> member
            else -> owner
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private val suite = V1(primitives)
    private val invites = Invites(suite)
    private val joins = JoinRecords(suite)
    private val proofs = AccountProofs(primitives)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http: HttpClient = client {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }
    private val outside: HttpClient = client { expectSuccess = false }

    /** The new account's password check, at the instance's range service (`core.breached-password`). */
    val passwordCheck: BreachCheck = BreachCheck(OutsideHttp(outside, origin)) {
        val r = SetupApi(origin, http).getSetupStatus()
        if (r.success) r.body().capabilities.firstOrNull { it.id == BreachCheck.CAPABILITY }?.serviceUrl else null
    }

    private var tenantId = ""
    private var served: ServedMemberInvite? = null
    private var opened: Opened? = null
    private var hasAccount = false

    // what the account step typed, kept until the done step's *Open the space* or [close]
    private var built: Built? = null

    /** The Emergency Kit step of a new account: the core writes the kit into the file the person chose. */
    var kit: KitStep? = null
        private set

    /** The details the register step took, kept across its kit step. */
    private class Pending(val username: String, val firstName: String, val lastName: String, val email: String, val password: String)

    private var pending: Pending? = null

    /** The invite read: its phase, or why it is none. Once. */
    suspend fun load() = lock.withLock {
        if (fragment.token.isEmpty()) return@withLock set { copy(phase = Phase.INVALID) }
        val p = try {
            preview()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withLock
        tenantId = p.tenantId
        val member = p.role == InviteRole.MEMBER
        set {
            copy(
                tenantName = p.tenantName, domain = p.domain, role = if (member) Role.MEMBER else Role.OWNER, handover = p.handover,
                linkInvite = member && p.ownerEmail == null, email = p.ownerEmail ?: "",
            )
        }
        if (!member) return@withLock set { copy(phase = if (p.hasAccount == true || p.handover) Phase.SIGNIN else Phase.REGISTER) }
        // a member invite opens only with its sealed part and the code: without them it cannot be checked
        val mode = fragment.sealed?.let { runCatching { invites.parseSealedLink(it).mode }.getOrNull() }
        val invite = p.invite
        if (invite == null || mode == null) return@withLock set { copy(phase = Phase.INVALID) }
        served = invite
        hasAccount = p.hasAccount ?: false
        set { copy(codeMode = mode) }
        val carried = fragment.code
        if (carried != null && mode == Invites.Mode.CODE && openInvite(carried)) return@withLock
        set { copy(phase = Phase.CODE, error = if (carried != null && mode == Invites.Mode.CODE) current.error else null) }
    }

    /** The code (or password) typed: the invite opened and checked, then the account step. */
    suspend fun code(typed: String) = lock.withLock {
        if (typed.isBlank() || current.phase != Phase.CODE) return@withLock
        openInvite(typed)
    }

    /** A link invite's invitee switches between a new account ([register]) and one they have. */
    fun choose(register: Boolean) {
        if (!current.linkInvite || current.phase !in setOf(Phase.REGISTER, Phase.SIGNIN)) return
        set { copy(phase = if (register) Phase.REGISTER else Phase.SIGNIN, error = null) }
    }

    /** The new account's password left its field: checked once against the range service. */
    suspend fun checkPassword(password: String) = passwordCheck.check(password).also { set { copy(breach = passwordCheck.current) } }

    /** *Use it anyway* on a breached password. */
    fun useAnyway(on: Boolean) {
        passwordCheck.useAnyway(on)
        set { copy(breach = passwordCheck.current) }
    }

    /**
     * Register step 1: the account built on this device (the web's `buildAccount`), then its Emergency Kit ([kit]) before
     * anything is sent. [email] is the typed address of a link invite (ignored for one bound to an address).
     */
    suspend fun register(username: String, firstName: String, lastName: String, email: String, password: String) = lock.withLock {
        if (current.phase != Phase.REGISTER) return@withLock
        val handle = username.trim().lowercase()
        val address = if (current.linkInvite) email.trim() else current.email
        if (handle.isEmpty() || password.length < PasswordChange.MIN_PASSWORD_LENGTH) return@withLock set { copy(error = Msg.PASSWORD_TOO_SHORT.takeIf { handle.isNotEmpty() }) }
        if (!isMailbox(address)) return@withLock set { copy(error = Msg.JOIN_EMAIL_INVALID) }
        busy {
            if (!passwordCheck.proceed(password)) {
                set { copy(breach = passwordCheck.current) }
                return@busy
            }
            val salt = unb64(SetupApi(origin, http).getSetupStatus().also { if (!it.success) throw IllegalStateException("setup ${it.status}") }.body().instanceSalt)
            built?.zero()
            val b = build(address, password, salt)
            built = b
            pending = Pending(handle, firstName, lastName, address, password)
            kit?.close()
            kit = KitStep(primitives, address, origin, b.secretKey.copyOf())
            set { copy(phase = Phase.KIT, breach = passwordCheck.current) }
        }
    }

    /** Register step 2, once the kit is saved and confirmed: the account registered and the invite accepted in one request. */
    suspend fun finishRegistration() = lock.withLock {
        val b = built ?: return@withLock
        val p = pending ?: return@withLock
        if (current.phase != Phase.KIT || kit?.current?.ready != true) return@withLock
        busy {
            val userId = NodeCreate.uuidOf(primitives)
            // the kit just saved is recorded with the account, sealed under its Master Key (ADR 0157 decision 4)
            val kitRecord = KitRecord.seal(suite, b.masterKey)
            val identityPub = suite.identityPublic(b.identity).serialize()
            val member = current.role == Role.MEMBER
            val proofPublic = proofs.secretKeyProofPublic(b.secretKey)
            val held = accept(
                send = { o ->
                    val join = o?.let { memberJoin(it, userId, b.identity, identityPub, proofPublic) }
                    val born = if (o == null) bornTenant(userId, b.identity, identityPub) else null
                    val key = o?.let { it.checked.epochKey to it.checked.grantedEpoch } ?: (born!!.key to 0L)
                    val name = sealedName(key.first, key.second, userId, p.firstName, p.lastName)
                    val r = TenantsApi(origin, http).registerAndAcceptTenantInvite(
                        RegisterAndAcceptRequest(
                            userId = userId, bootstrapSecret = fragment.token, username = p.username, account = b.material,
                            email = if (current.linkInvite) p.email else null,
                            grant = born?.grant?.wrappedGrant, grantSignature = born?.grant?.grantSignature,
                            recipientIdentityDigest = born?.grant?.recipientIdentityDigest,
                            selfGrant = join?.selfGrant, inviteProof = join?.inviteProof, sealedJoinRecord = join?.sealedJoinRecord,
                            wrappedJoinRecordValueKey = join?.wrappedJoinRecordValueKey, signerIdentityDigest = join?.signerIdentityDigest,
                            sealedName = name?.let { b64(it.sealed) }, wrappedNameValueKey = name?.let { b64(it.wrappedValueKey) },
                            epoch = key.second.toInt(), kitRecord = kitRecord,
                        ),
                    )
                    r to Held(key.first.copyOf(), key.second)
                },
            ) ?: return@busy
            // the new account holds the accepted key, and a link registration asks for its confirmation mail (ADR 0129 decision 4)
            val confirmed = afterBirth(p.email, p.password, b.secretKey.copyOf(), held, askConfirmation = current.linkInvite)
            held.zero()
            finish(p.email, p.password, SecretKeyText.format(primitives, b.secretKey), confirmed)
        }
    }

    /** An existing account: signed in here with its password and Secret Key, then the invite accepted (or the ownership taken). */
    suspend fun signInAndAccept(username: String, firstName: String, lastName: String, email: String, password: String, secretKey: String) = lock.withLock {
        if (current.phase != Phase.SIGNIN || password.isEmpty()) return@withLock
        val handle = username.trim().lowercase()
        if (current.asksUsername && handle.isEmpty()) return@withLock
        val address = if (current.linkInvite) email.trim() else current.email
        if (!isMailbox(address)) return@withLock set { copy(error = Msg.JOIN_EMAIL_INVALID) }
        val sk = SecretKeyText.read(primitives, secretKey) ?: return@withLock set { copy(error = Msg.SIGNIN_ERROR_SECRET_KEY) }
        busy {
            val account = try {
                signIn(origin, address, password, sk.copyOf())
            } catch (e: SignInFailure) {
                sk.fill(0)
                set { copy(error = signInWord(e)) }
                return@busy
            }
            try {
                if (current.handover) {
                    val r = account.respond { TenantsApi(origin, account.http).acceptTenantOwnership(AcceptOwnershipRequest(fragment.token)) }
                    if (!r.success) return@busy set { copy(error = errorWord(r)) }
                } else {
                    val identity = account.identityCopy()
                    try {
                        val o = opened
                        if (o != null && !anchored(account, o)) return@busy set { copy(error = Msg.JOIN_INVITE_UNCHECKABLE) }
                        val proofPublic = proofs.secretKeyProofPublic(sk)
                        val accepted = accept(
                            send = { c ->
                                val join = c?.let { memberJoin(it, account.userId, identity, account.myPublic, proofPublic) }
                                val born = if (c == null) bornTenant(account.userId, identity, account.myPublic) else null
                                val key = c?.let { it.checked.epochKey to it.checked.grantedEpoch } ?: (born!!.key to 0L)
                                val name = sealedName(key.first, key.second, account.userId, firstName, lastName)
                                val r = account.respond {
                                    TenantsApi(origin, account.http).acceptTenantInvite(
                                        AcceptInviteRequest(
                                            bootstrapSecret = fragment.token, username = handle,
                                            grant = born?.grant?.wrappedGrant, grantSignature = born?.grant?.grantSignature,
                                            recipientIdentityDigest = born?.grant?.recipientIdentityDigest,
                                            selfGrant = join?.selfGrant, inviteProof = join?.inviteProof, sealedJoinRecord = join?.sealedJoinRecord,
                                            wrappedJoinRecordValueKey = join?.wrappedJoinRecordValueKey, signerIdentityDigest = join?.signerIdentityDigest,
                                            sealedName = name?.let { b64(it.sealed) }, wrappedNameValueKey = name?.let { b64(it.wrappedValueKey) },
                                            epoch = key.second.toInt(),
                                        ),
                                    )
                                }
                                r to Held(key.first.copyOf(), key.second)
                            },
                            again = { c -> anchored(account, c) },
                        ) ?: return@busy
                        // the accepted key is held: the tenant's first open takes no epoch that does not link down to it
                        runCatching { account.holdEpochKey(tenantId, accepted.epoch, accepted.key) }.onFailure { if (it is CancellationException) throw it }
                        accepted.zero()
                    } finally {
                        identity.kemSeed.fill(0)
                        identity.signSeed.fill(0)
                    }
                }
            } finally {
                runCatching { account.retire() }.onFailure { if (it is CancellationException) throw it }
            }
            finish(address, password, SecretKeyText.format(primitives, sk), null)
            sk.fill(0)
        }
    }

    /**
     * *Open the space*: [session] — the app's own — signs in with what this flow holds, once; its answer is the platform's
     * to open as any sign-in's. Null where it did not ([State.error] then says so).
     */
    suspend fun <A : Connection, T : Any> openSpace(session: SessionModel<A, T>): SessionModel.Opened<A, T>? = lock.withLock {
        val h = signedIn ?: return@withLock null
        set { copy(busy = true, error = null) }
        val o = try {
            session.signIn(origin, h.email, h.password, h.secretKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (o != null) signedIn = null
        set { copy(busy = false, canOpen = o == null, error = if (o == null) Msg.JOIN_FAILED else null) }
        o
    }

    /** The flow closed: every key and secret it held zeroed, the kit step closed. */
    fun close() {
        opened?.forget()
        opened = null
        built?.zero()
        built = null
        pending = null
        signedIn = null
        kit?.close()
        kit = null
        passwordCheck.close()
        http.close()
        outside.close()
    }

    // ---- the invite ----

    private suspend fun preview(): InvitePreview? {
        val r = TenantsApi(origin, http).previewTenantInvite(AcceptPreviewRequest(fragment.token))
        if (r.success) return r.body()
        val code = errorCodeOf(r)
        set { copy(phase = if (code == "invite_redeemed") Phase.REDEEMED else if (r.status == 410) Phase.EXPIRED else Phase.INVALID) }
        return null
    }

    /** An opened, checked member invite: the invite key and the tenant key its grant carries, kept until the accept. */
    private class Opened(val served: ServedMemberInvite, val checked: Invites.CheckedInvite, val link: Invites.LinkPayload) {
        fun forget() {
            checked.epochKey.fill(0)
            checked.inviteKey.zero()
            link.seed.fill(0)
        }
    }

    /** Opens the link's sealed part with [typed] and checks the invite; true where it passed (the account step follows). */
    private suspend fun openInvite(typed: String): Boolean {
        val s = served ?: return false
        val sealedPart = fragment.sealed ?: return false
        set { copy(busy = true, error = null) }
        try {
            val link = runCatching { invites.parseSealedLink(sealedPart) }.getOrNull() ?: return refuse(Msg.JOIN_INVITE_UNCHECKABLE)
            val secret = invites.linkSecret(link.mode, typed) ?: return refuse(wrongWord(link.mode))
            val kek = try { invites.kekInviteLink(secret, link.salt) } finally { secret.fill(0) }
            val payload = try {
                invites.openLink(tenantId, s.inviteId, kek, link)
            } catch (e: OpenRefused) {
                return refuse(wrongWord(link.mode))
            } catch (e: IllegalArgumentException) {
                return refuse(wrongWord(link.mode))
            } finally {
                kek.fill(0)
            }
            val checked = try {
                check(s, payload)
            } catch (e: Invites.InviteCheckFailed) {
                payload.seed.fill(0)
                return refuse(Msg.JOIN_INVITE_UNCHECKABLE)
            }
            opened?.forget()
            opened = checked
            set { copy(phase = if (hasAccount) Phase.SIGNIN else Phase.REGISTER, busy = false) }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return refuse(Msg.JOIN_FAILED)
        }
    }

    private fun refuse(word: Msg): Boolean {
        set { copy(busy = false, error = word) }
        return false
    }

    private fun wrongWord(mode: Invites.Mode) = if (mode == Invites.Mode.CODE) Msg.JOIN_CODE_WRONG else Msg.JOIN_PASSWORD_WRONG

    /** [Invites.checkInvite] over what the preview served, decoded; a served byte string that is no base64 is no invite the link names. */
    private fun check(s: ServedMemberInvite, payload: Invites.LinkPayload): Opened {
        val decoded = try {
            Invites.ServedInvite(
                tenantId, s.inviteId, unb64(s.invitePublicKey), unb64(s.inviteKeySignature), Instant.parse(s.expiresAt).epochSeconds,
                s.signerUserId, unb64(s.signerIdentityPublicKey), s.signerKeyChain.map(::linkOf), unb64(s.anchorWrappedGrant),
                s.anchorEpoch.toLong(), unb64(s.anchorGrantSignature), unb64(s.wrappedGrant), s.grantedEpoch.toLong(), unb64(s.grantSignature),
                unb64(s.granterIdentityPublicKey), s.successions.map { Trust.SuccessionLink(it.epoch.toLong(), unb64(it.successionPublicKey), unb64(it.successionSignature)) },
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw Invites.InviteCheckFailed(Invites.CheckFailure.KEY_MISMATCH)
        }
        val checked = invites.checkInvite(payload, decoded)
        return Opened(s, checked, Invites.LinkPayload(payload.seed.copyOf(), payload.signerHead.copyOf(), payload.signerUserId).also { payload.seed.fill(0) })
    }

    private fun linkOf(k: me.raddatz.veyq.mobile.api.models.KeyChainStep) = Trust.KeyChainLink(
        k.index.toLong(), k.previousHead?.let(::unb64), unb64(k.identityPublicKey), unb64(k.secretKeyProofPublicKey),
        k.identitySignature?.let(::unb64), k.proofSignature?.let(::unb64),
    )

    /**
     * Whether a signed-in account may accept [o] against its pin store (the web's `memberInviteAnchored`): the inviting owner
     * judged by the chain the invite served, walked from the head inside the link (a first use pins that head); a foreign
     * granter the account already pinned judged against its own chain.
     */
    private suspend fun anchored(account: VeyqAccount, o: Opened): Boolean {
        val s = o.served
        val anchoring = account.anchoringOfAccount
        val foreign = s.grantedEpoch > s.anchorEpoch && s.granterUserId != s.signerUserId
        if (foreign && anchoring.pinned(s.granterUserId)) {
            val judged = anchoring.anchorChainKey(s.granterUserId, s.granterIdentityPublicKey, null)
            if (judged.anchor == Anchor.ANOMALY || judged.retired) return false
        }
        val head = b64(o.checked.signerHead)
        val chain = KeyChain(s.signerUserId, head, s.signerKeyChain)
        return anchoring.anchorServedChain(s.signerUserId, chain, ServedIdentity(b64(o.checked.signerIdentityPublicKey), head)) != Anchor.ANOMALY
    }

    /** The tenant key an accept went through with, to hold. */
    private class Held(val key: ByteArray, val epoch: Long) {
        fun zero() = key.fill(0)
    }

    /**
     * Sends an accept built by [send] from the opened invite (null for an OWNER invite). On `409 invite_changed` or `409
     * stale_epoch` the preview is read again, the invite checked again with the link's facts kept (no code asked again),
     * [again] judges it, and [send] goes once more. Null where it failed ([State.error] then says why).
     */
    private suspend fun accept(
        send: suspend (Opened?) -> Pair<HttpResponse<*>, Held>,
        again: (suspend (Opened) -> Boolean)? = null,
    ): Held? {
        val first = opened
        val (r, held) = send(first)
        if (r.success) return held
        held.zero()
        val code = errorCodeOf(r)
        if (first == null || r.status != 409 || (code != "invite_changed" && code != "stale_epoch")) {
            set { copy(error = errorWord(r, code)) }
            return null
        }
        val reread = runCatching { TenantsApi(origin, http).previewTenantInvite(AcceptPreviewRequest(fragment.token)) }.getOrNull()
        val s = reread?.takeIf { it.success }?.body()?.invite
        if (s == null || s.inviteId != first.served.inviteId) return null.also { set { copy(error = Msg.JOIN_INVITE_UNCHECKABLE) } }
        val re = try {
            check(s, Invites.LinkPayload(first.link.seed.copyOf(), first.link.signerHead, first.link.signerUserId))
        } catch (e: Invites.InviteCheckFailed) {
            return null.also { set { copy(error = Msg.JOIN_INVITE_UNCHECKABLE) } }
        }
        try {
            if (again != null && !again(re)) return null.also { set { copy(error = Msg.JOIN_INVITE_UNCHECKABLE) } }
            val (r2, held2) = send(re)
            if (r2.success) return held2
            held2.zero()
            set { copy(error = errorWord(r2)) }
            return null
        } finally {
            re.forget()
        }
    }

    /** A MEMBER accept's join (the web's `buildMemberJoin`): the self-grant, the invite key's proof, the sealed join record. */
    private class Join(
        val selfGrant: InviteSelfGrant, val inviteProof: String, val sealedJoinRecord: String, val wrappedJoinRecordValueKey: String,
        val signerIdentityDigest: String,
    )

    private fun memberJoin(o: Opened, userId: String, identity: IdentityPrivate, identityPub: ByteArray, proofPublic: ByteArray): Join {
        val s = o.served
        val c = o.checked
        val grant = sealGrant(c.epochKey, c.grantedEpoch, userId, identityPub, identity.signSeed)
        val proof = invites.signInviteAccept(c.inviteKey.priv.signSeed, s.inviteId, userId, identityPub)
        val record = invites.signJoinRecord(
            tenantId, userId, identityPub, proofPublic, s.inviteId, unb64(s.invitePublicKey), unb64(s.inviteKeySignature),
            Instant.parse(s.expiresAt).epochSeconds, s.signerUserId, c.signerIdentityPublicKey, c.inviteKey.priv.signSeed, identity.signSeed,
        )
        val sealed = joins.sealValueField(tenantId, c.grantedEpoch, c.epochKey, joins.recordContext(tenantId, userId), joins.encode(record))
        return Join(
            InviteSelfGrant(grant.wrappedGrant, grant.grantSignature, c.grantedEpoch.toInt(), grant.recipientIdentityDigest),
            b64(proof), b64(sealed.sealed), b64(sealed.wrappedValueKey), b64(primitives.sha256(c.signerIdentityPublicKey)),
        )
    }

    /** An OWNER's cold birth of the TENANT scope (the web's `bornTenantScope`): a fresh key granted to the owner at epoch 0. */
    private class Born(val key: ByteArray, val grant: VeyqAccount.SelfGrant)

    private fun bornTenant(userId: String, identity: IdentityPrivate, identityPub: ByteArray): Born {
        val key = primitives.randomBytes(32)
        return Born(key, sealGrant(key, 0, userId, identityPub, identity.signSeed))
    }

    private fun sealGrant(scopeKey: ByteArray, epoch: Long, toUserId: String, to: ByteArray, signSeed: ByteArray): VeyqAccount.SelfGrant {
        val wrapped = suite.sealTo(Context("scope-grant", "scopeId" to tenantId, "epoch" to epoch), suite.deserializeIdentityPublic(to).kem, scopeKey)
        val statement = suite.grantStatement(tenantId, "TENANT", epoch, toUserId, to, wrapped)
        return VeyqAccount.SelfGrant(b64(wrapped), b64(primitives.ed25519Sign(signSeed, statement)), b64(primitives.sha256(to)))
    }

    /** The OPTIONAL real name sealed under a fresh value key the tenant key wraps, bound to (tenant, member); null when both are empty. */
    private fun sealedName(key: ByteArray, epoch: Long, userId: String, first: String, last: String): JoinRecords.SealedValue? {
        if (first.isBlank() && last.isBlank()) return null
        val text = buildJsonObject {
            put("firstName", first.trim())
            put("lastName", last.trim())
        }.toString()
        return joins.sealValueField(tenantId, epoch, key, People.nameContext(tenantId, userId), utf8(text))
    }

    // ---- the account ----

    /** A new account's material (the web's `buildAccount`): its identity, Master Key and Secret Key stay here. */
    private class Built(val identity: IdentityPrivate, val masterKey: ByteArray, val secretKey: ByteArray, val material: AccountRegistration) {
        fun zero() {
            identity.kemSeed.fill(0)
            identity.signSeed.fill(0)
            masterKey.fill(0)
            secretKey.fill(0)
        }
    }

    /**
     * An identity, a Master Key with a PASSWORD wrap (`Argon2id(lp(secretKey) ‖ lp(password))`) and a RECOVERY wrap, the
     * auth key of `Argon2id(password, authSalt)` and the Secret-Key proof key — the cold birth of `accountBirth.ts`.
     */
    private fun build(email: String, password: String, instanceSalt: ByteArray): Built {
        val p = primitives
        val identity = IdentityPrivate(p.randomBytes(32), p.randomBytes(32))
        val masterKey = p.randomBytes(32)
        val secretKey = p.randomBytes(32)
        val priv = concat(lp(identity.kemSeed), lp(identity.signSeed))
        val wrappedPriv = suite.seal(Context("identity-priv"), masterKey, priv)
        priv.fill(0)
        val salt = p.randomBytes(16)
        val recSalt = p.randomBytes(16)
        val pwKek = suite.kekPasswordSecret(secretKey, password, salt)
        val recKek = suite.kekRecovery(secretKey, recSalt)
        val pwWrap = suite.seal(Context("master-key"), pwKek, masterKey)
        val recWrap = suite.seal(Context("master-key"), recKek, masterKey)
        pwKek.fill(0)
        recKek.fill(0)
        val authSeed = suite.authSeed(password, suite.authSalt(instanceSalt, email))
        val authPublic = p.ed25519Public(authSeed)
        authSeed.fill(0)
        val material = AccountRegistration(
            AccountRegistration.SuiteId.v1, b64(suite.identityPublic(identity).serialize()), b64(wrappedPriv), b64(authPublic),
            listOf(
                MasterKeyWrap(MasterKeyWrap.Method.PASSWORD, b64(pwWrap), kdfParams = b64(suite.encodeKdfParams(V1.ARGON, salt))),
                MasterKeyWrap(MasterKeyWrap.Method.RECOVERY, b64(recWrap), kdfParams = b64(suite.encodeKdfParams(V1.ARGON, recSalt))),
            ),
            b64(proofs.secretKeyProofPublic(secretKey)),
        )
        return Built(identity, masterKey, secretKey, material)
    }

    /**
     * After a registration: the new account signed in here once — to hold the accepted key under it (no vault held it at
     * the birth) and, for a link registration, to ask for its confirmation mail — and let go. Neither step is the join's: a
     * failure leaves the join done. The address the mail went to, or null.
     */
    private suspend fun afterBirth(email: String, password: String, secretKey: ByteArray, held: Held, askConfirmation: Boolean): String? = try {
        val account = signIn(origin, email, password, secretKey)
        try {
            runCatching { account.holdEpochKey(tenantId, held.epoch, held.key) }.onFailure { if (it is CancellationException) throw it }
            if (askConfirmation) {
                val r = runCatching { account.respond { AccountEmailApi(origin, account.http).requestEmailConfirmation() } }.getOrNull()
                if (r?.success == true) email else null
            } else {
                null
            }
        } finally {
            runCatching { account.retire() }.onFailure { if (it is CancellationException) throw it }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** What *Open the space* signs in with, kept in memory until it ran or the flow closed. */
    private class SignedIn(val email: String, val password: String, val secretKey: String)

    private var signedIn: SignedIn? = null

    private fun finish(email: String, password: String, secretKey: String, confirmationSentTo: String?) {
        opened?.forget()
        opened = null
        built?.zero()
        built = null
        pending = null
        kit?.close()
        kit = null
        signedIn = SignedIn(email, password, secretKey)
        set { copy(phase = Phase.DONE, error = null, confirmationSentTo = confirmationSentTo, canOpen = true) }
    }

    // ---- helpers ----

    private inline fun set(f: State.() -> State) = _state.update(f)

    private suspend inline fun busy(block: () -> Unit) {
        set { copy(busy = true, error = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            set { copy(error = Msg.JOIN_FAILED) }
        } finally {
            set { copy(busy = false) }
        }
    }

    private suspend fun errorCodeOf(r: HttpResponse<*>): String? = runCatching {
        (json.parseToJsonElement(r.response.bodyAsText()) as? JsonObject)?.get("code")?.jsonPrimitive?.content
    }.getOrNull()

    private suspend fun errorWord(r: HttpResponse<*>, known: String? = null): Msg {
        val code = known ?: errorCodeOf(r)
        return when {
            code == "invite_redeemed" -> Msg.JOIN_REDEEMED
            r.status == 410 -> Msg.JOIN_EXPIRED
            code == "already_member" -> Msg.JOIN_ALREADY_MEMBER
            r.status == 404 -> Msg.JOIN_INVALID
            code == "email_taken" -> Msg.JOIN_EMAIL_TAKEN
            code == "username_taken" -> Msg.JOIN_USERNAME_TAKEN
            code == "not_invited_owner" -> Msg.JOIN_WRONG_OWNER
            code == "not_tenant_member" -> Msg.JOIN_NOT_MEMBER_HANDOVER
            code == "invite_proof_invalid" -> Msg.JOIN_INVITE_UNCHECKABLE
            else -> Msg.JOIN_FAILED
        }
    }

    private fun signInWord(e: SignInFailure): Msg = when (e.reason) {
        SignInFailure.Reason.SECRET_KEY -> Msg.SIGNIN_ERROR_SECRET_KEY
        SignInFailure.Reason.UNREACHABLE -> Msg.SIGNIN_ERROR_UNREACHABLE
        else -> Msg.SIGNIN_ERROR_CREDENTIALS
    }

    companion object {
        /**
         * The join of [invite] on this device: its own cold sign-ins through [device] and [client] (the account door's);
         * *Open the space* signs in through the app's own session model ([openSpace]).
         */
        fun of(
            primitives: Primitives,
            device: ThisDevice,
            client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
            invite: me.raddatz.veyq.mobile.sharing.ReceivedLink.InviteLink,
        ): TenantJoin = TenantJoin(primitives, invite.origin, Invites.parseFragment(invite.fragment), client) { s, e, p, k ->
            VeyqAccount.signInWithKey(primitives, s, e, p, k, device, client)
        }

        private val ATOM = Regex("^[A-Za-z0-9!#$%&'*+\\-/=?^_`{|}~]+$")
        private val LABEL = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
        private val PRINTABLE = Regex("^[\\x20-\\x7e]+$")
        private val QUOTED = Regex("^\".*\"@.+$")

        /** The web's `isMailbox` (`shared/mailbox.ts`), exactly: what the contract would take; the rest is the server's. */
        fun isMailbox(value: String): Boolean {
            if (value.isEmpty() || value.length > 254 || !PRINTABLE.matches(value)) return false
            if (value.startsWith('"')) return QUOTED.matches(value)
            val at = value.indexOf('@')
            if (at <= 0 || at > 64) return false
            val local = value.substring(0, at)
            val domain = value.substring(at + 1)
            if (!local.split('.').all { ATOM.matches(it) }) return false
            if (domain.startsWith('[') && domain.endsWith(']') && domain.length > 2) return true
            return domain.length in 1..253 && domain.split('.').all { LABEL.matches(it) }
        }
    }
}
