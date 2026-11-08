package me.raddatz.veyq.mobile.android

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig

/** The app's HTTP client: OkHttp, with the debug build's resolver for the developer's `*.localhost` instance. */
object Network {
    fun client(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
        engine { (this as OkHttpConfig).config { DevDns.resolver?.let { dns(it) } } }
        block()
    }
}
