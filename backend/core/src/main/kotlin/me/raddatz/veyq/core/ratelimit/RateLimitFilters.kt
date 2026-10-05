package me.raddatz.veyq.core.ratelimit

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.httpError
import io.smallrye.config.SmallRyeConfig
import io.vertx.core.http.HttpServerRequest
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ResourceInfo
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.ConfigProvider

/**
 * The two **boundary-enforced** keys of the declarative rate-limit posture (ADR 0051): [RateLimitKey.IP] before
 * authentication and [RateLimitKey.USER] after it. Every other key in [RateLimitKey] is a marker the handler
 * enforces, and passes through both filters untouched.
 *
 * Both are bound by the `@RateLimit` `@NameBinding`, and **name-binding matches on the annotation TYPE, not its
 * value** — so each filter is invoked wherever *any* key is declared and must decide for itself. That decision is
 * the [RateLimitPosture.enforcedKey] read against [ResourceInfo]: not its key, not its request.
 */
private const val RETRY_AFTER = "Retry-After"

/** The shared 429: the contract `ErrorResponse` envelope (`rate_limited`) plus the `Retry-After` hint. */
private fun ContainerRequestContext.abortRateLimited(retryAfterSeconds: Long) = abortWith(
    Response.fromResponse(httpError(Failures.rateLimited))
        .header(RETRY_AFTER, retryAfterSeconds)
        .build(),
)

// A JAX-RS @Provider is instantiated too early in Quarkus boot to take the VeyqConfig @ConfigMapping as a CDI
// dependency (constructor OR field injection fails config-mapping registration). So both filters read the mapping
// via a programmatic lookup, lazily on first request — the mapping is registered by then — keeping VeyqConfig the
// single typed source for these knobs rather than falling back to scattered @ConfigProperty.
private val rateLimitConfig: VeyqConfig.RateLimit
    get() = (ConfigProvider.getConfig() as SmallRyeConfig).getConfigMapping(VeyqConfig::class.java).ratelimit()

/**
 * Enforces `@RateLimit(key = IP)`: a best-effort, per-client-IP token bucket over the **public** surface —
 * defense-in-depth against brute-force, challenge-spam and cheap DoS. Ed25519 makes login forgery infeasible, so
 * the real value is bounding DoS, challenge-row churn, and the rate of any offline-grind enabling traffic.
 *
 * Runs at `AUTHENTICATION` priority, which is what makes it usable pre-auth — and also why `IP` is only valid on a
 * `@PermitAll` endpoint (a rule the posture sweep asserts statically): on an authenticated endpoint the principal
 * is both available and the better axis, and an IP bound there throttles a legitimate active user instead.
 *
 * **It trusts the client IP**: only correct behind a proxy when `quarkus.http.proxy.proxy-address-forwarding=true`
 * AND the proxy is trusted (else `X-Forwarded-For` is spoofable). We intentionally do NOT trust forwarded headers
 * by default. Tuning: `veyq.ratelimit.auth.max-requests` (default 60) per `veyq.ratelimit.auth.window-seconds`
 * (default 60) per client IP; set the max very high to effectively disable (the `%test` profile does this so the
 * slice tests, all from 127.0.0.1, don't trip it).
 *
 * **All `key = IP` endpoints share one bucket per IP.** That is a deliberate property, not an oversight: it bounds
 * an attacker's total public-surface spend rather than handing them a fresh budget per route. It is also why the
 * tenant-invite mint does *not* use this key — an authenticated owner would be counted against the same bucket as
 * anonymous login traffic, so its bound is the per-(tenant,recipient) [RateLimitKey.RECIPIENT] marker instead.
 */
@Provider
@RateLimit(key = RateLimitKey.IP)
@Priority(Priorities.AUTHENTICATION)
class IpRateLimitFilter : ContainerRequestFilter {

    @Context
    private lateinit var request: HttpServerRequest

    @Context
    private lateinit var resourceInfo: ResourceInfo

    private val counter = TokenBucketCounter()

    override fun filter(ctx: ContainerRequestContext) {
        RateLimitPosture.enforcedKey(resourceInfo.resourceClass, resourceInfo.resourceMethod, RateLimitKey.IP)
            ?: return

        val ip = request.remoteAddress()?.hostAddress() ?: "unknown"
        val auth = rateLimitConfig.auth()
        val retryAfter = counter.consume(
            key = ip,
            nowNanos = System.nanoTime(),
            maxRequests = auth.maxRequests(),
            windowSeconds = auth.windowSeconds(),
        )
        if (retryAfter != null) ctx.abortRateLimited(retryAfter)
    }
}

/**
 * Enforces `@RateLimit(key = USER)`: a per-principal token bucket on an authenticated endpoint, where the caller's
 * identity — not their address — is the axis worth counting. Attributable and revocable by construction, so the
 * default is generous: it bounds runaway automation, it is not an anti-abuse measure.
 *
 * Runs **after** authorization, because there is nothing to key on before it: `quarkus.http.auth.proactive=false`
 * (ADR 0008, lazy authentication) means no identity is resolved at the `AUTHENTICATION` priority where
 * [IpRateLimitFilter] sits. Hence two filters rather than one with a branch. If the principal is somehow absent
 * the filter **does nothing** — `key = USER` requires `@Authenticated` (asserted statically by the posture sweep),
 * so an anonymous request here has already been rejected by the security layer; inventing a fallback key would
 * silently turn a per-user bound into a shared global one.
 *
 * Tuning: `veyq.ratelimit.user.max-requests` (default 600) per `veyq.ratelimit.user.window-seconds` (default 60).
 */
@Provider
@RateLimit(key = RateLimitKey.USER)
@Priority(Priorities.AUTHORIZATION + 10)
class UserRateLimitFilter : ContainerRequestFilter {

    @Context
    private lateinit var resourceInfo: ResourceInfo

    private val counter = TokenBucketCounter()

    override fun filter(ctx: ContainerRequestContext) {
        RateLimitPosture.enforcedKey(resourceInfo.resourceClass, resourceInfo.resourceMethod, RateLimitKey.USER)
            ?: return

        val principal = ctx.securityContext?.userPrincipal?.name ?: return
        val user = rateLimitConfig.user()
        val retryAfter = counter.consume(
            key = principal,
            nowNanos = System.nanoTime(),
            maxRequests = user.maxRequests(),
            windowSeconds = user.windowSeconds(),
        )
        if (retryAfter != null) ctx.abortRateLimited(retryAfter)
    }
}
