package com.veyq.core.tenant

import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import jakarta.enterprise.context.ApplicationScoped

/** The tenant a request resolved to: its id, and its **registered** domain — never the header it arrived under. */
data class ResolvedTenant(val id: String, val domain: String)

/**
 * The only way a resource learns its tenant (ADR 0066). Host → tenant goes through [TenantHostResolver] — the one
 * home of the header rule and the domain lookup — and the answer is the tenant row's own id and domain. A handler
 * receives that value, never a host: a spoofed or unknown `X-Forwarded-Host` resolves to no tenant, where passing
 * the header on would let a caller name any domain and have the server act for it (sign a URL for it, or pick its
 * SMTP relay — the recovery defect this bean was built to close, ADR 0054 §4).
 *
 * **Why a bean rather than `@Context HttpHeaders` on the resource.** The obvious spelling — a `@Context`-injected
 * `HttpHeaders` field on `MediaResource`/`SharingResource` — compiles, serves the authenticated paths correctly,
 * and breaks every **anonymous** one: adding it to `SharingResource` turned all 16 guest share tests into `401`,
 * because the field changes how RESTEasy Reactive instantiates the resource and the class's constructor-injected
 * `SecurityIdentity` is then resolved for a caller that has none. Measured, not reasoned: removing only that
 * field turns `ShareSliceTest` green again with nothing else changed. Reading the request through
 * [CurrentVertxRequest] keeps the resource an ordinary CDI bean, so the guest paths stay reachable.
 *
 * `TenantSuspensionFilter` does not use this bean; it calls the resolver directly. Whether a request-scoped
 * [CurrentVertxRequest] is populated inside a pre-matching filter after its worker hop has not been measured.
 */
@ApplicationScoped
class RequestTenant(
    private val hosts: TenantHostResolver,
    private val request: CurrentVertxRequest,
) {
    /** The current request's tenant, or null — for an unknown or absent host, the bootstrap domain, and when no
     *  request is in scope at all (a scheduled job, a test calling straight into a handler). */
    fun resolve(): ResolvedTenant? =
        request.current?.request()
            ?.let { req -> hosts.tenantFor(hosts.hostOf { req.getHeader(it) }) }
            ?.let { ResolvedTenant(it.id, it.domain) }
}
