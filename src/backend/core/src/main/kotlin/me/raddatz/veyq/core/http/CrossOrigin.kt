package me.raddatz.veyq.core.http

import io.vertx.core.MultiMap
import io.vertx.ext.web.RoutingContext

/**
 * **A response another origin may read** (ADR 0121 decision 3): the kernel half of the share API's CORS. Whoever decides
 * that a request may be answered cross-origin (`ShareCrossOrigin`, the sharing slice: the path, the switch, the link's
 * tenant) marks its routing context with [grant] before the response is written; the app shell's headers-end handler,
 * which already writes the isolation headers on every response, asks [isGranted] and writes [CORS] headers and a
 * `Cross-Origin-Resource-Policy: cross-origin` instead of `same-origin`. Split this way because the decision needs the
 * database and the header is written on the event loop, where a database read is refused — and because the kernel names
 * no slice.
 *
 * **Never credentials.** `Access-Control-Allow-Credentials` is written nowhere, so a browser never sends a cookie or reads
 * a credentialed answer cross-origin; `Authorization` is never an allowed header ([ALLOW_HEADERS]), so no bearer crosses
 * either. Every operation a grant can reach takes neither: the link is its whole capability (ADR 0076).
 */
object CrossOrigin {
    private const val KEY = "veyq.cross-origin"

    const val ALLOW_ORIGIN = "Access-Control-Allow-Origin"
    const val EXPOSE_HEADERS = "Access-Control-Expose-Headers"
    const val ALLOW_METHODS = "Access-Control-Allow-Methods"
    const val ALLOW_HEADERS_HEADER = "Access-Control-Allow-Headers"
    const val MAX_AGE = "Access-Control-Max-Age"
    const val REQUEST_METHOD = "Access-Control-Request-Method"

    /** The request headers a guest operation reads (ADR 0121 decision 3) — a preflight's answer. `ApiMethodIndexContractTest`
     *  holds it to every header parameter the contract's guest operations under `/api/shares/{shareId}` declare. */
    val ALLOW_HEADERS: List<String> = listOf("X-Share-Password", "X-Contributor-Token", "X-Share-Write", "If-None-Match", "Content-Type")

    /**
     * How long a browser may keep a preflight's answer, in seconds. Short on purpose but not zero: every guest call
     * carries a share header, so without a cache each one costs a round trip first. A switch turned off is not held up by
     * it — the actual answer then carries no `Access-Control-Allow-Origin` and the browser refuses to read it, cached
     * preflight or not — so the age bounds only how long a method the server stopped offering stays offered.
     */
    const val MAX_AGE_SECONDS = 600

    /** Mark [ctx]'s response as readable by any origin. */
    fun grant(ctx: RoutingContext) {
        ctx.put(KEY, true)
    }

    fun isGranted(ctx: RoutingContext): Boolean = ctx.get<Boolean>(KEY) == true

    /** The headers every granted answer carries, a refusal included, so a browser can read a `404` or a `403` too. */
    fun writeTo(headers: MultiMap) {
        headers.set(ALLOW_ORIGIN, "*")
        headers.set(EXPOSE_HEADERS, "ETag")
    }
}
