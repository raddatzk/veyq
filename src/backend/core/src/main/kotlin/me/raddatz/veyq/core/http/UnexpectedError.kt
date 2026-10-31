package me.raddatz.veyq.core.http

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.AuthenticationException
import io.quarkus.security.ForbiddenException
import io.quarkus.security.UnauthorizedException
import io.vertx.core.http.HttpHeaders
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.HttpException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext
import java.io.IOException
import java.sql.SQLException
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.RejectedExecutionException

/**
 * The one formatter for an **unexpected error** — a failure no mapper answered (ADR 0092 § 4). It logs, at ERROR, a
 * random error id, the method, the route **template**, the exception's class chain, a SQLState and vendor code where a
 * [SQLException] is in the chain, and the first frame in `me.raddatz.veyq`; never the URI, the query, a header or any
 * exception's message. A message is someone else's text — a database driver's carries the values a statement was
 * bound with, an email address among them on a colliding sign-up — and the URI carries a client's own query verbatim.
 * The full exception goes to DEBUG under [DETAIL_CATEGORY], off by default; raising it is the troubleshooting step on
 * the Run docs, and it knowingly logs messages that can name a person.
 *
 * The error id is random per event and derived from nothing (§ 7); the 500's `message` carries it, so a reader of
 * the answer can hand an operator the one line that belongs to it.
 */
object UnexpectedError {
    /**
     * The category that carries the full exception at DEBUG. Off by default: the root level is INFO. Lower case and
     * dotted only, unlike a class's own logger, because an operator raises it through the environment
     * (`QUARKUS_LOG_CATEGORY__ME_RADDATZ_VEYQ_CORE_HTTP_UNEXPECTED_DETAIL__LEVEL=DEBUG`, the Run docs' troubleshooting
     * step), and an environment variable's name cannot spell an upper-case letter of a category back.
     */
    const val DETAIL_CATEGORY = "me.raddatz.veyq.core.http.unexpected.detail"

    /** What [UnexpectedErrorRoutes] logs for a request that matched no JAX-RS resource method. */
    const val NO_TEMPLATE = "-"

    private const val OWN_PACKAGE = "me.raddatz.veyq."
    private const val MAX_CHAIN = 8

    private val log = Logger.getLogger(UnexpectedError::class.java)
    private val detail = Logger.getLogger(DETAIL_CATEGORY)

    /** Logs [failure] as § 4 says and returns the error id the answer carries. */
    fun log(method: String, route: String, failure: Throwable): String {
        val id = UUID.randomUUID().toString()
        log.errorf("Unexpected error, error id %s: %s %s failed: %s", id, method, route, describe(failure))
        if (detail.isDebugEnabled) detail.debugf(failure, "Unexpected error, error id %s: the full exception", id)
        return id
    }

    /**
     * The class chain, a SQLState and vendor code, and the first own frame — the line's whole content after the route.
     * Public so the formatter's content is pinned by a test without an HTTP round trip.
     */
    fun describe(failure: Throwable): String {
        val chain = causes(failure)
        val sql = chain.firstNotNullOfOrNull { it as? SQLException }
        val frame = chain.firstNotNullOfOrNull { t -> t.stackTrace.firstOrNull { it.className.startsWith(OWN_PACKAGE) } }
        return buildString {
            append(chain.joinToString(" <- ") { it.javaClass.name })
            if (sql != null) append(", SQLState ").append(sql.sqlState ?: "-").append(", vendor code ").append(sql.errorCode)
            if (frame != null) append(", at ").append(frame.className).append('.').append(frame.methodName).append(':').append(frame.lineNumber)
        }
    }

    /** The cause chain, outermost first: bounded, and cycle-safe — a cause may name an exception already in it. */
    private fun causes(failure: Throwable): List<Throwable> {
        val seen = IdentityHashMap<Throwable, Unit>()
        return generateSequence(failure) { it.cause }.takeWhile { seen.put(it, Unit) == null }.take(MAX_CHAIN).toList()
    }
}

