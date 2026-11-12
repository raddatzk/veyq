package me.raddatz.veyq.mobile.account

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.getenv
import platform.posix.memcpy

/** The web's kit fixtures on the Apple path (`VEYQ_REPO_ROOT`): the same reader compiled by Kotlin/Native, its zlib the system's. */
@OptIn(ExperimentalForeignApi::class)
class KitFixtureNativeTest : KitFixtureCases() {
    override fun fixture(name: String): ByteArray {
        val root = getenv("VEYQ_REPO_ROOT")?.toKString() ?: error("VEYQ_REPO_ROOT is not set")
        val data = NSData.dataWithContentsOfFile("$root/src/web/src/features/account/emergencyKit/__fixtures__/$name") ?: error("cannot read $name")
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }
}
