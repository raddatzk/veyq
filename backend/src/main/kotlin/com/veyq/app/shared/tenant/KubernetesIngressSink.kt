package com.veyq.app.shared.tenant

import com.veyq.app.shared.config.VeyqConfig.IngressTlsMode
import io.fabric8.kubernetes.api.model.networking.v1.Ingress
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLSBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.jboss.logging.Logger
import java.security.MessageDigest

/**
 * The Kubernetes sink (ADR 0064): the same projection as [TraefikFileIngressSink], written as `Ingress`
 * objects through the API instead of as YAML onto a volume. One `Ingress` per routable tenant domain, in the
 * backend's **own** namespace, and nothing else — ADR 0064's "namespace-local ServiceAccount + Role, never a
 * cluster-wide `ClusterRole`" is a property of this code first and of the chart's RBAC second: a Role the chart
 * forgot to narrow would still not make this class reach outside [namespace].
 *
 * **Reconcile, because a file sink's freebie is not available here.** Overwriting a file makes "the proxy's
 * state equals the table's" true in one call; an API-backed sink has to compute the difference and *delete*.
 * ADR 0063's argument for full-regenerate over an incremental patch ("every one of those states permanently
 * wrong, with nothing that ever notices") lands here as: an apply-only sink leaks a route to a suspended
 * tenant forever, and nothing ever notices, because the object it should have removed is exactly the object it
 * never looks at.
 *
 * **The ownership label decides what may be deleted, so it is computed with the desired state and not after
 * it.** [OWNER_LABEL] is set on every object this sink builds *and* is the selector it lists by, so an
 * `Ingress` an operator put in this namespace by hand is never a candidate for removal — it is not in the
 * list the diff is taken over. A file sink cannot make this mistake (it cannot delete a stranger's file by
 * being wrong about which lines are its content); a reconcile-by-list sink can, which is why
 * `KubernetesIngressSinkTest` decides it rather than this comment.
 *
 * **The domains arrive already validated** ([TenantIngressSink.apply]). That matters more here than it reads:
 * each becomes both a `spec.rules[].host` and, through [nameFor], an object name, and an illegal character in
 * either makes the API server reject the object — which on this sink's failure boundary strands every *other*
 * tenant's routing too, not just the bad one's. The check is [TenantIngressProjector]'s because it is the
 * column's shape rather than this API's grammar; what is this sink's own is [nameFor]'s length bound below,
 * which a valid hostname can still violate.
 *
 * **TLS is a passthrough: the sink stamps a reference, never a configuration.** Its Traefik counterpart emits
 * `tls.certResolver: letsencrypt` on every router — a *reference* to a resolver it never selects or configures
 * (that is the compose file's). The faithful translation here is `spec.tls` plus the operator's annotations
 * ([annotations], stamped verbatim and uninterpreted), per [tlsMode] — and never an `Issuer`, a `Certificate`
 * or a `Secret`, which are the ingress controller's and the operator's, and would need RBAC this design
 * exists to avoid. An earlier version of this comment said the sink emitted *nothing* and called that the
 * same cut; it was a strictly weaker one, and on a target where TLS is a precondition (the refresh cookie is
 * `Secure`, every mailed link is `https`) it shipped an instance that boots and then silently breaks sessions.
 * Under [IngressTlsMode.PER_DOMAIN] the `secretName` is the object's **own** [nameFor] — see that method for
 * why a second derived name would not be legal at the contract's domain cap.
 *
 * **The failure boundary is the whole reconcile, and that is a decision, not an oversight.** Every call that
 * can reach the API server is inside the one `try` — ADR 0063's incident was a `try` that started one line too
 * late, and an `Ingress` reconcile has more lines above the interesting one, not fewer. What this cannot
 * inherit from the file sink is atomicity: a rename either happened or did not, while a reconcile that dies
 * after three deletes and one create leaves the namespace half-projected. That is survivable for exactly the
 * reason full-regenerate exists — the next regenerate recomputes the whole answer and repairs it — and it is
 * why an incremental sink would have been the wrong shape here twice over.
 */
