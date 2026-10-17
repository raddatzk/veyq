package me.raddatz.veyq.core.http

import io.vertx.core.http.HttpServerRequest
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import me.raddatz.veyq.core.config.VeyqConfig

/**
 * The address [clientAddress] resolves for this request, as `<ip> <port>` (`-` for an unknown port) or `none` — the
 * observable of the port-source slices (ADR 0094 decision 7). Test scope only: it never ships, and the rate-limit
 * posture sweep skips test-scoped resources.
 *
 * `?source=header` hands the accessor the `header` source with the default header name instead of the configured
 * source: one more `@TestProfile` is one more Quarkus boot in `:core`'s test JVM, and its 2 GB heap did not survive a
 * header profile beside the PROXY one (measured: `OutOfMemoryError` late in the suite, twice). The mapping of
 * `veyq.http.client-port.source=header` itself is proven by booting the fast-jar with it (B6 of cr-client-port-sources).
 */
@Path("/test/client-address")
@PermitAll
class ClientAddressProbeResource(private val config: VeyqConfig) {

    @Context
    private lateinit var request: HttpServerRequest

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    fun probe(@QueryParam("source") source: String?): String {
        val http = config.http()
        val port = if (source == "header") HeaderSource else http.clientPort()
        val resolved = request.clientAddress(http.trustedProxies().orElse(null), port) ?: return "none"
        return "${resolved.ip.hostAddress} ${resolved.port ?: "-"}"
    }
}

private object HeaderSource : VeyqConfig.Http.ClientPort {
    override fun source() = VeyqConfig.ClientPortSource.HEADER
    override fun header() = ClientAddress.PORT_HEADER
}
