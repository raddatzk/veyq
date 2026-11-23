package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.plugins.HttpSend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * The HTTP client every live case (`VEYQ_LIVE`) builds, the one place a live run meets the instance's per-IP budget
 * (inbox #1191): the login challenge and verify, a token refresh, a link's redeem and the other pre-auth calls spend
 * `veyq.ratelimit.auth.*` (60 a minute by default, ADR 0051), which every browser, agent and live run on this machine
 * shares, and a module's live suite comes close to it on its own. A call the budget turned back — a `429` naming its
 * `Retry-After` — is sent again after that wait, as long as the waits for that one call stay within
 * [MAX_WAIT_SECONDS]; past it, or on a `429` without the header, the `429` reaches the case as before. The bucket refills
 * a token a second (ADR 0052), so a drained one answers `Retry-After: 1`, and the token freed is the first caller's —
 * one wait is not enough while a case's other calls and a neighbour's queue for it (measured 2026-11-23: one wait each,
 * a drained budget, seven of nine throwaway classes still red). The server turns such a call back before its handler
 * runs, so sending it again repeats nothing. The wait is the wall clock's, on `Dispatchers.IO`: a live case runs in
 * `runTest`, whose virtual time skips a plain `delay` (measured 2026-11-23: twenty sends in no time, every class red with
 * Ktor's `Max send count 20 exceeded`). Nothing else is retried: a failure a case provokes (a cut network, a
 * refused right) reaches it unchanged.
 */
object LiveHttp {
    /** The most a turned-back call waits in all: one window of the default budget and a little more. */
    const val MAX_WAIT_SECONDS = 65L

    /** OkHttp with the core's [block], the budget's wait after it ([budgetWait]). */
    fun client(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
        block()
        // a call waited out for its whole bound is sent at most every second: room for that, beside the core's own sends
        install(HttpSend) { maxSendCount = (2 * MAX_WAIT_SECONDS).toInt() }
        install(budgetWait)
    }

    /** The seconds a `429` asks to wait, where it is one and its `Retry-After` is a number of seconds. */
    fun waitFor(status: HttpStatusCode, retryAfter: String?): Long? =
        retryAfter?.trim()?.toLongOrNull()?.takeIf { status == HttpStatusCode.TooManyRequests && it >= 0 }

    class Bound {
        /** The most one call waits in all, its waits summed (each its `Retry-After` and a second). */
        var maxWaitSeconds: Long = MAX_WAIT_SECONDS
    }

    /** The wait itself, a plugin of its own so `LiveHttpTest` holds it over a played server (with a shorter [Bound]). */
    val budgetWait = createClientPlugin("LiveBudgetWait", ::Bound) {
        val bound = pluginConfig.maxWaitSeconds
        on(Send) { request ->
            var call = proceed(request)
            var waited = 0L
            while (true) {
                val wait = (waitFor(call.response.status, call.response.headers[HttpHeaders.RetryAfter]) ?: break) + 1
                if (waited + wait > bound) break
                System.err.println("veyq live: ${request.method.value} ${request.url.build().encodedPath} answered 429 (the per-IP budget, ADR 0051): sent again in $wait s")
                withContext(Dispatchers.IO) { delay(wait.seconds) }
                waited += wait
                call = proceed(request)
            }
            call
        }
    }
}
