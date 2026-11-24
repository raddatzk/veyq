package me.raddatz.veyq.mobile.media

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.qr.readRepoFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The forms of XMP and IPTC read since inbox #1141 — a HEIC's XMP item, a PNG's compressed `iTXt` and `zTXt` raw profile, a
 * JPEG's extended XMP — and #1185 — a bare TIFF walked by its IFDs' offsets with its XMP (tag 700) and IPTC (tag 33723) in
 * IFD0 last, a PNG's raw IPTC profile — damaged every way a cut or a flipped byte can damage them: the reader answers (what
 * it read, or null), never throws, never loops. The vectors hold the damage each rule names (a box past the file's end, a stream that does not
 * inflate, a chunk under another GUID); this holds what nobody named. The fixtures are `scripts/make_exif_fixture.py`'s.
 */
class ExifReaderDamageTest {
    private val fixtures = listOf(
        "exif-sample-xmp.heic", "exif-sample-compressed.png", "exif-sample-extended.jpg", "exif-sample-xmp.tif", "exif-sample-iptc.png",
    ).associateWith { readRepoFile("src/web/src/features/memories/__fixtures__/$it") }

    private suspend fun read(b: ByteArray): ExifTags? =
        ExifReader.read(MediaBytes(b.size.toDouble()) { from, length -> b.copyOfRange(from.toInt(), minOf(b.size, from.toInt() + length)) })

    @Test
    fun eachFixtureReadsItsXmpOrIptc() = runTest {
        for ((name, b) in fixtures) {
            val tags = read(b)
            if (name == "exif-sample-iptc.png") assertEquals("Hofgarten", tags?.iptcObjectName, name) else assertEquals("Marienplatz", tags?.xmpTitle, name)
        }
        assertEquals("Hofgarten", read(fixtures.getValue("exif-sample-xmp.tif"))?.iptcObjectName)
    }

    @Test
    fun everyCutOfAFixtureAnswers() = runTest {
        for ((_, b) in fixtures) for (n in 0..b.size) read(b.copyOf(n))
    }

    @Test
    fun aThousandFlippedBytesEachAnswer() = runTest {
        val random = Random(1185)
        for ((_, b) in fixtures) repeat(1000) {
            val damaged = b.copyOf()
            repeat(1 + random.nextInt(4)) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
            read(damaged)
        }
    }
}
