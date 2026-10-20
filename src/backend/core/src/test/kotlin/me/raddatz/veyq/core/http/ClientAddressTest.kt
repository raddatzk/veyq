package me.raddatz.veyq.core.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * B4, the **mutation-scoped** unit test of ADR 0094's rule (`backend/core/pitest.scope.json`): every row of the ADR's
 * topology table, with its "Arrives at the app" column as the input, and every boundary of the entry grammar, the
 * trust list and its CIDR match. The Quarkus-booted siblings ([me.raddatz.veyq.core.ratelimit.AuthRateLimitTest],
 * [me.raddatz.veyq.core.ratelimit.TrustedProxiesRateLimitTest]) prove the wiring; this proves the decision.
 */
class ClientAddressTest {

    /** The gateway as the socket shows it: an ephemeral pod address, with its own port. */
    private val gateway = ClientAddress.Resolved(InetAddress.getByName("10.42.0.7"), 40000)

    private fun trust(vararg ranges: String) = ranges.map { requireNotNull(ClientAddress.range(it)) { it } }

    private fun resolve(
        vararg lines: String,
        trusted: List<ClientAddress.Range> = emptyList(),
        source: ClientAddress.Resolved? = null,
    ) = ClientAddress.resolve(gateway, lines.toList(), trusted, source)

    private fun at(ip: String, port: Int? = null) = ClientAddress.Resolved(InetAddress.getByName(ip), port)

    // ---- ADR 0094's topology table, row by row ----

    @Test
    fun composeOrATraefikGatewayYieldsTheClient() =
        assertEquals(at("203.0.113.77"), resolve("203.0.113.77"))

    @Test
    fun anAppendingGatewayYieldsTheClientNotTheForgedLeftmost() =
        assertEquals(at("203.0.113.77"), resolve("198.51.100.66, 203.0.113.77"))

    @Test
    fun aLoadBalancerInFrontOfAReplacingGatewayYieldsTheLoadBalancer() =
        assertEquals(at("10.0.0.5"), resolve("10.0.0.5"))

    @Test
    fun aListedLoadBalancerInFrontOfAnAppendingChainIsSkipped() =
        assertEquals(at("203.0.113.77"), resolve("198.51.100.66, 203.0.113.77, 10.0.0.5", trusted = trust("10.0.0.5")))

    @Test
    fun theSameChainWithNoTrustListYieldsTheLoadBalancer() =
        assertEquals(at("10.0.0.5"), resolve("198.51.100.66, 203.0.113.77, 10.0.0.5"))

    @Test
    fun aLoadBalancerThatWritesThePortHandsItOver() = assertEquals(
        at("203.0.113.77", 51234),
        resolve("198.51.100.66, 203.0.113.77:51234, 10.0.0.5", trusted = trust("10.0.0.5")),
    )

    @Test
    fun aPodPastAnUnenforcedPolicyIsBelieved() =
        // Decision 6: nothing in the header tells this apart from the gateway, so the rule yields the forgery.
        assertEquals(at("198.51.100.66"), resolve("198.51.100.66"))

    // ---- the walk ----

    @Test
    fun withNoHeaderTheClientIsThePeerWithTheSocketsPort() {
        assertEquals(gateway, ClientAddress.resolve(gateway, emptyList(), emptyList(), null))
        assertNull(ClientAddress.resolve(null, emptyList(), emptyList(), null), "no inet peer and no header: nothing")
    }

    @Test
    fun twoHeaderLinesKeepTheirOrder() {
        assertEquals(at("203.0.113.77"), resolve("198.51.100.66", "203.0.113.77"))
        // The second line is the right one: listing it steps to the first.
        assertEquals(at("198.51.100.66"), resolve("198.51.100.66", "203.0.113.77", trusted = trust("203.0.113.77")))
    }

    @Test
    fun aNonIpEntryAtTheStopIsNoAddress() {
        listOf("unknown", "gateway.local", "", "_hidden", "1.2.3.4:0", "1.2.3.4:", "1.2.3.4:65536", "1.2.3.4:99999",
            "1.2.3.4:080x", "1.2.3", "256.1.1.1").forEach { stop ->
            assertNull(resolve("198.51.100.66, $stop"), "stop entry '$stop'")
        }
        // An empty entry between two, reached after a listed hop, and a trailing comma.
        assertNull(resolve("198.51.100.66,,10.0.0.5", trusted = trust("10.0.0.5")))
        assertNull(resolve("198.51.100.66,"))
        // A header line that is present but empty is an empty entry, not "no header".
        assertNull(resolve(""))
    }

