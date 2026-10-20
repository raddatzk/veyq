package me.raddatz.veyq.core.http

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.configuration.MemorySize
import io.quarkus.vertx.http.runtime.RouteConstants
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * A request whose body is past the server's request limit answers the contract's `413
 * ErrorResponse{code: request_too_large}` — the `RequestTooLarge` response `_common.openapi.yaml` holds and every
 * operation with a body declares (`validate_contracts_common.py` assertion 6). Quarkus answers the same request itself
 * with a bare `413` and no body (measured on Quarkus 3.36.0): a status the contract could not describe with its one
 * error shape, which the API fuzzing reported as undeclared (inbox #508). Two paths, both before authentication — the
 * limit is the instance's, not the caller's, and the answer names nothing the caller sent:
 *
 * - **A declared length** (`Content-Length`) is decided from the header alone, before a byte of the body is read, one
 *   route ahead of Quarkus' own check (`ROUTE_ORDER_UPLOAD_LIMIT`).
 * - **A body of unknown length** (sent chunked, or over HTTP/2, with no `Content-Length`) is read here, counted against
 *   the limit, and handed on whole: Quarkus REST reads a body the routing context already holds instead of the stream
 *   (`VertxResteasyReactiveRequestContext.createInputStream`). Left to Quarkus, such a body is cut off by its
 *   `VertxInputStream` while the operation reads it, which ends the response with the bare `413` itself before any
 *   mapper or filter can shape it. Vert.x's [BodyHandler] fails the request with `413` the moment the count passes the
 *   limit, and this route's failure handler answers it. Buffering it first is what Quarkus REST does with every JSON body
 *   anyway: the binding reads it whole.
 *
 * The connection is closed with the answer, as Quarkus closes it, because the body the client is still sending is
 * never read to its end.
 */
@ApplicationScoped
class RequestTooLargeRoutes(
    private val mapper: ObjectMapper,
    @param:ConfigProperty(name = "quarkus.http.limits.max-body-size", defaultValue = "10240K") private val limit: MemorySize,
) {

    /** Reads a body of unknown length up to the limit: no file uploads, no form merging — the API takes JSON only. */
    private val bodyOfUnknownLength: BodyHandler = BodyHandler.create(false)
        .setBodyLimit(limit.asLongValue())
        .setMergeFormAttributes(false)

    fun register(@Observes router: Router) {
        router.route().order(RouteConstants.ROUTE_ORDER_UPLOAD_LIMIT - 1).handler(::handle).failureHandler(::failed)
    }

    internal fun handle(ctx: RoutingContext) {
        val header = ctx.request().getHeader(HttpHeaders.CONTENT_LENGTH)
        if (header == null) {
            if (ctx.request().method() == HttpMethod.GET || ctx.request().method() == HttpMethod.HEAD) {
                ctx.next()
                return
            }
            // The body handler resumes the request Quarkus paused and reads it — or, when it carries no body at all
            // (neither `Transfer-Encoding` nor HTTP/2), passes it on untouched.
            ctx.put(READING, true)
            bodyOfUnknownLength.handle(ctx)
            return
        }
        val declared = header.toLongOrNull()
        if (declared == null || declared <= limit.asLongValue()) {
            ctx.next()
            return
        }
        answer(ctx)
    }

    /** The body handler's `fail(413)` — the only failure this route answers; every other one goes on to its handler. */
    internal fun failed(ctx: RoutingContext) {
        if (ctx.get<Boolean>(READING) == true && ctx.failure() == null && ctx.statusCode() == TOO_LARGE) {
            answer(ctx)
        } else {
            ctx.next()
        }
    }

    private fun answer(ctx: RoutingContext) {
        val response = ctx.response()
        if (response.ended() || response.closed()) return
        response
            .setStatusCode(requestTooLarge.status.httpStatus)
            .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .putHeader(HttpHeaders.CONNECTION, "close")
            .end(mapper.writeValueAsString(httpError(requestTooLarge).entity))
            .onComplete { ctx.request().connection().close() }
    }

    internal companion object {
        val requestTooLarge = httpFailure("request_too_large", FailureStatus.CONTENT_TOO_LARGE)
        private val TOO_LARGE = FailureStatus.CONTENT_TOO_LARGE.httpStatus

        /** Marks a request whose body this route reads, so its failure handler answers only its own `413`. */
        private const val READING = "veyq.request-too-large.reading"
    }
}
