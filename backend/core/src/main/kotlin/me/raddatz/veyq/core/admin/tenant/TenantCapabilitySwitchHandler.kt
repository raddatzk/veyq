package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.platform.capabilities.CapabilityEntryTexts
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.platform.capabilities.CapabilityTexts
import me.raddatz.veyq.core.platform.capabilities.StoredTenantCapabilitySwitches
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilityState
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchStore
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

/** A tenant-switchable entry as its tenant owner reads it: the catalog's words and both levels' state. */
data class TenantCapabilityView(val state: TenantCapabilityState, val texts: CapabilityEntryTexts)

sealed interface TenantCapabilitiesOutcome {
    data class Ok(val ids: List<String>) : TenantCapabilitiesOutcome
    data object Suspended : TenantCapabilitiesOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TenantCapabilitiesOutcome, HttpFailure by Failures.tenantNotFound
}

sealed interface TenantCapabilitySwitchesOutcome {
    data class Ok(val entries: List<TenantCapabilityView>) : TenantCapabilitySwitchesOutcome
    data object Suspended : TenantCapabilitySwitchesOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TenantCapabilitySwitchesOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : TenantCapabilitySwitchesOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
}

sealed interface TenantCapabilitySetOutcome {
    data class Stored(val view: TenantCapabilityView) : TenantCapabilitySetOutcome
    data object Suspended : TenantCapabilitySetOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TenantCapabilitySetOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : TenantCapabilitySetOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    /** No entry a tenant may switch has the id (unknown, or the Server Owner's alone); nothing is stored. */
    data object NoSuchEntry : TenantCapabilitySetOutcome, HttpFailure by httpFailure("capability_not_found", FailureStatus.NOT_FOUND)
}

/**
 * The tenant level of the capability register (uc.admin.tenant-switch-capabilities, ADR 0088 decisions 2, 3 and 12):
 * a member reads the tenant's effective set, the tenant owner reads and switches the entries a tenant may switch. The
 * gates are the tenant settings' ([TenantMailSettingsHandler]): the membership gate first (a non-member gets the same
 * 404 as an unknown tenant, ADR 0042), then the owner gate. A write is ordered as [CapabilitySwitchHandler]'s
 * (`core.admin.instance`): commit, then forget the cached sets, then read the entry back, so no gate follows a switch
 * before it is committed or keeps the old one after.
 */
@ApplicationScoped
class TenantCapabilitySwitchHandler(
    private val store: TenantStore,
    private val access: ScopeAccess,
    private val registry: CapabilityRegistry,
    private val switchStore: TenantCapabilitySwitchStore,
    private val switches: StoredTenantCapabilitySwitches,
) {
    /** The tenant's effective set, for every member (decision 3). */
    fun effective(callerId: String, tenantId: String): TenantCapabilitiesOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(TenantCapabilitiesOutcome.NotFound, TenantCapabilitiesOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return TenantCapabilitiesOutcome.NotFound
        return TenantCapabilitiesOutcome.Ok(registry.active(tenantId))
    }

    fun list(callerId: String, tenantId: String): TenantCapabilitySwitchesOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(TenantCapabilitySwitchesOutcome.NotFound, TenantCapabilitySwitchesOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return TenantCapabilitySwitchesOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TenantCapabilitySwitchesOutcome.NotOwner
        return TenantCapabilitySwitchesOutcome.Ok(registry.tenantRegister(tenantId).map { it.view() })
    }

    fun set(callerId: String, tenantId: String, capabilityId: String, off: Boolean): TenantCapabilitySetOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(TenantCapabilitySetOutcome.NotFound, TenantCapabilitySetOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return TenantCapabilitySetOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TenantCapabilitySetOutcome.NotOwner
        if (capabilityId !in CapabilityIds.TENANT_SWITCHABLE) return TenantCapabilitySetOutcome.NoSuchEntry
        QuarkusTransaction.requiringNew().run { switchStore.set(tenantId, capabilityId, off) }
        switches.invalidate()
        return TenantCapabilitySetOutcome.Stored(registry.tenantRegister(tenantId).single { it.id == capabilityId }.view())
    }

    private fun TenantCapabilityState.view() = TenantCapabilityView(this, CapabilityTexts.BY_ID.getValue(id))
}
