package me.raddatz.veyq.mobile.memories

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.media.localWallClock
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.getenv
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The metadata readers on the Apple path — the device's zone through Foundation, the same parse — over two of the web's
 * fixtures the JVM's `MediaMetadataFixtureTest` reads in full: a JPEG with XMP, and a HEIC's `Exif` item.
 */
@OptIn(ExperimentalForeignApi::class)
class MediaMetadataNativeTest {
    private fun photo(name: String): ByteSource {
        val root = getenv("VEYQ_REPO_ROOT")?.toKString() ?: error("VEYQ_REPO_ROOT is not set")
        val data = NSData.dataWithContentsOfFile("$root/src/web/src/features/memories/__fixtures__/$name") ?: error("cannot read $name")
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return ByteSource(bytes.size.toLong()) { from, length -> bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt()) }
    }

    @Test
    fun aJpegAndAHeicReadAsOnTheJvm() = runTest {
        val taken = localWallClock(2021, 7, 15, 18, 4, 5)
        for ((name, title) in listOf("exif-sample.jpg" to "Marienplatz", "exif-sample.heic" to null)) {
            val read = MediaMetadata.read(photo(name), "image/jpeg", 0)
            assertEquals(taken, read.capturedAt, name)
            assertEquals(48.136944444444445, read.gpsLat, name)
            assertEquals(11.575, read.gpsLng, name)
            assertEquals("\"Canon\"", read.exif?.get("make")?.toString(), name)
            assertEquals(title?.let { "\"$it\"" }, read.exif?.get("title")?.toString(), name)
            assertEquals("4032", read.exif?.get("w")?.toString(), name)
        }
    }

    @Test
    fun theDevicesWallClockRollsOverAsJavaScriptsDoes() {
        val noon = localWallClock(2021, 1, 15, 12, 0, 0)!!
        assertEquals(noon + 3_600_000, localWallClock(2021, 1, 15, 13, 0, 0))
        assertEquals(localWallClock(2022, 1, 15, 12, 0, 0), localWallClock(2021, 13, 15, 12, 0, 0), "month 13 is the next January")
    }
}
