package me.raddatz.veyq.core.http

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.configuration.MemorySize
import io.quarkus.vertx.http.runtime.RouteConstants
import io.vertx.core.http.HttpHeaders
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * A request whose declared body is past the server's request limit answers the contract's `413
 * ErrorResponse{code: request_too_large}` — the `RequestTooLarge` response `_common.openapi.yaml` holds and every
 * operation with a body declares (`validate_contracts_common.py` assertion 6). Quarkus answers the same request itself,
 * one route later (`ROUTE_ORDER_UPLOAD_LIMIT`), with a bare `413` and no body (measured on Quarkus 3.36.0): a status the
 * contract could not describe with its one error shape, which the API fuzzing reported as undeclared.
 *
 * Decided from the `Content-Length` header alone, before a byte of the body is read and before authentication: the
 * limit is the instance's, not the caller's, and the answer names nothing the caller sent. The connection is closed
 * with it, as Quarkus closes it, because the body the client is still sending is never read. **Its limit:** a body sent
 * chunked, with no `Content-Length`, is cut off by Quarkus while it is read — that answer stays Quarkus' own.
 */
@ApplicationScoped
class RequestTooLargeRoutes(
    private val mapper: ObjectMapper,
    @param:ConfigProperty(name = "quarkus.http.limits.max-body-size", defaultValue = "10240K") private val limit: MemorySize,
) {

    fun register(@Observes router: Router) {
        router.route().order(RouteConstants.ROUTE_ORDER_UPLOAD_LIMIT - 1).handler(::handle)
    }

    internal fun handle(ctx: RoutingContext) {
        val declared = ctx.request().getHeader(HttpHeaders.CONTENT_LENGTH)?.toLongOrNull()
        if (declared == null || declared <= limit.asLongValue()) {
            ctx.next()
            return
        }
        ctx.response()
            .setStatusCode(requestTooLarge.status.httpStatus)
            .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .putHeader(HttpHeaders.CONNECTION, "close")
            .end(mapper.writeValueAsString(httpError(requestTooLarge).entity))
            .onComplete { ctx.request().connection().close() }
    }

    internal companion object {
        val requestTooLarge = httpFailure("request_too_large", FailureStatus.CONTENT_TOO_LARGE)
    }
}
