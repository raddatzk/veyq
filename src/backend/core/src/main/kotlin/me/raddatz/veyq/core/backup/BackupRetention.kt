package me.raddatz.veyq.core.backup

import me.raddatz.veyq.core.generated.api.InstanceBackupRetentionApi
import me.raddatz.veyq.core.generated.model.InstanceBackupRetention
import me.raddatz.veyq.core.generated.model.InstanceBackupRetentionUpdate
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.instance.VeyqInstance
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.Response
import org.hibernate.annotations.DynamicUpdate

/** B as it stands, and whether the Server Owner set it. */
data class BackupRetentionDays(val days: Int, val chosen: Boolean)

/**
 * B, the days every capture veyq's tooling wrote is kept (ADR 0056 decision 8, an instance setting by ADR 0089): 30 until
 * the Server Owner sets 7 to 365. Two readers besides the console: `src/tools/instance_backup.py`, which reads the row over
 * the connection it dumps through and prunes every capture older than B (30 when it cannot read it), and the erasure
 * ledger's start, which drops every line older than B — no capture that could be restored predates it.
 */
@ApplicationScoped
class BackupRetention {

    fun retention(): BackupRetentionDays =
        BackupRetentionEntity.findById(BackupRetentionEntity.ROW)
            ?.let { BackupRetentionDays(it.retentionDays, chosen = true) }
            ?: BackupRetentionDays(DEFAULT_DAYS, chosen = false)

    /**
     * Set B ([days], 7 to 365 — the contract refuses anything else at the boundary; this refuses it again). Under the
     * instance row's lock, the order every instance-setting writer takes, so two first writes cannot both insert.
     */
    @Transactional
    fun setRetention(days: Int): BackupRetentionDays {
        require(days in MIN_DAYS..MAX_DAYS) { "the retention is $MIN_DAYS to $MAX_DAYS days" }
        VeyqInstance.findById(VeyqInstance.SINGLETON_ID, LockModeType.PESSIMISTIC_WRITE)
        val row = BackupRetentionEntity.findById(BackupRetentionEntity.ROW, LockModeType.PESSIMISTIC_WRITE)
            ?: BackupRetentionEntity().also { it.persist() }
        row.retentionDays = days
        return BackupRetentionDays(days, chosen = true)
    }

    companion object {
        /** The owner's Q21 (cr-backup-erasure-and-retention): 30 by default, the most the moderation Run page already
         *  recommends for a backup holding guest addresses; 7 to 365, a reason in the record of processing above 30. */
        const val DEFAULT_DAYS = 30
        const val MIN_DAYS = 7
        const val MAX_DAYS = 365
    }
}

/** The retention the Server Owner set (`veyq_backup_retention`, one row, id 1); no row reads as the default. */
@Entity
@Table(name = "veyq_backup_retention")
@DynamicUpdate
class BackupRetentionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: Int = ROW

    @Column(name = "retention_days", nullable = false)
    var retentionDays: Int = BackupRetention.DEFAULT_DAYS

    companion object : PanacheCompanionBase<BackupRetentionEntity, Int> {
        const val ROW = 1
    }
}

/**
 * HTTP adapter for B (ADR 0056 decision 8, ADR 0089): the Server Owner reads and sets it. Implements the generated
 * [InstanceBackupRetentionApi]; thin (ADR 0001). Admin-channel marked in the contract and gated here by the class-level
 * `@RolesAllowed(SERVER_OWNER)`: a missing token 401, a non-owner 403. The range (7 to 365) is the contract's, refused
 * with 400 before this runs.
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason = "Server-Owner-only read and write of one instance-wide value, the backups' retention in days. There is no " +
        "per-caller state to enumerate and no work an anonymous caller could amplify; the role gate bounds the caller " +
        "set to one account.",
)
class InstanceBackupRetentionResource(private val retention: BackupRetention) : InstanceBackupRetentionApi {

    override fun getInstanceBackupRetention(): Response = Response.ok(retention.retention().toWire()).build()

    override fun setInstanceBackupRetention(instanceBackupRetentionUpdate: InstanceBackupRetentionUpdate): Response =
        Response.ok(retention.setRetention(instanceBackupRetentionUpdate.retentionDays).toWire()).build()

    private fun BackupRetentionDays.toWire() = InstanceBackupRetention(retentionDays = days, chosen = chosen)
}
