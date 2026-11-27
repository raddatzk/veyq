package me.raddatz.veyq.mobile.account

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.raddatz.veyq.mobile.crypto.EmergencyKit
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.session.testPrimitives
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.text.PDFTextStripper

/**
 * The kit the core writes (ADR 0148 decision 5), read by PDFBox: `kit-app.pdf` in the web's kit fixtures is this writer's
 * output for the fixtures' fake data, byte for byte — `VEYQ_WRITE_FIXTURES=1` writes it anew after a deliberate change
 * to the writer. The web reads it by each of its three paths (`emergencyKit.import.test.ts`), the core by its own
 * (`KitFixtureCases`, `KitFixtureTest`).
 */
class KitWriterTest {
    private val p = testPrimitives
    private val fixture = File(System.getProperty("veyq.repoRoot") ?: error("veyq.repoRoot is not set"), FIXTURE)

    private fun english() = KitWriter.pdf(p, EMAIL, key(), SERVER, KitWords.of("en"), DAY)

    @Test
    fun theFixtureIsWhatTheWriterWritesByteForByte() {
        val bytes = english()
        if (System.getenv("VEYQ_WRITE_FIXTURES") == "1") fixture.writeBytes(bytes)
        assertContentEquals(fixture.readBytes(), bytes, "$FIXTURE is not what KitWriter writes: rewrite it with VEYQ_WRITE_FIXTURES=1")
        // a function of its inputs: written again, the same bytes
        assertContentEquals(bytes, english())
    }

