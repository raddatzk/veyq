package me.raddatz.veyq.core.tenant

import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteList
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerSet
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerSetBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerSetList
import io.fabric8.kubernetes.client.KubernetesClient
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR 0064's TLS passthrough in its per-tenant shape: `tls.mode=per-domain` writes, beside each domain's
 * `HTTPRoute`, one `ListenerSet` whose single HTTPS listener names that domain and references a certificate by the
 * object's **own name** — and the route attaches to that `ListenerSet`, not to the `Gateway`. The operator's
 * annotation map rides on every `ListenerSet` **verbatim** and on no route. Together those are what let
 * cert-manager obtain a certificate per tenant domain — including a custom domain that is nobody's subdomain —
 * with the backend holding no issuer, no CRD and no RBAC beyond the `httproutes`/`listenersets` verbs it has.
 *
 * It is also the `ListenerSet` half of [KubernetesGatewaySinkTest]'s two hard properties — a withdrawn domain's
 * `ListenerSet` is deleted, and one this sink does not own survives — because this is the one mode that writes
 * the kind.
 *
 * Same mock control plane as [KubernetesGatewaySinkTest] (`@WithKubernetesTestServer`), and the same wiring
 * proof: the sink under test is the one the producer built from *this profile's* config, so the hyphenated
 * `per-domain` binding onto the enum, and the quoted annotation key surviving SmallRye's property grammar, are
 * proven by these cases passing at all.
 *
 * **What the mock does NOT do, stated so the length case is read for what it asserts.** A real API server
 * rejects an object whose `certificateRefs` name is longer than 253 characters or not an RFC 1123 subdomain; the
 * mock in CRUD mode stores whatever it is handed, and it installs no CRD schema at all. So
 * [aDomainAtTheContractsLengthCapYieldsALegalSecretName] asserts the bound directly — length and character
 * class — rather than relying on a 422 that this server will never send.
 */
@QuarkusTest
@TestProfile(KubernetesGatewayTlsPerDomainTest.PerDomainWithAnnotations::class)
@WithKubernetesTestServer
class KubernetesGatewayTlsPerDomainTest {

