package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException

/** What a third party answered: its status, its headers by lower-case name, its body as sent. */
class OutsideAnswer(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * The core's one door to a third party (ADR 0143 D3, ADR 0151 decision 6) — the map's services and the breached-password
 * check's range service: a plain `GET` through the account's `outside` client — no bearer, no cookie — carrying
 * `User-Agent: veyq/<version> (+https://veyq.app; <instance origin>)` and nothing that names the person: no `Referer`, no
 * cookie, no token. The instance's origin is what the web's `Referer` or `Origin` already tells the same services, so
 * their per-application limits stay per instance (the tile policy blocks a library's default agent, which is what Ktor
 * would send otherwise). Only `https` addresses are asked.
 */
class OutsideHttp(private val client: HttpClient, instanceOrigin: String) {
    /** D3's identification of this app at this instance. */
    val userAgent: String = userAgent(instanceOrigin)

    /** The answer to a `GET` of [url] with [headers] beside the identification, or null where it was not reached or
     *  is no `https` address. Never throws but for the caller's own cancellation. */
    suspend fun get(url: String, headers: List<Pair<String, String>> = emptyList()): OutsideAnswer? {
        if (!url.startsWith("https://")) return null
        return try {
            val r = client.get(url) {
                header(HttpHeaders.UserAgent, userAgent)
                headers.forEach { (k, v) -> header(k, v) }
            }
            val names = r.headers.names().associate { it.lowercase() to (r.headers[it] ?: "") }
            OutsideAnswer(r.status.value, names, r.readRawBytes())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** The app's site, named in the identification so a service can find who we are. */
        const val SITE = "https://veyq.app"

        fun userAgent(instanceOrigin: String): String = "veyq/${VeyqAccount.MAJOR} (+$SITE; ${instanceOrigin.trimEnd('/')})"
    }
}
