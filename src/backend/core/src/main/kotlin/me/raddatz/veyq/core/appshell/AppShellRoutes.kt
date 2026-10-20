package me.raddatz.veyq.core.appshell

import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.config.VeyqConfig
import io.quarkus.vertx.http.runtime.RouteConstants
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger

/**
 * Reads the shell's two files from where the release image puts `src/web/dist` — the same classpath root Quarkus
 * serves static files from. A bean of its own only so a test can stand in a classpath with no shell on it.
 */
@ApplicationScoped
class AppShellFiles {
    fun read(name: String): ByteArray? =
        Thread.currentThread().contextClassLoader.getResourceAsStream("META-INF/resources/$name")?.use { it.readBytes() }
}

/**
 * **The app shell** (ADR 0069): serves the web client the way Quarkus' static handler alone cannot — an SPA
 * fallback, the policy as a response header, the security headers and a revalidated shell.
 *
 * It hooks in at the Vert.x router, not as a JAX-RS catch-all, for two reasons: a catch-all resource would compete
 * with the API's own path matching, and under `deny-unannotated-endpoints` it would need a `@PermitAll`. A route
 * also sees what JAX-RS never does — static files and Vert.x's own 404 — which is exactly where these headers
 * must go. The fallback does sit **behind** JAX-RS, though (the static handler sits in front of it), so a
 * pre-matching JAX-RS filter sees a deep link and never `/`. `TenantSuspensionFilter` confines itself to `/api`
 * for exactly that reason; a new pre-matching filter has to as well, or deep links and `/` part ways.
 */
