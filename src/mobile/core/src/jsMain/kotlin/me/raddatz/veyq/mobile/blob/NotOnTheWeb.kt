package me.raddatz.veyq.mobile.blob

/**
 * What the core's common code expects of a platform and the web has no form of (ADR 0155 decision 3): each answers with
 * a refusal that names it, and nothing a façade reaches may reach one. The web reads and writes no files of its own
 * choosing (its store is IndexedDB, its downloads the browser's), and inflates no PDF stream (the kit's read order runs
 * in the web's TypeScript). `account/NotOnTheWeb.kt` holds the Emergency Kit's file, in its own package.
 */
class NotOnTheWeb(what: String) : UnsupportedOperationException("not on the web: $what")

internal actual val deviceFiles: DeviceFiles
    get() = throw NotOnTheWeb("deviceFiles")

actual fun inflate(bytes: ByteArray, cap: Int): ByteArray? = throw NotOnTheWeb("inflate")
