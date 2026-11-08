package me.raddatz.veyq.mobile.android

import android.content.Intent

/**
 * Debug builds only — the app's counterpart of the web's `?devlogin` (src/web/CLAUDE.md): the seeded dev principal's
 * credentials arrive as launch extras (`src/mobile/android/devlogin.py` reads them from `.run/.env` and hands them to
 * `adb`), and the app signs in with them through the real sign-in. A release build has no such door (src/release).
 */
object DevLogin {
    fun from(intent: Intent?): Credentials? {
        val e = intent?.extras ?: return null
        return Credentials(
            server = e.getString("veyq.devServer") ?: return null,
            email = e.getString("veyq.devEmail") ?: return null,
            password = e.getString("veyq.devPassword") ?: return null,
            secretKey = e.getString("veyq.devSecretKey") ?: return null,
        )
    }

    class Credentials(val server: String, val email: String, val password: String, val secretKey: String)
}
