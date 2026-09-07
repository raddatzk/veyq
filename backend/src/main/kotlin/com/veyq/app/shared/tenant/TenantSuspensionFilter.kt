package com.veyq.app.shared.tenant

import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.httpError
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerRequestFilter

/**
 * The tenant-suspension lockout (uc.admin.suspend-tenant, ADR 0066): every request whose `Host` resolves to a
 * `SUSPENDED` tenant is refused `403 tenant_suspended`, no exceptions — read or write, authenticated or not.
 *
 * **It is pre-matching, and that placement is the guarantee, not a detail.** Suspension had to block
 * *immediately* rather than eventually, and every access token already in the wild outlives the status flip by
 * its full remaining lifetime. Refusing before authentication is evaluated is what makes a still-valid token
 * worth nothing here: the request never gets far enough for anyone to look at it.
 *
 * A plain `@Provider ContainerRequestFilter` at `@Priority(Priorities.AUTHENTICATION)` — the shape
 * `RateLimitFilters` uses — does **not** buy that, and is a misleading model here. Quarkus's eager security
 * (ADR 0008 / cr-0012) rejects an unauthenticated request to an `@Authenticated` route from a handler RESTEasy
 * Reactive places *after* matching but *before* post-match request filters, so no post-match priority gets ahead
 * of it: the filter answers nothing and the caller sees `401`. `IpRateLimitFilter` never notices, because
 * `key = IP` is only valid on `@PermitAll` routes, where eager security has nothing to reject. Measured, not
 * reasoned: dropping `preMatching = true` reds
 * `TenantSuspendSliceTest.anonymousRequestToASuspendedTenantIs403NotUnauthorized` with "expected 403 but was
 * 401" — against that test's own `401` control, so the failure names the ordering and nothing else.
 *
 * **The worker-pool hop is load-bearing too, and it is why this is a `Uni`-returning `@ServerRequestFilter`
 * rather than a `ContainerRequestFilter` class.** A pre-matching filter runs before RESTEasy Reactive has
 * matched a resource method, so it has made no blocking/non-blocking dispatch decision and runs the filter on
 * the IO thread — where the tenant lookup below throws `BlockingOperationNotAllowedException`, i.e. a 500 on
 * **every** request, suspended or not (measured: removing `runSubscriptionOn` reds all 10 tests in that class
 * with exactly that exception). `@Blocking` is not the fix: the build rejects it on a `ContainerRequestFilter`
 * override *and* on a `@ServerRequestFilter` method here ("may only be used on entrypoint methods"), so the
 * dispatch is explicit instead. It costs nothing in practice — every resource method in this service is
 * blocking already, so the request was bound for a worker thread regardless.
 *
 * **It touches no session, deliberately.** The temptation is to revoke the suspended tenant's members' sessions
 * as belt-and-braces; it is a defect, not hardening. Sessions are account-global, not tenant-scoped (ADR 0004:
 * `SessionFamilyEntity` carries no `tenantId`) and an account may hold memberships in several tenants — so a
 * revoke would sign a multi-tenant member out of every *other*, unaffected tenant too. Keying on the resolved
 * Host instead of on who is calling is what scopes the lockout to the one tenant that was suspended.
 * `TenantSuspendSliceTest.suspendingOneTenantLeavesAnotherReachableOnTheSameSession` keeps that pinned with a
 * member of two tenants and one suspension; adding a revoke here reds it.
 *
 * **It only ever narrows.** A Host claiming no tenant — the bootstrap domain, an unknown domain, `localhost` —
 * passes straight through, exactly as before this filter existed; the only new outcome it can produce is the
 * refusal above.
 */
@ApplicationScoped
class TenantSuspensionFilter(private val hosts: TenantHostResolver) {

    @ServerRequestFilter(preMatching = true)
    fun filter(ctx: ContainerRequestContext): Uni<Response?> =
        Uni.createFrom().item { refusalFor(ctx) }.runSubscriptionOn(Infrastructure.getDefaultWorkerPool())

    /** The `403 tenant_suspended` response, or null to let the request through untouched. */
    private fun refusalFor(ctx: ContainerRequestContext): Response? {
        val tenant = hosts.tenantFor(hosts.hostOf { ctx.getHeaderString(it) }) ?: return null
        return if (tenant.status == TenantEntity.SUSPENDED) httpError(Failures.tenantSuspended) else null
    }
}
