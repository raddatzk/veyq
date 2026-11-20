package me.raddatz.veyq.mobile.account

import me.raddatz.veyq.mobile.blob.NotOnTheWeb

/** The Emergency Kit's file is the browser's download on the web, never a path the core writes (`blob/NotOnTheWeb.kt`). */
internal actual fun saveKitFile(path: String, bytes: ByteArray): Unit = throw NotOnTheWeb("saveKitFile")