    @Test
    fun anA4PageInTheStandardFontsWithTheTokenTheFieldsAndNoDate() {
        val bytes = english()
        val latin1 = String(bytes, Charsets.ISO_8859_1)
        val token = EmergencyKit.write(p, EMAIL, key(), SERVER)
        assertTrue("/Keywords ($token)" in latin1, "the token is not raw in the bytes")
        for (absent in listOf("/CreationDate", "/ModDate", "/ID", "/FontFile", "/Filter")) assertFalse(absent in latin1, absent)
        Loader.loadPDF(bytes).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertEquals(595.28f, page.mediaBox.width)
            assertEquals(841.89f, page.mediaBox.height)
            val fonts = page.resources.fontNames.map { page.resources.getFont(it) }
            assertEquals(setOf("Helvetica", "Helvetica-Bold", "Courier-Bold"), fonts.map { it.name }.toSet())
            for (f in fonts) {
                assertTrue(f.isStandard14, "${f.name} is no standard 14 font")
                assertFalse(f.isEmbedded, "${f.name} is embedded")
            }
            val info = doc.documentInformation
            assertEquals(token, info.keywords)
            assertEquals("veyq Emergency Kit", info.title)
            assertNull(info.creationDate)
            assertNull(info.modificationDate)
            assertNull(doc.document.trailer.getDictionaryObject(org.apache.pdfbox.cos.COSName.ID))

            val form = doc.documentCatalog.acroForm
            assertEquals(listOf("address1", "address2", "address3"), form.fields.map { it.fullyQualifiedName })
            assertEquals("familie.example.org", form.getField("address1").valueAsString)
            assertEquals("", form.getField("address2").valueAsString)

            val link = page.annotations.filterIsInstance<PDAnnotationLink>().single()
            assertEquals("https://veyq.app/account/forgot-password/", (link.action as PDActionURI).uri)

            val text = PDFTextStripper().getText(doc)
            for (shown in listOf("Emergency Kit", EMAIL, "Created on November 18, 2026", "Page 1 of 1", "“Sign in”",
                "Help: veyq.app/account/forgot-password/", "Confidential · veyq Emergency Kit")) {
                assertTrue(shown in text, "not on the page: $shown")
            }
            // layout 2 (ADR 0157): the key's three lines under a label that says they are one key — a kit's first line alone
            // was once typed as the whole key; the web's kit says the same (kit.pdfSecretKey)
            assertEquals(2, KitWriter.LAYOUT)
            assertTrue("SECRET KEY · 9 BLOCKS IN 3 LINES" in text, "the key's label is not layout 2's")
            // the Secret Key, three lines of three groups
            val groups = SecretKeyText.format(p, key()).split('-')
            for (line in 0 until 3) assertTrue(groups.subList(line * 3, line * 3 + 3).joinToString("-") in text, "key line $line")
        }
    }

    @Test
    fun inGermanTheWordsAndTheDateAreGermanAndTheUmlautsSurviveWinAnsi() {
        val bytes = KitWriter.pdf(p, EMAIL, key(), SERVER, KitWords.of("de-DE"), DAY)
        Loader.loadPDF(bytes).use { doc ->
            val text = PDFTextStripper().getText(doc)
            for (shown in listOf("Notfall-Kit", "Erstellt am 18. November 2026", "SECRET KEY · 9 BLÖCKE IN 3 ZEILEN", "„Anmelden“", "Öffne veyq auf dem neuen Gerät",
                "Hilfe: veyq.app/de/account/forgot-password/", "Könnte es jemand anderes haben?")) {
                assertTrue(shown in text, "not on the page: $shown")
            }
            assertEquals("veyq-Notfall-Kit", doc.documentInformation.title)
        }
    }

    @Test
    fun withoutAServerTheFirstFieldIsEmptyAndTheTokenHasNoServer() {
        val bytes = KitWriter.pdf(p, EMAIL, key(), null, KitWords.of("en"), DAY)
        val pdf = KitPdf.parse(bytes)
        assertEquals("", pdf.field("address1"))
        val kit = EmergencyKit.read(p, pdf.keywords()!!)!!
        assertNull(kit.server)
        kit.wipe()
    }

    @Test
    fun aServerThatIsNoOriginIsRefused() {
        assertFailsWith<IllegalArgumentException> { KitWriter.pdf(p, EMAIL, key(), "https://familie.example.org/", KitWords.of("en"), DAY) }
    }

    @Test
    fun theAfmWidthsArePdfBoxsForEveryWinAnsiCode() {
        for ((mine, theirs) in listOf(KitFont.HELVETICA to Standard14Fonts.FontName.HELVETICA, KitFont.HELVETICA_BOLD to Standard14Fonts.FontName.HELVETICA_BOLD,
            KitFont.COURIER_BOLD to Standard14Fonts.FontName.COURIER_BOLD)) {
            val font = PDType1Font(theirs)
            for (code in 32..255) {
                if (code == 0x7f || code in intArrayOf(0x81, 0x8d, 0x8f, 0x90, 0x9d)) continue
                assertEquals(font.getWidth(code), mine.width(code).toFloat(), "${mine.baseFont} code $code")
            }
        }
    }

    @Test
    fun wrappingKeepsWordsWholeWhileTheyFitAndCutsAWordWiderThanALine() {
        val lines = KitWriter.split("aaa bbb ccc", KitFont.HELVETICA, 10.0, KitFont.HELVETICA.width("aaa bbb", 10.0))
        assertEquals(listOf("aaa bbb", "ccc"), lines)
        val cut = KitWriter.split("abcdefghij", KitFont.COURIER_BOLD, 10.0, KitFont.COURIER_BOLD.width("abcd", 10.0))
        assertEquals(listOf("abcd", "efgh", "ij"), cut)
        assertEquals("1.5", KitWriter.n(1.5))
        assertEquals("-0.001", KitWriter.n(-0.0009))
        assertEquals("841.89", KitWriter.n(841.89))
    }

    @Test
    fun theDateIsTheCivilDateOfItsEpochDay() {
        for (d in listOf(-719468L, -1L, 0L, 59L, 60L, 11016L, DAY, 2932896L)) {
            val date = LocalDate.ofEpochDay(d)
            assertEquals(Triple(date.year.toLong(), date.monthValue, date.dayOfMonth), KitWords.civil(d), "day $d")
        }
    }

    @Test
    fun writtenStraightToItsPathAndAFailedWriteLeavesNothing() {
        val dir = Files.createTempDirectory("kit").toFile()
        try {
            val path = File(dir, KitWriter.fileName(EMAIL))
            KitWriter.write(path.path, p, EMAIL, key(), SERVER, KitWords.of("en"), DAY)
            assertContentEquals(english(), path.readBytes())
            assertEquals(listOf(path.name), dir.list()!!.toList(), "a copy beside the kit")
            // a stream (Android's content Uri) gets the same bytes
            val out = ByteArrayOutputStream()
            KitWriter.write(out, p, EMAIL, key(), SERVER, KitWords.of("en"), DAY)
            assertContentEquals(english(), out.toByteArray())
            // a path that cannot be written: thrown, and nothing left
            val blocked = File(dir, "no-such-dir/kit.pdf")
            assertFailsWith<Exception> { KitWriter.write(blocked.path, p, EMAIL, key(), SERVER, KitWords.of("en"), DAY) }
            assertFalse(blocked.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        /** The web's kit fixtures' fake data (`emergencyKit.import.test.ts`): the Secret Key of the bytes 0..31. */
        const val EMAIL = "alex.beispiel@example.org"
        const val SERVER = "https://familie.example.org"
        const val FIXTURE = "src/web/src/features/account/emergencyKit/__fixtures__/kit-app.pdf"
        val DAY: Long = LocalDate.of(2026, 11, 18).toEpochDay()

        fun key() = ByteArray(32) { it.toByte() }
    }
}
