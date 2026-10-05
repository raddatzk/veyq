package me.raddatz.veyq.core.adminchannel

import io.vertx.core.net.SocketAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The admin-channel predicate, per address range (uc.admin.admin-channel `a-globally-routable-peer-is-the-public-
 * channel`). Each non-global range is asked on the admin port — where it must be accepted — and the global
 * neighbours just outside each range are asked the same way, so a range drawn one bit too wide reddens too.
 */
class AdminChannelTest {

    private val adminPort = 8090
    // Built from a resolved InetSocketAddress, the way Vert.x reports a real connection's addresses.
    private fun socket(host: String, port: Int) =
        SocketAddress.inetSocketAddress(InetSocketAddress(InetAddress.getByName(host), port))
    private fun on(port: Int) = socket("10.0.0.2", port)
    private fun peer(host: String) = socket(host, 51234)

    @Test
    fun aNonGlobalPeerOnTheAdminPortIsTheAdminChannel() = assertAll(
        listOf(
            "127.0.0.1", "127.255.0.9", "::1", // loopback
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.255.255", // RFC 1918
            "100.64.0.1", "100.127.255.255", // shared address space
            "169.254.0.1", "fe80::1", // link-local
            "fc00::1", "fd12:3456::1", // unique-local
            "::ffff:10.0.0.1", // an IPv4-mapped private peer, as a dual-stack socket reports it
        ).map { host -> Executable { assertTrue(AdminChannel.isAdminChannel(on(adminPort), peer(host), adminPort), host) } },
    )

    @Test
    fun aGloballyRoutablePeerIsThePublicChannelEvenOnTheAdminPort() = assertAll(
        listOf(
            "8.8.8.8", "2001:4860:4860::8888",
            "11.0.0.1", "172.15.255.255", "172.32.0.1", "192.167.255.255", "192.169.0.1", // outside RFC 1918
            "100.63.255.255", "100.128.0.1", // outside 100.64/10
            "169.253.255.255", "fec0::1", // the deprecated IPv6 site-local is not in the set
            "fe00::1", "fb00::1", // outside fc00::/7
        ).map { host -> Executable { assertFalse(AdminChannel.isAdminChannel(on(adminPort), peer(host), adminPort), host) } },
    )

    @Test
    fun aPrivatePeerOnAnyOtherPortIsThePublicChannel() {
        assertFalse(AdminChannel.isAdminChannel(on(8080), peer("127.0.0.1"), adminPort))
    }

    @Test
    fun aDomainSocketIsThePublicChannel() {
        assertFalse(AdminChannel.isAdminChannel(SocketAddress.domainSocketAddress("/tmp/veyq.sock"), peer("127.0.0.1"), adminPort))
        assertFalse(AdminChannel.isAdminChannel(on(adminPort), SocketAddress.domainSocketAddress("/tmp/veyq.sock"), adminPort))
    }

    @Test
    fun aMissingAddressIsThePublicChannel() {
        assertFalse(AdminChannel.isAdminChannel(null, peer("127.0.0.1"), adminPort))
        assertFalse(AdminChannel.isAdminChannel(on(adminPort), null, adminPort))
    }

    @Test
    fun onlyLiteralsAreParsedAndNothingIsResolved() {
        assertEquals("10.0.0.1", AdminChannel.literal("10.0.0.1")?.hostAddress)
        assertTrue(AdminChannel.literal("fd00::1") != null)
        // A name is never looked up: it is not a literal, so it is not recognised, so it is the public channel.
        assertNull(AdminChannel.literal("localhost"))
        assertNull(AdminChannel.literal("admin"))
        assertNull(AdminChannel.literal("999.1.1.1"))
    }
}
