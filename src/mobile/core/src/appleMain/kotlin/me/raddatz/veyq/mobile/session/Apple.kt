package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.navigation.Connections
import me.raddatz.veyq.mobile.navigation.TenantRef

/** The Apple app's door into its accounts: libsodium's primitives and the platform's HTTP client (URLSession). */
object AppleSessions {
    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun signIn(server: String, email: String, password: String, secretKey: String): VeyqAccount =
        VeyqAccount.signIn(LibsodiumPrimitives, server, email, password, secretKey) { block -> HttpClient(Darwin, block) }

    /** The servers the app is signed in to, typed for Swift (a generic class is awkward across the boundary). */
    fun connections(): Connections<VeyqAccount> = Connections()

    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun open(account: VeyqAccount, tenant: TenantRef): TenantSession = account.open(tenant)
}
