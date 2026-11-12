package me.raddatz.veyq.mobile.account

import java.io.File

/** The web's kit fixtures on the JVM (`veyq.repoRoot`). */
class KitFixtureTest : KitFixtureCases() {
    override fun fixture(name: String): ByteArray =
        File(System.getProperty("veyq.repoRoot") ?: error("veyq.repoRoot is not set"), "src/web/src/features/account/emergencyKit/__fixtures__/$name").readBytes()
}
