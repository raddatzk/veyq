package com.veyq.app.shared.tenant

import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import jakarta.enterprise.context.ApplicationScoped

/**
 * The domain a presigned blob URL for *this* request must be signed against (ADR 0063 / ADR 0013 §4): the
 * **resolved tenant's own registered domain**, or null when the request resolves to no tenant (the bootstrap
 * domain, an unknown host, a direct-port self-hosted call) — which signs against the configured default.
 *
 * **Why a bean rather than `@Context HttpHeaders` on the resource.** The obvious spelling — a `@Context`-injected
 * `HttpHeaders` field on `MediaResource`/`SharingResource` — compiles, serves the authenticated paths correctly,
 * and breaks every **anonymous** one: adding it to `SharingResource` turned all 16 guest share tests into `401`,
 * because the field changes how RESTEasy Reactive instantiates the resource and the class's constructor-injected
 * `SecurityIdentity` is then resolved for a caller that has none. Measured, not reasoned: removing only that
 * field turns `ShareSliceTest` green again with nothing else changed. Reading the request through
 * [CurrentVertxRequest] keeps the resource an ordinary CDI bean, so the guest paths stay reachable.
 *
 * The lookup itself is [TenantHostResolver.domainFor] — through the `tenant` table, never the raw header. That
 * is the security property this whole indirection exists to preserve: a spoofed `X-Forwarded-Host` resolves to
 * no tenant and gets the default, where echoing the header back would have the server sign for any origin a
 * caller cared to name.
 */
@ApplicationScoped
class RequestTenantDomain(
    private val hosts: TenantHostResolver,
    private val request: CurrentVertxRequest,
) {
    /** The current request's resolved tenant domain, or null. Null too when there is no request in scope at all
     *  (a scheduled job, a test calling straight into a handler) — which wants the default for the same reason. */
    fun resolve(): String? =
        request.current?.request()?.let { req -> hosts.domainFor { req.getHeader(it) } }
}
