package me.raddatz.veyq.core.platform.capabilities

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import me.raddatz.veyq.core.appshell.MusicFrameHost
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.tenant.TenantEntity

/**
 * The shell's [MusicFrameHost] (ADR 0131): the music host from the deployment, and the hosts whose pages may frame it —
 * the bootstrap domain and every tenant's — as a snapshot, [ShareEmbeddingHosts]' shape: recomputed at start and by every
 * tenant write that moves a domain (`TenantHandler`'s `reproject`). A tenant created since the last refresh cannot frame
 * the music until it runs, which is a refusal, never a widening.
 */
@ApplicationScoped
class MusicFrameHosts(private val music: MusicDeployment, config: VeyqConfig) : MusicFrameHost {
    private val bootstrap: String? = config.tenant().ingress().bootstrapDomain().orElse(null)?.trim()?.lowercase()?.ifEmpty { null }

    @Volatile
    private var snapshot: List<String> = listOfNotNull(bootstrap)

    override fun domain(): String? = music.domain

    override fun ancestors(): List<String> = snapshot

    fun onStart(@Observes event: StartupEvent) = refresh()

    /** Recompute the snapshot. Call after a write has committed, on a worker thread. */
    fun refresh() {
        if (music.domain == null) return // no frame, nothing to frame it
        snapshot = QuarkusTransaction.requiringNew().call {
            // A tenant's domain is stored lowercased (`TenantHandler`), the shape `TenantIngressProjector` routes.
            (listOfNotNull(bootstrap) + TenantEntity.listAll().map { it.domain }).filter { HOST.matches(it) }.distinct().sorted()
        }
    }

    private companion object {
        /** A policy source is written verbatim, so only a plain host name gets in (`TenantIngressProjector`'s shape). */
        val HOST = Regex("^([a-z0-9](-?[a-z0-9])*\\.)*[a-z0-9](-?[a-z0-9])*$")
    }
}
