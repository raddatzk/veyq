package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.identity.keychain.KeyChainStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** One accepted or pending moderator as the Server Owner's roster shows them. */
data class RosterEntry(
    val accountId: String,
    val address: String,
    val accepted: Boolean,
    val appointedAt: Instant,
    val acceptedAt: Instant?,
    val identityPublicKey: String?,
    val holdsCurrentEpoch: Boolean,
    val addressConfirmed: Boolean,
)

/** The roster and the key's state (`getInstanceModeration`). */
data class RosterView(
    val currentEpoch: Int?,
    val rotationDueSince: Instant?,
    val ownerId: String,
    val ownerIdentityPublicKey: String,
    val moderators: List<RosterEntry>,
)

/** An epoch's bundle with the caller's grant of it, or null where it waits for a delivery. */
data class EpochGrant(val epoch: Int, val kemPublicKey: String, val signPublicKey: String, val sealedGrant: String?)

/** An accepted moderator missing epochs the caller holds, with the key to seal to and the head of its key chain. */
data class PendingDelivery(val accountId: String, val identityPublicKey: String, val keyChainHead: String, val epochs: List<Int>)

/** A grant in a mint or a delivery: the moderator, the identity it is sealed to, the epoch and the ciphertext. */
data class GrantIn(val accountId: String, val identityPublicKey: String, val epoch: Int, val sealedGrant: String)

/** Where the last holder's erasure delivers the key (ADR 0101 decision 9): the Server Owner, the identity and key-chain
 *  head to anchor and seal to, and the live epochs the holder holds that the Server Owner misses (never empty). */
data class ServerOwnerDelivery(val serverOwnerId: String, val identityPublicKey: String, val keyChainHead: String, val epochs: List<Int>)

/** The key a last holder's erasure carries to the Server Owner: the identity sealed to, and one grant per epoch. */
data class ServerOwnerDeliveryIn(val identityPublicKey: String, val grants: List<EpochGrantIn>)

/** One epoch's grant in a [ServerOwnerDeliveryIn]. */
data class EpochGrantIn(val epoch: Int, val sealedGrant: String)

/** Which erasure door a moderator leaves the roster through (ADR 0101 decision 9, ADR 0099 § 5). */
sealed interface ErasureDoor {
    /** The person's own: as the last holder, [delivery] carries the key to the Server Owner first. */
    data class Own(val delivery: ServerOwnerDeliveryIn?) : ErasureDoor

    /** The Server Owner's: no client of the person is there to deliver, so the last holder goes with the key — once
     *  [keyLossConfirmed] says the Server Owner was warned. */
    data class ServerOwners(val keyLossConfirmed: Boolean) : ErasureDoor
}

/** A moderation write's refusals (codes per the two specs). */
object ModerationFailures {
    val moderationOff = httpFailure("moderation_off", FailureStatus.CONFLICT)
    val moderationOffKey = httpFailure("moderation_off", FailureStatus.NOT_FOUND)
    val moderationEnabled = httpFailure("moderation_enabled", FailureStatus.CONFLICT)
    val accountNotFound = httpFailure("account_not_found", FailureStatus.NOT_FOUND)
    val notModeratorOnRoster = httpFailure("not_moderator", FailureStatus.NOT_FOUND)
    val notModerator = httpFailure("not_moderator", FailureStatus.FORBIDDEN)
    val notAppointed = httpFailure("not_appointed", FailureStatus.NOT_FOUND)
    val lastHolder = httpFailure("moderation_last_holder", FailureStatus.CONFLICT)
    val deliveryRequired = httpFailure("moderation_delivery_required", FailureStatus.CONFLICT)
    val grantsIncomplete = httpFailure("moderation_grants_incomplete", FailureStatus.CONFLICT)
    val epochStale = httpFailure("moderation_epoch_stale", FailureStatus.CONFLICT)
    val identityChanged = httpFailure("identity_changed", FailureStatus.CONFLICT)
    val notHolder = httpFailure("not_holder", FailureStatus.CONFLICT)
}