class KubernetesIngressSink(
    private val client: KubernetesClient,
    private val namespace: String,
    private val ingressClassName: String?,
    private val serviceName: String,
    private val servicePort: Int,
    private val tlsMode: IngressTlsMode,
    /** The one `Secret` every object references under [IngressTlsMode.SHARED]; the producer guarantees it is set for that mode. */
    private val sharedTlsSecretName: String?,
    private val annotations: Map<String, String>,
) : TenantIngressSink {

    override val enabled = true

    override fun apply(domains: List<String>) {
        try {
            // Desired state first, ownership included — see the class KDoc. `nameFor` is deterministic, so the
            // same domain always addresses the same object and a regenerate updates rather than duplicates.
            val desired: Map<String, Ingress> = domains
                .associateBy { nameFor(it) }
                .mapValues { (name, domain) -> ingressFor(name, domain) }

            val ingresses = client.network().v1().ingresses().inNamespace(namespace)
            // Listed BY the ownership label, not filtered after: an unowned object never enters the candidate
            // set at all, so no later line can decide to remove one.
            val owned = ingresses.withLabel(OWNER_LABEL, OWNER_VALUE).list().items

            owned.filterNot { desired.containsKey(it.metadata.name) }.forEach { stale ->
                log.infof("Withdrawing tenant ingress %s/%s — its domain is no longer routable", namespace, stale.metadata.name)
                ingresses.withName(stale.metadata.name).delete()
            }
            desired.values.forEach { ingress ->
                client.resource(ingress).inNamespace(namespace).createOr { it.update() }
            }
        } catch (e: Exception) {
            // A failed projection must not fail the tenant operation that triggered it, and must not fail the
            // startup regenerate either: the row is committed and correct, and the next regenerate repairs the
            // routing. Loud, because until then the namespace does not match the table.
            log.errorf(
                e,
                "Could not project the tenant ingress into namespace %s — routing is stale until the next regenerate",
                namespace,
            )
        }
    }

    /**
     * The `Ingress` object name for a domain. Deterministic — a name derived from the domain rather than from
     * its position means adding some *other* tenant never renames an existing object, so a regenerate updates
     * in place instead of churning the whole set.
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

    /**
     * One host rule to the app `Service`, and no blobs route beside it. That is ADR 0064's own departure from
     * the compose sink: the ingress controller is operator-chosen and its prefix/Host-preservation behaviour is
     * not guaranteed, so the store gets an `Ingress` host of its own (with a bucket CORS policy and
     * `SigningMode.STORE_ORIGIN`) instead of ADR 0013 §4's same-origin path route. A path rule for the bucket
     * here would be the compose assumption smuggled onto a target that does not make it.
     */
    private fun ingressFor(name: String, domain: String): Ingress {
        val metadata = IngressBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(namespace)
            .withLabels<String, String>(mapOf(OWNER_LABEL to OWNER_VALUE))
        // Only when there is something to stamp: an empty `annotations: {}` is not what "no annotations" looks
        // like on an object an operator reads back, and the mode-none test asserts absence, not emptiness.
        if (annotations.isNotEmpty()) metadata.withAnnotations<String, String>(annotations)
        val spec = metadata
            .endMetadata()
            .withNewSpec()
            .withIngressClassName(ingressClassName)
            .addNewRule()
            .withHost(domain)
            .withNewHttp()
            .addNewPath()
            .withPath("/")
            .withPathType("Prefix")
            .withNewBackend()
            .withNewService()
            .withName(serviceName)
            .withNewPort().withNumber(servicePort).endPort()
            .endService()
            .endBackend()
            .endPath()
            .endHttp()
            .endRule()
        // The `when` is total over the mode with no `else`, so a fourth mode cannot be added without deciding
        // what it references. `name` is the object's own name — the one string already proven legal at the
        // contract's domain cap (see nameFor).
        val secretName = when (tlsMode) {
            IngressTlsMode.NONE -> null
            IngressTlsMode.PER_DOMAIN -> name
            IngressTlsMode.SHARED -> sharedTlsSecretName
        }
        // `addToTls` with a built object rather than the nested `addNewTl()`: fabric8 singularises the field
        // name for its nested builders, and a reader grepping for `tls` should find the reference here.
        if (secretName != null) spec.addToTls(IngressTLSBuilder().withHosts(domain).withSecretName(secretName).build())
        return spec.endSpec().build()
    }

    companion object {
        /**
         * The well-known key rather than a veyq-private one, so `kubectl get ingress -L` shows an operator who
         * owns what; the **value** is what makes it unambiguous — Helm stamps `Helm` here, an operator's own
         * object stamps nothing, and neither collides with this.
         */
        const val OWNER_LABEL = "app.kubernetes.io/managed-by"
        const val OWNER_VALUE = "veyq-tenant-ingress"

        private const val NAME_PREFIX = "veyq-tenant-"
        private const val MAX_NAME = 253
        private val log: Logger = Logger.getLogger(KubernetesIngressSink::class.java)
    }
}