/**
 * The entry point an unexpected error takes, for a JAX-RS resource and a Vert.x route alike: a Vert.x failure handler
 * ordered just ahead of Quarkus' own (`QuarkusErrorHandler`, `route().last()`), whose line names the request URI with
 * its query and logs the exception with its message. Read in Quarkus 3.36.0's sources: that handler's
 * `log.errorf(exception, "HTTP Request to %s failed, error id: %s", event.request().uri(), uuid)`, and Quarkus REST's
 * own `Request failed` line, reached only when no mapper matches.
 *
 * **One handler, and deliberately not an `ExceptionMapper<Throwable>`.** Quarkus REST hands an exception no mapper
 * matches to the routing context's failure (`QuarkusResteasyReactiveRequestContext.handleUnrecoverableError` →
 * `RoutingContext.fail`), so this one handler is reached by both. A JAX-RS mapper for `Throwable` would be a mapper for
 * a supertype of `NotFoundException`, and with one present Quarkus REST answers an unmatched route itself instead of
 * passing it on (`RestInitialHandler`, Quarkus 3.36.0): the app shell's SPA fallback and the admin channel's
 * unknown-route refusal both live behind that pass (the same trap [EmptyBadRequestFilter]'s KDoc records). And it
 * would also receive every bodiless `WebApplicationException` the boundary filters answer. So the JAX-RS side adds only
 * [ResourceTemplateFilter], which hands this handler the matched resource's template.
 *
 * What it leaves to the next handler: a failure with no exception (`fail(status)`, Quarkus answers the status), the
 * security exceptions Quarkus answers with a challenge or a bare status, and a worker pool's refusal (a fixed 503
 * line). A failure Vert.x raised with a client status (an [HttpException] below 500) answers that status, logged only
 * at DEBUG: it is the request's fault, not the instance's. An [IOException] is a client that went away.
 */
@ApplicationScoped
class UnexpectedErrorRoutes(private val mapper: ObjectMapper) {

    fun register(@Observes router: Router) {
        router.route().order(Int.MAX_VALUE - 1).failureHandler(::handle)
    }

    internal fun handle(ctx: RoutingContext) {
        val failure = ctx.failure()
        if (failure == null || passedOn(failure)) {
            ctx.next()
            return
        }
        val clientStatus = (failure as? HttpException)?.statusCode?.takeIf { it in 400..499 }
        if (clientStatus != null || failure is IOException) {
            if (detail.isDebugEnabled) detail.debugf(failure, "Request failed with %s", clientStatus ?: "a closed connection")
            end(ctx, clientStatus ?: FailureStatus.SERVER_ERROR.httpStatus, null)
            return
        }
        val id = UnexpectedError.log(ctx.request().method().name(), ctx.get<String>(ROUTE_KEY) ?: UnexpectedError.NO_TEMPLATE, failure)
        end(ctx, FailureStatus.SERVER_ERROR.httpStatus, mapper.writeValueAsString(httpError(internalError, "error id $id").entity))
    }

    private fun end(ctx: RoutingContext, status: Int, json: String?) {
        val response = ctx.response()
        when {
            response.ended() || response.closed() -> return
            // The head is out — a streamed body failed midway. No answer can follow it; closing tells the client.
            response.headWritten() -> ctx.request().connection().close()
            json == null -> response.setStatusCode(status).end()
            else -> response.setStatusCode(status).putHeader(HttpHeaders.CONTENT_TYPE, "application/json").end(json)
        }
    }

    private fun passedOn(failure: Throwable): Boolean =
        failure is UnauthorizedException || failure is ForbiddenException || failure is AuthenticationException ||
            failure is RejectedExecutionException

    internal companion object {
        /** Where [ResourceTemplateFilter] leaves the matched resource's template for [handle]. */
        const val ROUTE_KEY = "veyq.unexpected-error.route"

        val internalError = httpFailure("internal_error", FailureStatus.SERVER_ERROR)
        private val detail = Logger.getLogger(UnexpectedError.DETAIL_CATEGORY)
    }
}

/**
 * Records the matched resource method's template — its class's and its own `@Path` joined, as Quarkus REST matched
 * them — on the routing context, so [UnexpectedErrorRoutes] can name the route without the URI. Runs first among the
 * post-matching filters; a failure before it (a pre-matching filter's) is logged with [UnexpectedError.NO_TEMPLATE].
 */
@ApplicationScoped
class ResourceTemplateFilter {

    @ServerRequestFilter(priority = Int.MIN_VALUE)
    // The parameter is the resteasy-reactive context, not `ContainerRequestContext`: a void filter method declared
    // with the JAX-RS one is handed a wrapper that guards `abortWith` and does not expose the server context
    // (measured, Quarkus 3.36.0), so the template went unrecorded and every line read `-`.
    fun record(ctx: ResteasyReactiveContainerRequestContext) {
        val request = ctx.serverRequestContext as? ResteasyReactiveRequestContext ?: return
        val target = request.target ?: return
        val routing = request.serverRequest().unwrap(RoutingContext::class.java) ?: return
        routing.put(UnexpectedErrorRoutes.ROUTE_KEY, template(target.classPath?.template, target.path?.template))
    }

    internal companion object {
        fun template(classPath: String?, methodPath: String?): String =
            ("/" + listOfNotNull(classPath, methodPath).joinToString("/")).replace(Regex("/{2,}"), "/").let {
                if (it.length > 1) it.trimEnd('/') else it
            }
    }
}
