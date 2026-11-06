package me.raddatz.veyq.core.sharing

import io.quarkus.narayana.jta.QuarkusTransaction
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Response
import me.raddatz.veyq.core.http.ApiMethodIndex
import me.raddatz.veyq.core.http.CrossOrigin
import me.raddatz.veyq.core.http.DeclaredMethods
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext

/**
 * **The guest share API answers any origin** while `core.public-shares.embeddable` is effective (ADR 0121 decision 3,
 * uc.sharing.browse-a-received-share-in-place): a member of another instance browses a link in place, their browser
 * talking to this instance directly. It decides, per request, whether the answer is cross-origin readable
 * ([CrossOrigin.grant], the app shell writes the headers) and answers a CORS preflight itself.
 *
 * **Which paths.** Every declared path under `/api/shares/{shareId}`, plus `/api/setup` and `/api/moderation/key` (the
 * two pre-auth reads a foreign client needs: the instance's major and capabilities, and the key a report seals to) —
 * and on them only the **public** methods ([ApiMethodIndex.DeclaredPath.publicMethods], `@PermitAll`), each application's
 * guest operations included without core naming one. A curator's operation on a link (revoke, reactivate, reissue, its
 * sealed name, `PATCH`/`DELETE` of the link) takes a bearer and stays same-origin; so does every other path.
 *
 * **Whose switch.** `/api/setup` and `/api/moderation/key` read the instance level. A share path reads the tenant of the
 * link **if the guest gate serves it** ([ShareGuestGate.activeShare]); for any other id — unknown, revoked, expired,
 * blocked, its role switched off — the instance level, which is exactly what an unknown id gets. So the headers are no
 * existence oracle: every id the gate answers `404` gets the same CORS answer as an id no link has, and only a link that
 * is served (whose existence its own answer already shows) can differ, by its tenant's switch. Not the request's `Host`:
 * a link of one tenant is reachable from another tenant's domain (`TenantSuspensionFilter`'s note), and the tenant that
 * switched embedding off is the link's.
 *
 * **Pre-matching, before [me.raddatz.veyq.core.http.UndeclaredMethodFilter]** ([PRIORITY] below its default), because
 * no operation declares `OPTIONS` and that filter refuses it `405`: a preflight this filter answers never reaches it, a
 * preflight it does not answer (switched off, a path outside the set, an `OPTIONS` that is no preflight) gets the `405`
 * exactly as before. Pre-matching also puts the grant ahead of every refusal JAX-RS can produce — authentication, the
 * rate limits, the handler's `404`, a `500` — so a browser reads those too. **Not covered:** an answer Vert.x writes before
 * JAX-RS sees the request (a body over the request limit, `RequestTooLargeRoutes`) carries no CORS header, so a browser
 * reports it as a network error. **The worker hop** is `TenantSuspensionFilter`'s: a pre-matching filter runs on the IO
 * thread, where the gate's database reads are refused; a request this filter has nothing to decide for is passed on
 * without one. A preflight is not rate-limited (the limiters are post-matching); what it costs is the gate's reads.
 */
@ApplicationScoped
class ShareCrossOrigin(
    private val declared: DeclaredMethods,
    private val gate: ShareGuestGate,
    private val registry: CapabilityRegistry,
) {

    @ServerRequestFilter(preMatching = true, priority = PRIORITY)
    fun filter(ctx: ResteasyReactiveContainerRequestContext): Uni<Response?> {
        val request = ctx.serverRequestContext as ResteasyReactiveRequestContext
        val path = request.pathWithoutPrefix
        val target = declared.index().match(path)?.takeIf { it.publicMethods.isNotEmpty() && isCrossOriginPath(it.shape) }
            ?: return PASS
        val preflight = ctx.method == HttpMethod.OPTIONS && ctx.getHeaderString(CrossOrigin.REQUEST_METHOD) != null
        val public = ctx.method in target.publicMethods || (ctx.method == HttpMethod.HEAD && HttpMethod.GET in target.publicMethods)
        if (!preflight && !public) return PASS
        val shareId = if (target.shape[1] == SHARES) ApiMethodIndex.segmentsOf(path)[2] else null
        val routing = request.serverRequest().unwrap(RoutingContext::class.java)
        return Uni.createFrom().item {
            if (!embeddable(shareId)) return@item null
            CrossOrigin.grant(routing)
            if (!preflight) return@item null
            Response.noContent()
                .header(CrossOrigin.ALLOW_METHODS, target.publicMethods.sorted().joinToString(", "))
                .header(CrossOrigin.ALLOW_HEADERS_HEADER, CrossOrigin.ALLOW_HEADERS.joinToString(", "))
                .header(CrossOrigin.MAX_AGE, CrossOrigin.MAX_AGE_SECONDS.toString())
                .build()
        }.runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
    }

    /** Whether `core.public-shares.embeddable` is effective for [shareId]'s answer (the class's § Whose switch); the
     *  instance level for a path that names no link. */
    internal fun embeddable(shareId: String?): Boolean = QuarkusTransaction.joiningExisting().call {
        val served = shareId?.let { gate.activeShare(it) }
        if (served != null) {
            registry.isActiveInScope(CapabilityIds.PUBLIC_SHARES_EMBEDDABLE, served.scopeId)
        } else {
            registry.isActive(CapabilityIds.PUBLIC_SHARES_EMBEDDABLE, null)
        }
    }

    private companion object {
        /** Ahead of the pre-matching filters at the default (`UndeclaredMethodFilter`, `TenantSuspensionFilter`). */
        const val PRIORITY = Priorities.USER - 100

        const val API = "api"
        const val SHARES = "shares"

        val PASS: Uni<Response?> = Uni.createFrom().nullItem()

        /** `/api/shares/{shareId}` and below; `/api/setup`; `/api/moderation/key` — ADR 0121 decision 3's set. */
        fun isCrossOriginPath(shape: List<String?>): Boolean =
            (shape.size >= 3 && shape[0] == API && shape[1] == SHARES && shape[2] == null) ||
                shape == listOf(API, "setup") ||
                shape == listOf(API, "moderation", "key")
    }
}
