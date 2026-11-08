package me.raddatz.veyq.mobile.android

import android.content.Intent

/** A release build signs in through its form only. */
object DevLogin {
    fun from(@Suppress("UNUSED_PARAMETER") intent: Intent?): Credentials? = null

    class Credentials(val server: String, val email: String, val password: String, val secretKey: String)
}
