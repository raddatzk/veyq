package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl

// CIO has no TLS on Kotlin/Native; Curl is linked in statically (ADR 0123's Consequences, the core on Linux).
internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Curl, block)