    @Test
    fun aNonIpEntryLeftOfTheStopIsIgnored() =
        assertEquals(at("203.0.113.77"), resolve("unknown, gateway.local, 203.0.113.77"))

    @Test
    fun everyEntryListedYieldsTheLeftmostWithItsPort() {
        assertEquals(at("10.0.0.6"), resolve("10.0.0.6, 10.0.0.5", trusted = trust("10.0.0.0/8")))
        assertEquals(at("10.0.0.6", 1234), resolve("10.0.0.6:1234, 10.0.0.5", trusted = trust("10.0.0.0/8")))
        assertEquals(at("10.0.0.6"), resolve("10.0.0.6", trusted = trust("10.0.0.6")))
    }

    @Test
    fun aPortLeftOfTheStopIsNeverRead() =
        assertEquals(at("203.0.113.77"), resolve("198.51.100.66:4711, 203.0.113.77"))

    @Test
    fun aListedHopWithAPortIsSkippedByItsIpAlone() = assertEquals(
        at("203.0.113.77", 51234),
        resolve("203.0.113.77:51234, 10.0.0.5:8080", trusted = trust("10.0.0.5")),
    )

    @Test
    fun ipv4MappedAddressesCompareAsIpv4OnEverySide() {
        assertEquals(at("203.0.113.77"), resolve("203.0.113.77, ::ffff:10.0.0.5", trusted = trust("10.0.0.5")))
        assertEquals(at("203.0.113.77"), resolve("203.0.113.77, 10.0.0.5", trusted = trust("::ffff:10.0.0.5")))
        val mapped = ClientAddress.literal("::ffff:10.0.0.5")
        assertTrue(mapped is Inet4Address, "a mapped literal is the IPv4 address")
        assertEquals("10.0.0.5", mapped?.hostAddress)
    }

    // ---- the entry grammar, ports included ----

    @Test
    fun theEntryForms() {
        assertEquals(at("203.0.113.77", 51234), ClientAddress.entry("203.0.113.77:51234"))
        assertEquals(at("203.0.113.77", 1), ClientAddress.entry(" 203.0.113.77:1 "))
        assertEquals(at("203.0.113.77", 65535), ClientAddress.entry("203.0.113.77:65535"))
        assertEquals(at("2001:db8::1", 443), ClientAddress.entry("[2001:db8::1]:443"))
        assertEquals(at("2001:db8::1"), ClientAddress.entry("[2001:db8::1]"))
        assertEquals(at("2001:db8::1"), ClientAddress.entry("2001:db8::1"))
    }

    @Test
    fun aBareIpv6AddressIsNeverSplitAtAColon() {
        // Not 2001:db8::1 with port 4711: a bare IPv6 address has no port, so this is another address (R4).
        val entry = ClientAddress.entry("2001:db8::1:4711")
        assertEquals(at("2001:db8::1:4711"), entry)
        assertNull(entry?.port)
    }

    @Test
    fun malformedEntriesAreNoAddress() {
        listOf("[2001:db8::1", "[2001:db8::1]x", "[2001:db8::1]:", "[2001:db8::1]:0", "[1.2.3.4]", "[1.2.3.4]:80",
            "[gateway]:80", "gateway:80", "1:2", ":80", "1.2.3.4:5:6").forEach { raw ->
            assertNull(ClientAddress.entry(raw), "entry '$raw'")
        }
    }

    @Test
    fun onlyLiteralsAreParsedAndNothingIsResolved() {
        assertEquals("10.0.0.1", ClientAddress.literal("10.0.0.1")?.hostAddress)
        assertEquals("255.255.255.255", ClientAddress.literal("255.255.255.255")?.hostAddress)
        assertTrue(ClientAddress.literal("fd00::1") is Inet6Address)
        assertTrue(ClientAddress.literal("::1") is Inet6Address)
        // A name is never looked up: it is not a literal, so it is not recognised. `localhost` would resolve, and
        // `beef` and `cafe` are hex digits without a colon, which the JDK hands to the resolver.
        listOf("localhost", "admin", "beef", "cafe", "999.1.1.1", "256.0.0.1", "1.2.3", "1.2.3.4.5", ".:", "g::1",
            "fe80::1%eth0", "").forEach { text ->
            assertNull(ClientAddress.literal(text), "literal '$text'")
        }
    }

