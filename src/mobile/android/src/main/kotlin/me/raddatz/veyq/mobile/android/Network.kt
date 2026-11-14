package me.raddatz.veyq.mobile.android

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.CookieJar

/**
 * The app's HTTP client: OkHttp, with the debug build's resolver for the developer's `*.localhost` instance. It keeps
 * nothing on disk (`src/mobile/CLAUDE.md` § Nothing on disk the app did not choose): no response cache and no cookie
 * jar. Both are OkHttp's defaults, pinned here so the rule does not rest on a default.
 */
object Network {
    fun client(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
        engine {
            (this as OkHttpConfig).config {
                cache(null)
                cookieJar(CookieJar.NO_COOKIES)
                DevDns.resolver?.let { dns(it) }
            }
        }
        block()
    }
}
