package com.veyq.app.shared.tenant

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
 * `tls.mode=shared`: every projected `Ingress` references the ONE operator-supplied `Secret` — the wildcard
 * certificate kept as an operator's choice rather than as everyone's ceiling (ADR 0064). This class is also the
 * **positive control** for [KubernetesIngressTlsSharedWithoutSecretTest]: same mock server, same mode, and the
 * only difference between an enabled sink here and a disabled one there is `tls.secret-name`.
 */
@QuarkusTest
@TestProfile(KubernetesIngressTlsSharedTest.SharedWithASecret::class)
@WithKubernetesTestServer
class KubernetesIngressTlsSharedTest {

    class SharedWithASecret : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.tenant.ingress.sink" to "kubernetes",
            "veyq.tenant.ingress.kubernetes.tls.mode" to "shared",
            "veyq.tenant.ingress.kubernetes.tls.secret-name" to "wildcard-example-com",
        )
    }

    @Inject
    lateinit var sink: TenantIngressSink

    @Inject
    lateinit var client: KubernetesClient

    @Test
    fun everyProjectedIngressReferencesTheOneSharedSecret() {
        assertTrue(sink.enabled, "control: with a secret-name the shared mode is a working sink")

        sink.apply(listOf("a.example.com", "b.example.com"))

        val owned = owned()
        assertEquals(2, owned.size)
        for (ingress in owned) {
            val tls = ingress.spec.tls.single()
            assertEquals("wildcard-example-com", tls.secretName, "the shared Secret, not the object's own name")
            assertEquals(listOf(ingress.spec.rules.single().host), tls.hosts)
        }
    }

    private fun owned() = client.network().v1().ingresses().inNamespace(client.namespace)
        .withLabel(KubernetesIngressSink.OWNER_LABEL, KubernetesIngressSink.OWNER_VALUE)
        .list().items
}
