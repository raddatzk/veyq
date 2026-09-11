package com.veyq.app.shared.tenant

import com.veyq.app.shared.config.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.jboss.logging.Logger
import java.nio.file.Path

/**
 * Picks the one [TenantIngressSink] this instance projects into, from config — the same shape
 * `BlobStoreProducer.blobStore()` already uses for the object store: a `@Produces` factory that decides once at
 * boot, and exactly one caller ([TenantIngressProjector]) holding the resulting interface and never branching
 * on which implementation it got.
 *
 * **A producer rather than the plain factory `StoreProvisionerFactory` is**, because "no sink" is expressible
 * here as a *bean*: [DisabledIngressSink] is a total implementation of the contract, not the `null` that forces
 * that class into a hand-rolled factory.
 */
@ApplicationScoped
class TenantIngressSinkProducer(config: VeyqConfig) {

    private val dynamicConfigPath: String? = config.tenant().ingress().dynamicConfigPath().orElse(null)

    @Produces
    @ApplicationScoped
    fun ingressSink(): TenantIngressSink {
        val path = dynamicConfigPath ?: return DisabledIngressSink
        log.infof("Tenant ingress projection: Traefik file provider at %s", path)
        return TraefikFileIngressSink(Path.of(path))
    }

    private companion object {
        val log: Logger = Logger.getLogger(TenantIngressSinkProducer::class.java)
    }
}
