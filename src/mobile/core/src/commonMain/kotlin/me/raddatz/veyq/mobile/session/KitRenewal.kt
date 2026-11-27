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
import me.raddatz.veyq.mobile.account.KitRecord
import me.raddatz.veyq.mobile.account.KitStep
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * Making a new Emergency Kit when a clearer one exists (uc.account.renew-emergency-kit steps 3-6, ADR 0157 decision 6; the
 * web's `KitRenewalDialog.vue`), opened from Settings → Security where [me.raddatz.veyq.mobile.account.KitStatus] says
 * the account's kit is of an older layout. **Proven before printed:** the password and the Secret Key (typed, or the old
 * kit read or scanned into [intake]) are checked against the stored floor ([ColdCredential.prove]); only the key that
 * passed goes into the [KitStep], which prints the current layout with the same Secret Key. A refused proof makes nothing
 * and writes nothing (E1). The proof seals the kit record with the Master Key it proved and drops the key; the record is
 * written once the kit was saved and kept ([KitStep]'s "I saved it"), never at render; a write that fails leaves a valid kit
 * and says so ([Msg.KIT_RENEW_NOT_NOTED], E3).
 */
class KitRenewal(
    private val door: CredentialDoor,
    private val primitives: Primitives,
) {
    private val suite = V1(primitives)

    /** The Secret Key section's control (ADR 0151 decision 1): typed, a kit read or scanned. */
    val intake = me.raddatz.veyq.mobile.account.KeyIntake(primitives, me.raddatz.veyq.mobile.account.KeyIntake.Account(door.email, door.server))

    @Serializable
    data class State(
        val working: Boolean = false,
        /** Why no kit was made ([Msg.KIT_RENEW_REFUSED], [Msg.KIT_RENEW_FAILED]); null otherwise. */
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
     * The new kit after [password] and the Secret Key as the field holds it ([secretKey]: typed, a pasted token, or empty
     * for the held kit's) passed the floor: the step to save it, or null and [State.refusal]. The caller closes the step.
     */
    suspend fun kit(password: String, secretKey: String): KitStep? = kit(password, intake.key(secretKey))

    /** [kit] with the key read: [secretKey] (32 bytes) zeroed by this call. */
    internal suspend fun kit(password: String, secretKey: ByteArray): KitStep? = acting.withLock {
        try {
            _state.value = State(working = true)
            val mk = ColdCredential.prove(suite, door.masterKey(), door.keyMaterial(), password, secretKey)
            val record = try {
                KitRecord.seal(suite, mk)
            } finally {
                mk.fill(0)
            }
            _state.value = State()
            KitStep(primitives, door.email, door.server, secretKey, kept = { door.writeKitRecord(record) }).also { intake.spent() }
        } catch (e: ColdCredential.Refused) {
            // a vault behind a secured account is "didn't match" here, as on every credential screen but the rotation's
            _state.value = State(refusal = if (ColdCredential.staleAsRejected(e).reason == ColdCredential.Refusal.REJECTED) Msg.KIT_RENEW_REFUSED else Msg.KIT_RENEW_FAILED)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = State(refusal = Msg.KIT_RENEW_FAILED)
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

/** A new Emergency Kit for this account, where a clearer one exists (ADR 0157 decision 6). */
fun VeyqAccount.kitRenewal(): KitRenewal = KitRenewal(AccountCredentialDoor(this), primitives)
