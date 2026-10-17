package me.raddatz.veyq.core.http

import io.vertx.core.http.HttpServerRequest
import me.raddatz.veyq.core.config.VeyqConfig.ClientPortSource
import me.raddatz.veyq.core.config.VeyqConfig.Http.ClientPort
import org.eclipse.microprofile.config.spi.Converter
import java.net.Inet4Address
import java.net.InetAddress

/**
 * **The one place veyq learns a client's address** (ADR 0094):
 * the rightmost `X-Forwarded-For` entry that `veyq.http.trusted-proxies` does not list, with the port that entry
 * carries — or, where it carries none, the port of the one source `veyq.http.client-port.source` names (decision 7).
 * The socket peer is vouched for by the network (ADR 0071's reachability), never by a header, and the entry the walk
 * stops at was written by a proxy the request really passed. Its consumers are the per-IP rate limiter and, later,
 * the guest-write record of ADR 0093 § 10; neither reads an address anywhere else
 * (`scripts/validate_lookup_single_home.py` rule D holds both headers' names to this file).
 *
 * This object is the pure decision, and the backend mutation gate's scope (`backend/core/pitest.scope.json`): no
 * request, no clock, no config. The request accessor is [clientAddress], below it, and holds no decision.
 *
 * Nothing here logs, and nothing here resolves a name: an entry is a literal or it is nothing.
 */
object ClientAddress {

    /**
     * The default of `veyq.http.client-port.header`: the header an edge that overwrites it on every request writes
     * the client's `address:port` into (ADR 0094 decision 7) — on Compose, the recipe's local Traefik plugin.
     */
    const val PORT_HEADER = "X-Veyq-Client-Addr"

    /**
     * A client's address and its source port. [port] is null when no hop wrote one and no configured source spoke —
     * "unknown" (ADR 0094 decisions 3 and 7). The rate limiter keys on [ip] alone: a port changes with every
     * connection.
     */
    data class Resolved(val ip: InetAddress, val port: Int?)

    /** One `veyq.http.trusted-proxies` element: an address, or a CIDR range, as its bytes and a prefix length. */
    class Range(address: InetAddress, internal val prefix: Int) {
        internal val network: ByteArray = address.address
    }

    /** `veyq.http.trusted-proxies`, parsed. Empty means the gateway is the only hop (ADR 0094 decision 3). */
    class TrustList(val ranges: List<Range>)

    /**
     * ADR 0094 decisions 3 and 7. [forwardedFor] is every `X-Forwarded-For` line in arrival order; with none the
     * client is the [peer]. Otherwise the entries are walked from the right, a listed one skipped by its IP alone; the
     * first one not listed is the client, with the port it carries — and if it is not an IP address there is no
     * client (null), never a guess. If every entry is listed, the leftmost is the client.
     *
     * [portSource] is the configured source's statement of the client (decision 7), already chosen by the caller:
     * null when the source is `none` or said nothing usable. It speaks only where the stop entry carries no port of
     * its own, and only if its IP equals that entry's; otherwise the port stays unknown. It never changes the IP.
     */
    fun resolve(peer: Resolved?, forwardedFor: List<String>, trusted: List<Range>, portSource: Resolved?): Resolved? {
        val entries = forwardedFor.flatMap { it.split(',') }
        if (entries.isEmpty()) return peer
        for (i in entries.indices.reversed()) {
            val entry = entry(entries[i]) ?: return null
            if (i == 0 || !listed(trusted, entry.ip)) return withPort(entry, portSource)
        }
        return null // unreachable: the loop returns at i == 0
    }

    private fun withPort(stop: Resolved, source: Resolved?): Resolved {
        if (stop.port != null || source == null || source.port == null) return stop
        return if (source.ip == stop.ip) Resolved(stop.ip, source.port) else stop
    }

    /**
     * The `header` source's statement (decision 7): exactly one line holding exactly one entry, which carries a port
     * — or null. Two lines, a comma, an entry without a port or a value that is no address all say nothing, because
     * an edge that overwrites the header writes one `address:port` and nothing else.
     */
    fun headerSource(lines: List<String>): Resolved? {
        if (lines.size != 1 || ',' in lines[0]) return null
        val stated = entry(lines[0]) ?: return null
        return if (stated.port != null) stated else null
    }

    private fun listed(trusted: List<Range>, ip: InetAddress): Boolean {
        for (range in trusted) if (covers(range, ip)) return true
        return false
    }

    /**
     * One `X-Forwarded-For` entry, trimmed: `203.0.113.77`, `203.0.113.77:51234`, `[2001:db8::1]`,
     * `[2001:db8::1]:443`, or a bare `2001:db8::1` — or null. A bare IPv6 address never carries a port: its colons
     * are never split. A bracket holds an IPv6 address only.
     */
    fun entry(raw: String): Resolved? {
        val text = raw.trim()
        if (text.startsWith('[')) {
            // No closing bracket leaves the inner text empty, which is no address.
            val inner = text.drop(1).substringBefore(']', missingDelimiterValue = "")
            if (':' !in inner) return null
            val ip = literal(inner) ?: return null
            val rest = text.substringAfter(']')
            if (rest.isEmpty()) return Resolved(ip, null)
            if (!rest.startsWith(':')) return null
            return port(rest.drop(1))?.let { Resolved(ip, it) }
        }
        if (text.count { it == ':' } == 1) {
            // Exactly one colon: an IPv4 address with its port. An IPv6 address has at least two.
            val ip = literal(text.substringBefore(':')) ?: return null
            return port(text.substringAfter(':'))?.let { Resolved(ip, it) }
        }
        return literal(text)?.let { Resolved(it, null) }
    }

