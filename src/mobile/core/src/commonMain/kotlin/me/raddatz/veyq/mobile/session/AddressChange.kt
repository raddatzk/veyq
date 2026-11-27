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
import me.raddatz.veyq.mobile.account.KitStep
import me.raddatz.veyq.mobile.api.models.EmailChangeRequest
import me.raddatz.veyq.mobile.crypto.AccountProofs
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * Change the address the account signs in with (uc.account.change-email, ADR 0074 decision 8, ADR 0148 decision 6): a
 * port of the web's `emailChange.ts` request. The password and the Secret Key are proven against the stored floor first
 * ([ColdCredential]); the auth key is derived under the NEW address's salt from the unchanged password, attested by the
 * current one (`emailChangeAttestationStatement`) and proven by the Secret Key (`emailChangeProofStatement`). The server
 * keeps it pending and mails a link to the new address. **The confirm is the web's alone** (owner 2026-11-18): the link
 * opens the web's page, which confirms; the confirm revokes every other session family, this app's included, and the
 * app takes the new address at its next sign-in, re-addressing the record it keeps ([VeyqAccount]'s sign-in).
 *
 * The new Emergency Kit follows the confirm, as on the web (its confirm page offers it once the server holds the new
 * address, inbox #1010): before the link is opened the account's address is still the old one, so a kit made then would
 * print it. The app offers the kit once its sign-in took the new address ([State.kitOffered], [CredentialDoor.addressTaken]
 * — the record this device keeps, or the request it remembered, named the old one); [kit] checks the password and the Secret Key against the floor
 * ([ColdCredential.prove]) — a kit made from a mistyped key fails exactly on the day it is needed —, then makes [KitStep]
 * with the address the server holds now ([CredentialDoor.heldEmail]) and the same Secret Key.
 */
class AddressChange(
    private val door: CredentialDoor,
    private val primitives: Primitives,
) {
    private val suite = V1(primitives)
    private val proofs = AccountProofs(primitives)

    /** The Secret Key section's control (ADR 0151 decision 1), the request's and the kit step's: held for this flow. */
    val intake = me.raddatz.veyq.mobile.account.KeyIntake(primitives, me.raddatz.veyq.mobile.account.KeyIntake.Account(door.email, door.server))

    @Serializable
    data class State(
        val working: Boolean = false,
        /** The request went through: [Msg.EMAIL_CHANGE_SENT] with this address. */
        val sentTo: String? = null,
        @Serializable(with = MsgKey::class) val refusal: Msg? = null,
        /** "Print a new Emergency Kit" is offered: this session's sign-in took the new address (a change confirmed on the
         *  web). Never right after a request — the confirm is still to come, and the account's address the old one. */
        val kitOffered: Boolean = false,
    )

    private val _state = MutableStateFlow(State(kitOffered = door.addressTaken))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value
    private val acting = Mutex()

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (State) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    /** Asks for the change with the Secret Key as the field holds it (typed, a pasted kit token, or empty for the held
     *  kit's, [KeyIntake.key]); the held key is spent once the request went through. */
    suspend fun request(newEmail: String, password: String, secretKey: String) = request(newEmail, password, intake.key(secretKey))

    /** Asks for the change to [newEmail]; [secretKey] (32 bytes) is zeroed by this call whatever it comes to. */
    internal suspend fun request(newEmail: String, password: String, secretKey: ByteArray) = acting.withLock {
        try {
            val address = newEmail.trim()
            if (address.isEmpty()) return@withLock
            val offered = current.kitOffered
            if (address.lowercase() == door.email.trim().lowercase()) {
                _state.value = State(refusal = Msg.EMAIL_CHANGE_UNCHANGED, kitOffered = offered)
                return@withLock
            }
            _state.value = State(working = true, kitOffered = offered)
            _state.value = send(address, password, secretKey)?.let { State(refusal = it, kitOffered = offered) } ?: State(sentTo = address, kitOffered = offered)
            if (current.sentTo != null) {
                intake.spent()
                runCatching { door.addressChangeRequested() }
            }
        } finally {
            secretKey.fill(0)
        }
    }

    private suspend fun send(address: String, password: String, secretKey: ByteArray): Msg? {
        try {
            ColdCredential.prove(suite, door.masterKey(), door.keyMaterial(), password, secretKey).fill(0)
        } catch (e: ColdCredential.Refused) {
            return when (ColdCredential.staleAsRejected(e).reason) {
                ColdCredential.Refusal.REJECTED -> Msg.EMAIL_CHANGE_ERROR_CREDENTIAL
                else -> Msg.EMAIL_CHANGE_ERROR_FAILED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Msg.EMAIL_CHANGE_ERROR_FAILED
        }
        var currentSeed: ByteArray? = null
        var newSeed: ByteArray? = null
        try {
            val challenge = door.challenge(door.email)
            val instanceSalt = unb64(challenge.instanceSalt)
            currentSeed = suite.authSeed(password, suite.authSalt(instanceSalt, door.email))
            newSeed = suite.authSeed(password, suite.authSalt(instanceSalt, address))
            val currentAuth = primitives.ed25519Public(currentSeed)
            val newAuth = primitives.ed25519Public(newSeed)
            val attestation = primitives.ed25519Sign(currentSeed, proofs.emailChangeAttestationStatement(door.userId, address, newAuth))
            val proof = proofs.secretKeyProofSign(secretKey, proofs.emailChangeProofStatement(currentAuth, newAuth, address))
            val reply = door.requestEmailChange(EmailChangeRequest(address, b64(newAuth), b64(attestation), b64(proof)))
            if (reply.ok) return null
            return when (reply.status) {
                403 -> Msg.EMAIL_CHANGE_ERROR_CREDENTIAL
                503 -> Msg.EMAIL_CHANGE_MAIL_UNAVAILABLE
                404 -> Msg.EMAIL_CHANGE_NOT_MEMBER
                429 -> Msg.EMAIL_CHANGE_RATE_LIMITED
                else -> Msg.EMAIL_CHANGE_ERROR_FAILED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Msg.EMAIL_CHANGE_ERROR_FAILED
        } finally {
            currentSeed?.fill(0)
            newSeed?.fill(0)
        }
    }

    /**
     * A new kit with the account's new address — the server's ([CredentialDoor.heldEmail]) —, after [password] and
     * [secretKey] (zeroed by this call) passed the floor: the step to save it, or null and [State.refusal]
     * ([Msg.EMAIL_CHANGE_KIT_REFUSED], [Msg.EMAIL_CHANGE_KIT_FAILED]). Null and nothing said where it is not offered
     * ([State.kitOffered]). The caller closes the step.
     */
    suspend fun kit(password: String, secretKey: String): KitStep? = kit(password, intake.key(secretKey))

    /** [kit] with the key read: [secretKey] (32 bytes) zeroed by this call. */
    internal suspend fun kit(password: String, secretKey: ByteArray): KitStep? = acting.withLock {
        try {
            if (!current.kitOffered) return@withLock null
            // the kit's record sealed while the proof holds the Master Key, written once the kit is kept (ADR 0157 decision 4)
            val mk = ColdCredential.prove(suite, door.masterKey(), door.keyMaterial(), password, secretKey)
            val record = try {
                me.raddatz.veyq.mobile.account.KitRecord.seal(suite, mk)
            } finally {
                mk.fill(0)
            }
            KitStep(primitives, door.heldEmail(), door.server, secretKey, kept = { door.writeKitRecord(record) }).also { intake.spent() }
        } catch (e: ColdCredential.Refused) {
            _state.value = current.copy(refusal = Msg.EMAIL_CHANGE_KIT_REFUSED)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = current.copy(refusal = Msg.EMAIL_CHANGE_KIT_FAILED)
            null
        } finally {
            secretKey.fill(0)
        }
    }

    /** The flow closed: a held kit's key is zeroed. */
    fun close() {
        intake.clear()
    }
}
