package me.raddatz.veyq.core.tenant

import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteList
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerSet
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
 * ADR 0064's sink: the same projection ADR 0063 defines, written as Gateway API objects instead of as a Traefik
 * file. Its two hard properties are the two a file sink never had to have — **it must delete**, and **it must
 * not touch what it does not own** — and neither is decidable by reading the code, so both are decided here.
 * This class runs `tls.mode=none`, where only `HTTPRoute`s are written; the `ListenerSet` half of both
 * properties is [KubernetesGatewayTlsPerDomainTest]'s, the one mode that writes one.
 *
 * **Against a mock API server in this JVM (`@WithKubernetesTestServer`), and that is the whole reason the
 * dependency is `quarkus-kubernetes-client` rather than a bare fabric8 client.** This repository has no
 * cluster and no CI that could obtain one; a sink proven only against a hand-written fake of its own client
 * would be proven against the assumptions it was written with. The mock is a real fabric8 control plane in
 * CRUD mode: it stores what is created, serves label selectors, and 404s what was deleted. It does **not**
 * install the Gateway API CRDs or run a gateway implementation: whether a real `Gateway` accepts these routes is
 * not observable here (ADR 0064 states that; inbox #430's kind leg is where it becomes observable).
 *
 * **The sink under test is the one the producer selected from config**, not one constructed here — so
 * `veyq.tenant.ingress.sink=kubernetes` reaching [KubernetesGatewaySink], and the namespace being resolved
 * from the client rather than from a required config value, are proven by these tests working at all rather
 * than by a separate assertion about wiring.
 */
@QuarkusTest
@TestProfile(KubernetesGatewaySinkTest.ProjectingIntoKubernetes::class)
@WithKubernetesTestServer
class KubernetesGatewaySinkTest {

    /**
     * **No `veyq.tenant.ingress.kubernetes.namespace` here, deliberately.** The namespace must come from the
     * client — in a cluster that is the pod's mounted ServiceAccount token, here it is what the mock server
     * configures — because a required config value would mean one image per install (ADR 0064's chart depends
     * on the opposite). [namespace] below reads the same source the sink does, so a regression that ignored it
     * and hardcoded one would put the objects somewhere these assertions do not look.
     */
    class ProjectingIntoKubernetes : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.tenant.ingress.sink" to "kubernetes",
            // Not the default `veyq`, so a sink that ignored the setting and hardcoded the chart's name is red.
            "veyq.tenant.ingress.kubernetes.gateway-name" to "operator-renamed",
            // Explicit, although it is the default: [inModeNoneNoListenerSetAndNoAnnotationsAreWritten] is the case
            // that must go red BY NAME if the sink's ListenerSet branch ever becomes unconditional, and a profile
            // that merely inherits the default would leave that lever aimed at nothing.
            "veyq.tenant.ingress.kubernetes.tls.mode" to "none",
            // Configured, so the mode-none case below asserts that the map is NOT stamped where no ListenerSet is
            // — rather than passing because there was nothing to stamp.
            "veyq.tenant.ingress.kubernetes.annotations.\"cert-manager.io/issuer\"" to "veyq-acme",
        )
    }

    @Inject
    lateinit var sink: TenantIngressSink

    @Inject
    lateinit var client: KubernetesClient

    // --- what it creates ---

    @Test
    fun anHttpRouteIsCreatedPerRoutableDomain() {
        sink.apply(listOf("a.example.com", "b.example.com"))

        assertEquals(listOf("a.example.com", "b.example.com"), projectedHosts(), "one HTTPRoute per routable domain")
    }

    @Test
    fun theProjectedRouteCarriesTheRuleTheChartWillServe() {
        // Asserted on the rule, not merely on the object existing: a route with the right name and the wrong
        // backend — or the wrong parent — is the failure a "two objects exist" assertion cannot see. Where a route
        // attaches is the TLS mode's decision: under `none` the Gateway's plain-HTTP listener;
        // KubernetesGatewayTlsPerDomainTest and KubernetesGatewayTlsSharedTest hold the other two.
        sink.apply(listOf("a.example.com"))

        val route = owned().single()
        val parent = route.spec.parentRefs.single()
        assertEquals("Gateway", parent.kind)
        assertEquals("gateway.networking.k8s.io", parent.group)
        assertEquals("operator-renamed", parent.name, "the configured Gateway is the parent, never a hardcoded name")
        assertEquals(KubernetesGatewaySink.HTTP_LISTENER, parent.sectionName, "mode none attaches to the plain-HTTP listener")
        assertEquals(listOf("a.example.com"), route.spec.hostnames)
        val rule = route.spec.rules.single()
        val match = rule.matches.single().path
        assertEquals("PathPrefix", match.type)
        assertEquals("/", match.value)
        val backend = rule.backendRefs.single()
        assertEquals("veyq-app", backend.name, "the Service the chart deploys the app as")
        assertEquals(8080, backend.port)
    }

    @Test
    fun inModeNoneNoListenerSetAndNoAnnotationsAreWritten() {
        // `none` is the default and the mode for a deployment that terminates TLS upstream (a cloud load
        // balancer, a mesh) — a ListenerSet there would open an HTTPS listener whose certificate nothing
        // provisions. Absence, not emptiness: an operator reading the route back must see no annotations section,
        // although annotations ARE configured in this profile — an HTTPRoute carries no certificate (ADR 0064).
        sink.apply(listOf("a.example.com"))

        assertEquals(emptyList<ListenerSet>(), ownedListenerSets(), "tls.mode=none must write no ListenerSet")
        val route = owned().single()
        assertTrue(route.metadata.annotations.isNullOrEmpty(), "an HTTPRoute gets no annotations: ${route.metadata.annotations}")
    }

    @Test
    fun reprojectingTheSameSetUpdatesInPlaceRatherThanDuplicating() {
        // The object name is derived from the domain, so a regenerate has to address the object it already
        // wrote. A name derived from position — or a create that does not tolerate an existing object — shows
        // up here as either a duplicate or a thrown 409 that the catch would then silently swallow.
        sink.apply(listOf("a.example.com"))
        sink.apply(listOf("a.example.com"))

        assertEquals(1, owned().size, "the same domain addresses the same object on every regenerate")
    }

    // --- what it removes ---

    @Test
    fun aDomainThatStoppedRoutingLosesItsRoute() {
        // ADR 0063's full-regenerate argument, bitten on this sink. An apply-only implementation passes every
        // test above and fails this one — and in production would leak a route to a suspended tenant forever,
        // because the object it should remove is the one it never looks at.
        sink.apply(listOf("a.example.com"))
        assertTrue(projectedHosts().contains("a.example.com"), "control: it was routing a moment ago")

        sink.apply(listOf("b.example.com"))

        assertFalse(projectedHosts().contains("a.example.com"), "a withdrawn domain's HTTPRoute must be DELETED, not left behind")
        assertTrue(projectedHosts().contains("b.example.com"), "…and the new one is there")
    }

    @Test
    fun anEmptyProjectionRemovesEverythingThisSinkOwns() {
        // The Kubernetes counterpart of TenantIngressEmptyProjectionTest's subject. There is no file to leave a
        // header in, so "no routable tenants" is simply an empty owned set — and reaching it by deletion is the
        // same property as above, taken to its limit.
        sink.apply(listOf("a.example.com", "b.example.com"))

        sink.apply(emptyList())

        assertEquals(emptyList<String>(), projectedHosts())
    }

    // A malformed domain is NOT this class's subject: TenantIngressProjector filters the column's shape once,
    // for every sink, and TenantIngressHostnameTest + TenantIngressProjectorTest hold it there.

    // --- what it must never touch ---

    @Test
    fun anHttpRouteThisSinkDoesNotOwnSurvivesARegenerate() {
        // The property nothing in this tree had needed before ADR 0064: a file sink cannot delete a stranger's
        // file by being wrong about which lines are its content, but a reconcile-by-list sink can delete a
        // stranger's object by being wrong about which objects are its. This object is in the sink's own
        // namespace, is an HTTPRoute, and is not in the desired set — everything a careless reconcile needs to
        // remove it. The live example beside the chart's ACME opt-in: cert-manager's `cm-acme-http-solver-*`
        // HTTPRoute, created in this namespace mid-order and never labelled with veyq's value.
        val stranger = HTTPRouteBuilder()
            .withNewMetadata()
            .withName("operator-own-status-page")
            .withNamespace(namespace)
            .withLabels<String, String>(mapOf("app.kubernetes.io/managed-by" to "Helm"))
            .endMetadata()
            .withNewSpec().withHostnames("status.example.org").endSpec()
            .build()
        client.resource(stranger).inNamespace(namespace).create()
        try {
            sink.apply(listOf("a.example.com"))

            assertNotNull(
                routes().withName("operator-own-status-page").get(),
                "an HTTPRoute without veyq's ownership label is not this sink's to delete",
            )
            assertEquals(listOf("a.example.com"), projectedHosts(), "…and the projection is still correct beside it")
        } finally {
            // Restored in the same edit that creates it: the mock server's state is shared by every method in
            // this class, so a leftover object would make a later assertion about "everything in the namespace"
            // fail for a reason that has nothing to do with the code under test.
            routes().withName("operator-own-status-page").delete()
        }
    }

    // --- helpers ---

    /** The namespace the sink resolved from the client — read from the same source, never restated. */
    private val namespace: String get() = client.namespace

    private fun routes() = client.resources(HTTPRoute::class.java, HTTPRouteList::class.java).inNamespace(namespace)

    private fun owned() = routes()
        .withLabel(KubernetesGatewaySink.OWNER_LABEL, KubernetesGatewaySink.OWNER_VALUE)
        .list().items

    private fun ownedListenerSets() = client.resources(ListenerSet::class.java, ListenerSetList::class.java)
        .inNamespace(namespace)
        .withLabel(KubernetesGatewaySink.OWNER_LABEL, KubernetesGatewaySink.OWNER_VALUE)
        .list().items

    /** Every host this sink currently routes, read back from the API server. */
    private fun projectedHosts(): List<String> = owned().flatMap { it.spec.hostnames }.sorted()
}
