package com.veyq.core.tenant

import io.fabric8.kubernetes.client.KubernetesClient
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR 0064's TLS passthrough in its per-tenant shape: `tls.mode=per-domain` emits one `spec.tls` entry per
 * projected domain whose `secretName` is the `Ingress` object's **own name**, and the operator's annotation map
 * rides on every object **verbatim**. Together those are what let cert-manager (or any controller that reads
 * `Ingress` annotations) obtain a certificate per tenant domain — including a custom domain that is nobody's
 * subdomain — with the backend holding no issuer, no CRD and no RBAC beyond the `Ingress` verbs it already has.
 *
 * Same mock control plane as [KubernetesIngressSinkTest] (`@WithKubernetesTestServer`), and the same wiring
 * proof: the sink under test is the one the producer built from *this profile's* config, so the hyphenated
 * `per-domain` binding onto the enum, and the quoted annotation key surviving SmallRye's property grammar, are
 * proven by these cases passing at all.
 *
 * **What the mock does NOT do, stated so the length case is read for what it asserts.** A real API server
 * rejects an object whose `secretName` is longer than 253 characters or not an RFC 1123 subdomain; the mock in
 * CRUD mode stores whatever it is handed. So [aDomainAtTheContractsLengthCapYieldsALegalSecretName] asserts the
 * bound directly — length and character class — rather than relying on a 422 that this server will never send.
 */
@QuarkusTest
@TestProfile(KubernetesIngressTlsPerDomainTest.PerDomainWithAnnotations::class)
@WithKubernetesTestServer
class KubernetesIngressTlsPerDomainTest {

    class PerDomainWithAnnotations : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.tenant.ingress.sink" to "kubernetes",
            "veyq.tenant.ingress.kubernetes.tls.mode" to "per-domain",
            // One key a real issuer reads, and one the backend knows nothing about: the second is the control
            // that proves the map is passed through rather than interpreted from a known set.
            "veyq.tenant.ingress.kubernetes.annotations.\"cert-manager.io/cluster-issuer\"" to "letsencrypt",
            "veyq.tenant.ingress.kubernetes.annotations.\"example.com/whatever\"" to "survives the round trip",
        )
    }

    @Inject
    lateinit var sink: TenantIngressSink

    @Inject
    lateinit var client: KubernetesClient

    @Test
    fun perDomainEmitsOneTlsEntryPerDomainNamedAfterTheObject() {
        sink.apply(listOf("a.example.com", "custom-domain.org"))

        val owned = owned()
        assertEquals(2, owned.size, "one Ingress per domain, and a custom domain is not excluded")
        for (ingress in owned) {
            val host = ingress.spec.rules.single().host
            val tls = ingress.spec.tls.single()
            assertEquals(listOf(host), tls.hosts, "the TLS entry covers exactly this object's own host")
            assertEquals(ingress.metadata.name, tls.secretName, "secretName is the object's own name, never a second derived one")
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

        val ingress = owned().single()
        val secretName = ingress.spec.tls.single().secretName
        assertEquals(ingress.metadata.name, secretName)
        assertTrue(secretName.length <= 253, "a Secret name is capped at 253 characters: ${secretName.length}")
        assertTrue(
            Regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$").matches(secretName),
            "a Secret name is an RFC 1123 subdomain: $secretName",
        )
        // Positive control on the same predicate: a short domain's name still carries the domain, so the case
        // above is the digest branch and not every name being a digest.
        sink.apply(listOf("short.example.com"))
        assertTrue(owned().single().spec.tls.single().secretName.contains("short-example-com"))
    }

    @Test
    fun theAnnotationMapIsStampedVerbatimAndUninterpreted() {
        sink.apply(listOf("a.example.com"))

        val annotations = owned().single().metadata.annotations
        assertEquals("letsencrypt", annotations["cert-manager.io/cluster-issuer"])
        assertEquals(
            "survives the round trip", annotations["example.com/whatever"],
            "a key the backend has no opinion about must arrive unchanged — the map is a passthrough, not a schema",
        )
        assertEquals(2, annotations.size, "…and nothing the operator did not configure is added: $annotations")
    }

    private val namespace: String get() = client.namespace

    private fun owned() = client.network().v1().ingresses().inNamespace(namespace)
        .withLabel(KubernetesIngressSink.OWNER_LABEL, KubernetesIngressSink.OWNER_VALUE)
        .list().items
}
