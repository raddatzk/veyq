package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.instance.VeyqInstance
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * One application switched off in one tenant, and the day its content there is erased
 * (uc.platform.erase-switched-off-application, ADR 0079 decision 8, ADR 0088 decision 5). A row exists exactly while
 * the application is off for the tenant at either level, and while a due erasure is still running: it is
 * [ApplicationErasurePurge]'s work list, the day the banner and the tenant owner's panel name, and the claims of the
 * two mails ([noticeMailedAt], [reminderMailedAt]: the attempt was made, so each goes out once). A row that exists keeps
 * [offSince] and [dueAt] — a second level switching off never moves a day members were told. Erased with its tenant
 * (`ON DELETE CASCADE`).
 */
@Entity
@Table(name = "veyq_application_erasure")
@IdClass(ApplicationErasureKey::class)
@DynamicUpdate
class ApplicationErasureEntity : PanacheEntityBase {
    @Id
    @Column(name = "tenant_id", columnDefinition = "uuid")
    lateinit var tenantId: String

    /** The register id of the application: its one-segment root entry ([ApplicationErasureStore.APPLICATIONS]). */
    @Id
    @Column(name = "application", length = 64)
    lateinit var application: String

    /** The level that made the row ([SwitchLevel] by name), for the notice's words: who switched it off. */
    @Column(name = "switch_level", nullable = false, length = 16)
    lateinit var switchedBy: String

    @Column(name = "off_since", nullable = false)
    lateinit var offSince: Instant

    @Column(name = "due_at", nullable = false)
    lateinit var dueAt: Instant

    @Column(name = "notice_mailed_at")
    var noticeMailedAt: Instant? = null

    @Column(name = "reminder_mailed_at")
    var reminderMailedAt: Instant? = null

    fun key() = ApplicationErasureKey(tenantId, application)

    companion object : PanacheCompanionBase<ApplicationErasureEntity, ApplicationErasureKey>
}

/** The row's composite key. Defaults give JPA its no-argument constructor. */
data class ApplicationErasureKey(var tenantId: String = "", var application: String = "") : Serializable

/** The day one application's content in a tenant is erased, as the API serves it. */
data class ApplicationErasureDay(val application: String, val dueAt: Instant)

/**
 * The rows of [ApplicationErasureEntity] and the one rule that keeps them: [reconcile]. Every method runs in the
 * caller's transaction.
 *
 * **The lock order is the instance singleton's row, then the tenant's row** ([lockInstance], [lockTenant]), and every
 * writer of a row's existence takes both before it reads the switches: the tenant switch
 * ([TenantCapabilitySwitchStore.set]), the instance switch ([CapabilitySwitchStore.set], one tenant row after another
 * in ascending id order), a tenant's creation (`TenantStore.createPendingTenant`) and the purge's end of an erasure and
 * its opening of a missing row ([ApplicationErasurePurge]). Two levels switching at once therefore read each other's
 * committed switch, and the row is what the final state implies: an off application without its row would never be
 * erased, and an on application with a row not yet due would be erased against its switch. The purge's erasure of a
 * scope takes the tenant's row first too, so a switch-on waits for a scope being erased, and a tenant's own purge
 * (tenant row, its scopes, then the cascade onto these rows) never waits crosswise on it.
 */
@ApplicationScoped
class ApplicationErasureStore(config: VeyqConfig) {
    /** `veyq.application.off.retention`: the day is the moment the application went off plus this. */
    val window: Duration = config.application().off().retention()

    fun lockInstance() {
        VeyqInstance.findById(VeyqInstance.SINGLETON_ID, LockModeType.PESSIMISTIC_WRITE)
    }

    fun lockTenant(tenantId: String): TenantEntity? = TenantEntity.findById(tenantId, LockModeType.PESSIMISTIC_WRITE)

    /**
     * Bring [application]'s row for [tenantId] in line with both levels' switches as they are stored now. Under
     * [lockInstance] and [lockTenant], which the caller took in that order. Off and no row: the row is made, from
     * [now], due a [window] later, made by [level]; with [noticeOwed] false (a tenant created while the instance holds
     * the application off: nobody is a member yet) its notice counts as sent. On and a row not yet due: it goes, and
     * nothing is erased. Every other case keeps what is there — a row that is due stays until the purge has handled
     * every scope, even when the application is switched on meanwhile.
     *
     * [now] is null in production, and the clock is then read here, under both locks: a purge that erases a scope holds
     * the tenant's row and decided with a clock already past the day, so a switch-on that waited for it reads a later
     * clock and keeps the row too. A test passes the moment it drives.
     */
    fun reconcile(
        tenantId: String, application: String, level: SwitchLevel, now: Instant?, noticeOwed: Boolean = true,
    ): ApplicationErasureEntity? {
        require(application in APPLICATIONS) { "$application is no application of the register" }
        val at = now ?: Instant.now()
        val row = ApplicationErasureEntity.findById(ApplicationErasureKey(tenantId, application))
        val off = isOff(tenantId, application)
        return when {
            off && row == null -> ApplicationErasureEntity().apply {
                this.tenantId = tenantId
                this.application = application
                switchedBy = level.name
                offSince = at
                dueAt = at.plus(window)
                if (!noticeOwed) noticeMailedAt = at
            }.also { it.persist() }
            !off && row != null && row.dueAt.isAfter(at) -> null.also { row.delete() }
            else -> row
        }
    }

    /** Whether [application] is off for [tenantId] at either level, as stored now (never the cached sets). */
    fun isOff(tenantId: String, application: String): Boolean =
        CapabilitySwitchEntity.findById(application) != null ||
            TenantCapabilitySwitchEntity.count("tenantId = ?1 and capabilityId = ?2", tenantId, application) > 0

    /** The level a row made now names: the instance while it holds the application off, else the tenant. */
    fun levelOf(application: String): SwitchLevel =
        if (CapabilitySwitchEntity.findById(application) != null) SwitchLevel.INSTANCE else SwitchLevel.TENANT

    /** [tenantId]'s named days, in register order. */
    fun days(tenantId: String): List<ApplicationErasureDay> {
        val byApp = ApplicationErasureEntity.list("tenantId", tenantId).associateBy { it.application }
        return APPLICATIONS.mapNotNull { app -> byApp[app]?.let { ApplicationErasureDay(app, it.dueAt) } }
    }

    companion object {
        /**
         * The applications of the register: every root entry, its one-segment id the only one a root may be (ADR 0088
         * decision 5; `core` is no entry). Derived from the catalog, so core names none.
         */
        val APPLICATIONS: List<String> = CapabilityIds.ADVERTISED.filter { '.' !in it }
    }
}
