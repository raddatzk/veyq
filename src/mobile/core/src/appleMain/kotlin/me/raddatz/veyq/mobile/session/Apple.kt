package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives

/** The Apple app's door into a session: libsodium's primitives and the platform's HTTP client (URLSession). */
object AppleSessions {
    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun signIn(server: String, email: String, password: String, secretKey: String): VeyqSession =
        VeyqSession.signIn(LibsodiumPrimitives, server, email, password, secretKey) { block -> HttpClient(Darwin, block) }
}
