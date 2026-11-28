package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.account.BreachCheck
import me.raddatz.veyq.mobile.account.KeyIntake
import me.raddatz.veyq.mobile.api.apis.LoginApi
import me.raddatz.veyq.mobile.api.apis.RecoveryApi
import me.raddatz.veyq.mobile.api.apis.SetupApi
import me.raddatz.veyq.mobile.api.models.LoginChallengeRequest
import me.raddatz.veyq.mobile.api.models.RecoveryCompleteRequest
import me.raddatz.veyq.mobile.api.models.RecoveryMaterial
import me.raddatz.veyq.mobile.api.models.RecoveryTokenRequest
import me.raddatz.veyq.mobile.crypto.AccountProofs
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.OpenRefused
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.sharing.ReceivedLink
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey
import me.raddatz.veyq.mobile.words.Notice

/** A recovery finish's step (the web's three, and the link checked or spent); its own name on the wire beside the join's `Phase`. */
enum class RecoveryPhase { LOADING, SPENT, KEY, PASSWORD, DONE }

/**
 * Finishing a password recovery (uc.account.recover-access steps 3–11, ADR 0054), the port of the web's `RecoverView.vue`
 * over `features/account/recovery/recovery.ts`. The mailed link (`<origin>/recover#<token>`) reaches it through *Open a
 * link* or shared text ([ReceivedLink.RecoveryLink]) — never `veyq://`, which carries no token (ADR 0150 § O1).
 *
 * The phases are the web's three steps: the material released for the token ([RecoveryPhase.LOADING]; any failure is
 * [RecoveryPhase.SPENT], the web's `linkSpent`), the Secret Key ([RecoveryPhase.KEY]: [intake], the flows' key control, bound to the
 * released address — the web's `expected-email`), opening the RECOVERY wrap (`kekRecovery`, no password), then a new
 * password ([RecoveryPhase.PASSWORD], its breached-password check [breach]) sealed into a fresh PASSWORD wrap, a fresh auth key and
 * the recovery proof (`secret_key_proof.recovery`) sent once ([RecoveryPhase.DONE]). The server then drops every device's trust
 * and every session; the Master Key, the identity and the Secret Key stay, so no rotation follows and the kit keeps
 * working. [RecoveryPhase.DONE] hands the sign-in its server and address ([SessionModel.recovered]).
 *
 * The token, the Secret Key, the Master Key and the passwords live in this object only, never in its [state]; [close]
 * zeroes what is held, and so does the done step.
 */
