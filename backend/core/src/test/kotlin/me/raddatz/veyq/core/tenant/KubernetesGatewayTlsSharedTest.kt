package me.raddatz.veyq.core.tenant

import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `tls.mode=shared`: every projected `HTTPRoute` attaches to the `Gateway`'s one hostname-less HTTPS listener,
 * which the chart points at the ONE operator-supplied `Secret` — the wildcard certificate kept as an operator's
 * choice rather than as everyone's ceiling (ADR 0064). The sink writes no `ListenerSet` here: the certificate is
 * the `Gateway`'s, not a tenant's. This class is also the **positive control** for
 * [KubernetesGatewayTlsSharedWithoutSecretTest]: same mock server, same mode, and the only difference between an
 * enabled sink here and a disabled one there is `tls.secret-name`.
 */
@QuarkusTest
@TestProfile(KubernetesGatewayTlsSharedTest.SharedWithASecret::class)
@WithKubernetesTestServer
class KubernetesGatewayTlsSharedTest {

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
    fun everyProjectedRouteAttachesToTheGatewaysSharedHttpsListener() {
        assertTrue(sink.enabled, "control: with a secret-name the shared mode is a working sink")

        sink.apply(listOf("a.example.com", "b.example.com"))

        val owned = owned()
        assertEquals(2, owned.size)
        for (route in owned) {
            val parent = route.spec.parentRefs.single()
            assertEquals("Gateway", parent.kind, "the shared certificate is the Gateway's listener, not a ListenerSet")
            assertEquals("veyq", parent.name)
            assertEquals(KubernetesGatewaySink.SHARED_HTTPS_LISTENER, parent.sectionName)
        }
        assertEquals(
            emptyList<ListenerSet>(),
            client.resources(ListenerSet::class.java, ListenerSetList::class.java).inNamespace(client.namespace)
                .withLabel(KubernetesGatewaySink.OWNER_LABEL, KubernetesGatewaySink.OWNER_VALUE).list().items,
            "tls.mode=shared must write no ListenerSet",
        )
    }

    private fun owned() = client.resources(HTTPRoute::class.java, HTTPRouteList::class.java).inNamespace(client.namespace)
        .withLabel(KubernetesGatewaySink.OWNER_LABEL, KubernetesGatewaySink.OWNER_VALUE)
        .list().items
}
