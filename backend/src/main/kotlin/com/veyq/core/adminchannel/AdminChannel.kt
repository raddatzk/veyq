package com.veyq.core.adminchannel

import io.vertx.core.http.HttpServerRequest
import io.vertx.core.net.SocketAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * **Which door a request came through** (ADR 0065, uc.admin.admin-channel step 3): it is on the admin channel iff
 * it arrived on the admin listener's port **and** its socket peer is not globally routable. No header takes part.
 *
 * The peer rule is a belt, not the guarantee: the shipped targets route nothing but the admin container to the
 * listener. It covers the topologies they do not use — host networking, a published port — and a NAT that rewrites
 * a public peer to a private one defeats it by design (ADR 0065 residual (iii)).
 */
object AdminChannel {

    fun isAdminChannel(request: HttpServerRequest, adminPort: Int): Boolean =
        // The SOCKET, never the request: `request.remoteAddress()` is the forwarded-parsed client address and
        // `host()` the forwarded host, so anyone who writes `Forwarded: for=10.0.0.1` or `X-Forwarded-Host` looks
        // private through them. Quarkus' ForwardedServerRequestWrapper rewrites exactly those and delegates
        // `localAddress()` and `connection()` to the socket (measured at refine: a forged header moved `host()`
        // and `remoteAddress()` and left `localAddress()` untouched).
        isAdminChannel(request.localAddress(), request.connection().remoteAddress(), adminPort)

    fun isAdminChannel(local: SocketAddress?, peer: SocketAddress?, adminPort: Int): Boolean {
        if (local == null || !local.isInetSocket || local.port() != adminPort) return false
        val peerAddress = peer?.takeIf { it.isInetSocket }?.hostAddress()?.let(::literal) ?: return false
        return !isGloballyRoutable(peerAddress)
    }

    /**
     * Non-global means loopback, RFC 1918, shared address space (100.64/10), link-local and unique-local
     * (`fc00::/7`). The last is spelled out because Java's `isSiteLocalAddress` knows only the deprecated
     * `fec0::/10` for IPv6 — which is not in the set.
     */
    fun isGloballyRoutable(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return false
        return when (address) {
            is Inet4Address -> !(address.isSiteLocalAddress || isSharedAddressSpace(address.address))
            is Inet6Address -> !isUniqueLocal(address.address)
            else -> true
        }
    }

    private fun isSharedAddressSpace(b: ByteArray) = b[0].toInt() and 0xFF == 100 && b[1].toInt() and 0xC0 == 64

    private fun isUniqueLocal(b: ByteArray) = b[0].toInt() and 0xFE == 0xFC

    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /**
     * An IP literal, parsed without a DNS lookup — or null. `InetAddress.getByName` resolves a name, so it is only
     * handed a dotted quad or a string containing `:`, both of which it parses as literals. A socket peer is always
     * a literal; anything else is not recognised, and an unrecognised peer is the public channel.
     */
    fun literal(text: String): InetAddress? =
        if (IPV4_LITERAL.matches(text) || ':' in text) runCatching { InetAddress.getByName(text) }.getOrNull() else null
}
