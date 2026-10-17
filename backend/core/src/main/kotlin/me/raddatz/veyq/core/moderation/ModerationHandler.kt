package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.VeyqUser
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

/** An accepted moderator missing epochs the caller holds. */
data class PendingDelivery(val accountId: String, val identityPublicKey: String, val epochs: List<Int>)

/** A grant in a mint or a delivery: the moderator, the identity it is sealed to, the epoch and the ciphertext. */
data class GrantIn(val accountId: String, val identityPublicKey: String, val epoch: Int, val sealedGrant: String)

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

    /** Every accepted moderator missing epochs [holderId] holds, with the identity to seal them to. */
    @Transactional
    fun pendingDeliveries(holderId: String): List<PendingDelivery> {
        val grants = ModerationGrantEntity.listAll()
        val held = grants.filter { it.moderatorId == holderId }.map { it.epoch }.toSortedSet()
        val byModerator = grants.groupBy({ it.moderatorId }, { it.epoch })
        return ModeratorEntity.list("removedAt is null and acceptedAt is not null order by acceptedAt, accountId")
            .filter { it.accountId != holderId }
            .mapNotNull { m ->
                val missing = held.filter { it !in (byModerator[m.accountId] ?: emptyList()) }
                val key = VeyqUser.findById(m.accountId)?.identityPublicKey
                if (missing.isEmpty() || key == null) null else PendingDelivery(m.accountId, key, missing)
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

    private fun leave(accountId: String, actorId: String, event: ModerationEvent, now: Instant, unknown: HttpFailure): ModerationOutcome<Unit> {
        val current = currentEpoch(lock = true)
        val row = ModeratorEntity.findById(accountId, LockModeType.PESSIMISTIC_WRITE)?.takeIf { it.onRoster }
            ?: return ModerationOutcome.Refused(unknown)
        val holders = current?.let { holdersOf(it.epoch) } ?: emptySet()
        val heldCurrent = row.acceptedAt != null && accountId in holders
        // The key would be lost with the last one who holds it (ADR 0099 § 5).
        if (heldCurrent && (holders - accountId).isEmpty()) return ModerationOutcome.Refused(ModerationFailures.lastHolder)
        ModerationGrantEntity.delete("moderatorId", accountId)
        row.removedAt = now
        ModeratorAddress.clear(row)
        if (heldCurrent && current != null && current.rotationDueAt == null) current.rotationDueAt = now
        log.closeFor(accountId, now)
        log.append(event, now, accountId, actorId)
        return ModerationOutcome.Ok(Unit)
    }

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
    }
}
