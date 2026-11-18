package me.raddatz.veyq.mobile.bridge

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url

/**
 * A debug build's request log on Linux and Windows (inbox #947), Android's `DevNetLog` for the bridge's hosts: every request
 * the core sends to a host outside the developer's `localhost`/`*.localhost` instance — a map tile, a geocode, an exchange
 * rate table, the object store where it is not local — as one line once answered: its method and address, the
 * `User-Agent` actually sent, and whether a `Referer`, a `Cookie` or an `Authorization` header went with it (their
 * presence only, never a value), and the status. A presigned address's query is a bearer credential until it lapses, so it
 * is never written: such a query is said as `?<presigned>`.
 *
 * Installed on every client the core makes only where the host's [Config.netLog] asked for it (its debug build), and each
 * line goes to the host ([HostRequest.NetLogLine]); a release build's config asks for none, so nothing is installed.
 */
internal object NetLog {
    /** The line for a request of [method] to [url] with [headers], answered [status]; null for the developer's own instance. */
    fun line(method: String, url: Url, headers: Headers, status: Int): String? {
        val host = url.host
        if (host == "localhost" || host.endsWith(".localhost")) return null
        fun has(name: String) = if (headers[name] != null) "yes" else "no"
        val query = url.encodedQuery.takeIf { it.isNotEmpty() }?.let { q ->
            if (url.parameters.names().any { it.startsWith("X-Amz-", ignoreCase = true) }) "?<presigned>" else "?$q"
        }.orEmpty()
        return "$method ${url.protocol.name}://$host${url.encodedPath}$query ua=[${headers[HttpHeaders.UserAgent]}] " +
            "referer=${has(HttpHeaders.Referrer)} cookie=${has(HttpHeaders.Cookie)} authorization=${has(HttpHeaders.Authorization)} -> $status"
    }

    /** The client plugin that hands each line to [write]. */
    fun plugin(write: (String) -> Unit) = createClientPlugin("veyq-net") {
        onResponse { response ->
            val r = response.call.request
            line(r.method.value, r.url, r.headers, response.status.value)?.let(write)
        }
    }
}
