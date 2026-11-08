package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.navigation.Connections
import me.raddatz.veyq.mobile.navigation.TenantRef
import platform.Foundation.NSUserDefaults

/** The device's trust marks in UserDefaults: no secret (`TrustMarks`), kept across launches, gone with the app. */
object UserDefaultsStore : DeviceStore {
    private val defaults get() = NSUserDefaults.standardUserDefaults
    override fun read(key: String): String? = defaults.stringForKey(key)
    override fun write(key: String, value: String) = defaults.setObject(value, key)
    override fun remove(key: String) = defaults.removeObjectForKey(key)
}

/** The Apple app's door into its accounts: libsodium's primitives and the platform's HTTP client (URLSession). */
object AppleSessions {
    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun signIn(server: String, email: String, password: String, secretKey: String): VeyqAccount =
        VeyqAccount.signIn(LibsodiumPrimitives, server, email, password, secretKey, UserDefaultsStore) { block -> HttpClient(Darwin, block) }

    /** The servers the app is signed in to, typed for Swift (a generic class is awkward across the boundary). */
    fun connections(): Connections<VeyqAccount> = Connections()

    @Throws(SignInFailure::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun open(account: VeyqAccount, tenant: TenantRef): TenantSession = account.open(tenant)
}
