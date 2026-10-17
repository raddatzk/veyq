package me.raddatz.veyq.core.moderation.guestwrite

import me.raddatz.veyq.core.http.ClientSeen
import me.raddatz.veyq.core.instance.VeyqInstance
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.sharing.ShareEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The retention as it stands: N days, and whether the Server Owner ever set it (else N is [GuestWriteRecords.DEFAULT_DAYS]). */
data class GuestWriteRetention(val days: Int, val chosen: Boolean)

/**
 * The guest-write record (ADR 0093 § 10, en.moderation.guest-write-record): a row per guest write that enters a guest
 * as last editor (ADR 0093 § 8), with what the server already saw of the client ([ClientSeen], ADR 0094) — kept N days,
 * copied by a hold ([GuestWriteRecordHoldSnapshot]), purged by [GuestWriteRecordPurge].
 *
 * Every writer calls [record] **in its own transaction, after its write succeeded** — a refused or rolled-back write
 * records nothing, a replay that wrote nothing records nothing — with the identity that wrote (the authenticated caller,
 * never a request field) and the [ClientSeen] its resource resolved once. Nothing here logs: the address is evidence,
 * and evidence never rides a log (ADR 0092 § 1).
 */
@ApplicationScoped
class GuestWriteRecords(private val capabilities: CapabilityRegistry) {

    /**
     * Record one guest write of [identityId] on [objectId] in [scopeId]'s space. Nothing when [seen] is null (the
     * resolver yielded no address, ADR 0094), and nothing while the switch is off for that space (the entry or an
     * ancestor, ADR 0088 decision 2). The port is recorded where [seen] holds one, else the address alone.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun record(
        seen: ClientSeen?, identityId: String, scopeId: String, kind: GuestWriteObject, objectId: String, fieldGroup: String?,
    ) {
        if (seen == null) return
        if (!capabilities.isActiveInScope(SWITCH, scopeId)) return
        GuestWriteRecordEntity().apply {
            id = UUID.randomUUID().toString()
            shareIdentityId = identityId
            objectKind = kind.name
            this.objectId = objectId
            this.fieldGroup = fieldGroup
            clientIp = seen.ip
            clientPort = seen.port
            observedAt = seen.at
            // N as it stands at the write: a later lengthening never extends this row (ADR 0092 § 2).
            expiresAt = seen.at.plus(Duration.ofDays(retentionDays().toLong()))
        }.persist()
    }

    /**
     * The N a link's resolve discloses to its guest (ADR 0093 § 10, decision 6): the retention while the switch is on for
     * [scopeId]'s space and [role] lets a guest write; null otherwise — a READ guest is recorded nowhere, so it is told
     * nothing, and while the switch is off nothing is recorded and nothing is said. The switch decides, never the mode.
     */
    fun disclosedDays(scopeId: String, role: String): Int? {
        if (role != ShareEntity.READ_WRITE && role != ShareEntity.WRITE_ONLY) return null
        if (!capabilities.isActiveInScope(SWITCH, scopeId)) return null
        return retentionDays()
    }

    fun retentionDays(): Int = retention().days

    fun retention(): GuestWriteRetention =
        GuestWriteRetentionEntity.findById(GuestWriteRetentionEntity.ROW)
            ?.let { GuestWriteRetention(it.retentionDays, chosen = true) }
            ?: GuestWriteRetention(DEFAULT_DAYS, chosen = false)

    /**
     * Set N ([days], 1 to 30 — the contract refuses anything else at the boundary; this refuses it again). Under the
     * instance row's lock, the order every instance-setting writer takes (`OperatorDetailsStore.lockedRow`), so two
     * first writes cannot both insert. The next purge applies a shortening to every row.
     */
    @Transactional
    fun setRetention(days: Int): GuestWriteRetention {
        require(days in MIN_DAYS..MAX_DAYS) { "the retention is $MIN_DAYS to $MAX_DAYS days" }
        VeyqInstance.findById(VeyqInstance.SINGLETON_ID, LockModeType.PESSIMISTIC_WRITE)
        val row = GuestWriteRetentionEntity.findById(GuestWriteRetentionEntity.ROW, LockModeType.PESSIMISTIC_WRITE)
            ?: GuestWriteRetentionEntity().also { it.persist() }
        row.retentionDays = days
        return GuestWriteRetention(days, chosen = true)
    }

    /**
     * Delete the records of erased objects ([objectIds]: nodes, an application's items), in the eraser's transaction —
     * a row goes with what it names (ADR 0093 § 10). What a hold copied stays in the hold.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun forget(objectIds: Collection<String>) {
        objectIds.distinct().chunked(BATCH).forEach { GuestWriteRecordEntity.delete("objectId in ?1", it) }
    }

    /**
     * The purge (ADR 0093 § 10, ADR 0092 § 2): while the switch is off at the instance level, every live row; otherwise
     * every row past its own end or older than N as it stands now — so a shortening reaches every row at once and a
     * lengthening only new ones. Answers how many rows went. Hold copies are not here: they follow the hold's end.
     */
    @Transactional
    fun purge(now: Instant): Long {
        if (!capabilities.isActive(SWITCH, null)) return GuestWriteRecordEntity.deleteAll()
        val oldest = now.minus(Duration.ofDays(retentionDays().toLong()))
        return GuestWriteRecordEntity.delete("expiresAt <= ?1 or observedAt <= ?2", now, oldest)
    }

    companion object {
        /** The switch (ADR 0088): operator-only, its default per operating mode (ADR 0093 § 14). */
        const val SWITCH = CapabilityIds.PUBLIC_SHARES_GUEST_WRITE_RECORD

        /** N's default and range (ADR 0092 § 2): seven days, the supervisory practice; one to thirty. */
        const val DEFAULT_DAYS = 7
        const val MIN_DAYS = 1
        const val MAX_DAYS = 30

        /** The one field group a guest-write record names today: a node's or an identity's name (ADR 0093 § 8). An
         *  upload's content (with the meta it carries) names none. */
        const val NAME = "name"

        private const val BATCH = 500
    }
}
