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
 * The requirement values set at each level (ADR 0088 decision 8): name → value, `1` for a set flag, else days. An
 * interface so [CapabilityRegistry]'s arithmetic is tested against maps handed in directly (`CapabilityRegistryTest`).
 */
interface CapabilityRequirementValues {
    /** The instance level's values, by requirement name. */
    fun instance(): Map<String, Int>

    /** [tenantId]'s own values, by requirement name. */
    fun tenant(tenantId: String): Map<String, Int>

    companion object {
        /** No requirement set anywhere. */
        val NONE = object : CapabilityRequirementValues {
            override fun instance(): Map<String, Int> = emptyMap()
            override fun tenant(tenantId: String): Map<String, Int> = emptyMap()
        }
    }
}

/** Whether [value] fits this requirement's kind: a set flag is `1`; days are 1 to 3650 (the contract bounds them too). */
fun CapabilityRequirement.accepts(value: Int): Boolean = when (kind) {
    RequirementKind.FLAG -> value == 1
    RequirementKind.DAYS -> value in 1..3650
}

/** One requirement the Server Owner set; a requirement not set has no row. */
@Entity
@Table(name = "veyq_capability_requirement")
@DynamicUpdate
class CapabilityRequirementEntity : PanacheEntityBase {
    @Id
    @Column(name = "requirement", length = 64)
    lateinit var requirement: String

    @Column(name = "setting", nullable = false)
    var value: Int = 0

    companion object : PanacheCompanionBase<CapabilityRequirementEntity, String>
}

/** One requirement a tenant owner set for their tenant; unique per tenant and requirement, erased with the tenant. */
@Entity
@Table(name = "veyq_tenant_capability_requirement")
@DynamicUpdate
class TenantCapabilityRequirementEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "tenant_id", nullable = false, columnDefinition = "uuid")
    lateinit var tenantId: String

    @Column(name = "requirement", nullable = false, length = 64)
    lateinit var requirement: String

    @Column(name = "setting", nullable = false)
    var value: Int = 0

    companion object : PanacheCompanionBase<TenantCapabilityRequirementEntity, String>
}

/**
 * The requirements' persistence. Every method runs in the caller's transaction, and a write takes the lock of the row
 * it belongs under (the instance singleton, the tenant), for the reason [CapabilitySwitchStore.set] gives: two writes
 * of one requirement would otherwise both read "no row" and the second fail on the key.
 */
@ApplicationScoped
class CapabilityRequirementStore {

    fun instance(): Map<String, Int> = CapabilityRequirementEntity.listAll().associate { it.requirement to it.value }

    fun tenant(tenantId: String): Map<String, Int> =
        TenantCapabilityRequirementEntity.list("tenantId", tenantId).associate { it.requirement to it.value }

    /** Store the instance's [value] for [requirement], or remove it when null. */
    fun setInstance(requirement: String, value: Int?) {
        VeyqInstance.findById(VeyqInstance.SINGLETON_ID, LockModeType.PESSIMISTIC_WRITE)
        val row = CapabilityRequirementEntity.findById(requirement)
        when {
            value == null -> row?.delete()
            row == null -> CapabilityRequirementEntity().apply { this.requirement = requirement; this.value = value }.persist()
            else -> row.value = value
        }
    }

    /** Store [tenantId]'s [value] for [requirement], or remove it when null. */
    fun setTenant(tenantId: String, requirement: String, value: Int?) {
        TenantEntity.findById(tenantId, LockModeType.PESSIMISTIC_WRITE)
        val row = TenantCapabilityRequirementEntity.find("tenantId = ?1 and requirement = ?2", tenantId, requirement).firstResult()
        when {
            value == null -> row?.delete()
            row == null -> TenantCapabilityRequirementEntity().apply {
                id = UUID.randomUUID().toString(); this.tenantId = tenantId; this.requirement = requirement; this.value = value
            }.persist()
            else -> row.value = value
        }
    }
}

/**
 * The production [CapabilityRequirementValues]: both levels read once and cached under ONE generation that [invalidate]
 * bumps after a committed write — [StoredCapabilitySwitches]' pattern, for the same single-process reason.
 */
@ApplicationScoped
class StoredCapabilityRequirementValues(
    private val store: CapabilityRequirementStore,
) : CapabilityRequirementValues {
    private val generation = AtomicLong()

    @Volatile
    private var instanceCache: Cached? = null
    private val tenantCache = ConcurrentHashMap<String, Cached>()

    override fun instance(): Map<String, Int> {
        val current = generation.get()
        instanceCache?.takeIf { it.generation == current }?.let { return it.values }
        return QuarkusTransaction.joiningExisting().call { store.instance() }.also { instanceCache = Cached(current, it) }
    }

    override fun tenant(tenantId: String): Map<String, Int> {
        val current = generation.get()
        tenantCache[tenantId]?.takeIf { it.generation == current }?.let { return it.values }
        return QuarkusTransaction.joiningExisting().call { store.tenant(tenantId) }.also { tenantCache[tenantId] = Cached(current, it) }
    }

    /** Forget every cached value. Call after the transaction that changed one has COMMITTED. */
    fun invalidate() {
        generation.incrementAndGet()
        tenantCache.clear()
    }

    private class Cached(val generation: Long, val values: Map<String, Int>)
}
