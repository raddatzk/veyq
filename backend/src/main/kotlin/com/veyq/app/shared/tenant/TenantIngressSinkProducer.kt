package com.veyq.app.shared.tenant

import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.config.VeyqConfig.IngressSinkType
import io.fabric8.kubernetes.client.KubernetesClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
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
 *
 * **Which sink is named, never inferred** from which of the two configuration blocks happens to be populated —
 * ADR 0067 Decision 1 settled that shape once for the object store. The `when` is total over
 * [IngressSinkType] with no `else`, so a third deployment target cannot be added without deciding this.
 */
@ApplicationScoped
class TenantIngressSinkProducer(
    private val config: VeyqConfig,
    /**
     * An `Instance`, so a deployment that projects into a file — or into nothing — never constructs a
     * Kubernetes client at all. Building one reads a kubeconfig or a ServiceAccount token; a self-hoster on a
     * bare JVM has neither, and should not have to.
     */
    private val kubernetes: Instance<KubernetesClient>,
) {

    @Produces
    @ApplicationScoped
    fun ingressSink(): TenantIngressSink {
        val ingress = config.tenant().ingress()
        return when (ingress.sink()) {
            IngressSinkType.TRAEFIK_FILE -> traefikFileSink(ingress.dynamicConfigPath().orElse(null))
            IngressSinkType.KUBERNETES -> kubernetesSink(ingress.kubernetes())
        }
    }

    private fun traefikFileSink(path: String?): TenantIngressSink {
        if (path == null) return DisabledIngressSink
        log.infof("Tenant ingress projection: Traefik file provider at %s", path)
        return TraefikFileIngressSink(Path.of(path))
    }

    private fun kubernetesSink(k8s: VeyqConfig.Tenant.Ingress.Kubernetes): TenantIngressSink {
        // The override is second, never first: in a cluster the namespace comes from the pod's own mounted
        // ServiceAccount token, which the fabric8 client has already read by the time this runs. Requiring the
        // value in config instead would make one image un-deployable without a per-install rebuild.
        val client = kubernetes.get()
        val namespace = k8s.namespace().orElse(null) ?: client.namespace
        if (namespace.isNullOrBlank()) {
            // Loud and disabled rather than a failed boot: an instance that cannot project its routing still has
            // to serve /api/setup (ADR 0063). There is nothing to reconcile *into*, so there is no sink.
            log.error(
                "veyq.tenant.ingress.sink=kubernetes but no namespace is resolvable — neither " +
                    "veyq.tenant.ingress.kubernetes.namespace nor an in-cluster ServiceAccount supplied one. " +
                    "Tenant domains will not be routed.",
            )
            return DisabledIngressSink
        }
        log.infof("Tenant ingress projection: Kubernetes Ingress objects in namespace %s", namespace)
        return KubernetesIngressSink(
            client = client,
            namespace = namespace,
            ingressClassName = k8s.ingressClassName().orElse(null),
            serviceName = k8s.serviceName(),
            servicePort = k8s.servicePort(),
        )
    }

    private companion object {
        val log: Logger = Logger.getLogger(TenantIngressSinkProducer::class.java)
    }
}
