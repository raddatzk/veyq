package me.raddatz.veyq.core.adminchannel

import io.vertx.core.http.HttpServerRequest
import io.vertx.core.net.SocketAddress
import me.raddatz.veyq.core.http.ClientAddress
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
        // The REAL SOCKET, never the request and never a statement about it. `request.remoteAddress()` is the
        // forwarded-parsed client address and `host()` the forwarded host, so anyone who writes
        // `Forwarded: for=10.0.0.1` or `X-Forwarded-Host` looks private through them (Quarkus'
        // ForwardedServerRequestWrapper rewrites exactly those). And under the PROXY protocol the connection's own
        // `localAddress()` / `remoteAddress()` are the PROXY header's destination and source (Vert.x's overrides,
        // ADR 0094 M10): a header naming the admin port and a private client would open the Server-Owner operations
        // on the public port. `(true)` asks for the channel's real addresses, which nothing a client sends can move.
        isAdminChannel(request.connection().localAddress(true), request.connection().remoteAddress(true), adminPort)

    fun isAdminChannel(local: SocketAddress?, peer: SocketAddress?, adminPort: Int): Boolean {
        if (local == null || !local.isInetSocket || local.port() != adminPort) return false
        val peerAddress = peer?.takeIf { it.isInetSocket }?.hostAddress()?.let(ClientAddress::literal) ?: return false
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
}
