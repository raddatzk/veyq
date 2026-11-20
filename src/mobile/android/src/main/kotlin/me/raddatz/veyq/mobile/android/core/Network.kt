package me.raddatz.veyq.mobile.android.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import me.raddatz.veyq.mobile.android.DevDns
import me.raddatz.veyq.mobile.android.DevNetLog
import okhttp3.CookieJar

/**
 * The app's HTTP client: OkHttp, with the debug build's resolver for the developer's `*.localhost` instance. It keeps
 * nothing on disk (`src/mobile/CLAUDE.md` § Nothing on disk the app did not choose): no response cache and no cookie
 * jar. Both are OkHttp's defaults, pinned here so the rule does not rest on a default. A debug build logs what leaves
 * for an outside host (`DevNetLog`, a pass's request log).
 */
object Network {
    fun client(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
        engine {
            (this as OkHttpConfig).config {
                cache(null)
                cookieJar(CookieJar.NO_COOKIES)
                DevDns.resolver?.let { dns(it) }
                DevNetLog.interceptor?.let { addNetworkInterceptor(it) }
            }
        }
        block()
    }
}