    class PerDomainWithAnnotations : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.tenant.ingress.sink" to "kubernetes",
            "veyq.tenant.ingress.kubernetes.tls.mode" to "per-domain",
            // One key a real issuer reads, and one the backend knows nothing about: the second is the control
            // that proves the map is passed through rather than interpreted from a known set.
            "veyq.tenant.ingress.kubernetes.annotations.\"cert-manager.io/issuer\"" to "veyq-acme",
            "veyq.tenant.ingress.kubernetes.annotations.\"example.com/whatever\"" to "survives the round trip",
        )
    }

    @Inject
    lateinit var sink: TenantIngressSink

    @Inject
    lateinit var client: KubernetesClient

    @Test
    fun perDomainWritesOneListenerSetPerDomainWhoseCertificateIsNamedAfterTheObject() {
        // Also the positive control for the `none` and `shared` cases' "no ListenerSet": the same sink, one
        // setting apart, does write them.
        sink.apply(listOf("a.example.com", "custom-domain.org"))

        val sets = ownedListenerSets()
        assertEquals(2, sets.size, "one ListenerSet per domain, and a custom domain is not excluded")
        for (set in sets) {
            val parent = set.spec.parentRef
            assertEquals("Gateway", parent.kind)
            assertEquals("veyq", parent.name, "the chart's Gateway, by the setting's default")
            val listener = set.spec.listeners.single()
            assertEquals("HTTPS", listener.protocol)
            assertEquals(443, listener.port)
            assertEquals("Terminate", listener.tls.mode)
            val ref = listener.tls.certificateRefs.single()
            assertEquals(set.metadata.name, ref.name, "the certificate is the object's own name, never a second derived one")
            assertEquals(null, ref.namespace, "same namespace — cert-manager issues only into the listener's own")
            val route = owned().single { it.metadata.name == set.metadata.name }
            assertEquals(listOf(listener.hostname), route.spec.hostnames, "the listener covers exactly its route's host")
            val routeParent = route.spec.parentRefs.single()
            assertEquals("ListenerSet", routeParent.kind, "under per-domain the route attaches to its ListenerSet")
            assertEquals(set.metadata.name, routeParent.name)
        }
    }

    @Test
    fun aDomainAtTheContractsLengthCapYieldsALegalSecretName() {
        // `contracts/api/tenants.openapi.yaml` allows a 253-character domain, and a Secret name has the same
        // 253-character cap an object name has. This is the domain nameFor's digest branch exists for; the TLS
        // reference has to inherit that legality rather than re-derive a name that would not have it (a `-tls`
        // suffix on a name already at the cap is an object the API server rejects — which on this sink's
        // failure boundary strands EVERY tenant's routing, not this one's).
        val label = "a".repeat(63)
        val domain = "$label.$label.$label.${"b".repeat(61)}"
        assertEquals(253, domain.length, "control: the domain is at the contract's cap")

        sink.apply(listOf(domain))

        val set = ownedListenerSets().single()
        val secretName = certificateOf(set)
        assertEquals(set.metadata.name, secretName)
        assertTrue(secretName.length <= 253, "a Secret name is capped at 253 characters: ${secretName.length}")
        assertTrue(
            Regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$").matches(secretName),
            "a Secret name is an RFC 1123 subdomain: $secretName",
        )
        // Positive control on the same predicate: a short domain's name still carries the domain, so the case
        // above is the digest branch and not every name being a digest.
        sink.apply(listOf("short.example.com"))
        assertTrue(certificateOf(ownedListenerSets().single()).contains("short-example-com"))
    }

    @Test
    fun theAnnotationMapIsStampedVerbatimOnTheListenerSetAndOnNoRoute() {
        sink.apply(listOf("a.example.com"))

        val annotations = ownedListenerSets().single().metadata.annotations
        assertEquals("veyq-acme", annotations["cert-manager.io/issuer"])
        assertEquals(
            "survives the round trip", annotations["example.com/whatever"],
            "a key the backend has no opinion about must arrive unchanged — the map is a passthrough, not a schema",
        )
        assertEquals(2, annotations.size, "…and nothing the operator did not configure is added: $annotations")
        val route = owned().single()
        assertTrue(route.metadata.annotations.isNullOrEmpty(), "an HTTPRoute carries no certificate and gets no annotations")
    }

    @Test
    fun aDomainThatStoppedRoutingLosesItsListenerSetToo() {
        // The deletion bite on the second kind. A sink that pruned only routes would pass KubernetesGatewaySinkTest
        // and leave every withdrawn tenant's HTTPS listener — and cert-manager's renewal of its certificate — open
        // on the Gateway forever.
        // Asserted on membership, not on the whole set: the mock's state is shared by every method here, so an
        // equality would go red at the control for whatever an earlier method left — and never reach its point.
        sink.apply(listOf("a.example.com"))
        assertTrue(listenerHosts().contains("a.example.com"), "control: it had a listener a moment ago")

        sink.apply(listOf("b.example.com"))

        assertFalse(listenerHosts().contains("a.example.com"), "a withdrawn domain's ListenerSet must be DELETED, not left behind")
        assertTrue(listenerHosts().contains("b.example.com"), "…and the new one is there")
        assertEquals(listOf("b.example.com"), owned().flatMap { it.spec.hostnames }, "…and the withdrawn route went with it")
    }

    @Test
    fun aListenerSetThisSinkDoesNotOwnSurvivesARegenerate() {
        // The ownership property on the second kind: listed by the label, never filtered after. An operator's own
        // ListenerSet in this namespace is admitted by the chart's Gateway exactly as the sink's are, so nothing
        // but the label tells the two apart.
        val stranger = ListenerSetBuilder()
            .withNewMetadata()
            .withName("operator-own-listener")
            .withNamespace(namespace)
            .withLabels<String, String>(mapOf("app.kubernetes.io/managed-by" to "Helm"))
            .endMetadata()
            .withNewSpec()
            .withNewParentRef().withName("veyq").endParentRef()
            .addNewListener().withName("https").withHostname("status.example.org").withPort(443).withProtocol("HTTPS").endListener()
            .endSpec()
            .build()
        client.resource(stranger).inNamespace(namespace).create()
        try {
            sink.apply(listOf("a.example.com"))

            assertNotNull(
                listenerSets().withName("operator-own-listener").get(),
                "a ListenerSet without veyq's ownership label is not this sink's to delete",
            )
            assertEquals(listOf("a.example.com"), listenerHosts(), "…and the projection is still correct beside it")
        } finally {
            // Restored in the same edit that creates it: the mock server's state is shared by every method here.
            listenerSets().withName("operator-own-listener").delete()
        }
    }

    private val namespace: String get() = client.namespace

    private fun listenerSets() = client.resources(ListenerSet::class.java, ListenerSetList::class.java).inNamespace(namespace)

    private fun ownedListenerSets() = listenerSets()
        .withLabel(KubernetesGatewaySink.OWNER_LABEL, KubernetesGatewaySink.OWNER_VALUE)
        .list().items

    private fun owned() = client.resources(HTTPRoute::class.java, HTTPRouteList::class.java).inNamespace(namespace)
        .withLabel(KubernetesGatewaySink.OWNER_LABEL, KubernetesGatewaySink.OWNER_VALUE)
        .list().items

    private fun listenerHosts(): List<String> = ownedListenerSets().flatMap { set -> set.spec.listeners.map { it.hostname } }.sorted()

    private fun certificateOf(set: ListenerSet): String = set.spec.listeners.single().tls.certificateRefs.single().name
}