@ApplicationScoped
class AppShellRoutes(
    config: VeyqConfig,
    private val blobStore: BlobStore,
    files: AppShellFiles,
    private val external: ExternalOrigins,
) {
    private val policy: String? = files.read(AppShell.POLICY_FILE)?.toString(Charsets.UTF_8)

    // Decided at boot: whether there is a shell, and that its policy is whole. Its store origin is NOT — the store is
    // a runtime setting (ADR 0089 decision 5), so the header's policy is read per response through [cspNow].
    private val shell: AppShell = AppShell.from(
        indexHtml = files.read(AppShell.INDEX_FILE),
        policy = policy,
        storeOrigin = blobStore.storeOrigin,
    )

    init {
        // Fail closed for every store a Server Owner may set later, not only the one at boot: a policy with no
        // `connect-src` cannot take a store origin, and learning that at the first request after a setting changes
        // would be a 500 on every response.
        // The same for the outside services a Server Owner may configure later (ADR 0088 decision 7): the policy must
        // carry an `img-src` for the map tiles and a `connect-src` for the geocoder and the breach check.
        if (shell is AppShell.Present) {
            AppShell.withOrigins(policy!!, listOf("https://boot-check.invalid"), listOf("https://boot-check.invalid"))
        }
    }

    private class Emitted(val storeOrigin: String?, val external: ExternalOriginSet, val csp: String)

    @Volatile
    private var emitted: Emitted? = null

    /** The policy for right now: the base policy plus the origin of the store the backend signs against and the origins
     *  of the outside services in use ([ExternalOrigins], a snapshot, so no database is read here). [emitted] is a memo
     *  checked against both on every call, so a racing response that stores the policy of an older state costs the
     *  next one a rebuild, never a stale header (inbox #445). */
    private fun cspNow(): String {
        val origin = blobStore.storeOrigin
        val services = external.current()
        emitted?.let { if (it.storeOrigin == origin && it.external == services) return it.csp }
        return AppShell.withOrigins(policy!!, listOfNotNull(origin) + services.connect, services.img)
            .also { emitted = Emitted(origin, services, it) }
    }

    private val hsts: String? = config.http().hsts().let { if (it.enabled()) "max-age=${it.maxAge().seconds}" else null }

    fun register(@Observes router: Router) {
        when (shell) {
            AppShell.Absent -> log.info("app shell: no web client on the classpath — serving the API only")
            is AppShell.Present -> log.infof("app shell: serving the web client; policy %s", cspNow())
        }
        // First, so the end handler is registered before anything can write a response — including a JAX-RS
        // error, a static file and Vert.x's own 404.
        router.route().order(RouteConstants.ROUTE_ORDER_HEADERS).handler { ctx ->
            ctx.addHeadersEndHandler { onHeadersEnd(ctx) }
            ctx.next()
        }
        if (shell is AppShell.Present) {
            // Last of all. The fallback's contract is "nothing else answers", and the only way to know that
            // without a second copy of every route is to be reached only once everything else has passed: the
            // static handler (ROUTE_ORDER_DEFAULT) and JAX-RS both call next() on a path they do not serve.
            router.route().order(Int.MAX_VALUE).method(HttpMethod.GET).method(HttpMethod.HEAD)
                .handler { ctx -> fallback(ctx, shell) }
        }
    }

    private fun onHeadersEnd(ctx: RoutingContext) {
        val headers = ctx.response().headers()
        headers.set(NOSNIFF_HEADER, "nosniff")
        // Never `no-referrer`: the default tile and geocoder providers require a Referer from a web page
        // (ADR 0016). This value sends the origin cross-origin, never a path.
        headers.set(REFERRER_POLICY_HEADER, "strict-origin-when-cross-origin")
        hsts?.let { headers.set(HSTS_HEADER, it) }

        val shell = shell as? AppShell.Present ?: return
        val path = ctx.normalizedPath()
        if (isUnder(path, API_PREFIX)) return
        // Every response the shell serves, sw.js included: a worker takes its policy from its own script's
        // response, so this is what puts the playback Service Worker under the policy.
        headers.set(CSP_HEADER, cspNow())
        // Revalidate everything but the content-hashed chunks, whose name changes whenever their bytes do. An
        // `immutable` index.html or sw.js survives an upgrade by a day and asks for chunks the new image lacks.
        if (!path.startsWith(ASSETS_PREFIX)) headers.set(HttpHeaders.CACHE_CONTROL, "no-cache")
    }

    private fun fallback(ctx: RoutingContext, shell: AppShell.Present) {
        val path = ctx.normalizedPath()
        // A script or fetch never asks for HTML, so a missing chunk stays a 404 instead of HTML parsed as script.
        // The router's own catch-all sends an unknown path to sign-in, so the paths are not listed here.
        if (isUnder(path, API_PREFIX) || isUnder(path, MEDIA_PREFIX) || isUnder(path, DOWNLOAD_PREFIX) || !acceptsHtml(ctx)) {
            ctx.next()
            return
        }
        val response = ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
        if (ctx.request().method() == HttpMethod.HEAD) {
            response.putHeader(HttpHeaders.CONTENT_LENGTH, shell.indexHtml.size.toString()).end()
        } else {
            response.end(Buffer.buffer(shell.indexHtml))
        }
    }

    private companion object {
        val log: Logger = Logger.getLogger(AppShellRoutes::class.java)

        const val API_PREFIX = "/api"

        /** The playback Service Worker's own namespace (ADR 0047): a request it did not intercept is not a page. */
        const val MEDIA_PREFIX = "/__media"

        /** The same Service Worker's download route (ADR 0047 § The download route): one-shot, a second open is no page. */
        const val DOWNLOAD_PREFIX = "/__download"
        const val ASSETS_PREFIX = "/assets/"

        const val CSP_HEADER = "Content-Security-Policy"
        const val NOSNIFF_HEADER = "X-Content-Type-Options"
        const val REFERRER_POLICY_HEADER = "Referrer-Policy"
        const val HSTS_HEADER = "Strict-Transport-Security"

        fun isUnder(path: String, prefix: String) = path == prefix || path.startsWith("$prefix/")

        /** `Accept` names `text/html` itself. A wildcard does not count: every fetch sends one. */
        fun acceptsHtml(ctx: RoutingContext): Boolean =
            ctx.request().headers().getAll(HttpHeaders.ACCEPT)
                .flatMap { it.split(',') }
                .any { it.substringBefore(';').trim().equals("text/html", ignoreCase = true) }
    }
}
