package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.winhttp.WinHttp

// WinHTTP: TLS is Schannel's and the CA store the system's, so the library carries no OpenSSL (ADR 0127 decision 5).
internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(WinHttp, block)
