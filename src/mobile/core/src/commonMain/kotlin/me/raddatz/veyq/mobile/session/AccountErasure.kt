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
import me.raddatz.veyq.mobile.api.models.EraseOwnAccountRequest
import me.raddatz.veyq.mobile.api.models.LastOwnerChoice
import me.raddatz.veyq.mobile.crypto.AccountProofs
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey

/**
 * Erase one's own account (uc.account.erase-account, ADR 0101, ADR 0148 decisions 8 and 9): a port of the web's
 * `eraseAccount.ts`. [load] reads the preview — the tenants this account is the last owner of (one with other members
 * needs a choice: delete it along, or one of its members as the new owner; one without is deleted along) —; the Server
 * Owner is told why not (`409 server_owner_account`); the last holder of the moderation key the preview names is told that
 * the key goes to the Server Owner first ([State.handsOver]), and the request carries it ([CredentialDoor.sealForServerOwner],
 * #965) — one transaction, so a refused erasure reappoints nobody.
 *
 * [erase] proves the PASSWORD only (no Secret Key: erasing re-wraps nothing): the auth key, derived as sign-in derives
 * it, signs `[userId, nonce]` of a fresh login challenge the request spends. A `200` ends every session of the account,
 * so nothing is sent after it: the device forgets the account as the web's ending does for a browser
 * ([CredentialDoor.erased]: the offline store wiped, the saved key, the trust marks and the record removed).
 */
class AccountErasure(
    private val door: CredentialDoor,
    private val primitives: Primitives,
) {
    private val suite = V1(primitives)
    private val proofs = AccountProofs(primitives)

    @Serializable
    data class Member(val userId: String, val name: String)

    /** A tenant whose last owner this account is. [members] empty: deleted along (the web's "sole" list). */
    @Serializable
    data class Tenant(val tenantId: String, val name: String, val members: List<Member>, val newOwnerId: String? = null, val chosen: Boolean = false)

    @Serializable
    data class State(
        val loaded: Boolean = false,
        val working: Boolean = false,
        /** The Server Owner's account: [Msg.ERASURE_SERVER_OWNER], no form. */
        val serverOwner: Boolean = false,
        /** The account holds the moderation key alone: it goes to the Server Owner first ([Msg.ERASURE_KEY_DELIVERY_TITLE],
         *  [Msg.ERASURE_KEY_DELIVERY_BODY], the web's note). */
        val handsOver: Boolean = false,
        val server: String = "",
        val tenants: List<Tenant> = emptyList(),
        /** The account is erased and this device forgot it: [Msg.ERASURE_ENDED]; the host signs out to the start. */
        val erased: Boolean = false,
        @Serializable(with = MsgKey::class) val refusal: Msg? = null,
    ) {
        /** Every tenant with other members has its choice. */
        val choicesMade: Boolean get() = tenants.all { it.members.isEmpty() || it.chosen }
    }

    private val _state = MutableStateFlow(State(server = door.server))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value
    private val acting = Mutex()

    /** The preview's hand-over, when this account is the last holder of an epoch the Server Owner misses. */
    private var delivery: me.raddatz.veyq.mobile.api.models.ServerOwnerKeyDelivery? = null

    class Watch internal constructor(private val job: kotlinx.coroutines.Job) {
        fun stop() = job.cancel()
    }

    fun watch(listener: (State) -> Unit): Watch = Watch(CoroutineScope(Dispatchers.Default).launch { state.collect(listener) })

    suspend fun load() = acting.withLock {
        try {
            val (reply, preview) = door.erasurePreview()
            if (reply.status == 409 && reply.code == "server_owner_account") {
                _state.value = State(loaded = true, serverOwner = true, server = door.server)
                return@withLock
            }
            if (!reply.ok || preview == null) {
                _state.value = State(loaded = true, server = door.server, refusal = Msg.ERASURE_LOAD_FAILED)
                return@withLock
            }
            delivery = preview.serverOwnerDelivery
            val tenants = preview.tenants.map { t ->
                val names = if (t.otherMemberIds.isEmpty()) emptyMap() else door.memberNames(t.tenantId, t.otherMemberIds)
                Tenant(t.tenantId, t.name, t.otherMemberIds.map { Member(it, names[it] ?: it) })
            }
            _state.value = State(loaded = true, handsOver = delivery != null, server = door.server, tenants = tenants)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = State(loaded = true, server = door.server, refusal = Msg.ERASURE_LOAD_FAILED)
        }
    }

    /** Tenant [tenantId]'s choice: [newOwnerId] one of its members, or null to delete it along. */
    fun choose(tenantId: String, newOwnerId: String?) {
        _state.value = current.copy(
            tenants = current.tenants.map { t ->
                if (t.tenantId != tenantId || (newOwnerId != null && t.members.none { it.userId == newOwnerId })) t
                else t.copy(newOwnerId = newOwnerId, chosen = true)
            },
            refusal = null,
        )
    }

    /** Erases the account with [password]; refused before any request without every choice, or where the hand-over could not be built. */
    suspend fun erase(password: String) = acting.withLock {
        val s = current
        if (!s.loaded || s.serverOwner || s.erased) return@withLock
        if (!s.choicesMade) {
            _state.value = s.copy(refusal = Msg.ERASURE_ERROR_CHOICE_REQUIRED)
            return@withLock
        }
        _state.value = s.copy(working = true, refusal = null)
        val choices = s.tenants.filter { it.members.isNotEmpty() }.map { LastOwnerChoice(it.tenantId, it.newOwnerId) }
        var seed: ByteArray? = null
        var handOver: Msg? = null
        val reply = try {
            // sealed first, before the challenge: a hand-over that cannot be built sends nothing (the web's order)
            val upload = delivery?.let { door.sealForServerOwner(it) }
            val challenge = door.challenge(door.email)
            seed = suite.authSeed(password, suite.authSalt(unb64(challenge.instanceSalt), door.email))
            val signature = primitives.ed25519Sign(seed, proofs.eraseAccountAttestationStatement(door.userId, unb64(challenge.nonce)))
            door.erase(EraseOwnAccountRequest(challenge.nonce, me.raddatz.veyq.mobile.crypto.b64(signature), choices, upload))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModerationGrants.DeliveryRefused) {
            handOver = if (e.reason == ModerationGrants.DeliveryRefused.Reason.UNANCHORED) Msg.ERASURE_ERROR_KEY_UNANCHORED else Msg.ERASURE_ERROR_KEY_NOT_HELD
            null
        } catch (e: Exception) {
            null
        } finally {
            seed?.fill(0)
        }
        if (reply?.ok == true) {
            runCatching { door.erased() }
            _state.value = current.copy(working = false, erased = true)
            return@withLock
        }
        val refusal = when {
            handOver != null -> handOver
            reply == null -> Msg.ERASURE_ERROR_FAILED
            reply.status == 403 -> Msg.ERASURE_ERROR_PASSWORD
            reply.status == 409 && reply.code == "last_owner_choice_required" -> Msg.ERASURE_ERROR_CHOICE_REQUIRED
            reply.status == 409 && reply.code == "server_owner_account" -> Msg.ERASURE_SERVER_OWNER
            reply.status == 409 && reply.code == "moderation_delivery_required" -> Msg.ERASURE_ERROR_KEY_DELIVERY
            reply.status == 409 && reply.code == "identity_changed" -> Msg.ERASURE_ERROR_KEY_IDENTITY_CHANGED
            else -> Msg.ERASURE_ERROR_FAILED
        }
        _state.value = current.copy(working = false, refusal = refusal)
    }
}
