package me.raddatz.veyq.mobile.android

import android.content.Intent

/**
 * Debug builds only — the app's counterpart of the web's `?devlogin` (src/web/CLAUDE.md): the seeded dev principal's
 * credentials arrive as launch extras (`src/mobile/android/devlogin.py` reads them from `.run/.env` and hands them to
 * `adb`), and the app signs in with them through the real sign-in — its offer to stay signed in answered with the
 * presence level with `veyq.devRemember` — or, with `veyq.devUnlockPassword` alone, unlocks the password-trusted account
 * its unlock screen shows and fills "Manage servers"' password field once. A release build has no such door (src/release).
 */
object DevLogin {
    fun from(intent: Intent?): Credentials? {
        val e = intent?.extras ?: return null
        return Credentials(
            server = e.getString("veyq.devServer") ?: return null,
            email = e.getString("veyq.devEmail") ?: return null,
            password = e.getString("veyq.devPassword") ?: return null,
            secretKey = e.getString("veyq.devSecretKey") ?: return null,
            remember = e.getString("veyq.devRemember") == "1",
        )
    }

    fun unlockPassword(intent: Intent?): String? = intent?.extras?.getString("veyq.devUnlockPassword")

    class Credentials(val server: String, val email: String, val password: String, val secretKey: String, val remember: Boolean)
}
