package me.raddatz.veyq.mobile.android

import android.util.Log
import okhttp3.Interceptor

/**
 * Debug builds only: the request log of a device pass (ADR 0143's phase 9). Every request to a host outside the
 * developer's `*.localhost` instance — a map tile, a geocode, an exchange-rate table — goes to logcat (`veyq-net`) as it
 * leaves OkHttp: its method, host and path, the `User-Agent` actually sent, and whether a `Referer`, a `Cookie` or an
 * `Authorization` header went with it — their presence only, never a value. The instance's own requests (and the
 * store's presigned URLs, whose query is a credential) are not logged.
 */
object DevNetLog {
    val interceptor: Interceptor? = Interceptor { chain ->
        val r = chain.request()
        val host = r.url.host
        val response = chain.proceed(r)
        if (host != "localhost" && !host.endsWith(".localhost")) {
            fun has(name: String) = if (r.header(name) != null) "yes" else "no"
            Log.i(
                "veyq-net",
                "${r.method} https://$host${r.url.encodedPath}${r.url.encodedQuery?.let { "?$it" } ?: ""} " +
                    "ua=[${r.header("User-Agent")}] referer=${has("Referer")} cookie=${has("Cookie")} " +
                    "authorization=${has("Authorization")} -> ${response.code}",
            )
        }
        response
    }
}
