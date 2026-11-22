package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.winhttp.WinHttp
import me.raddatz.veyq.mobile.session.DeviceKeys
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.WindowsDeviceKeys

// WinHTTP: TLS is Schannel's and the CA store the system's, so the library carries no OpenSSL (ADR 0136 decision 5).
internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(WinHttp, block)

/** DPAPI and Windows Hello, Hello's prompt owned by the host's window and in its words. */
internal actual fun platformDeviceKeys(config: Config, store: DeviceStore): DeviceKeys = WindowsDeviceKeys(store, config.window, config.prompt)

/** The Credential Locker (WinRT's PasswordVault): a credential per guest session under its hashed name. */
internal actual fun platformGuestSessions(): me.raddatz.veyq.mobile.sharing.GuestSessions = me.raddatz.veyq.mobile.sharing.WindowsGuestSessions()

/** Whether an SVG is drawn as an image: not proven on Windows, so an SVG is shown as its text (inbox #1059). */
internal actual fun platformDrawsSvgAsImage(): Boolean = false
