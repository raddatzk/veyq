package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A debug build's request log (inbox #947, Android's `DevNetLog`): one line per request to an outside host as it was
 * answered — its address, the `User-Agent` sent, whether a `Referer`, cookie or `Authorization` went (never a value) —,
 * none for the developer's `*.localhost` instance, and a presigned address's query never written.
 *
 * Bites: the `*.localhost` check removed → the instance's own request logged, red here; the presigned query written as it
 * is → the signature in the line, red here.
 */
class NetLogTest {
    @Test
    fun eachOutsideRequestIsOneLineAndNoValueOfAHeaderOrAPresignedQueryIsWritten() = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        val client = HttpClient(MockEngine { r -> respond("", if (r.url.host == "tile.openstreetmap.org") HttpStatusCode.OK else HttpStatusCode.NotModified) }) {
            expectSuccess = false
            install(NetLog.plugin { lines += it })
        }
        client.get("https://tile.openstreetmap.org/12/2200/1343.png") { header(HttpHeaders.UserAgent, "veyq/1 (+https://veyq.app; http://veyq.localhost:8080)") }
        client.get("https://nominatim.openstreetmap.org/reverse?lat=52.520&lon=13.405&format=jsonv2") {
            header(HttpHeaders.UserAgent, "veyq/1")
            header(HttpHeaders.Authorization, "Bearer SECRET-TOKEN")
            header(HttpHeaders.Cookie, "a=SECRET-COOKIE")
        }
        client.get("http://veyq.localhost:8080/api/setup")
        client.get("https://store.example/bucket/blob?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=SECRET-SIGNATURE")
        assertEquals(
            listOf(
                "GET https://tile.openstreetmap.org/12/2200/1343.png ua=[veyq/1 (+https://veyq.app; http://veyq.localhost:8080)] referer=no cookie=no authorization=no -> 200",
                "GET https://nominatim.openstreetmap.org/reverse?lat=52.520&lon=13.405&format=jsonv2 ua=[veyq/1] referer=no cookie=yes authorization=yes -> 304",
                "GET https://store.example/bucket/blob?<presigned> ua=[null] referer=no cookie=no authorization=no -> 304",
            ),
            lines.toList(),
        )
        client.close()
    }
}
