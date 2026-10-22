package me.raddatz.veyq.core.moderation.guestwrite

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import me.raddatz.veyq.core.http.ClientSeen
import me.raddatz.veyq.core.instance.VeyqInstance
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
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
 * and evidence never rides a log (ADR 0092 § 1). The record has no switch of its own (ADR 0093 § 10): it is kept
 * wherever a guest may write, and `core.public-shares.contribute` / `.drop-box` already decide where that is — a write
 * that reaches [record] is one those switches let through.
 */
@ApplicationScoped
class GuestWriteRecords {

    /**
     * Record one guest write of [identityId] on [objectId]. Nothing when [seen] is null (the resolver yielded no address,
     * ADR 0094). The port is recorded where [seen] holds one, else the address alone.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun record(seen: ClientSeen?, identityId: String, kind: GuestWriteObject, objectId: String, fieldGroup: String?) {
        if (seen == null) return
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
     * The N a link's resolve discloses to its guest (ADR 0093 § 10, decision 6): the retention while [role] lets a guest
     * write; null otherwise — a READ guest is recorded nowhere, so it is told nothing.
     */
    fun disclosedDays(role: String): Int? {
        if (role != ShareEntity.READ_WRITE && role != ShareEntity.WRITE_ONLY) return null
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
     * A guest's objection (GDPR Art. 21(1)): delete every live row [identityId] wrote, and count — never touch — the rows
     * a hold copied of it, which stay until their hold ends (ADR 0093 § 4). The Server Owner's decision, on the admin
     * channel; the next write of the same identity is recorded again, as the guest page says.
     */
    @Transactional
    fun eraseIdentity(identityId: String): GuestWriteErasure {
        val erased = GuestWriteRecordEntity.delete("shareIdentityId", identityId)
        val kept = HoldRowEntity.list("sourceTable", TABLE).count { row ->
            HOLD_JSON.readValue(row.payload, HOLD_COLUMNS)[IDENTITY_COLUMN].equals(identityId, ignoreCase = true)
        }
        return GuestWriteErasure(erased, kept)
    }

    /**
     * The purge (ADR 0093 § 10, ADR 0092 § 2): every row past its own end or older than N as it stands now — so a
     * shortening reaches every row at once and a lengthening only new ones. Answers how many rows went. Hold copies are
     * not here: they follow the hold's end.
     */
    @Transactional
    fun purge(now: Instant): Long {
        val oldest = now.minus(Duration.ofDays(retentionDays().toLong()))
        return GuestWriteRecordEntity.delete("expiresAt <= ?1 or observedAt <= ?2", now, oldest)
    }

    companion object {
        /** N's default and range (ADR 0092 § 2): seven days, the supervisory practice; one to thirty. */
        const val DEFAULT_DAYS = 7
        const val MIN_DAYS = 1
        const val MAX_DAYS = 30

        /** The one field group a guest-write record names today: a node's or an identity's name (ADR 0093 § 8). An
         *  upload's content (with the meta it carries) names none. */
        const val NAME = "name"

        private const val BATCH = 500

        /** The table a hold row names as its source, and the column of a copied row that names the identity. */
        private const val TABLE = "veyq_guest_write_record"
        private const val IDENTITY_COLUMN = "share_identity_id"
        private val HOLD_JSON = ObjectMapper()
        private val HOLD_COLUMNS = object : TypeReference<Map<String, String?>>() {}
    }
}

/** What [GuestWriteRecords.eraseIdentity] did: the live rows it deleted, and the held copies it left. */
data class GuestWriteErasure(val erased: Long, val keptInHolds: Int)
