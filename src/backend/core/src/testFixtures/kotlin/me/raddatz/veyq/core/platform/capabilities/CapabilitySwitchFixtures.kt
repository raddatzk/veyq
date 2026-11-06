package me.raddatz.veyq.core.platform.capabilities

import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction

/**
 * Switches capability entries off the way production does (ADR 0088 decision 12): a row in `veyq_capability_switch`
 * per entry, written through [CapabilitySwitchStore] and followed by [StoredCapabilitySwitches.invalidate] once it
 * committed — what `CapabilitySwitchHandler.set` does behind `PUT /api/instance/capabilities/{capabilityId}`, minus the
 * HTTP. So a gate test runs against the production switches on the default profile; the Admin API itself is
 * `InstanceCapabilitiesSliceTest`'s.
 *
 * **Shared state, restored in the same edit.** The `%test` H2 database lives as long as the JVM, so a row left behind
 * would switch the entry off for every later class. [with] refuses to start over a row an earlier test left, and
 * switches everything back on in a `finally`.
 */
object CapabilitySwitchFixtures {

    /** Runs [block] with exactly [ids] switched off, then switches everything back on, even when [block] throws. */
    fun <T> with(vararg ids: String, block: () -> T): T {
        val left = QuarkusTransaction.requiringNew().call { store().switchedOff() }
        check(left.isEmpty()) { "a previous test left $left switched off" }
        ids.forEach { id -> QuarkusTransaction.requiringNew().run { store().set(id, off = true) } }
        switches().invalidate()
        embeddingHosts().refresh()
        try {
            return block()
        } finally {
            clear()
        }
    }

    /** Switches every entry back on — for a class that switches through the Admin API and must not leave a row. */
    fun clear() {
        QuarkusTransaction.requiringNew().run {
            CapabilitySwitchEntity.deleteAll()
            dropUnimpliedErasures()
        }
        switches().invalidate()
        embeddingHosts().refresh()
    }

    private fun store(): CapabilitySwitchStore = Arc.container().instance(CapabilitySwitchStore::class.java).get()
    private fun switches(): StoredCapabilitySwitches = Arc.container().instance(StoredCapabilitySwitches::class.java).get()
}

/** The app shell's per-domain `https:` snapshot ([ShareEmbeddingHosts]), which a switch handler refreshes after its write:
 *  a fixture that writes the rows directly refreshes it too, or a test's switch left the next class's policy narrowed. */
internal fun embeddingHosts(): ShareEmbeddingHosts = Arc.container().instance(ShareEmbeddingHosts::class.java).get()

/**
 * The tenant level's [CapabilitySwitchFixtures]: rows in `veyq_tenant_capability_switch` written through
 * [TenantCapabilitySwitchStore] and followed by [StoredTenantCapabilitySwitches.invalidate] once committed — what
 * `TenantCapabilitySwitchHandler.set` does behind `PUT /api/tenants/{tenantId}/capability-switches/{capabilityId}`, minus
 * the HTTP and the owner gate (those are `TenantCapabilitySwitchesSliceTest`'s). Restored in a `finally`, as there.
 */
object TenantCapabilitySwitchFixtures {

    /** Runs [block] with exactly [ids] switched off for [tenantId], then switches every tenant's entries back on. */
    fun <T> with(tenantId: String, vararg ids: String, block: () -> T): T {
        val left = QuarkusTransaction.requiringNew().call { TenantCapabilitySwitchEntity.count() }
        check(left == 0L) { "a previous test left $left tenant switch row(s)" }
        ids.forEach { id -> QuarkusTransaction.requiringNew().run { store().set(tenantId, id, off = true) } }
        switches().invalidate()
        embeddingHosts().refresh()
        try {
            return block()
        } finally {
            clear()
        }
    }

    /** Switches every tenant's entries back on — for a class that switches through the API and must not leave a row. */
    fun clear() {
        QuarkusTransaction.requiringNew().run {
            TenantCapabilitySwitchEntity.deleteAll()
            dropUnimpliedErasures()
        }
        switches().invalidate()
        embeddingHosts().refresh()
    }

    private fun store(): TenantCapabilitySwitchStore = Arc.container().instance(TenantCapabilitySwitchStore::class.java).get()
    private fun switches(): StoredTenantCapabilitySwitches =
        Arc.container().instance(StoredTenantCapabilitySwitches::class.java).get()
}

/**
 * The erasure rows (uc.platform.erase-switched-off-application) the switches no longer imply, gone with the switch rows
 * a fixture's clear deletes directly: a row left behind would make a later class's tick erase that tenant's content.
 * In the caller's transaction.
 */
fun dropUnimpliedErasures() {
    val store = Arc.container().instance(ApplicationErasureStore::class.java).get()
    ApplicationErasureEntity.listAll().filter { !store.isOff(it.tenantId, it.application) }.forEach { it.delete() }
}

/**
 * Requirement values set the way production sets them (ADR 0088 decision 8): rows written through
 * [CapabilityRequirementStore] and followed by [StoredCapabilityRequirementValues.invalidate] once committed — what the
 * two `setRequirement` handlers do behind their `PUT`, minus the HTTP and the owner gates. Restored in a `finally`.
 */
object CapabilityRequirementFixtures {

    /** Runs [block] with [instance] set at the instance level and [tenant] for [tenantId], then clears every value. */
    fun <T> with(
        instance: Map<String, Int> = emptyMap(), tenantId: String? = null, tenant: Map<String, Int> = emptyMap(), block: () -> T,
    ): T {
        val left = QuarkusTransaction.requiringNew().call {
            CapabilityRequirementEntity.count() + TenantCapabilityRequirementEntity.count()
        }
        check(left == 0L) { "a previous test left $left requirement row(s)" }
        instance.forEach { (name, value) -> QuarkusTransaction.requiringNew().run { store().setInstance(name, value) } }
        if (tenantId != null) {
            tenant.forEach { (name, value) -> QuarkusTransaction.requiringNew().run { store().setTenant(tenantId, name, value) } }
        }
        values().invalidate()
        try {
            return block()
        } finally {
            clear()
        }
    }

    /** Clears every value at both levels — for a class that sets them through the API and must not leave a row. */
    fun clear() {
        QuarkusTransaction.requiringNew().run {
            CapabilityRequirementEntity.deleteAll()
            TenantCapabilityRequirementEntity.deleteAll()
        }
        values().invalidate()
    }

    private fun store(): CapabilityRequirementStore = Arc.container().instance(CapabilityRequirementStore::class.java).get()
    private fun values(): StoredCapabilityRequirementValues =
        Arc.container().instance(StoredCapabilityRequirementValues::class.java).get()
}
