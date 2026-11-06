package me.raddatz.veyq.core.http

import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What the server saw of a request's client: the address of core's one resolver (ADR 0094) in text form, its source
 * port where one is known (null: no hop wrote one and no configured source stated it), the server time it was seen, in
 * milliseconds UTC, and the request's `Origin` header as [RequestOrigin] keeps it (null: none sent). The evidence a
 * guest write records (ADR 0093 § 10). The address, port and time are nothing a client can choose; the origin is what
 * a browser stated and no page's script can change — and what any other client chooses freely, so it tells apart a
 * write a foreign page made a visitor's browser send (ADR 0121), and never proves a write came from no such page.
 */
data class ClientSeen(val ip: String, val port: Int?, val at: Instant, val origin: String?)

/**
 * The `Origin` header as the guest-write record keeps it (ADR 0093 § 10): a serialized origin — `scheme://host[:port]`,
 * lowercase, as a browser writes it (RFC 6454 § 6.1) — or the literal `null` an opaque origin (a sandboxed frame, a
 * `data:` page) sends, verbatim; no header, null. Anything else — a second `Origin` header, a path, an uppercase
 * letter, a userinfo, more than [MAX] characters — is no browser's and is kept as [INVALID], never as sent: the column
 * holds only the two shapes and the marker, so a client cannot write free text into the evidence.
 */
object RequestOrigin {
    const val MAX = 255
    const val INVALID = "invalid"
    const val OPAQUE = "null"

    /** Scheme per RFC 3986 § 3.1; host a DNS name (IDNs arrive as punycode), an IPv4 or a bracketed IPv6; port 1–5 digits. */
    private val SERIALIZED = Regex("^[a-z][a-z0-9+.-]*://(?:[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.?|\\[[0-9a-f:.]+])(?::[0-9]{1,5})?$")

    /** [values] are every `Origin` header the request carried, in order. */
    fun kept(values: List<String>): String? {
        if (values.isEmpty()) return null
        val value = values.singleOrNull() ?: return INVALID
        if (value.length > MAX) return INVALID
        return if (value == OPAQUE || SERIALIZED.matches(value)) value else INVALID
    }
}

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
        return ClientSeen(ip, resolved.port, Instant.now().truncatedTo(ChronoUnit.MILLIS), RequestOrigin.kept(req.headers().getAll(ORIGIN)))
    }

    private companion object {
        const val ORIGIN = "Origin"
    }
}

/**
 * `veyq.http.client-port.*` as [RequestClient] reads it. A bean of its own only so a slice can state the `header` source
 * for one test (`QuarkusMock`) instead of a `@TestProfile`: one more profile is one more boot in `:core`'s test JVM,
 * whose heap is near its ceiling (src/backend/CLAUDE.md), and the mapping itself was proven by booting the fast-jar
 * with each source.
 */
@ApplicationScoped
class ClientPortSetting(private val config: VeyqConfig) {
    fun current(): VeyqConfig.Http.ClientPort = config.http().clientPort()
}
