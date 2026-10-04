package com.veyq.core.adminchannel

import com.veyq.core.config.VeyqConfig
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.quarkus.vertx.http.runtime.VertxHttpRecorder
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * **The admin listener** (ADR 0065, uc.admin.admin-channel): a second Vert.x HTTP server, and the one door the
 * Server-Owner operations answer on. Its request handler is Quarkus' own root handler — the one Quarkus installs
 * on its HTTP, TLS and domain-socket servers — so a request here runs the identical pipeline: forwarding,
 * security, JAX-RS, the app shell. What makes a request *admin-channel* is decided per request by [AdminChannel],
 * from the socket; this class only opens the door.
 *
 * **It opens on `StartupEvent`, not on Quarkus' `HttpServerStart`, and the difference is two properties.**
 * `HttpServerStart` would hand over the main server's options to copy, but it is fired *asynchronously, after* the
 * main socket is bound — so a bind failure there can no longer fail startup (uc E2) — and it is fired once per
 * server start, which a dev-mode hot reload does not repeat: the listener closed by the reload's `ShutdownEvent`
 * would never come back. `StartupEvent` runs synchronously before the main socket opens (Quarkus' build steps
 * order it `finalizeRouter → StartupEvent → openSocket`), and again after every hot reload. The root handler is
 * already final by then; [VertxHttpRecorder.getRootHandler] is a stable delegator that reads the live one per
 * request, which is what keeps this door on the reloaded application. Measured in `quarkusDev`: after a reload
 * this listener answers with the new code. The one difference from the main port is the request that TRIGGERS a
 * reload by arriving here — it loses its connection, because this server closes with the old application, while
 * the main port holds such a request across the restart. Dev-only; the next request is answered.
 *
 * **Its options are Vert.x defaults plus the main listener's host.** The main server's options are not copied —
 * Quarkus builds them inside `openSocket`, after this has run — and they do not need to be: the body-size limit
 * is enforced in the root handler, not by the server options, and the admin surface sends a bearer token and a
 * cookie, far below any header limit.
 */
@ApplicationScoped
class AdminListener(
    private val vertx: Vertx,
    private val config: VeyqConfig,
    @param:ConfigProperty(name = "quarkus.http.host") private val host: String,
) {
    @Volatile
    private var server: HttpServer? = null

    /** The port the listener is bound to — the configured one, or the free one `0` asked for (`%test`). */
    fun port(): Int = checkNotNull(server) { "the admin listener is not open" }.actualPort()

    fun open(@Observes event: StartupEvent) {
        // Uncaught on purpose: an exception out of a StartupEvent observer fails startup, which is uc E2.
        server = bind(vertx, host, config.adminChannel().port(), VertxHttpRecorder.getRootHandler())
        log.infof("admin listener on %s:%d — the Server-Owner operations answer only here", host, port())
    }

    fun close(@Observes event: ShutdownEvent) {
        // Awaited, so a dev-mode hot reload's next StartupEvent finds the port free again.
        server?.close()?.toCompletionStage()?.toCompletableFuture()?.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        server = null
    }

    companion object {
        private val log: Logger = Logger.getLogger(AdminListener::class.java)
        private const val TIMEOUT_SECONDS = 30L

        /** Binds, or throws naming the address — never answers with a half-open door. */
        fun bind(vertx: Vertx, host: String, port: Int, handler: Handler<HttpServerRequest>): HttpServer =
            try {
                vertx.createHttpServer(HttpServerOptions().setHost(host).setPort(port))
                    .requestHandler(handler)
                    .listen()
                    .toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw IllegalStateException(
                    "the admin listener could not bind $host:$port (veyq.admin-channel.port) — without it nobody " +
                        "can set up or administer this instance, so the backend does not start",
                    e.cause ?: e,
                )
            }
    }
}
