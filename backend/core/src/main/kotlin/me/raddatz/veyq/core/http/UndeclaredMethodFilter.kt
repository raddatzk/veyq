package me.raddatz.veyq.core.http

import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import me.raddatz.veyq.core.adminchannel.AdminChannel
import me.raddatz.veyq.core.adminchannel.AdminChannelFilter
import me.raddatz.veyq.core.adminchannel.AdminListener
import me.raddatz.veyq.core.generated.AdminChannelOperations
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext

/**
 * **An API path answers exactly the methods its contract declares** (inbox #494): any other method — `OPTIONS` and
 * `TRACE` among them — is `405` with an `Allow` that lists the path's declared methods and the contract's
 * `ErrorResponse{code: method_not_allowed}`. Quarkus REST answered a bare `405` (RFC 9110 requires the `Allow`), a
 * `404` where a literal path has a templated sibling that declares the method (`PUT /api/scopes/invitations` went to
 * `/api/scopes/{scopeId}` and failed its UUID), and `OPTIONS` with `200` and every method of the resource CLASS.
 *
 * **`OPTIONS` is not answered.** The API is same-origin and uses no CORS, so no preflight ever needs one, and no
 * operation declares it: it is one more undeclared method, refused with the same `Allow` — which is all an `OPTIONS`
 * answer would have said. The app shell does the same: Vert.x refuses a method its `GET`/`HEAD` routes do not take
 * with `405` and an `Allow`. `HEAD` stays answered wherever `GET` is declared (JAX-RS answers it from the `GET`, RFC 9110
 * § 9.3.2) and is not listed, since `Allow` lists the contract's methods; where `GET` is not declared it is refused.
 *
 * **What it leaves alone.** A declared method passes untouched — a malformed path id still gets its `404 not_found`
 * ([EmptyNotFoundFilter]) and authentication its `401`. An `/api` path no resource declares stays the unknown route
 * for every method: `GET` and `HEAD` reach Vert.x' own 404 as before, and every other method gets that same answer
 * here (the app shell's `GET`/`HEAD` fallback route would otherwise make Vert.x answer it `405` and advertise a `GET`
 * the path does not have). Off the admin channel, a Server-Owner path (ADR 0065) is such an unknown route for every
 * method it does not declare too — the declared ones get the same answer from [AdminChannelFilter] — so neither the
 * `Allow` nor the status tells the public port more than an unknown route would.
 *
 * **Pre-matching**, so it sees the request before JAX-RS picks a resource class — which is where the class's methods
 * and the templated sibling came from — and before authentication: an undeclared method needs no session to be
 * refused, and the methods a path declares are the public contract. The path is the one routing matches
 * (`getPathWithoutPrefix`: normalised, not decoded), so no spelling reaches a resource this filter did not see. Not an
 * exception mapper: a mapper for `NotAllowedException` would see neither the `404`s nor the `OPTIONS`.
 */
@ApplicationScoped
class UndeclaredMethodFilter(private val declared: DeclaredMethods, private val listener: AdminListener) {

    @ServerRequestFilter(preMatching = true)
    fun refuse(ctx: ResteasyReactiveContainerRequestContext, request: HttpServerRequest): Response? {
        val path = (ctx.serverRequestContext as ResteasyReactiveRequestContext).pathWithoutPrefix
        val segments = ApiMethodIndex.segmentsOf(path)
        if (segments.firstOrNull() != API_SEGMENT) return null
        val method = ctx.method
        val target = declared.index().match(path)
            ?: return if (method == HttpMethod.GET || method == HttpMethod.HEAD) null else AdminChannelFilter.unknownRoute()
        if (method in target.methods || (method == HttpMethod.HEAD && HttpMethod.GET in target.methods)) return null
        val adminChannel by lazy { AdminChannel.isAdminChannel(request, listener.port()) }
        val allowed = target.methods.filter { (_, operationId) -> operationId !in AdminChannelOperations.operationIds || adminChannel }.keys
        if (allowed.isEmpty()) return AdminChannelFilter.unknownRoute()
        return Response.fromResponse(httpError(methodNotAllowed))
            .header(HttpHeaders.ALLOW, allowed.sorted().joinToString(", "))
            .type(MediaType.APPLICATION_JSON_TYPE)
            .build()
    }

    private companion object {
        const val API_SEGMENT = "api"
        val methodNotAllowed = httpFailure("method_not_allowed", FailureStatus.METHOD_NOT_ALLOWED)
    }
}
