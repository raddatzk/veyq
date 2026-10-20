package me.raddatz.veyq.core.tenant

import me.raddatz.veyq.core.http.ClientAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * B7 (ADR 0094 decision 7, D6 of its CR): the routing file attaches the client-address plugin to every app router
 * exactly when the app reads its port from a header — and never to a blobs router or the admin router. The app and
 * its routing file follow the one setting of one process, so the app never reads a header Traefik does not overwrite.
 */
class TraefikFileClientAddrTest {

    private fun render(header: String?) = TraefikFileIngressSink(
        Path.of("build/test-tenant-ingress-render-only/tenants.yml"), "veyq.example.org", "admin.example.org",
        TraefikFileIngressSink.Upstreams(app = "http://app:8080", blobs = "http://garage:3900", admin = "http://admin:8080"),
        { "blobs" }, header,
    ).render(listOf("tenant.example.com"))

    /** Each router's name and its `middlewares:` line (empty when it has none), read off the rendered YAML's keys. */
    private fun routers(rendered: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var inRouters = false
        var current: String? = null
        for (line in rendered.lines()) {
            when {
                line == "  routers:" -> inRouters = true
                line.startsWith("  ") && !line.startsWith("   ") -> inRouters = false
                inRouters && line.startsWith("    ") && !line.startsWith("     ") -> {
                    current = line.trim().removeSuffix(":"); out[current] = ""
                }
                inRouters && current != null && line.trim().startsWith("middlewares:") -> out[current] = line.trim()
            }
        }
        return out
    }

    @Test
    fun underHeaderEveryAppRouterCarriesThePluginAndNoOtherDoes() {
        val rendered = render(ClientAddress.PORT_HEADER)
        val routers = routers(rendered)
        val apps = routers.filterKeys { it.endsWith("-app") }
        assertEquals(setOf("veyq-app", "veyq-tenant-tenant-example-com-app"), apps.keys, rendered)
        apps.forEach { (name, middlewares) -> assertEquals("middlewares: [veyq-client-addr]", middlewares, name) }
        routers.filterKeys { !it.endsWith("-app") }.forEach { (name, middlewares) ->
            assertFalse("veyq-client-addr" in middlewares, "$name must not carry the plugin:\n$rendered")
        }
        assertTrue(routers.containsKey("veyq-admin"), "control: the admin router is rendered, so its check above ran:\n$rendered")
        val definition = "    veyq-client-addr:\n      plugin:\n        clientaddr:\n          header: \"${ClientAddress.PORT_HEADER}\"\n"
        assertTrue(definition in rendered, "the middleware hands the plugin the header the app reads:\n$rendered")
    }

    @Test
    fun theHeaderTheAppReadsIsTheOneThePluginOverwrites() {
        val rendered = render("X-Edge-Client")
        assertTrue("          header: \"X-Edge-Client\"\n" in rendered, rendered)
    }

    @Test
    fun underAnyOtherSourceNoRouterCarriesItAndNothingDefinesIt() {
        // `none` and `proxy-protocol` both reach the sink as no header (TenantIngressSinkProducer).
        val rendered = render(null)
        assertFalse("veyq-client-addr" in rendered, rendered)
        assertFalse("clientaddr" in rendered, rendered)
        assertTrue(routers(rendered).getValue("veyq-blobs").contains("veyq-blobs-inert"), "control: middlewares still render:\n$rendered")
    }
}