    private val PORT = Regex("""\d{1,5}""")

    private fun port(text: String): Int? = if (PORT.matches(text)) text.toInt().takeIf { it in 1..65535 } else null

    private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

    // Hex digits, dots and at least one colon, starting with a hex digit or a colon: that is the JDK's numeric
    // branch, which parses an IPv6 literal or throws, and never looks the text up as a name.
    private val IPV6_TEXT = Regex("""(?=[0-9A-Fa-f:])[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*""")

    /**
     * An IP literal, parsed without a DNS lookup — or null. A dotted quad is built from its bytes; any other text
     * must look like an IPv6 literal (above) before `InetAddress.getByName` sees it, because that resolves a name.
     * An IPv4-mapped IPv6 literal (`::ffff:10.0.0.5`) comes back as the IPv4 address, so the entries, the trust list
     * and the peer compare alike.
     */
    fun literal(text: String): InetAddress? {
        IPV4.matchEntire(text)?.let { m ->
            val octets = m.groupValues.drop(1).map { it.toInt() }
            if (octets.max() > 255) return null
            return InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() })
        }
        if (!IPV6_TEXT.matches(text)) return null
        return runCatching { InetAddress.getByName(text) }.getOrNull()
    }

    private val PREFIX = Regex("""\d{1,3}""")

    /** One trust-list element, `10.0.0.5` or `10.0.0.0/8` (IPv6 alike) — or null for anything else. */
    fun range(text: String): Range? {
        val address = text.substringBefore('/')
        val ip = literal(address) ?: return null
        val bits = ip.address.size * 8
        if ('/' !in text) return Range(ip, bits)
        val prefixText = text.substringAfter('/')
        if (!PREFIX.matches(prefixText)) return null
        var prefix = prefixText.toInt()
        if (':' in address && ip is Inet4Address) {
            // An IPv4-mapped range parsed to IPv4: its prefix still counts the 96 bits of the mapping.
            if (prefix < 96) return null
            prefix -= 96
        }
        return if (prefix <= bits) Range(ip, prefix) else null
    }

    /** Whether [range] holds [ip]: the same family, and the first `prefix` bits equal. */
    fun covers(range: Range, ip: InetAddress): Boolean {
        val bytes = ip.address
        if (bytes.size != range.network.size) return false
        val whole = range.prefix / 8
        for (i in 0 until whole) if (bytes[i] != range.network[i]) return false
        val rest = range.prefix % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (bytes[whole].toInt() and mask) == (range.network[whole].toInt() and mask)
    }

    /**
     * The whole `veyq.http.trusted-proxies` value: comma-separated, each element trimmed and an IP address or a CIDR
     * range. A hostname, a bad prefix or an empty element refuses the value — the mapping fails, so the app does not
     * start (ADR 0094 decision 5). The message names the element's position, not its text.
     */
    fun trustList(value: String): TrustList {
        val elements = value.split(',')
        val ranges = ArrayList<Range>(elements.size)
        for (i in elements.indices) {
            ranges += range(elements[i].trim()) ?: throw IllegalArgumentException(
                "veyq.http.trusted-proxies: element ${i + 1} is not an IP address or a CIDR range " +
                    "(a hostname is never resolved, and an empty element is refused)",
            )
        }
        return TrustList(ranges)
    }
}

/** SmallRye's converter for `veyq.http.trusted-proxies` ([ClientAddress.trustList]); it is the one parser of it. */
class TrustedProxiesConverter : Converter<ClientAddress.TrustList> {
    override fun convert(value: String): ClientAddress.TrustList = ClientAddress.trustList(value)
}

/**
 * The client of this request by [ClientAddress.resolve], its port source picked by `veyq.http.client-port.source`.
 *
 * The peer is the connection's `remoteAddress()`, never the request's: the request's is Quarkus's forwarding-parsed
 * client, which a forwarding setting turned back on would hand a forged header (the measurement in `AdminChannel`).
 * Under `proxy-protocol` the connection's address is the PROXY header's source — the gateway's own statement of its
 * peer, the only connection the network lets reach this port (ADR 0071) and the only kind Vert.x accepts once the
 * protocol is on — so it is both the peer and the port source. Under `header` and `none` there is no PROXY header
 * (startup refuses the mismatch, `ClientPortSourceCheck`), so it is the socket itself. The configured header is read
 * under `header` alone: under `none` a client-sent one is never looked at, whatever it says.
 */
fun HttpServerRequest.clientAddress(trusted: ClientAddress.TrustList?, port: ClientPort): ClientAddress.Resolved? {
    val peer = socketPeer()
    val portSource = when (port.source()) {
        ClientPortSource.NONE -> null
        ClientPortSource.PROXY_PROTOCOL -> peer
        ClientPortSource.HEADER -> ClientAddress.headerSource(headers().getAll(port.header()))
    }
    return ClientAddress.resolve(peer, headers().getAll("X-Forwarded-For"), trusted?.ranges.orEmpty(), portSource)
}

/**
 * The connection's peer, with its port, or null when the connection is not an inet socket. Never a header — but
 * under the PROXY protocol the PROXY header's source, which is what the gateway accepted (see [clientAddress]).
 */
fun HttpServerRequest.socketPeer(): ClientAddress.Resolved? {
    val socket = connection().remoteAddress()?.takeIf { it.isInetSocket } ?: return null
    return socket.hostAddress()?.let(ClientAddress::literal)?.let { ClientAddress.Resolved(it, socket.port()) }
}
