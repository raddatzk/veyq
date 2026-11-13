package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.api.apis.TokenApi
import me.raddatz.veyq.mobile.api.models.RefreshRequest
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The handed-over token is presented exactly once (ADR 0004's clone case revokes the family on a second presentation).
 * OkHttp — the engine of the Android app and of the JVM tests — retries silently on a connection failure by default
 * (`retryOnConnectionFailure`), which a review named as a risk for this one POST. A server that reads the POST and drops
 * the connection without answering counts what reaches it: the redeem ([VeyqAccount.redeemOnce]) must reach it once.
 * Measured 2026-11-13: the generated call over a shared client reached it once too — OkHttp re-sends only where the
 * request cannot have reached the server (a failed connect, a stale pooled connection), and the redeem's own client
 * has no pooled connection.
 */
class OneShotRedeemTest {
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(OkHttp, block) }

    /** A server on a free port that reads each request's head and body and closes without answering; the POSTs it saw. */
    private fun dropping(): Pair<Int, AtomicInteger> {
        val server = ServerSocket(0)
        val seen = AtomicInteger()
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val input = it.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: return@use
                            if (line.startsWith("POST")) seen.incrementAndGet()
                            if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
                            if (line.isEmpty()) break
                        }
                        repeat(length) { input.read() }
                    }
                }
            }
        }
        return server.localPort to seen
    }

    private fun http() = client {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun the_generated_call_is_retried_by_okhttp_the_redeem_is_sent_once() = runBlocking {
        val (port, seen) = dropping()
        val base = "http://127.0.0.1:$port"
        http().use { h -> runCatching { TokenApi(base, h).refreshSession(RefreshRequest("t")) } }
        val generated = seen.getAndSet(0)
        println("OneShotRedeem: the generated refresh reached a dropping server $generated time(s)")

        runCatching { VeyqAccount.redeemOnce(base, "t", client) }
        assertEquals(1, seen.get(), "the handed token was presented more than once")
    }
}