/** What a write answers: the value, or the refusal it ended with (nothing stored then). */
sealed interface ModerationOutcome<out T> {
    data class Ok<T>(val value: T) : ModerationOutcome<T>
    data class Refused(val failure: HttpFailure) : ModerationOutcome<Nothing>, HttpFailure by failure
}

/**
 * Who moderates and the key they hold (ADR 0099 §§ 1–5, uc.moderation.appoint-moderators,
 * uc.moderation.serve-as-moderator): the roster, the epochs and their grants. Operator-blind: it stores public bundles
 * and sealed grants it cannot open, never `M_n`.
 *
 * **One lock serializes every roster and key write: the current epoch's row** (PESSIMISTIC_WRITE), taken first. A mint
 * reads "the accepted moderators" and refuses unless its grants cover exactly them; without the lock an accept that
 * commits between that read and the mint's insert leaves a moderator out of the new epoch, and a removal racing a
 * delivery could hand the key to someone the roster just dropped. Turning moderation on has no epoch to lock; two
 * concurrent turn-ons collide on epoch 1's primary key and the loser rolls back. Lock order: epoch row, then moderator
 * rows, then grants (an identity rotation holds the account row before it, `RotationHandler`).
 */
@ApplicationScoped
class ModerationHandler(
    private val accounts: AccountStore,
    private val log: ModerationLog,
    private val keyChain: KeyChainStore,
) {
    private val rng = SecureRandom()

    // ── Server Owner (admin channel) ─────────────────────────────────────────────────────────────

    @Transactional
    fun roster(ownerId: String): RosterView {
        val current = currentEpoch(lock = false)
        val owner = VeyqUser.findById(ownerId) ?: error("the caller's account is gone")
        val rows = ModeratorEntity.list("removedAt is null order by appointedAt, accountId")
        val holders = current?.let { holdersOf(it.epoch) } ?: emptySet()
        return RosterView(
            currentEpoch = current?.epoch,
            rotationDueSince = current?.rotationDueAt,
            ownerId = ownerId,
            ownerIdentityPublicKey = owner.identityPublicKey,
            moderators = rows.map { m ->
                val user = VeyqUser.findById(m.accountId)
                RosterEntry(
                    accountId = m.accountId,
                    address = user?.email ?: "",
                    accepted = m.acceptedAt != null,
                    appointedAt = m.appointedAt,
                    acceptedAt = m.acceptedAt,
                    identityPublicKey = user?.identityPublicKey?.takeIf { m.acceptedAt != null },
                    holdsCurrentEpoch = m.acceptedAt != null && m.accountId in holders,
                    addressConfirmed = ModeratorAddress.confirmed(m, user?.email),
                )
            },
        )
    }

    /**
     * Epoch 1, sealed to the Server Owner alone; the Server Owner becomes an accepted moderator holding it. First-run
     * setup calls it in its own transaction (`SetupStore.persistOwner`): no operation turns moderation on later.
     */
    @Transactional
    fun enable(ownerId: String, kem: String, sign: String, identityPublicKey: String, sealedGrant: String, now: Instant = Instant.now()): ModerationOutcome<Unit> {
        if (!bundleWellFormed(kem, sign)) return ModerationOutcome.Refused(Failures.invalidRequest)
        if (ModerationEpochEntity.count() > 0) return ModerationOutcome.Refused(ModerationFailures.moderationEnabled)
        val owner = VeyqUser.findById(ownerId) ?: error("the caller's account is gone")
        if (owner.identityPublicKey != identityPublicKey) return ModerationOutcome.Refused(ModerationFailures.identityChanged)
        val row = upsertAppointment(ownerId, ownerId, now)
        row.acceptedAt = now
        log.append(ModerationEvent.APPOINTED, now, ownerId, ownerId)
        log.append(ModerationEvent.ACCEPTED, now, ownerId, ownerId)
        insertEpoch(1, kem, sign, MintReason.INITIAL)
        insertGrant(ownerId, 1, sealedGrant, ownerId)
        log.append(ModerationEvent.EPOCH_MINTED, now, ownerId, ownerId, epoch = 1, reason = MintReason.INITIAL.name)
        return ModerationOutcome.Ok(Unit)
    }

    /** A pending appointment for the account with [address]; nothing changes for someone already on the roster. */
    @Transactional
    fun appoint(ownerId: String, address: String, now: Instant = Instant.now()): ModerationOutcome<Unit> {
        currentEpoch(lock = true) ?: return ModerationOutcome.Refused(ModerationFailures.moderationOff)
        val account = accounts.findByEmail(address) ?: return ModerationOutcome.Refused(ModerationFailures.accountNotFound)
        val existing = ModeratorEntity.findById(account.id, LockModeType.PESSIMISTIC_WRITE)
        if (existing?.onRoster == true) return ModerationOutcome.Ok(Unit)
        upsertAppointment(account.id, ownerId, now)
        log.append(ModerationEvent.APPOINTED, now, account.id, ownerId)
        return ModerationOutcome.Ok(Unit)
    }

    /** The Server Owner removes a moderator (pending or accepted): role and every grant go in this transaction. */
    @Transactional
    fun remove(ownerId: String, accountId: String, now: Instant = Instant.now()): ModerationOutcome<Unit> =
        leave(accountId, ownerId, ModerationEvent.REMOVED, now, ModerationFailures.notModeratorOnRoster)

    /**
     * An account erasure takes its moderator off the roster as a removal does (ADR 0101 decision 2, ADR 0099): every
     * grant goes and the epoch is due for rotation, in the erasure's transaction. Logged as a step-down when the account
     * erases itself, as a removal when the Server Owner does. Not on the roster: nothing to do.
     *
     * The last holder of the current epoch is not refused, an erasure may not wait on a third person (ADR 0101
     * decision 9, ADR 0099 § 5): at the person's own door ([ErasureDoor.Own]) the key goes to the Server Owner first
     * ([deliverToServerOwner]), and the erasure is refused (`moderation_delivery_required`) unless it then holds every
     * live epoch the person holds; at the Server Owner's ([ErasureDoor.ServerOwners]) the key is lost with them, once
     * the Server Owner confirmed it (`moderation_last_holder` until then). A refusal here rolls the erasure back whole,
     * the delivery with it.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun leaveForErasure(accountId: String, actorId: String, now: Instant, door: ErasureDoor): ModerationOutcome<Unit> {
        if (ModeratorEntity.findById(accountId)?.onRoster != true) return ModerationOutcome.Ok(Unit)
        val event = if (actorId == accountId) ModerationEvent.STEPPED_DOWN else ModerationEvent.REMOVED
        val keyMayGo = when (door) {
            is ErasureDoor.Own -> {
                val delivered = deliverToServerOwner(accountId, door.delivery, now)
                if (delivered is ModerationOutcome.Refused) return delivered
                false
            }
            is ErasureDoor.ServerOwners -> door.keyLossConfirmed
        }
        return leave(accountId, actorId, event, now, ModerationFailures.notModeratorOnRoster, keyMayGo = keyMayGo)
    }

    /**
     * What [accountId]'s erasure must deliver to the Server Owner, or null where it delivers nothing: the account is not
     * the last accepted holder of the current epoch, it is the Server Owner, or the Server Owner holds every live epoch
     * it holds already (the erasure then only reappoints them). The preview serves it (`getOwnAccountErasurePreview`).
     */
    @Transactional
    fun serverOwnerDeliveryFor(accountId: String): ServerOwnerDelivery? {
        val current = currentEpoch(lock = false) ?: return null
        if (!isLastHolder(accountId, current.epoch)) return null
        val ownerId = accounts.serverOwnerId()?.takeIf { it != accountId } ?: return null
        val owner = VeyqUser.findById(ownerId) ?: return null
        val missing = epochsHeldBy(accountId) - epochsHeldBy(ownerId)
        val head = keyChain.headOf(ownerId) ?: return null
        return if (missing.isEmpty()) null else ServerOwnerDelivery(ownerId, owner.identityPublicKey, head, missing.sorted())
    }

    /**
     * The last holder's own erasure carries the key to the Server Owner (ADR 0101 decision 9, ADR 0099 § 5), in the
     * erasure's transaction, before the holder leaves. Nothing to do unless [holderId] is the last accepted holder of the
     * current epoch. Then [delivery] must be sealed to the Server Owner's identity as it is now (`identity_changed`),
     * name only epochs the holder holds, and cover every one of them the Server Owner misses
     * (`moderation_delivery_required`) — checked before anything is written. The Server Owner is then an accepted
     * moderator holding them: reappointed by the delivery, with no acceptance (the Server Owner, whose own account is
     * never erased, is always there to receive it). Each grant is stored and logged as a holder's delivery is
     * ([deliver]); the reappointment is logged as an appointment and an acceptance by the holder, reason `DELIVERY`.
     *
     * Lock order as [deliver]'s: the epoch row, then the Server Owner's moderator row (the holder's comes after, in
     * [leave]), then the grants. The erasure locked the Server Owner's account row before the epoch's
     * (`AccountErasureHandler.eraseOwnIn`), so an identity rotation of the Server Owner either commits before the identity
     * is read here or waits for this transaction.
     */
    private fun deliverToServerOwner(holderId: String, delivery: ServerOwnerDeliveryIn?, now: Instant): ModerationOutcome<Unit> {
        val current = currentEpoch(lock = true) ?: return ModerationOutcome.Ok(Unit)
        if (!isLastHolder(holderId, current.epoch)) return ModerationOutcome.Ok(Unit)
        val ownerId = accounts.serverOwnerId()?.takeIf { it != holderId }
            ?: return ModerationOutcome.Refused(ModerationFailures.deliveryRequired)
        val row = ModeratorEntity.findById(ownerId, LockModeType.PESSIMISTIC_WRITE)
        val held = epochsHeldBy(holderId)
        val has = epochsHeldBy(ownerId).toMutableSet()
        val grants = delivery?.grants ?: emptyList()
        if (held.any { it !in has }) {
            if (delivery == null) return ModerationOutcome.Refused(ModerationFailures.deliveryRequired)
            if (VeyqUser.findById(ownerId)?.identityPublicKey != delivery.identityPublicKey) {
                return ModerationOutcome.Refused(ModerationFailures.identityChanged)
            }
            if (grants.any { it.epoch !in held } || !grants.map { it.epoch }.toSet().containsAll(held - has)) {
                return ModerationOutcome.Refused(ModerationFailures.deliveryRequired)
            }
        }
        if (row?.accepted != true) {
            val appointed = if (row?.onRoster == true) row else upsertAppointment(ownerId, ownerId, now).also {
                log.append(ModerationEvent.APPOINTED, now, ownerId, holderId, reason = DELIVERY_REASON)
            }
            appointed.acceptedAt = now
            log.append(ModerationEvent.ACCEPTED, now, ownerId, holderId, reason = DELIVERY_REASON)
        }
        grants.forEach { g ->
            if (g.epoch in held && has.add(g.epoch)) {
                insertGrant(ownerId, g.epoch, g.sealedGrant, holderId)
                log.append(ModerationEvent.DELIVERED, now, ownerId, holderId, epoch = g.epoch)
            }
        }
        return ModerationOutcome.Ok(Unit)
    }

    /** The next epoch for exactly the accepted moderators (ADR 0099 §§ 3–4). */
    @Transactional
    fun mint(ownerId: String, epoch: Int, kem: String, sign: String, reason: MintReason, grants: List<GrantIn>, now: Instant = Instant.now()): ModerationOutcome<Unit> {
        if (!bundleWellFormed(kem, sign) || reason == MintReason.INITIAL) return ModerationOutcome.Refused(Failures.invalidRequest)
        val current = currentEpoch(lock = true) ?: return ModerationOutcome.Refused(ModerationFailures.moderationOff)
        // The grants are opaque here, so the epoch they were sealed for is the client's word: a mint that raced another
        // would file grants under an epoch they do not open as. Exactly the next one, or nothing.
        if (epoch != current.epoch + 1) return ModerationOutcome.Refused(ModerationFailures.epochStale)
        val accepted = ModeratorEntity.list("removedAt is null and acceptedAt is not null").map { it.accountId }.toSet()
        val named = grants.map { it.accountId }
        // Exactly the accepted moderators, each once: a duplicate, a stranger or a gap is the same refusal — a grant
        // set that does not name the roster is one the Server Owner's client built from a roster that moved.
        if (named.size != named.toSet().size || named.toSet() != accepted) {
            return ModerationOutcome.Refused(ModerationFailures.grantsIncomplete)
        }
        if (grants.any { VeyqUser.findById(it.accountId)?.identityPublicKey != it.identityPublicKey }) {
            return ModerationOutcome.Refused(ModerationFailures.identityChanged)
        }
        insertEpoch(epoch, kem, sign, reason)
        grants.forEach { insertGrant(it.accountId, epoch, it.sealedGrant, ownerId) }
        log.append(ModerationEvent.EPOCH_MINTED, now, ownerId, ownerId, epoch = epoch, reason = reason.name)
        return ModerationOutcome.Ok(Unit)
    }

    // ── Moderator (public listener) ──────────────────────────────────────────────────────────────

    /** The published bundle of the current epoch, or null while moderation is off. */
    @Transactional
    fun currentKey(): ModerationEpochEntity? = currentEpoch(lock = false)

    @Transactional
    fun appointmentOf(accountId: String): ModeratorEntity? = ModeratorEntity.findById(accountId)?.takeIf { it.onRoster }

    /** Whether [accountId], accepted, misses a grant of some live epoch. */
    @Transactional
    fun awaitingKey(accountId: String): Boolean {
        val held = ModerationGrantEntity.list("moderatorId", accountId).map { it.epoch }.toSet()
        return ModerationEpochEntity.listAll().any { it.epoch !in held }
    }

    /** Accept a pending appointment: from now on the account carries the MODERATOR role. */
    @Transactional
    fun accept(accountId: String, now: Instant = Instant.now()): ModerationOutcome<Unit> {
        currentEpoch(lock = true)
        val row = ModeratorEntity.findById(accountId, LockModeType.PESSIMISTIC_WRITE)?.takeIf { it.onRoster }
            ?: return ModerationOutcome.Refused(ModerationFailures.notAppointed)
        if (row.acceptedAt != null) return ModerationOutcome.Ok(Unit)
        row.acceptedAt = now
        log.append(ModerationEvent.ACCEPTED, now, accountId, accountId)
        return ModerationOutcome.Ok(Unit)
    }

    /** The moderator's own step-down (key-proved). */
    @Transactional
    fun stepDown(accountId: String, now: Instant = Instant.now()): ModerationOutcome<Unit> =
        leave(accountId, accountId, ModerationEvent.STEPPED_DOWN, now, ModerationFailures.notModerator)

    /** Every live epoch with the caller's grant, oldest first. */
    @Transactional
    fun grantsOf(accountId: String): Pair<Int, List<EpochGrant>> {
        val mine = ModerationGrantEntity.list("moderatorId", accountId).associateBy { it.epoch }
        val epochs = ModerationEpochEntity.list("order by epoch")
        return (epochs.lastOrNull()?.epoch ?: 0) to epochs.map {
            EpochGrant(it.epoch, it.kemPublicKey, it.signPublicKey, mine[it.epoch]?.sealedGrant)
        }
    }

    /** A fresh challenge for a key proof: 32 random bytes, bound to [accountId], single use, five minutes. */
    @Transactional
    fun challenge(accountId: String, now: Instant = Instant.now()): String {
        val bytes = ByteArray(CHALLENGE_BYTES).also(rng::nextBytes)
        val text = Base64.getEncoder().encodeToString(bytes)
        ModerationChallengeEntity().apply {
            challenge = text
            this.accountId = accountId
            expiresAt = now.plus(CHALLENGE_TTL)
        }.persist()
        return text
    }

    /**
     * Every accepted moderator missing epochs [holderId] holds, with the identity to seal them to and the head of its key
     * chain — what the holder anchors that identity by (ADR 0028 Decision 2), as every directory read that
     * serves another user's key serves its head.
     */
    @Transactional
    fun pendingDeliveries(holderId: String): List<PendingDelivery> {
        val grants = ModerationGrantEntity.listAll()
        val held = grants.filter { it.moderatorId == holderId }.map { it.epoch }.toSortedSet()
        val byModerator = grants.groupBy({ it.moderatorId }, { it.epoch })
        val recipients = ModeratorEntity.list("removedAt is null and acceptedAt is not null order by acceptedAt, accountId")
            .filter { it.accountId != holderId }
        val heads = keyChain.heads(recipients.map { it.accountId })
        return recipients.mapNotNull { m ->
            val missing = held.filter { it !in (byModerator[m.accountId] ?: emptyList()) }
            val key = VeyqUser.findById(m.accountId)?.identityPublicKey
            val head = heads[m.accountId]
            if (missing.isEmpty() || key == null || head == null) null else PendingDelivery(m.accountId, key, head, missing)
        }
    }

    /**
     * A holder's delivery to an accepted moderator: refused unless the recipient is accepted, the identity sealed to is
     * still theirs, and the holder holds every epoch it delivers. An epoch the recipient already holds is skipped, so a
     * second holder's delivery of the same epochs changes nothing.
     */
    @Transactional
    fun deliver(holderId: String, recipientId: String, identityPublicKey: String, grants: List<GrantIn>, now: Instant = Instant.now()): ModerationOutcome<Unit> {
        currentEpoch(lock = true) ?: return ModerationOutcome.Refused(ModerationFailures.notHolder)
        val recipient = ModeratorEntity.findById(recipientId, LockModeType.PESSIMISTIC_WRITE)?.takeIf { it.accepted }
            ?: return ModerationOutcome.Refused(ModerationFailures.notModeratorOnRoster)
        if (VeyqUser.findById(recipient.accountId)?.identityPublicKey != identityPublicKey) {
            return ModerationOutcome.Refused(ModerationFailures.identityChanged)
        }
        val held = ModerationGrantEntity.list("moderatorId", holderId).map { it.epoch }.toSet()
        if (grants.any { it.epoch !in held }) return ModerationOutcome.Refused(ModerationFailures.notHolder)
        val has = ModerationGrantEntity.list("moderatorId", recipientId).map { it.epoch }.toMutableSet()
        grants.forEach { g ->
            if (has.add(g.epoch)) {
                insertGrant(recipientId, g.epoch, g.sealedGrant, holderId)
                log.append(ModerationEvent.DELIVERED, now, recipientId, holderId, epoch = g.epoch)
            }
        }
        return ModerationOutcome.Ok(Unit)
    }

    /**
     * A moderator rotated their identity (ADR 0099 § 4, ADR 0060): the grants their old identity opened are treated as
     * exposed and deleted — a holder delivers the live epochs again at the new identity — and, where they held the
     * current epoch, a rotation is due. Joins the rotation's transaction, which holds the account row.
     */
    @Transactional
    fun onIdentityRotated(accountId: String, now: Instant = Instant.now()) {
        if (ModeratorEntity.findById(accountId)?.accepted != true) return
        val current = currentEpoch(lock = true) ?: return
        val heldCurrent = ModerationGrantEntity.count("moderatorId = ?1 and epoch = ?2", accountId, current.epoch) > 0
        ModerationGrantEntity.delete("moderatorId", accountId)
        if (heldCurrent && current.rotationDueAt == null) current.rotationDueAt = now
        log.append(ModerationEvent.IDENTITY_ROTATED, now, accountId, accountId)
    }

    // ── shared ───────────────────────────────────────────────────────────────────────────────────

    /** [keyMayGo]: the last holder leaves all the same — only the Server Owner's erasure door, after its warning. */
    private fun leave(
        accountId: String, actorId: String, event: ModerationEvent, now: Instant, unknown: HttpFailure, keyMayGo: Boolean = false,
    ): ModerationOutcome<Unit> {
        val current = currentEpoch(lock = true)
        val row = ModeratorEntity.findById(accountId, LockModeType.PESSIMISTIC_WRITE)?.takeIf { it.onRoster }
            ?: return ModerationOutcome.Refused(unknown)
        val holders = current?.let { holdersOf(it.epoch) } ?: emptySet()
        val heldCurrent = row.acceptedAt != null && accountId in holders
        // The key would be lost with the last one who holds it (ADR 0099 § 5).
        if (!keyMayGo && heldCurrent && (holders - accountId).isEmpty()) return ModerationOutcome.Refused(ModerationFailures.lastHolder)
        ModerationGrantEntity.delete("moderatorId", accountId)
        row.removedAt = now
        ModeratorAddress.clear(row)
        if (heldCurrent && current != null && current.rotationDueAt == null) current.rotationDueAt = now
        log.closeFor(accountId, now)
        log.append(event, now, accountId, actorId)
        return ModerationOutcome.Ok(Unit)
    }

    /** Whether [accountId] is an accepted moderator holding [epoch] and no other accepted moderator does. */
    private fun isLastHolder(accountId: String, epoch: Int): Boolean = holdersOf(epoch).let { accountId in it && it.size == 1 }

    /** The live epochs [accountId] holds a grant of. */
    private fun epochsHeldBy(accountId: String): Set<Int> =
        ModerationGrantEntity.list("moderatorId", accountId).map { it.epoch }.toSet()

    /** The accepted moderators holding a grant of [epoch]. */
    private fun holdersOf(epoch: Int): Set<String> {
        val accepted = ModeratorEntity.list("removedAt is null and acceptedAt is not null").map { it.accountId }.toSet()
        return ModerationGrantEntity.list("epoch", epoch).map { it.moderatorId }.filter { it in accepted }.toSet()
    }

    private fun currentEpoch(lock: Boolean): ModerationEpochEntity? {
        val query = ModerationEpochEntity.find("order by epoch desc")
        val top = query.firstResult() ?: return null
        return if (lock) ModerationEpochEntity.findById(top.epoch, LockModeType.PESSIMISTIC_WRITE) else top
    }

    private fun upsertAppointment(accountId: String, by: String, now: Instant): ModeratorEntity {
        val existing = ModeratorEntity.findById(accountId)
        val row = existing ?: ModeratorEntity().apply { this.accountId = accountId }
        row.appointedBy = by
        row.appointedAt = now
        row.acceptedAt = null
        row.removedAt = null
        ModeratorAddress.clear(row)
        // Hibernate takes an insert's values when it is persisted, so a new row is persisted only once it is whole.
        if (existing == null) row.persist()
        return row
    }

    private fun insertEpoch(epoch: Int, kem: String, sign: String, reason: MintReason) {
        ModerationEpochEntity().apply {
            this.epoch = epoch
            kemPublicKey = kem
            signPublicKey = sign
            this.reason = reason.name
        }.persist()
    }

    private fun insertGrant(moderatorId: String, epoch: Int, sealed: String, by: String) {
        ModerationGrantEntity().apply {
            id = UUID.randomUUID().toString()
            this.moderatorId = moderatorId
            this.epoch = epoch
            sealedGrant = sealed
            deliveredBy = by
        }.persist()
    }

    /** An X-Wing encapsulation key is 1216 bytes, an Ed25519 public key 32: anything else is no bundle. */
    private fun bundleWellFormed(kem: String, sign: String): Boolean =
        decodedSize(kem) == KEM_PUBLIC_BYTES && decodedSize(sign) == SIGN_PUBLIC_BYTES

    private fun decodedSize(b64: String): Int = runCatching { Base64.getDecoder().decode(b64).size }.getOrDefault(-1)

    companion object {
        const val CHALLENGE_BYTES = 32
        val CHALLENGE_TTL: Duration = Duration.ofMinutes(5)
        const val KEM_PUBLIC_BYTES = 1216
        const val SIGN_PUBLIC_BYTES = 32

        /** The log's `reason` on the Server Owner's appointment and acceptance by a last holder's delivery. */
        const val DELIVERY_REASON = "DELIVERY"
    }
}
