package me.raddatz.veyq.core.tenant

import me.raddatz.veyq.core.config.VeyqConfig.IngressTlsMode
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.KubernetesResourceList
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteList
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerSet
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerSetBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerSetList
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ParentReference
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ParentReferenceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.jboss.logging.Logger
import java.security.MessageDigest

/**
 * The Kubernetes sink (ADR 0064): the same projection as [TraefikFileIngressSink], written as Gateway API objects
 * through the API instead of as YAML onto a volume. Per routable tenant domain one `HTTPRoute` (that host to the
 * app `Service`) and, under [IngressTlsMode.PER_DOMAIN], one `ListenerSet` carrying that host's HTTPS listener,
 * which the route attaches to — in the backend's **own** namespace, and nothing else. ADR 0064's "namespace-local
 * ServiceAccount + Role, never a cluster-wide `ClusterRole`" is a property of this code first and of the chart's
 * RBAC second: a Role the chart forgot to narrow would still not make this class reach outside [namespace]. And
 * it never writes a `Gateway`: the chart owns that one ([gatewayName]), the Role grants nothing on `gateways`, and
 * a tenant domain reaches the network only through a `ListenerSet` the chart's `Gateway` admits.
 *
 * **Reconcile, because a file sink's freebie is not available here.** Overwriting a file makes "the proxy's
 * state equals the table's" true in one call; an API-backed sink has to compute the difference and *delete*.
 * ADR 0063's argument for full-regenerate over an incremental patch ("every one of those states permanently
 * wrong, with nothing that ever notices") lands here as: an apply-only sink leaks a route to a suspended
 * tenant forever, and nothing ever notices, because the object it should have removed is exactly the object it
 * never looks at. Two kinds means two diffs — each kind is listed and pruned on its own, so a withdrawn domain
 * loses its `ListenerSet` as well as its route, and a switch away from `per-domain` removes every `ListenerSet`.
 *
 * **The ownership label decides what may be deleted, so it is computed with the desired state and not after
 * it.** [OWNER_LABEL] is set on every object this sink builds *and* is the selector it lists **each kind** by,
 * so an `HTTPRoute` or `ListenerSet` an operator put in this namespace by hand is never a candidate for removal
 * — it is not in the list the diff is taken over. A file sink cannot make this mistake (it cannot delete a
 * stranger's file by being wrong about which lines are its content); a reconcile-by-list sink can, which is why
 * `KubernetesGatewaySinkTest` decides it rather than this comment.
 *
 * **The domains arrive already validated** ([TenantIngressSink.apply]). That matters more here than it reads:
 * each becomes a route `hostname`, a listener `hostname` and, through [nameFor], an object name, and an illegal
 * character in any of them makes the API server reject the object — which on this sink's failure boundary
 * strands every *other* tenant's routing too, not just the bad one's. The check is [TenantIngressProjector]'s
 * because it is the column's shape rather than this API's grammar; what is this sink's own is [nameFor]'s length
 * bound below, which a valid hostname can still violate.
 *
 * **TLS is a passthrough: the sink stamps a reference, never a configuration.** Its Traefik counterpart emits
 * `tls.certResolver: letsencrypt` on every router — a *reference* to a resolver it never selects or configures
 * (that is the compose file's). The faithful translation here, per [tlsMode], is where a route attaches plus,
 * under `per-domain`, a listener's `certificateRefs` and the operator's annotations ([annotations], stamped
 * verbatim and uninterpreted on every `ListenerSet`, which is what cert-manager reads) — and never an `Issuer`, a
 * `Certificate` or a `Secret`, which are cert-manager's and the operator's, and would need RBAC this design
 * exists to avoid. An `HTTPRoute` carries no certificate, so it gets no annotations. Under `per-domain` the
 * `certificateRefs` name is the object's **own** [nameFor] — see that method for why a second derived name would
 * not be legal at the contract's domain cap.
 *
 * **The failure boundary is the whole reconcile, and that is a decision, not an oversight.** Every call that
 * can reach the API server is inside the one `try` — ADR 0063's incident was a `try` that started one line too
 * late, and a two-kind reconcile has more lines above the interesting one, not fewer. What this cannot inherit
 * from the file sink is atomicity: a rename either happened or did not, while a reconcile that dies after three
 * deletes and one create leaves the namespace half-projected. That is survivable for exactly the reason
 * full-regenerate exists — the next regenerate recomputes the whole answer and repairs it — and it is why an
 * incremental sink would have been the wrong shape here twice over.
 */