    // ---- the trust list ----

    @Test
    fun aSingleAddressCoversExactlyItself() {
        val range = trust("10.0.0.5").single()
        assertTrue(ClientAddress.covers(range, InetAddress.getByName("10.0.0.5")))
        assertFalse(ClientAddress.covers(range, InetAddress.getByName("10.0.0.4")))
        assertFalse(ClientAddress.covers(range, InetAddress.getByName("10.0.0.6")))
    }

    @Test
    fun aCidrRangeMatchesInsideAndMissesOutside() {
        val byte = trust("10.0.0.0/8").single()
        assertTrue(ClientAddress.covers(byte, InetAddress.getByName("10.200.0.1")))
        assertFalse(ClientAddress.covers(byte, InetAddress.getByName("11.0.0.1")))
        val sixteen = trust("10.1.0.0/16").single()
        assertTrue(ClientAddress.covers(sixteen, InetAddress.getByName("10.1.255.255")))
        assertFalse(ClientAddress.covers(sixteen, InetAddress.getByName("10.2.0.0")))
        // Not on a byte boundary: /12 keeps four bits of the second byte, /9 one.
        val twelve = trust("10.0.0.0/12").single()
        assertTrue(ClientAddress.covers(twelve, InetAddress.getByName("10.15.255.255")))
        assertFalse(ClientAddress.covers(twelve, InetAddress.getByName("10.16.0.0")))
        val nine = trust("10.0.0.0/9").single()
        assertTrue(ClientAddress.covers(nine, InetAddress.getByName("10.127.0.1")))
        assertFalse(ClientAddress.covers(nine, InetAddress.getByName("10.128.0.1")))
        val all = trust("0.0.0.0/0").single()
        assertTrue(ClientAddress.covers(all, InetAddress.getByName("203.0.113.77")))
        val v6 = trust("2001:db8::/32").single()
        assertTrue(ClientAddress.covers(v6, InetAddress.getByName("2001:db8:ffff::1")))
        assertFalse(ClientAddress.covers(v6, InetAddress.getByName("2001:db9::1")))
        // A range of one family never covers an address of the other.
        assertFalse(ClientAddress.covers(all, InetAddress.getByName("2001:db8::1")))
        assertFalse(ClientAddress.covers(trust("::/0").single(), InetAddress.getByName("10.0.0.1")))
    }

    @Test
    fun anIpv4MappedRangeCountsTheMappingsBits() {
        assertTrue(ClientAddress.covers(trust("::ffff:10.0.0.0/104").single(), InetAddress.getByName("10.1.2.3")))
        assertFalse(ClientAddress.covers(trust("::ffff:10.0.0.0/104").single(), InetAddress.getByName("11.1.2.3")))
        assertTrue(ClientAddress.covers(trust("::ffff:0.0.0.0/96").single(), InetAddress.getByName("8.8.8.8")))
        assertNull(ClientAddress.range("::ffff:10.0.0.0/95"))
    }

    @Test
    fun theWidestPrefixOfEachFamilyIsAcceptedAndOneMoreIsNot() {
        assertNotNull(ClientAddress.range("10.0.0.5/32"))
        assertNull(ClientAddress.range("10.0.0.5/33"))
        assertNotNull(ClientAddress.range("2001:db8::1/128"))
        assertNull(ClientAddress.range("2001:db8::1/129"))
        assertNotNull(ClientAddress.range("::ffff:10.0.0.5/128"))
        assertNull(ClientAddress.range("::ffff:10.0.0.5/129"))
    }

    @Test
    fun theConverterAcceptsAddressesAndRanges() {
        val list = TrustedProxiesConverter().convert("10.0.0.5, 10.1.0.0/16 ,2001:db8::/32")
        assertEquals(3, list.ranges.size)
        assertTrue(ClientAddress.covers(list.ranges[1], InetAddress.getByName("10.1.2.3")))
    }

