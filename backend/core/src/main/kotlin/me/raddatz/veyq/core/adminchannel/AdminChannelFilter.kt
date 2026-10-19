package me.raddatz.veyq.core.adminchannel

import me.raddatz.veyq.core.generated.AdminChannelOperations
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jboss.resteasy.reactive.server.SimpleResourceInfo

/**
 * **The admin-channel gate** (ADR 0065, uc.admin.admin-channel step 5): a Server-Owner operation reached anywhere
 * but the admin channel is answered as if it did not exist.
 *
 * **Keyed on the matched resource method, not on the path** — so no spelling of the path (a trailing slash, an
 * encoded segment, a doubled separator) routes around it: whatever JAX-RS matched is what is checked. A resource
 * method's name is its operationId, because every resource implements the generated `…Api` interface, and the set
 * is generated from the contract markers ([AdminChannelOperations]); `AdminChannelPostureSweepTest` holds both
 * halves of that to the Server-Owner gate.
 *
 * **Post-match, and so behind security — deliberately.** `@RolesAllowed` refuses an anonymous request before any
 * post-match filter runs (measured, at every priority), so such a caller gets the 401 it always got and nothing
 * executes (uc A1). A uniform 404 would need the path index in front of JAX-RS to answer declared methods too, and
 * would hide nothing: the operation list is in a public repository. That index does answer the methods such a path
 * does NOT declare, with this same refusal off the admin channel (`UndeclaredMethodFilter`, inbox #494).
 */
@ApplicationScoped
class AdminChannelFilter(private val listener: AdminListener) {

    @ServerRequestFilter
    fun gate(resource: SimpleResourceInfo, request: HttpServerRequest): Response? {
        if (resource.methodName !in AdminChannelOperations.operationIds) return null
        if (AdminChannel.isAdminChannel(request, listener.port())) return null
        return unknownRoute()
    }

    internal companion object {
        /**
         * What an `/api` path no resource declares is answered with: JAX-RS matches nothing and passes the request
         * on, the router runs out of routes, and Vert.x writes its own page — `RoutingContextImpl.DEFAULT_404`, a
         * private constant written by `checkHandleNoMatch`, which a post-match filter cannot reach without Vert.x
         * internals. So it is spelled here, and `AdminChannelGateSliceTest.theRefusalIsIdenticalToAnUnknownRoute`
         * compares it with the LIVE unknown-route answer on every build: a Vert.x that changes its page reddens that
         * test instead of making the refusal quietly distinguishable. (An empty 404 was the first shape; measured, it
         * differs in content type and body, which is exactly what that test exists to catch.)
         */
        fun unknownRoute(): Response = Response.status(Response.Status.NOT_FOUND)
            .type("text/html; charset=utf-8")
            .entity("<html><body><h1>Resource not found</h1></body></html>")
            .build()
    }
}
