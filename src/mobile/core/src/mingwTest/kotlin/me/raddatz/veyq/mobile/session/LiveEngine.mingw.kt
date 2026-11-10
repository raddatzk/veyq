package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.winhttp.WinHttp

internal actual val liveEngineName: String = "windows (winhttp)"

internal actual fun liveHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(WinHttp, block)
