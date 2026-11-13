package me.raddatz.veyq.core.tenant

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The music host on Compose (ADR 0131 decision 2): with `veyq.music.domain` set, the routing file sends its host to the
 * app, which serves the music frame there and nothing else; without it, no router names it. The app decides what the
 * host answers, so the router is the plain one, with no blobs route and no client-address plugin.
 */
class TraefikFileMusicRouteTest {

    private fun render(musicDomain: String?, header: String? = null) = TraefikFileIngressSink(
        Path.of("build/test-tenant-ingress-render-only/tenants.yml"), "veyq.example.org", null,
        TraefikFileIngressSink.Upstreams(app = "http://app:8080", blobs = "http://garage:3900", admin = "http://admin:8080"),
        { "blobs" }, header, musicDomain,
    ).render(emptyList())

    @Test
    fun theMusicHostIsRoutedToTheAppOnlyWhenSet() {
        val rendered = render("music.example.org")
        val router = "    veyq-music:\n      rule: \"Host(`music.example.org`)\"\n"
        assertTrue(rendered.contains(router), rendered)
        val block = rendered.substringAfter(router).substringBefore("\n    veyq")
        assertTrue("service: veyq-app" in block, block)
        assertFalse("PathPrefix" in block || "middlewares" in block, block)
        assertFalse("music.example.org" in render(null), "control: unset, no router names it")
    }

    @Test
    fun theMusicRouterCarriesNoClientAddressPlugin() {
        val rendered = render("music.example.org", "X-Veyq-Client-Port")
        val block = rendered.substringAfter("    veyq-music:\n").substringBefore("\n    veyq")
        assertFalse("veyq-client-addr" in block, block)
        assertTrue("veyq-client-addr" in rendered.substringAfter("    veyq-app:\n").substringBefore("\n    veyq"), "control: the app router has it")
    }
}
