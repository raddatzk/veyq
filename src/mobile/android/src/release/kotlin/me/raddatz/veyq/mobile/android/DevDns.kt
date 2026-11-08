package me.raddatz.veyq.mobile.android

import okhttp3.Dns

/** A release build resolves every host through the system. */
object DevDns {
    val resolver: Dns? = null
}
