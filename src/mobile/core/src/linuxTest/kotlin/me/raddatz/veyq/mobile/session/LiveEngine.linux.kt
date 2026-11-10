package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl

internal actual val liveEngineName: String = "linux (curl)"

internal actual fun liveHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Curl, block)