class KubernetesGatewaySink(
    private val client: KubernetesClient,
    private val namespace: String,
    /** The chart's `Gateway`, in [namespace]: every `ListenerSet` and every `none`/`shared` route attaches to it. */
    private val gatewayName: String,
    private val serviceName: String,
    private val servicePort: Int,
    private val tlsMode: IngressTlsMode,
    /** The one `Secret` the `Gateway`'s shared listener references under [IngressTlsMode.SHARED]; the producer guarantees it is set for that mode. */
    private val sharedTlsSecretName: String?,
    private val annotations: Map<String, String>,
) : TenantIngressSink {

    override val enabled = true

    override fun apply(domains: List<String>) {
        try {
            // Desired state first, ownership included — see the class KDoc. `nameFor` is deterministic, so the
            // same domain always addresses the same objects and a regenerate updates rather than duplicates.
            val names: Map<String, String> = domains.associateBy { nameFor(it) }
            val desiredRoutes: Map<String, HTTPRoute> = names.mapValues { (name, domain) -> routeFor(name, domain) }
            // The `when` is total over the mode with no `else`, so a fourth mode cannot be added without deciding
            // whether it writes a `ListenerSet`. Only `per-domain` does: `none` and `shared` attach every route to
            // a listener the chart's `Gateway` already has.
            val desiredListenerSets: Map<String, ListenerSet> = when (tlsMode) {
                IngressTlsMode.NONE, IngressTlsMode.SHARED -> emptyMap()
                IngressTlsMode.PER_DOMAIN -> names.mapValues { (name, domain) -> listenerSetFor(name, domain) }
            }

            // Routes are pruned before their ListenerSets and written after them, so no route is ever left
            // pointing at a parent this reconcile already removed, nor written before the parent it names.
            prune("HTTPRoute", HTTPRoute::class.java, HTTPRouteList::class.java, desiredRoutes.keys)
            prune("ListenerSet", ListenerSet::class.java, ListenerSetList::class.java, desiredListenerSets.keys)
            desiredListenerSets.values.forEach { client.resource(it).inNamespace(namespace).createOr { op -> op.update() } }
            desiredRoutes.values.forEach { client.resource(it).inNamespace(namespace).createOr { op -> op.update() } }
        } catch (e: Exception) {
            // A failed projection must not fail the tenant operation that triggered it, and must not fail the
            // startup regenerate either: the row is committed and correct, and the next regenerate repairs the
            // routing. Loud, because until then the namespace does not match the table.
            log.errorf(
                e,
                "Could not project the tenant routes into namespace %s — routing is stale until the next regenerate",
                namespace,
            )
        }
    }

    /**
     * Deletes every object of one kind this sink owns that is not in [desired]. Listed BY the ownership label, not
     * filtered after: an unowned object never enters the candidate set at all, so no later line can decide to
     * remove one.
     */
    private fun <T : HasMetadata, L : KubernetesResourceList<T>> prune(
        kind: String,
        type: Class<T>,
        listType: Class<L>,
        desired: Set<String>,
    ) {
        val objects = client.resources(type, listType).inNamespace(namespace)
        objects.withLabel(OWNER_LABEL, OWNER_VALUE).list().items
            .filterNot { desired.contains(it.metadata.name) }
            .forEach { stale ->
                log.infof("Withdrawing tenant %s %s/%s — its domain is no longer routable", kind, namespace, stale.metadata.name)
                objects.withName(stale.metadata.name).delete()
            }
    }

    /**
     * The object name for a domain, shared by its `HTTPRoute` and its `ListenerSet` (two kinds, so no clash).
     * Deterministic — a name derived from the domain rather than from its position means adding some *other*
     * tenant never renames an existing object, so a regenerate updates in place instead of churning the whole set.
     *
     * The digest branch is not decoration. A name is an RFC 1123 subdomain capped at 253 characters, and
     * `contracts/api/tenants.openapi.yaml` already allows a `domain` 253 characters long — so the prefix alone
     * can push a **legal** domain past a legal name, and an over-long name is rejected by the API server, which
     * on this sink's failure boundary strands every other tenant too. Collapsing to a digest keeps the mapping
     * one-to-one and always legal.
     */
    private fun nameFor(domain: String): String {
        val slug = "$NAME_PREFIX${domain.replace('.', '-')}"
        if (slug.length <= MAX_NAME) return slug
        val digest = MessageDigest.getInstance("SHA-256").digest(domain.toByteArray())
        return NAME_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    private fun metadata(name: String, annotated: Boolean): ObjectMeta {
        val metadata = ObjectMetaBuilder()
            .withName(name)
            .withNamespace(namespace)
            .withLabels<String, String>(mapOf(OWNER_LABEL to OWNER_VALUE))
        // Only when there is something to stamp: an empty `annotations: {}` is not what "no annotations" looks
        // like on an object an operator reads back, and the mode-none test asserts absence, not emptiness.
        if (annotated && annotations.isNotEmpty()) metadata.withAnnotations<String, String>(annotations)
        return metadata.build()
    }

    /**
     * One host, everything under `/` to the app `Service`, and no blobs route beside it. That is ADR 0064's own
     * departure from the compose sink: the gateway implementation is operator-chosen and its prefix/Host-preservation
     * behaviour is not guaranteed, so the store gets a host of its own (with a bucket CORS policy and
     * `SigningMode.STORE_ORIGIN`) instead of ADR 0013 §4's same-origin path route. A path rule for the bucket
     * here would be the compose assumption smuggled onto a target that does not make it.
     */
    private fun routeFor(name: String, domain: String): HTTPRoute = HTTPRouteBuilder()
        .withMetadata(metadata(name, annotated = false))
        .withNewSpec()
        .withParentRefs(parentFor(name))
        .withHostnames(domain)
        .addNewRule()
        .addNewMatch().withNewPath().withType("PathPrefix").withValue("/").endPath().endMatch()
        .addNewBackendRef().withName(serviceName).withPort(servicePort).endBackendRef()
        .endRule()
        .endSpec()
        .build()

    /**
     * Where a route attaches is the TLS mode, expressed in routing: the `Gateway`'s plain-HTTP listener under
     * `none`, its one hostname-less HTTPS listener under `shared`, and under `per-domain` the domain's own
     * `ListenerSet`. The `when` is total with no `else` — the same reason as in [apply].
     */
    private fun parentFor(name: String): ParentReference = when (tlsMode) {
        IngressTlsMode.NONE -> gatewayParent(HTTP_LISTENER)
        IngressTlsMode.SHARED -> gatewayParent(SHARED_HTTPS_LISTENER)
        IngressTlsMode.PER_DOMAIN -> ParentReferenceBuilder()
            .withGroup(GATEWAY_GROUP)
            .withKind("ListenerSet")
            .withName(name)
            .build()
    }

    private fun gatewayParent(sectionName: String): ParentReference = ParentReferenceBuilder()
        .withGroup(GATEWAY_GROUP)
        .withKind("Gateway")
        .withName(gatewayName)
        .withSectionName(sectionName)
        .build()

    /**
     * One HTTPS listener for one host, its certificate referenced by the object's **own** name — the one string
     * already proven legal at the contract's domain cap (see [nameFor]); `<name>-tls` would not be. This and the
     * `Gateway`'s `allowedListeners` are the whole of how a runtime tenant gets a certificate: cert-manager reads
     * the annotations stamped here and issues into that `Secret`, and the backend writes no `Secret` itself.
     */
    private fun listenerSetFor(name: String, domain: String): ListenerSet = ListenerSetBuilder()
        .withMetadata(metadata(name, annotated = true))
        .withNewSpec()
        .withNewParentRef().withGroup(GATEWAY_GROUP).withKind("Gateway").withName(gatewayName).endParentRef()
        .addNewListener()
        .withName(LISTENER_SET_LISTENER)
        .withHostname(domain)
        .withPort(HTTPS_PORT)
        .withProtocol("HTTPS")
        .withNewTls().withMode("Terminate").addNewCertificateRef().withKind("Secret").withName(name).endCertificateRef().endTls()
        .endListener()
        .endSpec()
        .build()

    companion object {
        /**
         * The well-known key rather than a veyq-private one, so `kubectl get httproutes -L` shows an operator who
         * owns what; the **value** is what makes it unambiguous — Helm stamps `Helm` here, an operator's own
         * object stamps nothing, and neither collides with this. The value says "ingress" in ADR 0063's sense
         * (the projection), not the Kubernetes kind, and `scripts/chart_meta_check.py` reads it from this line.
         */
        const val OWNER_LABEL = "app.kubernetes.io/managed-by"
        const val OWNER_VALUE = "veyq-tenant-ingress"

        /**
         * The chart's `Gateway` listener names this sink attaches to. They are a contract with
         * `infra/kubernetes/charts/veyq/templates/gateway.yaml`, held there by `scripts/chart_meta_check.py`, which reads them from
         * these lines: a renamed listener would leave every route `Accepted: False` with no error on this side.
         */
        const val HTTP_LISTENER = "http"
        const val SHARED_HTTPS_LISTENER = "https"

        private const val LISTENER_SET_LISTENER = "https"
        private const val HTTPS_PORT = 443
        private const val GATEWAY_GROUP = "gateway.networking.k8s.io"
        private const val NAME_PREFIX = "veyq-tenant-"
        private const val MAX_NAME = 253
        private val log: Logger = Logger.getLogger(KubernetesGatewaySink::class.java)
    }
}
