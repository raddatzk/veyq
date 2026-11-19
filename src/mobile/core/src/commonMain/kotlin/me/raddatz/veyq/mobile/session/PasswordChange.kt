package me.raddatz.veyq.mobile.session

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
import me.raddatz.veyq.mobile.api.models.AccountKeyMaterial
import me.raddatz.veyq.mobile.api.models.ChangePasswordRequest
import me.raddatz.veyq.mobile.api.models.LoginChallenge
import me.raddatz.veyq.mobile.api.models.RotatedWrap
import me.raddatz.veyq.mobile.crypto.AccountProofs
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * What the account's credential flows (the password, the address, the erasure, a kit made anew) read and write of the
 * signed-in account: [VeyqAccount]'s ([AccountCredentialDoor]) in the app, a played one in the tests.
 */
interface CredentialDoor {
    val userId: String
    val email: String
    val server: String

    /** A COPY of the account's Master Key (the caller zeroes it), null once the account closed. */
    fun masterKey(): ByteArray?

    /** `getAccountKeyMaterial`, read now. */
    suspend fun keyMaterial(): AccountKeyMaterial

    /** A login challenge for [email]: the instance salt the auth key derives under, and a nonce. */
    suspend fun challenge(email: String): LoginChallenge

    suspend fun changePassword(body: ChangePasswordRequest): FlowReply

    /** After a `204`: the server deleted every wrap but PASSWORD and RECOVERY, this device's saved key with them — the
     *  local record of it goes as *Stop trusting* forgets it, the offline store stays (the same Master Key). */
    suspend fun passwordChanged()

    suspend fun requestEmailChange(body: me.raddatz.veyq.mobile.api.models.EmailChangeRequest): FlowReply

    /** After a change was requested: this device remembers it, so the sign-in that takes the new address offers the kit. */
    fun addressChangeRequested() {}

    /** Whether this session's sign-in took a new address — a change confirmed on the web since this device last signed
     *  in (ADR 0148 decision 6): what offers the new kit ([AddressChange.State.kitOffered]). */
    val addressTaken: Boolean get() = false

    /** `getAccountEmail`: the address the server holds now — what a new kit prints (the web's confirm page reads it). */
    suspend fun heldEmail(): String = email

    /** `getOwnAccountErasurePreview`: its answer, or the refusal's status and code (`409 server_owner_account`). */
    suspend fun erasurePreview(): Pair<FlowReply, me.raddatz.veyq.mobile.api.models.AccountErasurePreview?>

    /** The display names of [userIds], members of tenant [tenantId], as this account reads them (the id where it reads none). */
    suspend fun memberNames(tenantId: String, userIds: List<String>): Map<String, String>

    /** Whether this account holds a sealed grant of the instance moderation key (`listMyModerationGrants`), which the
     *  apps cannot carry or hand on yet (ADR 0148 decision 9, #965). */
    suspend fun holdsModerationKey(): Boolean

    suspend fun erase(body: me.raddatz.veyq.mobile.api.models.EraseOwnAccountRequest): FlowReply

    /** After the erasure's `200` (every session of the account ended): this device forgets the account — its offline
     *  store wiped, its saved key, its trust marks and its record removed, its keys zeroed — and sends nothing more. */
    suspend fun erased()
}

/**
 * Change the password (uc.account.change-password, ADR 0148 decision 6): a port of the web's `changePassword.ts` step
 * for step. The current password and the Secret Key are proven **together, against the stored floor** first
 * ([ColdCredential]); then the old password's auth key attests the new one (its raw public key, `v1.json#auth_attestation`),
 * the vault's Master Key is wrapped anew under Argon2id(lp(Secret Key) ‖ lp(new password), fresh salt), and the
 * Secret-Key proof signs the change (`passwordChangeProofStatement`). The server checks both signatures and, on `204`,
 * deletes every other wrap and revokes every other session family: this device's saved key with them, so the trust row
 * offers each level and the password trust again ([trustChanges]) — once the Settings page shown reads anew (its host's
 * `refresh` after [State.done]; the bridge's for the desktops, inbox #1009).
 *
 * The new password passes the web's length policy ([MIN_PASSWORD_LENGTH]); the apps make no breached-password check
 * (none on their sign-in either — inbox #969). The Secret Key, the passwords and every key derived here live for the call
 * only: zeroed in a `finally`.
 */
