package me.raddatz.veyq.core.http

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What the server saw of a request's client: the address of core's one resolver (ADR 0094) in text form, its source
 * port where one is known (null: no hop wrote one and no configured source stated it), and the server time it was
 * seen, in milliseconds UTC. The evidence a guest write records (ADR 0093 § 10) — nothing a client can choose.
 */
data class ClientSeen(val ip: String, val port: Int?, val at: Instant)

/**
 * The current request's client, resolved once per call by [ClientAddress] through [clientAddress] with the configured
 * trust list and port source — the guest-write record's one way to the address, as the rate limiter's filter is the
 * other consumer's (ADR 0094 decision 1). A resource calls [seen] once and hands the value down to the write, which
 * records it in its own transaction (`GuestWriteRecords`); `GuestWriteRecordedTest` holds that every guest-write
 * resource does.
 *
 * A bean rather than `@Context HttpServerRequest` on the resource, for the reason [me.raddatz.veyq.core.tenant.RequestTenant]
 * gives: a `@Context` field turns every anonymous path of `SharingResource` into a `401`. Nothing here logs.
 */
@ApplicationScoped
class RequestClient(
    private val request: CurrentVertxRequest,
    private val config: VeyqConfig,
    private val ports: ClientPortSetting,
) {
    /** The client of the current request, or null — when the resolver yields no address (ADR 0094: then nothing is
     *  recorded), and when no request is in scope (a scheduled job, a test calling straight into a handler). */
    fun seen(): ClientSeen? {
        val req = request.current?.request() ?: return null
        val http = config.http()
        val resolved = req.clientAddress(http.trustedProxies().orElse(null), ports.current()) ?: return null
        // A zone (`%eth0`) is the server's own interface, no part of the client's address — and no part of the record.
        val ip = resolved.ip.hostAddress?.substringBefore('%') ?: return null
        return ClientSeen(ip, resolved.port, Instant.now().truncatedTo(ChronoUnit.MILLIS))
    }
}

/**
 * `veyq.http.client-port.*` as [RequestClient] reads it. A bean of its own only so a slice can state the `header` source
 * for one test (`QuarkusMock`) instead of a `@TestProfile`: one more profile is one more boot in `:core`'s test JVM,
 * whose heap is near its ceiling (backend/CLAUDE.md), and the mapping itself is proven by ADR 0094 M13.
 */
@ApplicationScoped
class ClientPortSetting(private val config: VeyqConfig) {
    fun current(): VeyqConfig.Http.ClientPort = config.http().clientPort()
}
