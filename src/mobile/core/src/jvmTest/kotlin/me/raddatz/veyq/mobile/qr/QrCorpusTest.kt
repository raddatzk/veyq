package me.raddatz.veyq.mobile.qr

import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The core's QR decoder over ZXing's own black-box corpus (`qrcode-1..6`, fetched by `:core:fetchQrCorpus` and pinned by
 * SHA-256), held to upstream's own thresholds per set and rotation (`QRCodeBlackBox{1..6}TestCase` at the pinned
 * commit), with and without trying harder, and to none misread — as `AbstractBlackBoxTestCase` runs them: each image
 * rotated bicubically, read first as a pure code and then through the detector, its text and its `ERRORS_CORRECTED`
 * compared. A segment the core leaves encoded (Shift_JIS, GB2312) is decoded here by the JVM to be compared.
 */
class QrCorpusTest {
    private val corpus = File(System.getProperty("veyq.qrCorpus"))

    /** Upstream's `addTest(mustPass, tryHarder, rotation)` of each set. */
    private val thresholds = mapOf(
        1 to listOf(Triple(17, 17, 0f), Triple(14, 14, 90f), Triple(17, 17, 180f), Triple(14, 14, 270f)),
        2 to listOf(Triple(32, 32, 0f), Triple(30, 30, 90f), Triple(31, 31, 180f), Triple(31, 31, 270f)),
        3 to listOf(Triple(38, 38, 0f), Triple(39, 39, 90f), Triple(36, 36, 180f), Triple(39, 39, 270f)),
        4 to listOf(Triple(36, 36, 0f), Triple(35, 35, 90f), Triple(35, 35, 180f), Triple(35, 35, 270f)),
        5 to listOf(Triple(19, 19, 0f), Triple(19, 19, 90f), Triple(19, 19, 180f), Triple(19, 19, 270f)),
        6 to listOf(Triple(15, 15, 0f), Triple(14, 14, 90f), Triple(13, 13, 180f), Triple(14, 14, 270f)),
    )

    @Test fun set1() = blackBox(1)
    @Test fun set2() = blackBox(2)
    @Test fun set3() = blackBox(3)
    @Test fun set4() = blackBox(4)
    @Test fun set5() = blackBox(5)
    @Test fun set6() = blackBox(6)

    private enum class Outcome { PASSED, MISREAD, NOT_FOUND }

    private fun blackBox(set: Int) {
        val dir = File(corpus, "qrcode-$set")
        val images = dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "gif", "png") }!!.sortedBy { it.name }
        assertTrue(images.isNotEmpty(), "no images in $dir: run :core:fetchQrCorpus")
        val tests = thresholds.getValue(set)
        val passed = IntArray(tests.size)
        val tryHarderPassed = IntArray(tests.size)
        val misread = mutableListOf<String>()
        for (file in images) {
            val base = file.name.substringBefore('.')
            val expected = File(dir, "$base.txt").takeIf { it.isFile }?.readText(Charsets.UTF_8)
                ?: File(dir, "$base.bin").readText(Charsets.ISO_8859_1)
            val metadata = Properties().apply { File(dir, "$base.metadata.txt").takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use { load(it) } }
            val image = ImageIO.read(file)
            for ((x, test) in tests.withIndex()) {
                val frame = QrImages.frame(QrImages.rotate(image, test.third))
                for (tryHarder in listOf(false, true)) {
                    when (read(frame, tryHarder, expected, metadata)) {
                        Outcome.PASSED -> if (tryHarder) tryHarderPassed[x]++ else passed[x]++
                        Outcome.MISREAD -> misread += "${file.name} at ${test.third.toInt()}°${if (tryHarder) " trying harder" else ""}"
                        Outcome.NOT_FOUND -> {}
                    }
                }
            }
        }
        val report = tests.withIndex().joinToString("; ") { (x, t) ->
            "${t.third.toInt()}°: ${passed[x]}/${images.size} (needs ${t.first}), trying harder ${tryHarderPassed[x]} (needs ${t.second})"
        }
        println("qrcode-$set: $report")
        for ((x, t) in tests.withIndex()) {
            assertTrue(passed[x] >= t.first, "qrcode-$set at ${t.third.toInt()}°: ${passed[x]} read, upstream reads ${t.first} — $report")
            assertTrue(tryHarderPassed[x] >= t.second, "qrcode-$set at ${t.third.toInt()}° trying harder: ${tryHarderPassed[x]} read, upstream reads ${t.second} — $report")
        }
        assertEquals(emptyList(), misread, "qrcode-$set misread")
    }

    private fun read(frame: QrFrame, tryHarder: Boolean, expected: String, metadata: Properties): Outcome {
        val content = QrDecoder.pure(frame) ?: QrDecoder.content(frame, tryHarder) ?: return Outcome.NOT_FOUND
        if (QrImages.text(content) != expected) return Outcome.MISREAD
        metadata.getProperty("ERRORS_CORRECTED")?.let { if (it.toInt() != content.errorsCorrected) return Outcome.MISREAD }
        return Outcome.PASSED
    }
}
