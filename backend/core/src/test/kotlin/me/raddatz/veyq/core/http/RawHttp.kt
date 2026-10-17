package me.raddatz.veyq.core.http

import java.net.InetAddress
import java.net.Socket

/**
 * One HTTP/1.1 exchange over a raw socket, optionally opened by a PROXY protocol v1 line — the one request shape
 * RestAssured cannot send. What comes back is every byte read until the server closes: an empty string is "closed
 * with no status line", which is how Vert.x answers a connection that does not open with the PROXY header it expects.
 */
object RawHttp {

    fun exchange(
        port: Int,
        proxyLine: String?,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
        jsonBody: String? = null,
    ): String =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 20_000
            val body = jsonBody?.toByteArray(Charsets.UTF_8)
            val request = buildString {
                proxyLine?.let { append(it).append("\r\n") }
                append(if (body == null) "GET" else "POST").append(" $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                body?.let { append("Content-Type: application/json\r\nContent-Length: ${it.size}\r\n") }
                append("\r\n")
                body?.let { append(String(it, Charsets.UTF_8)) }
            }
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    /**
     * The status code of [response], or null when it holds no status line. HTTP/1.0 too: Vert.x answers a request
     * line it cannot parse — a PROXY line with the protocol off — `HTTP/1.0 400` (measured).
     */
    fun status(response: String): Int? = Regex("""^HTTP/1\.[01] (\d{3}) """).find(response)?.groupValues?.get(1)?.toInt()

    /** The body of [response], after the blank line that ends its head. */
    fun body(response: String): String = response.substringAfter("\r\n\r\n", "")
}
