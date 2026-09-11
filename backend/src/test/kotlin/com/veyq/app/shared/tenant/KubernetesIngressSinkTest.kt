package com.veyq.app.shared.tenant

import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
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
 * ADR 0064's sink: the same projection ADR 0063 defines, written as `Ingress` objects instead of as a Traefik
 * file. Its two hard properties are the two a file sink never had to have — **it must delete**, and **it must
 * not touch what it does not own** — and neither is decidable by reading the code, so both are decided here.
 *
 * **Against a mock API server in this JVM (`@WithKubernetesTestServer`), and that is the whole reason the
 * dependency is `quarkus-kubernetes-client` rather than a bare fabric8 client.** This repository has no
 * cluster and no CI that could obtain one; a sink proven only against a hand-written fake of its own client
 * would be proven against the assumptions it was written with. The mock is a real fabric8 control plane in
 * CRUD mode: it stores what is created, serves label selectors, and 404s what was deleted.
 *
 * **The sink under test is the one the producer selected from config**, not one constructed here — so
 * `veyq.tenant.ingress.sink=kubernetes` reaching [KubernetesIngressSink], and the namespace being resolved
 * from the client rather than from a required config value, are proven by these tests working at all rather
 * than by a separate assertion about wiring.
 */
@QuarkusTest
@TestProfile(KubernetesIngressSinkTest.ProjectingIntoKubernetes::class)
@WithKubernetesTestServer
class KubernetesIngressSinkTest {

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
            "veyq.tenant.ingress.kubernetes.ingress-class-name" to "nginx",
        )
    }

    @Inject
    lateinit var sink: TenantIngressSink

    @Inject
    lateinit var client: KubernetesClient

    // --- what it creates ---

    @Test
    fun anIngressIsCreatedPerRoutableDomain() {
        sink.apply(listOf("a.example.com", "b.example.com"))

        assertEquals(listOf("a.example.com", "b.example.com"), projectedHosts(), "one Ingress per routable domain")
    }

    @Test
    fun theProjectedIngressCarriesTheRuleTheChartWillServe() {
        // Asserted on the rule, not merely on the object existing: an Ingress with the right name and the wrong
        // backend is the failure a "two objects exist" assertion cannot see. The class name is passed through
        // untouched (ADR 0064's controller is operator-chosen), and there is no TLS block at all — certificates
        // are the chart's concern, this sink writes routing.
        sink.apply(listOf("a.example.com"))

        val ingress = owned().single()
        assertEquals("nginx", ingress.spec.ingressClassName, "the configured class is passed through")
        val rule = ingress.spec.rules.single()
        assertEquals("a.example.com", rule.host)
        val path = rule.http.paths.single()
        assertEquals("/", path.path)
        assertEquals("Prefix", path.pathType)
        assertEquals("veyq-app", path.backend.service.name, "the Service the chart deploys the app as")
        assertEquals(8080, path.backend.service.port.number)
        assertTrue(ingress.spec.tls.isNullOrEmpty(), "no TLS block: cert-manager wiring is the chart's, not this sink's")
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
    fun aDomainThatStoppedRoutingLosesItsIngress() {
        // ADR 0063's full-regenerate argument, bitten on this sink for the first time. An apply-only
        // implementation passes every test above and fails this one — and in production would leak a route to a
        // suspended tenant forever, because the object it should remove is the one it never looks at.
        sink.apply(listOf("a.example.com"))
        assertTrue(projectedHosts().contains("a.example.com"), "control: it was routing a moment ago")

        sink.apply(listOf("b.example.com"))

        assertFalse(projectedHosts().contains("a.example.com"), "a withdrawn domain's Ingress must be DELETED, not left behind")
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

    // --- what it must never touch ---

    @Test
    fun anIngressThisSinkDoesNotOwnSurvivesARegenerate() {
        // The property nothing in this tree has ever needed: a file sink cannot delete a stranger's file by
        // being wrong about which lines are its content, but a reconcile-by-list sink can delete a stranger's
        // object by being wrong about which objects are its. This object is in the sink's own namespace, is an
        // Ingress, and is not in the desired set — everything a careless reconcile needs to remove it.
        val stranger = IngressBuilder()
            .withNewMetadata()
            .withName("operator-own-status-page")
            .withNamespace(namespace)
            .withLabels<String, String>(mapOf("app.kubernetes.io/managed-by" to "Helm"))
            .endMetadata()
            .withNewSpec()
            .addNewRule().withHost("status.example.org").endRule()
            .endSpec()
            .build()
        client.resource(stranger).inNamespace(namespace).create()
        try {
            sink.apply(listOf("a.example.com"))

            assertNotNull(
                client.network().v1().ingresses().inNamespace(namespace).withName("operator-own-status-page").get(),
                "an Ingress without veyq's ownership label is not this sink's to delete",
            )
            assertEquals(listOf("a.example.com"), projectedHosts(), "…and the projection is still correct beside it")
        } finally {
            // Restored in the same edit that creates it: the mock server's state is shared by every method in
            // this class, so a leftover object would make a later assertion about "everything in the namespace"
            // fail for a reason that has nothing to do with the code under test.
            client.network().v1().ingresses().inNamespace(namespace).withName("operator-own-status-page").delete()
        }
    }

    @Test
    fun aDomainThatIsNotARoutableHostnameIsDropped() {
        // The boundary already rejects these, so this is about the row that predates that validation or arrives
        // by a path that skips it. Here the string would become both a rule host and an object name, and an
        // illegal one makes the API server reject the object — which on this sink's failure boundary would
        // strand every OTHER tenant's routing too. The good domain beside it is what shows that happening.
        sink.apply(listOf("not_a_hostname.example.com", "ok.example.com"))

        assertEquals(listOf("ok.example.com"), projectedHosts(), "the malformed domain is dropped, the sound one still routes")
    }

    // --- helpers ---

    /** The namespace the sink resolved from the client — read from the same source, never restated. */
    private val namespace: String get() = client.namespace

    private fun owned() = client.network().v1().ingresses().inNamespace(namespace)
        .withLabel(KubernetesIngressSink.OWNER_LABEL, KubernetesIngressSink.OWNER_VALUE)
        .list().items

    /** Every host this sink currently routes, read back from the API server. */
    private fun projectedHosts(): List<String> =
        owned().flatMap { it.spec.rules.map { rule -> rule.host } }.sorted()
}
