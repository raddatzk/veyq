package me.raddatz.veyq.core.http

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * The PROXY protocol on the public listener, as a gateway that sends it requires (ADR 0094 decision 7). One profile
 * for every class that speaks PROXY to the app, so they share one boot, with the port source that goes with it
 * (startup refuses either without the other, `ClientPortSourceCheck`). The admin listener never takes PROXY.
 */
class ProxyProtocolProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = mapOf(
        "quarkus.http.proxy.use-proxy-protocol" to "true",
        "veyq.http.client-port.source" to "proxy-protocol",
        // B5's bucket: low enough that three requests fill it (no other class of this profile hits an IP-keyed route).
        "veyq.ratelimit.auth.max-requests" to "3",
        "veyq.ratelimit.auth.window-seconds" to "60",
    )
}