class PasswordChange(
    /** The account the change is of: [VeyqAccount.passwordChange]'s, a played one in a test. */
    private val door: CredentialDoor,
    private val primitives: Primitives,
) {
    private val suite = V1(primitives)
    private val proofs = AccountProofs(primitives)

    /** The Secret Key section's control (ADR 0151 decision 1): a pasted kit token, a kit file or a scan, held for this flow. */
    val intake = me.raddatz.veyq.mobile.account.KeyIntake(primitives, me.raddatz.veyq.mobile.account.KeyIntake.Account(door.email, door.server))

    @Serializable
    data class State(
        val working: Boolean = false,
        /** The change went through: [Msg.PASSWORD_CHANGE_DONE] with [Msg.PASSWORD_CHANGE_DONE_NOTE]; this device is no
         *  longer trusted, and Settings' trust row, read anew, offers the levels and the password trust ([trustChanges]). */
        val done: Boolean = false,
        @Serializable(with = MsgKey::class) val refusal: Msg? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value
    private val acting = Mutex()

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (State) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    /**
     * Changes the password with the Secret Key as the field holds it ([secretKey]: typed, a pasted kit token, or empty
     * while the [intake] holds a kit's key — [KeyIntake.key]); the held key is spent once the change went through.
     */
    suspend fun run(currentPassword: String, next: String, confirm: String, secretKey: String) =
        run(currentPassword, next, confirm, intake.key(secretKey))

    /**
     * Changes the password: [currentPassword] and [secretKey] (32 bytes; zeroed by this call, whatever it comes to)
     * prove the account, [next] is installed once [confirm] repeats it and it is long enough. Refusals change nothing.
     */
    internal suspend fun run(currentPassword: String, next: String, confirm: String, secretKey: ByteArray) = acting.withLock {
        try {
            if (next != confirm) return@withLock refuse(Msg.PASSWORD_CHANGE_MISMATCH)
            if (next.length < MIN_PASSWORD_LENGTH) return@withLock refuse(Msg.PASSWORD_TOO_SHORT)
            _state.value = State(working = true)
            _state.value = when (val outcome = change(currentPassword, next, secretKey)) {
                null -> State(done = true)
                else -> State(refusal = outcome)
            }
            if (current.done) intake.spent()
        } finally {
            secretKey.fill(0)
        }
    }

    private fun refuse(msg: Msg) {
        _state.value = State(refusal = msg)
    }

    /** The change itself; null once it went through, else what to say. */
    private suspend fun change(currentPassword: String, next: String, secretKey: ByteArray): Msg? {
        val mk = try {
            ColdCredential.prove(suite, door.masterKey(), door.keyMaterial(), currentPassword, secretKey)
        } catch (e: ColdCredential.Refused) {
            return when (ColdCredential.staleAsRejected(e).reason) {
                ColdCredential.Refusal.NO_FLOOR -> Msg.PASSWORD_CHANGE_ERROR_NO_FLOOR
                ColdCredential.Refusal.LOCKED -> Msg.PASSWORD_CHANGE_ERROR_FAILED
                else -> Msg.PASSWORD_CHANGE_ERROR_CREDENTIAL
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Msg.PASSWORD_CHANGE_ERROR_FAILED
        }
        var oldSeed: ByteArray? = null
        var newSeed: ByteArray? = null
        var kek: ByteArray? = null
        try {
            val challenge = door.challenge(door.email)
            val authSalt = suite.authSalt(unb64(challenge.instanceSalt), door.email)
            newSeed = suite.authSeed(next, authSalt)
            oldSeed = suite.authSeed(currentPassword, authSalt)
            val newAuth = primitives.ed25519Public(newSeed)
            val oldAuth = primitives.ed25519Public(oldSeed)
            val attestation = proofs.passwordChangeAttestation(oldSeed, newAuth)

            val salt = primitives.randomBytes(16)
            kek = suite.kekPasswordSecret(secretKey, next, salt, V1.ARGON)
            val wrapped = suite.seal(Context("master-key"), kek, mk)
            val kdfParams = suite.encodeKdfParams(V1.ARGON, salt)
            val statement = proofs.passwordChangeProofStatement(oldAuth, newAuth, AccountProofs.ProofWrap(wrapped, kdfParams))
            val proof = proofs.secretKeyProofSign(secretKey, statement)

            val reply = door.changePassword(
                ChangePasswordRequest(
                    passwordWrap = RotatedWrap(b64(wrapped), b64(kdfParams)),
                    authPublicKey = b64(newAuth),
                    attestationSignature = b64(attestation),
                    secretKeyProofSignature = b64(proof),
                ),
            )
            if (!reply.ok) return if (reply.status == 403) Msg.PASSWORD_CHANGE_ERROR_ATTESTATION else Msg.PASSWORD_CHANGE_ERROR_FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Msg.PASSWORD_CHANGE_ERROR_FAILED
        } finally {
            mk.fill(0)
            oldSeed?.fill(0)
            newSeed?.fill(0)
            kek?.fill(0)
        }
        runCatching { door.passwordChanged() }
        return null
    }

    /** Back to the form ("Change it again"). */
    fun reset() {
        _state.value = State()
    }

    /** The flow closed: a held kit's key is zeroed. */
    fun close() {
        intake.clear()
    }

    companion object {
        /** The web's `MIN_PASSWORD_LENGTH` (`shared/passwordPolicy.ts`): the password is checked on the client only. */
        const val MIN_PASSWORD_LENGTH = 10
    }
}
