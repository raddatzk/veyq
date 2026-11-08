package me.raddatz.veyq.mobile.android

import android.content.Intent

/** A release build signs in and unlocks through its forms only. */
object DevLogin {
    fun from(@Suppress("UNUSED_PARAMETER") intent: Intent?): Credentials? = null

    fun unlockPassword(@Suppress("UNUSED_PARAMETER") intent: Intent?): String? = null

    class Credentials(val server: String, val email: String, val password: String, val secretKey: String, val remember: Boolean)
}