    @Test
    fun theConverterRefusesWhatIsNoAddressOrRange() {
        listOf("gateway.local", "10.0.0.0/33", "10.0.0.5,,10.0.0.6", "10.0.0.5,", "", "10.0.0.0/", "10.0.0.0/abc",
            "10.0.0.0/-1", "10.0.0.5:80", "[2001:db8::1]", "10.0.0.5/8/8").forEach { value ->
            assertThrows(IllegalArgumentException::class.java, { TrustedProxiesConverter().convert(value) }, value)
        }
        // The refusal names the element's position, never its text.
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            TrustedProxiesConverter().convert("10.0.0.5, gateway.local")
        }
        assertTrue(refusal.message!!.contains("element 2"), refusal.message)
        assertFalse(refusal.message!!.contains("gateway.local"), refusal.message)
    }

    // ---- B1: the configured port source (ADR 0094 decision 7) ----

    @Test
    fun aSourceWithTheStopEntrysIpHandsItsPortOver() =
        assertEquals(at("203.0.113.77", 51234), resolve("203.0.113.77", source = at("203.0.113.77", 51234)))

    @Test
    fun aSourceWithAnotherIpLeavesThePortUnknown() =
        assertEquals(at("203.0.113.77"), resolve("203.0.113.77", source = at("198.51.100.66", 51234)))

    @Test
    fun theStopEntrysOwnPortWinsOverTheSource() =
        assertEquals(at("203.0.113.77", 443), resolve("203.0.113.77:443", source = at("203.0.113.77", 51234)))

    @Test
    fun aSourceWithoutAPortLeavesThePortUnknown() =
        assertEquals(at("203.0.113.77"), resolve("203.0.113.77", source = at("203.0.113.77")))

    @Test
    fun theSourceSpeaksForTheStopEntryOnlyAfterTheWalk() = assertEquals(
        at("203.0.113.77", 51234),
        resolve("198.51.100.66, 203.0.113.77, 10.0.0.5", trusted = trust("10.0.0.5"), source = at("203.0.113.77", 51234)),
    )

    @Test
    fun aSourceNamingTheClientDoesNotMoveTheStopEntry() =
        // The same chain with nothing listed stops at the load balancer; a source naming the client does not move it.
        assertEquals(at("10.0.0.5"), resolve("203.0.113.77, 10.0.0.5", source = at("203.0.113.77", 51234)))

    @Test
    fun withNoForwardedForAProxyPeerIsTheClientWithItsPort() {
        val proxied = at("203.0.113.77", 51234)
        assertEquals(proxied, ClientAddress.resolve(proxied, emptyList(), emptyList(), proxied))
    }

    @Test
    fun aRefusedStopEntryStaysRefusedWhateverTheSourceSays() =
        assertNull(resolve("198.51.100.66, gateway.local", source = at("203.0.113.77", 51234)))

    @Test
    fun theHeaderSourceIsOneLineWithOneEntryAndAPort() {
        assertEquals(at("203.0.113.77", 51234), ClientAddress.headerSource(listOf("203.0.113.77:51234")))
        assertEquals(at("2001:db8::1", 51234), ClientAddress.headerSource(listOf(" [2001:db8::1]:51234 ")))
    }

    @Test
    fun theHeaderSourceSaysNothingForAnythingElse() {
        listOf(
            emptyList(),
            listOf("203.0.113.77:51234", "203.0.113.77:51235"),
            listOf("203.0.113.77:51234, 203.0.113.77:51235"),
            listOf("203.0.113.77:51234,"),
            listOf("203.0.113.77"),
            listOf("2001:db8::1"),
            listOf("[2001:db8::1]"),
            listOf("gateway.local:51234"),
            listOf("203.0.113.77:0"),
            listOf(""),
        ).forEach { assertNull(ClientAddress.headerSource(it), it.toString()) }
    }

    @Test
    fun aBareIpv6HeaderValueIsNeverSplitIntoAPort() =
        // `2001:db8::1:4711` is one IPv6 address, not an address with port 4711.
        assertNull(ClientAddress.headerSource(listOf("2001:db8::1:4711")))
}
