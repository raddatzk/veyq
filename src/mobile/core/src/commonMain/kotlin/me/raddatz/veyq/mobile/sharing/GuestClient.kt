package me.raddatz.veyq.mobile.sharing

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.session.VeyqAccount

/**
 * The guest's way to one link's instance (ADR 0150 decision 2): an HTTP client with **no bearer** — a guest is no account,
 * and every guest call under `/api/shares/{shareId}` takes none — over the platform's engine (`AccountDoor`'s factory,
 * the same no-disk clients, `src/mobile/CLAUDE.md` § Nothing on disk), and the suite. Signed in or not, a link is opened
 * through one of these, never through the account's client: the share's instance may be another, and this instance's
 * own share page asks no session either. Closed with the open link.
 */
class GuestClient(val origin: String, val suite: V1, internal val http: HttpClient) {
    /** A fresh client id for a write (an identity, a node): a UUID v4 from the suite's randomness. */
    internal fun uuid(): String {
        val b = suite.p.randomBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = me.raddatz.veyq.mobile.crypto.hex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    fun close() = http.close()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** A guest's client of [origin] over the platform's [client] factory. */
        fun of(primitives: Primitives, origin: String, client: (block: HttpClientConfig<*>.() -> Unit) -> HttpClient): GuestClient =
            GuestClient(
                origin.trimEnd('/'), V1(primitives),
                client {
                    expectSuccess = false
                    install(ContentNegotiation) { json(JSON) }
                    install(io.ktor.client.plugins.HttpTimeout) {
                        connectTimeoutMillis = VeyqAccount.CONNECT_MS
                        socketTimeoutMillis = VeyqAccount.SERVER_WORK_MS
                    }
                },
            )
    }
}
