package me.raddatz.veyq.mobile.android

import okhttp3.Dns
import java.net.InetAddress

/**
 * Debug builds only: a dev tenant is a `*.localhost` host (src/web/CLAUDE.md), which the emulator resolves to itself.
 * The developer's machine is 10.0.2.2 from inside the emulator, so the dev host goes there and keeps its name in the
 * Host header — the backend picks the tenant from it.
 */
object DevDns {
    val resolver: Dns? = Dns { host ->
        if (host == "localhost" || host.endsWith(".localhost")) listOf(InetAddress.getByName("10.0.2.2")) else Dns.SYSTEM.lookup(host)
    }
}
