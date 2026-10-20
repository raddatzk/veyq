package me.raddatz.veyq.core.http

import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.interceptor.Interceptor
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.config.VeyqConfig.ClientPortSource
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * **The PROXY protocol and the port source agree, or the app does not start** (ADR 0094 decision 7). With
 * `veyq.http.client-port.source=proxy-protocol` and the protocol off, the "PROXY source" the resolver would believe is
 * the gateway's own socket; with the protocol on and any other source, every connection's peer is the PROXY header's
 * source while nothing was meant to believe it. Either is a misconfiguration nothing else surfaces, so it stops the
 * boot, naming both keys and no address (ADR 0092). It is a check at startup, not at configuration: the schema
 * migration has already run by then, as it has for every other startup refusal (`AdminListener`'s bind). Under `header` the header's name must be one, since the
 * Compose routing file carries it.
 */
@ApplicationScoped
class ClientPortSourceCheck(
    private val config: VeyqConfig,
    @param:ConfigProperty(name = PROXY_KEY, defaultValue = "false") private val proxyProtocol: Boolean,
) {

    fun check(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) event: StartupEvent) {
        // Uncaught on purpose: an exception out of a StartupEvent observer fails startup (AdminListener's precedent).
        // First among the observers, so no other startup work — the admin listener's bind, a projection — runs on a
        // refused configuration. Flyway's migrate-at-start is no observer: it runs before every one (measured, B6).
        val source = config.http().clientPort().source()
        if ((source == ClientPortSource.PROXY_PROTOCOL) != proxyProtocol) {
            throw IllegalStateException(
                "$SOURCE_KEY=${source.name.lowercase().replace('_', '-')} and $PROXY_KEY=$proxyProtocol disagree: the " +
                    "PROXY protocol is the client port's source exactly when both say so — set $SOURCE_KEY=proxy-protocol " +
                    "together with $PROXY_KEY=true, or neither (ADR 0094 decision 7)",
            )
        }
        // The name goes into the Traefik routing file the app renders (Compose), so it is a header name or nothing.
        if (source == ClientPortSource.HEADER && !HEADER_NAME.matches(config.http().clientPort().header())) {
            throw IllegalStateException("$HEADER_KEY is not an HTTP header name (letters, digits and dashes)")
        }
    }

    private companion object {
        const val PROXY_KEY = "quarkus.http.proxy.use-proxy-protocol"
        const val SOURCE_KEY = "veyq.http.client-port.source"
        const val HEADER_KEY = "veyq.http.client-port.header"
        val HEADER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9-]*")
    }
}