class RecoveryFinish internal constructor(
    private val primitives: Primitives,
    /** The server the link is on: its origin. */
    val origin: String,
    private val token: String,
    private val door: Door,
    /** The new password's breached-password check, at the instance's range service; none in a test that plays none. */
    val breach: BreachCheck = BreachCheck.none(),
) {
    /** The three calls the finish makes, without a session (`contracts/api/auth.openapi.yaml`, the recovery's and the login challenge). */
    interface Door {
        /** `releaseRecoveryMaterial`; null where the token is unknown, expired or spent (one indistinguishable 404). */
        suspend fun release(token: String): RecoveryMaterial?

        /** `requestLoginChallenge`'s `instanceSalt` for [email]: the auth salt's input. */
        suspend fun instanceSalt(email: String): String

        /** `completePasswordRecovery`: its HTTP status (204 done). */
        suspend fun complete(body: RecoveryCompleteRequest): Int

        /** The door's clients closed. */
        fun close() {}
    }

    @Serializable
    data class State(
        val phase: RecoveryPhase = RecoveryPhase.LOADING,
        /** The account the link is for, as the server released it ([Msg.RECOVERY_FOR_ACCOUNT]); empty before. */
        val email: String = "",
        val working: Boolean = false,
        @Serializable(with = MsgKey::class) val refusal: Msg? = null,
    ) {
        /** The key step's line naming the account. */
        val forAccount: Notice? = email.takeIf { it.isNotEmpty() }?.let { Notice(Msg.RECOVERY_FOR_ACCOUNT, it) }

        /** The step's heading, as the web's. */
        @Serializable(with = MsgKey::class) val title: Msg = when (phase) {
            RecoveryPhase.KEY -> Msg.RECOVERY_STEP_KEY
            RecoveryPhase.PASSWORD, RecoveryPhase.DONE -> Msg.RECOVERY_STEP_PASSWORD
            else -> Msg.RECOVERY_TITLE
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private val suite = V1(primitives)
    private val proofs = AccountProofs(primitives)
    private val acting = Mutex()

    /** The Secret Key step's control (ADR 0151 decision 1): typed, a pasted kit token, a kit file or a scan. */
    val intake = KeyIntake(primitives)

    private var material: RecoveryMaterial? = null
    private var masterKey: ByteArray? = null
    private var secretKey: ByteArray? = null

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (State) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    /** The material released for the link's token (the web's `consumeFragment`); the token is not spent by it. Once. */
    suspend fun load() = acting.withLock {
        if (current.phase != RecoveryPhase.LOADING) return@withLock
        val m = try {
            door.release(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (m == null) {
            _state.value = State(phase = RecoveryPhase.SPENT, refusal = Msg.RECOVERY_LINK_SPENT)
            return@withLock
        }
        material = m
        intake.bind(KeyIntake.Account(m.email, origin))
        _state.value = State(phase = RecoveryPhase.KEY, email = m.email)
        runCatching { breach.load() }
    }

    /** *Continue* with the Secret Key as the field holds it ([secretKey]: typed, a pasted kit token, or empty while a kit is held). */
    suspend fun unlock(secretKey: String) = unlock(intake.key(secretKey))

    /**
     * The RECOVERY wrap opened with [key] (32 bytes; kept for the proof on success, zeroed otherwise): the web's
     * `openWithSecretKey`. A wrong key says [Msg.RECOVERY_KEY_FAILED] and keeps the link; a served KDF profile the suite
     * does not write says [Msg.SIGNIN_ERROR_UNREACHABLE] (the web's `common.unreachable`).
     */
    internal suspend fun unlock(key: ByteArray) = acting.withLock {
        val m = material
        if (m == null || current.phase != RecoveryPhase.KEY || current.working) {
            key.fill(0)
            return@withLock
        }
        _state.value = current.copy(working = true, refusal = null)
        var kek: ByteArray? = null
        try {
            val (params, salt) = try {
                suite.decodeKdfParams(unb64(m.kdfParams))
            } catch (e: IllegalArgumentException) {
                key.fill(0)
                _state.value = current.copy(working = false, refusal = Msg.SIGNIN_ERROR_UNREACHABLE)
                return@withLock
            }
            kek = suite.kekRecovery(key, salt, params)
            val mk = try {
                suite.open(Context("master-key"), kek, unb64(m.wrappedKey))
            } catch (e: OpenRefused) {
                key.fill(0)
                _state.value = current.copy(working = false, refusal = Msg.RECOVERY_KEY_FAILED)
                return@withLock
            }
            masterKey = mk
            secretKey = key
            intake.spent()
            _state.value = current.copy(phase = RecoveryPhase.PASSWORD, working = false, refusal = null)
        } catch (e: CancellationException) {
            key.fill(0)
            throw e
        } catch (e: Exception) {
            key.fill(0)
            _state.value = current.copy(working = false, refusal = Msg.RECOVERY_KEY_FAILED)
        } finally {
            kek?.fill(0)
        }
    }

    /**
     * *Reset password* (the web's `completeRecovery`): [next] once [confirm] repeats it, it is long enough
     * ([PasswordChange.MIN_PASSWORD_LENGTH]) and the breach check lets it pass ([BreachCheck.proceed]). A fresh PASSWORD
     * wrap under `kekPasswordSecret`, a fresh auth key under the instance's salt and the recovery proof, sent once; any
     * refusal says [Msg.RECOVERY_PROOF_FAILED]. Done: every key this flow held zeroed.
     */
    suspend fun finish(next: String, confirm: String) = acting.withLock {
        val m = material ?: return@withLock
        val mk = masterKey ?: return@withLock
        val sk = secretKey ?: return@withLock
        if (current.phase != RecoveryPhase.PASSWORD || current.working) return@withLock
        if (next != confirm) return@withLock refuse(Msg.RECOVERY_MISMATCH)
        if (next.length < PasswordChange.MIN_PASSWORD_LENGTH) return@withLock refuse(Msg.PASSWORD_TOO_SHORT)
        _state.value = current.copy(working = true, refusal = null)
        if (!breach.proceed(next)) {
            _state.value = current.copy(working = false)
            return@withLock
        }
        var kek: ByteArray? = null
        var authSeed: ByteArray? = null
        val status = try {
            val salt = primitives.randomBytes(16)
            kek = suite.kekPasswordSecret(sk, next, salt, V1.ARGON)
            val wrapped = suite.seal(Context("master-key"), kek, mk)
            val kdfParams = suite.encodeKdfParams(V1.ARGON, salt)
            val authSalt = suite.authSalt(unb64(door.instanceSalt(m.email)), m.email)
            authSeed = suite.authSeed(next, authSalt)
            val authPublic = primitives.ed25519Public(authSeed)
            val statement = proofs.recoveryProofStatement(token, authPublic, AccountProofs.ProofWrap(wrapped, kdfParams))
            val signature = proofs.secretKeyProofSign(sk, statement)
            door.complete(RecoveryCompleteRequest(token, b64(signature), b64(wrapped), b64(kdfParams), b64(authPublic)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        } finally {
            kek?.fill(0)
            authSeed?.fill(0)
        }
        if (status !in 200..299) return@withLock refuse(Msg.RECOVERY_PROOF_FAILED)
        forget()
        _state.value = current.copy(phase = RecoveryPhase.DONE, working = false, refusal = null)
    }

    private fun refuse(msg: Msg) {
        _state.value = current.copy(working = false, refusal = msg)
    }

    /** The new password's field was left with [newPassword] in it: the breached-password check runs. */
    suspend fun check(newPassword: String) = breach.check(newPassword)

    /** The new password was edited: the check's verdict, and any acceptance of it, is gone. */
    fun edited() = breach.edited()

    /** *Use it anyway* for a breached new password, or taken back. */
    fun useAnyway(on: Boolean) = breach.useAnyway(on)

    /** The server and address the sign-in is filled with once [RecoveryPhase.DONE] ([SessionModel.recovered]); null before. */
    val signIn: Pair<String, String>? get() = if (current.phase == RecoveryPhase.DONE) origin to current.email else null

    private fun forget() {
        masterKey?.fill(0)
        secretKey?.fill(0)
        masterKey = null
        secretKey = null
    }

    /** The flow closed: every key it held zeroed, a held kit's key too, the check's password dropped. */
    fun close() {
        forget()
        material = null
        intake.clear()
        breach.close()
        door.close()
    }

    /** What the flow holds of the keys, for the tests that check its zeroing: the Master Key's and the Secret Key's arrays. */
    internal val heldForTest: List<ByteArray> get() = listOfNotNull(masterKey, secretKey)

    companion object {
        /** The finish of [link] over [door] (a test's played server; [breach] none). */
        fun over(primitives: Primitives, link: ReceivedLink.RecoveryLink, door: Door, breach: BreachCheck = BreachCheck.none()): RecoveryFinish =
            RecoveryFinish(primitives, link.origin, link.token, door, breach)

        /** The finish of [link] over its server: two clients of its own (the API's and the range service's), closed with it. */
        fun of(
            primitives: Primitives,
            client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient,
            link: ReceivedLink.RecoveryLink,
        ): RecoveryFinish {
            val http = client {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val outside = client { expectSuccess = false }
            val origin = link.origin
            val door = object : Door {
                override suspend fun release(token: String): RecoveryMaterial? {
                    val r = RecoveryApi(origin, http).releaseRecoveryMaterial(RecoveryTokenRequest(token))
                    return if (r.success) r.body() else null
                }

                override suspend fun instanceSalt(email: String): String {
                    val r = LoginApi(origin, http).requestLoginChallenge(LoginChallengeRequest(email))
                    check(r.success) { "login challenge ${r.status}" }
                    return r.body().instanceSalt
                }

                override suspend fun complete(body: RecoveryCompleteRequest): Int =
                    RecoveryApi(origin, http).completePasswordRecovery(body).status

                override fun close() {
                    http.close()
                    outside.close()
                }
            }
            val breach = BreachCheck(OutsideHttp(outside, origin)) {
                val r = SetupApi(origin, http).getSetupStatus()
                if (r.success) r.body().capabilities.firstOrNull { it.id == BreachCheck.CAPABILITY }?.serviceUrl else null
            }
            return RecoveryFinish(primitives, origin, link.token, door, breach)
        }
    }
}
