package me.raddatz.veyq.core.admin.instance

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * An in-test HTTP server standing in for a release source — a mirror in `release_assets.py`'s layout or the GitHub
 * Releases API — that records every request it receives, so a test can assert both what the check read and what it
 * did **not** send (no request at all; nothing naming the running release).
 *
 * Each route answers its [Route]: a body, a status, a delay before the answer, or a redirect. A path with no route is
 * a 404, which a test notices as a failed check.
 */
class FakeReleaseSource : AutoCloseable {

    /** One request as it arrived: the method, the path with its query, and every header line. */
    data class Seen(val method: String, val uri: String, val headers: List<String>) {
        fun mentions(text: String): Boolean = uri.contains(text) || headers.any { it.contains(text) }
    }

    data class Route(val status: Int = 200, val body: ByteArray = ByteArray(0), val delayMs: Long = 0, val location: String? = null)

    private val routes = mutableMapOf<String, Route>()
    val seen: MutableList<Seen> = CopyOnWriteArrayList()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newFixedThreadPool(4)
        createContext("/") { exchange ->
            exchange.use {
                seen += Seen(
                    it.requestMethod,
                    it.requestURI.toString(),
                    it.requestHeaders.flatMap { (name, values) -> values.map { v -> "$name: $v" } },
                )
                val route = synchronized(routes) { routes[it.requestURI.path] } ?: Route(status = 404)
                if (route.delayMs > 0) Thread.sleep(route.delayMs)
                route.location?.let { location -> it.responseHeaders.add("Location", location) }
                it.responseHeaders.add("Content-Type", "application/json")
                if (route.body.isEmpty()) {
                    it.sendResponseHeaders(route.status, -1)
                } else {
                    it.sendResponseHeaders(route.status, route.body.size.toLong())
                    it.responseBody.write(route.body)
                }
            }
        }
        start()
    }

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    fun route(path: String, route: Route): FakeReleaseSource = apply { synchronized(routes) { routes[path] = route } }

    fun json(path: String, body: String): FakeReleaseSource = route(path, Route(body = body.toByteArray()))

    /**
     * Serve a mirror under [prefix]: `releases.json` listing [releases] (`tag` to `date`), and a manifest per tag whose
     * major is in [unscheduledTags] marked unscheduled.
     */
    fun mirror(prefix: String, releases: List<Pair<String, String>>, unscheduledTags: Set<String> = emptySet()): FakeReleaseSource = apply {
        val list = releases.joinToString(",") { (tag, date) ->
            """{"tag":"$tag","date":"$date","prerelease":false,"manifest":"$tag/manifest.json"}"""
        }
        json("$prefix/releases.json", """{"releases":[$list]}""")
        releases.forEach { (tag, date) -> json("$prefix/$tag/manifest.json", manifest(tag, date, tag in unscheduledTags)) }
    }

    override fun close() = server.stop(0)

    companion object {
        fun manifest(tag: String, date: String, unscheduled: Boolean): String =
            """{"release":"${tag.removePrefix("v")}","date":"$date","images":{"app":"veyq:local","admin":"veyq-admin:local"},"unscheduled":$unscheduled}"""
    }
}
