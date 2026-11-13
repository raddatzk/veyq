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
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirementStore
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceAddresses
import me.raddatz.veyq.core.platform.capabilities.ServiceAddress
import me.raddatz.veyq.core.platform.capabilities.DeployedValues
import me.raddatz.veyq.core.platform.capabilities.MusicDeployment
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements
import me.raddatz.veyq.core.platform.capabilities.StoredCapabilityRequirementValues
import me.raddatz.veyq.core.platform.capabilities.TenantRequirementState
import me.raddatz.veyq.core.platform.capabilities.accepts
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.platform.capabilities.CapabilityTexts
import me.raddatz.veyq.core.platform.capabilities.ShareEmbeddingHosts
import me.raddatz.veyq.core.platform.capabilities.StoredTenantCapabilitySwitches
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilityState
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchStore
import me.raddatz.veyq.core.platform.capabilities.ApplicationErasureDay
import me.raddatz.veyq.core.platform.capabilities.ApplicationErasureStore
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/** A tenant-switchable entry as its tenant owner reads it: the catalog's words, both levels' state and its requirements. */
data class TenantCapabilityView(
    val state: TenantCapabilityState, val texts: CapabilityEntryTexts, val requirements: List<TenantRequirementState>,
    /** For an application's own entry, the day its content in the tenant is erased while one is named; else null. */
    val erasureDueAt: Instant? = null,
)

sealed interface TenantCapabilitiesOutcome {
    data class Ok(
        val ids: List<String>, val requirements: Map<String, Int>, val services: Map<String, ServiceAddress>,
        val deployed: Map<String, DeployedValues>,
        /** The named erasure days of the tenant's switched-off applications (uc.platform.erase-switched-off-application). */
        val erasures: List<ApplicationErasureDay>,
    ) : TenantCapabilitiesOutcome
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
    /** No tenant-switchable entry has a requirement of the name; nothing is stored. */
    data object NoSuchRequirement : TenantCapabilitySetOutcome, HttpFailure by Failures.requirementNotFound
    /** A flag's value is not `1`; nothing is stored. */
    data object InvalidValue : TenantCapabilitySetOutcome, HttpFailure by Failures.invalidRequest
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
    private val embeddingHosts: ShareEmbeddingHosts,
    private val requirementStore: CapabilityRequirementStore,
    private val requirementValues: StoredCapabilityRequirementValues,
    private val services: ExternalServiceAddresses,
    private val music: MusicDeployment,
    private val erasures: ApplicationErasureStore,
) {
    /** The tenant's effective set, for every member (decision 3). */
    fun effective(callerId: String, tenantId: String): TenantCapabilitiesOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(TenantCapabilitiesOutcome.NotFound, TenantCapabilitiesOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return TenantCapabilitiesOutcome.NotFound
        return TenantCapabilitiesOutcome.Ok(
            registry.active(tenantId), registry.requirements(tenantId), services.configured(), music.settings.values(), days(tenantId),
        )
    }

    private fun days(tenantId: String): List<ApplicationErasureDay> =
        QuarkusTransaction.joiningExisting().call { erasures.days(tenantId) }

    fun list(callerId: String, tenantId: String): TenantCapabilitySwitchesOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(TenantCapabilitySwitchesOutcome.NotFound, TenantCapabilitySwitchesOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return TenantCapabilitySwitchesOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TenantCapabilitySwitchesOutcome.NotOwner
        val due = days(tenantId).associate { it.application to it.dueAt }
        return TenantCapabilitySwitchesOutcome.Ok(registry.tenantRegister(tenantId).map { it.view(tenantId, due) })
    }

    fun set(callerId: String, tenantId: String, capabilityId: String, off: Boolean): TenantCapabilitySetOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(TenantCapabilitySetOutcome.NotFound, TenantCapabilitySetOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return TenantCapabilitySetOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TenantCapabilitySetOutcome.NotOwner
        if (capabilityId !in CapabilityIds.TENANT_SWITCHABLE) return TenantCapabilitySetOutcome.NoSuchEntry
        QuarkusTransaction.requiringNew().run { switchStore.set(tenantId, capabilityId, off) }
        switches.invalidate()
        embeddingHosts.refresh() // core.share-embedding's `https:` follows a tenant's switch (ADR 0121 decision 2)
        return TenantCapabilitySetOutcome.Stored(registry.tenantRegister(tenantId).single { it.id == capabilityId }.view(tenantId))
    }

    /** Set ([value]) or clear (null) the tenant's value of one requirement of a tenant-switchable entry (decision 8). A
     *  looser value than the instance's is stored and listed as not in effect: a tenant tightens only. */
    fun setRequirement(callerId: String, tenantId: String, capabilityId: String, name: String, value: Int?): TenantCapabilitySetOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(TenantCapabilitySetOutcome.NotFound, TenantCapabilitySetOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return TenantCapabilitySetOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TenantCapabilitySetOutcome.NotOwner
        val def = CapabilityRequirements.BY_NAME[name]
            ?.takeIf { it.capabilityId == capabilityId && capabilityId in CapabilityIds.TENANT_SWITCHABLE }
            ?: return TenantCapabilitySetOutcome.NoSuchRequirement
        if (value != null && !def.accepts(value)) return TenantCapabilitySetOutcome.InvalidValue
        QuarkusTransaction.requiringNew().run { requirementStore.setTenant(tenantId, name, value) }
        requirementValues.invalidate()
        return TenantCapabilitySetOutcome.Stored(registry.tenantRegister(tenantId).single { it.id == capabilityId }.view(tenantId))
    }

    private fun TenantCapabilityState.view(tenantId: String) =
        view(tenantId, days(tenantId).associate { it.application to it.dueAt })

    private fun TenantCapabilityState.view(tenantId: String, due: Map<String, Instant>) =
        TenantCapabilityView(this, CapabilityTexts.BY_ID.getValue(id), registry.tenantRequirements(tenantId, id), due[id])
}
