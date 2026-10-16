package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.instance.VeyqInstance
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which capability entries are switched off on this instance (ADR 0088). [CapabilityRegistry] asks it on every call,
 * so a switch takes effect without a restart: the production one, [StoredCapabilitySwitches], reads the Server Owner's
 * switches from the database (decision 12). A `fun interface` so the registry's arithmetic is tested against a set
 * handed in directly (`CapabilityRegistryTest`).
 */
fun interface CapabilitySwitches {
    /** The catalog ids switched off right now. An id here also switches off every entry below it (the AND along the path). */
    fun switchedOff(): Set<String>
}

/**
 * One register entry the Server Owner switched off (ADR 0088 decision 12); an entry that is on has no row. [id] is the
 * catalog's dotted id, not a row id, and the row's only column: the console shows the state, not its history.
 */
@Entity
@Table(name = "veyq_capability_switch")
@DynamicUpdate
class CapabilitySwitchEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 64)
    lateinit var id: String

    companion object : PanacheCompanionBase<CapabilitySwitchEntity, String>
}

/** The switches' persistence. Every method runs in the caller's transaction. */
@ApplicationScoped
class CapabilitySwitchStore {

    fun switchedOff(): Set<String> = CapabilitySwitchEntity.listAll().mapTo(mutableSetOf()) { it.id }

    /**
     * Store [id]'s own switch: a row while it is off, none while it is on. Writing the state it already has changes
     * nothing, so an entry switched off twice keeps its one row.
     *
     * **Under the instance singleton's row lock.** Two writes of one entry would otherwise both read "no row" and both
     * insert, and the second would fail on the primary key instead of answering what the first already stored. Only
     * this write takes that lock, and it takes no other, so it waits behind nothing but itself.
     */
    fun set(id: String, off: Boolean) {
        VeyqInstance.findById(VeyqInstance.SINGLETON_ID, LockModeType.PESSIMISTIC_WRITE)
        val row = CapabilitySwitchEntity.findById(id)
        when {
            off && row == null -> CapabilitySwitchEntity().apply { this.id = id }.persist()
            !off && row != null -> row.delete()
        }
    }
}

/**
 * The production [CapabilitySwitches]: the stored switches, read once and cached; [invalidate] after a committed
 * write makes the next read go to the database again. One backend process serves an instance (the recipes run one; the
 * chart's `Recreate` strategy keeps it one), so an in-process invalidation reaches every reader — the argument
 * `StoreSettings` makes for the store setting.
 *
 * **A cached set is tagged with the generation it was read under**, and served only while that generation is
 * current. A reader that queried just before a write committed would otherwise store the old set after the write's
 * [invalidate] had run, and serve it until the next write.
 */
@ApplicationScoped
class StoredCapabilitySwitches(
    private val store: CapabilitySwitchStore,
) : CapabilitySwitches {
    private val generation = AtomicLong()

    @Volatile
    private var cached: Cached? = null

    override fun switchedOff(): Set<String> {
        val current = generation.get()
        cached?.takeIf { it.generation == current }?.let { return it.off }
        return QuarkusTransaction.joiningExisting().call { store.switchedOff() }.also { cached = Cached(current, it) }
    }

    /** Forget the cached set. Call after the transaction that changed a switch has COMMITTED — before it, a reader
     *  could read and cache the old set under the new generation. */
    fun invalidate() {
        generation.incrementAndGet()
    }

    private class Cached(val generation: Long, val off: Set<String>)
}

/**
 * Which entries a tenant owner switched off for their tenant (ADR 0088 decisions 2 and 12): the tenant level the
 * registry ANDs with the instance level. A `fun interface` for the same reason as [CapabilitySwitches]. Only an entry in
 * [CapabilityIds.TENANT_SWITCHABLE] is ever stored, because [TenantCapabilitySwitchStore.set]'s caller refuses any other.
 */
fun interface TenantCapabilitySwitches {
    /** The catalog ids [tenantId] switched off right now; an id here switches off every entry below it in that tenant. */
    fun switchedOff(tenantId: String): Set<String>
}

/**
 * One entry a tenant owner switched off for their tenant; an entry that is on has no row. Unique per tenant and entry;
 * the row id is a server-minted UUID with no other meaning. Erased with its tenant (`ON DELETE CASCADE`).
 */
@Entity
@Table(name = "veyq_tenant_capability_switch")
@DynamicUpdate
class TenantCapabilitySwitchEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "tenant_id", nullable = false, columnDefinition = "uuid")
    lateinit var tenantId: String

    @Column(name = "capability_id", nullable = false, length = 64)
    lateinit var capabilityId: String

    companion object : PanacheCompanionBase<TenantCapabilitySwitchEntity, String>
}

/** The tenant switches' persistence. Every method runs in the caller's transaction. */
@ApplicationScoped
class TenantCapabilitySwitchStore {

    fun switchedOff(tenantId: String): Set<String> =
        TenantCapabilitySwitchEntity.list("tenantId", tenantId).mapTo(mutableSetOf()) { it.capabilityId }

    /**
     * Store [capabilityId]'s own switch for [tenantId]: a row while it is off, none while it is on; writing the state it
     * has changes nothing. **Under the tenant row's lock**, for the reason [CapabilitySwitchStore.set] takes the
     * instance's: two writes of one entry would otherwise both read "no row" and the second fail on the key.
     */
    fun set(tenantId: String, capabilityId: String, off: Boolean) {
        TenantEntity.findById(tenantId, LockModeType.PESSIMISTIC_WRITE)
        val row = TenantCapabilitySwitchEntity.find("tenantId = ?1 and capabilityId = ?2", tenantId, capabilityId).firstResult()
        when {
            off && row == null -> TenantCapabilitySwitchEntity().apply {
                this.id = UUID.randomUUID().toString()
                this.tenantId = tenantId
                this.capabilityId = capabilityId
            }.persist()
            !off && row != null -> row.delete()
        }
    }
}

/**
 * The production [TenantCapabilitySwitches]: each tenant's stored switches, read once and cached, all under ONE
 * generation that [invalidate] bumps after any committed write — the [StoredCapabilitySwitches] argument (one backend
 * process serves an instance) and its guard against a reader caching the old set after the write's invalidate.
 */
@ApplicationScoped
class StoredTenantCapabilitySwitches(
    private val store: TenantCapabilitySwitchStore,
) : TenantCapabilitySwitches {
    private val generation = AtomicLong()
    private val cached = ConcurrentHashMap<String, Cached>()

    override fun switchedOff(tenantId: String): Set<String> {
        val current = generation.get()
        cached[tenantId]?.takeIf { it.generation == current }?.let { return it.off }
        return QuarkusTransaction.joiningExisting().call { store.switchedOff(tenantId) }
            .also { cached[tenantId] = Cached(current, it) }
    }

    /** Forget every cached set. Call after the transaction that changed a switch has COMMITTED. */
    fun invalidate() {
        generation.incrementAndGet()
        cached.clear()
    }

    private class Cached(val generation: Long, val off: Set<String>)
}
