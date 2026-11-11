package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.LinuxDeviceKeys

// CIO has no TLS on Kotlin/Native; Curl is linked in statically (ADR 0123's Consequences, the core on Linux).
internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Curl, block)

/** The Secret Service through libsecret, loaded at run time; without it nothing is kept (`StaySignedIn.NONE`). */
internal actual fun platformDeviceKeys(config: Config, store: DeviceStore): DeviceKeys = LinuxDeviceKeys()
