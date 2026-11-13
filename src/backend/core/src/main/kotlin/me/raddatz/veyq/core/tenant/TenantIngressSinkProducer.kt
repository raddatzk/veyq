package me.raddatz.veyq.core.tenant

import me.raddatz.veyq.core.appshell.MusicFrameHost
import me.raddatz.veyq.core.blobstore.StoreSettings
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.config.VeyqConfig.ClientPortSource
import me.raddatz.veyq.core.config.VeyqConfig.IngressSinkType
import me.raddatz.veyq.core.config.VeyqConfig.IngressTlsMode
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
    private val store: StoreSettings,
    /**
     * An `Instance`, so a deployment that projects into a file — or into nothing — never constructs a
     * Kubernetes client at all. Building one reads a kubeconfig or a ServiceAccount token; a self-hoster on a
     * bare JVM has neither, and should not have to.
     */
    private val kubernetes: Instance<KubernetesClient>,
    /** The music host, from the one source that knows it (ADR 0131 decision 2): none while the TEMPORARY release flag
     *  musicReleased is off (decision 8), so no router names a host for it. */
    private val music: MusicFrameHost,
) {

    @Produces
    @ApplicationScoped
    fun ingressSink(): TenantIngressSink {
        val ingress = config.tenant().ingress()
        return when (ingress.sink()) {
            IngressSinkType.TRAEFIK_FILE -> traefikFileSink(ingress)
            IngressSinkType.KUBERNETES -> kubernetesSink(ingress.kubernetes())
        }
    }

    private fun traefikFileSink(ingress: VeyqConfig.Tenant.Ingress): TenantIngressSink {
        val path = ingress.dynamicConfigPath().orElse(null) ?: return DisabledIngressSink
        val bootstrapDomain = hostname(ingress.bootstrapDomain().orElse(null), "bootstrap-domain")
        if (bootstrapDomain == null) {
            // Loud, never a failed boot (ADR 0063: the projection is not a startup requirement). The sink still
            // writes the tenants' routes, and the admin surface's localhost port bypasses Traefik entirely — but
            // the domain the operator opens first routes nowhere, so this is an error, not a warning.
            log.error(
                "veyq.tenant.ingress.dynamic-config-path is set but veyq.tenant.ingress.bootstrap-domain is not a " +
                    "routable hostname — the bootstrap domain will not be routed.",
            )
        }
        val upstream = ingress.upstream()
        log.infof("Tenant ingress projection: Traefik file provider at %s", path)
        return TraefikFileIngressSink(
            target = Path.of(path),
            bootstrapDomain = bootstrapDomain,
            adminDomain = hostname(ingress.adminDomain().orElse(null), "admin-domain"),
            upstreams = TraefikFileIngressSink.Upstreams(app = upstream.app(), blobs = upstream.blobs(), admin = upstream.admin()),
            bucket = { store.bundledRouteBucket() },
            clientPortHeader = config.http().clientPort().takeIf { it.source() == ClientPortSource.HEADER }?.header(),
            musicDomain = music.domain(),
        )
    }

    /**
     * A configured domain goes through the same shape check a tenant row does, for the same reason: it is
     * interpolated into a Traefik `Host(` matcher. A blank value is absent — compose passes an unset
     * `${ADMIN_DOMAIN:-}` through as the empty string.
     */
    private fun hostname(value: String?, key: String): String? {
        val domain = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (TenantIngressProjector.isRoutableHostname(domain)) return domain
        log.errorf("veyq.tenant.ingress.%s=%s is not a routable hostname and is ignored", key, domain)
        return null
    }

    private fun kubernetesSink(k8s: VeyqConfig.Tenant.Ingress.Kubernetes): TenantIngressSink {
        // Config shape first, before a client is even built: `shared` names ONE Secret the Gateway's shared
        // listener references, and without it the mode has nothing to reference. Loud and disabled rather than a failed
        // boot (the same contract as the namespace below), and rather than silently falling back to `none` —
        // an instance whose routing works and whose TLS quietly does not is the failure TLS-as-precondition
        // exists to name. `KubernetesGatewayTlsSharedWithoutSecretTest` decides this.
        val tls = k8s.tls()
        val sharedTlsSecretName = tls.secretName().orElse(null)?.takeIf { it.isNotBlank() }
        if (tls.mode() == IngressTlsMode.SHARED && sharedTlsSecretName == null) {
            log.error(
                "veyq.tenant.ingress.kubernetes.tls.mode=shared but veyq.tenant.ingress.kubernetes.tls.secret-name " +
                    "is not set — there is no TLS Secret for the projected routes to be served under. " +
                    "Tenant domains will not be routed.",
            )
            return DisabledIngressSink
        }
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
        log.infof(
            "Tenant ingress projection: Gateway API objects in namespace %s under Gateway %s (tls.mode=%s, %d annotation(s))",
            namespace, k8s.gatewayName(), tls.mode(), k8s.annotations().size,
        )
        return KubernetesGatewaySink(
            client = client,
            namespace = namespace,
            gatewayName = k8s.gatewayName(),
            serviceName = k8s.serviceName(),
            servicePort = k8s.servicePort(),
            tlsMode = tls.mode(),
            sharedTlsSecretName = sharedTlsSecretName,
            annotations = k8s.annotations(),
        )
    }

    private companion object {
        val log: Logger = Logger.getLogger(TenantIngressSinkProducer::class.java)
    }
}
