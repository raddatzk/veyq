package me.raddatz.veyq.core.platform.capabilities

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import me.raddatz.veyq.core.appshell.HostConnectSources
import me.raddatz.veyq.core.tenant.TenantEntity

/**
 * Which hosts' pages may connect to any `https` origin (ADR 0121 decision 2): `core.share-embedding` effective for the
 * tenant whose domain serves the page, and for a host that claims no tenant (the bootstrap domain, an unknown one) at the
 * instance level. The app shell asks [forHost] on the event loop, where no database is read, so the answer is a snapshot —
 * [ExternalServiceOrigins]' shape — recomputed at start and by every write that can change it: a Server Owner's switch
 * (`CapabilitySwitchHandler.set`), a tenant owner's (`TenantCapabilitySwitchHandler.set`) and every tenant write that
 * moves a domain (`TenantHandler`'s `reproject`, after create, revoke, change-domain, suspend, reactivate and deletion).
 *
 * **A stale snapshot never widens a tenant's policy past its own effective state.** It is keyed by the domains of the
 * tenants that existed when it was taken: a tenant created since is not in it and follows the instance level, which is
 * exactly its state (a new tenant has no switch of its own); a domain whose tenant was removed keeps its old answer,
 * which the AND along the levels holds at or below the instance level. The one write that could widen — a tenant moved
 * to a domain the snapshot does not know while its own switch is off — is why the domain writes refresh it. Before the
 * first refresh it widens nothing.
 */
@ApplicationScoped
class ShareEmbeddingHosts(private val registry: CapabilityRegistry) : HostConnectSources {

    private class Snapshot(val instance: Boolean, val byDomain: Map<String, Boolean>)

    @Volatile
    private var snapshot = Snapshot(instance = false, byDomain = emptyMap())

    override fun forHost(host: String?): List<String> {
        val current = snapshot
        val on = host?.let { current.byDomain[it] } ?: current.instance
        return if (on) HTTPS else emptyList()
    }

    fun onStart(@Observes event: StartupEvent) = refresh()

    /** Recompute the snapshot. Call after a write has committed and its caches were invalidated, on a worker thread. */
    fun refresh() {
        snapshot = QuarkusTransaction.requiringNew().call {
            Snapshot(
                instance = registry.isActive(CapabilityIds.SHARE_EMBEDDING, null),
                // A tenant's domain is stored lowercased (`TenantHandler`), as `TenantHostResolver.hostOf` reads a host.
                byDomain = TenantEntity.listAll().associate { it.domain to registry.isActive(CapabilityIds.SHARE_EMBEDDING, it.id) },
            )
        }
    }

    private companion object {
        /** The scheme source a kept link's page needs to read its share's own instance, wherever it is. */
        val HTTPS = listOf("https:")
    }
}
