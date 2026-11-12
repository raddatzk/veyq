package me.raddatz.veyq.mobile.account

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.unhex
import me.raddatz.veyq.mobile.session.testPrimitives

/** The kit's read order (KitImport) and the core's PDF reader (KitPdf) over PDFs built here, a renderer and a QR played. */
class KitImportTest {
    private val p = testPrimitives

    @Test
    fun aRawTokenEndsTheReadWithoutAPageDrawn() = runTest {
        val played = PlayedPictures()
        val found = assertNotNull(read(pdf(info("($TOKEN_A)")), played))
        assertKit(found, "https://familie.example.org", KitImport.ServerFrom.TOKEN)
        assertEquals(emptyList(), played.asked)
    }

    @Test
    fun keywordsAsAUtf16HexString() = runTest {
        val utf16 = "feff" + TOKEN_A.map { "00" + it.code.toString(16).padStart(2, '0') }.joinToString("")
        val bytes = pdf(info("<$utf16>"))
        assertNoRawToken(bytes)
        val played = PlayedPictures()
        assertKit(assertNotNull(read(bytes, played)), "https://familie.example.org", KitImport.ServerFrom.TOKEN)
        assertEquals(emptyList(), played.asked)
    }

    @Test
    fun keywordsAsALiteralWithOctalEscapesAndAContinuation() = runTest {
        val escaped = "\\166\\145\\171q\\\nkit:" + TOKEN_A.removePrefix("veyqkit:")
        val bytes = pdf(info("($escaped)"))
        assertNoRawToken(bytes)
        assertKit(assertNotNull(read(bytes, PlayedPictures())), "https://familie.example.org", KitImport.ServerFrom.TOKEN)
    }

    @Test
    fun keywordsInsideAFlateObjectStream() = runTest {
        val bytes = pdf(objStm(OBJSTM_INFO_A), trailer = "<< /Size 4 /Root 1 0 R /Info 2 0 R >>")
        assertNoRawToken(bytes)
        assertKit(assertNotNull(read(bytes, PlayedPictures())), "https://familie.example.org", KitImport.ServerFrom.TOKEN)
    }

    @Test
    fun anIncrementalUpdatesLaterDefinitionWins() = runTest {
        val old = token("""{"v":3,"email":"old@example.org","sk":"$KEY_TEXT","srv":"https://old.example.org"}""")
        val bytes = concat(
            pdf(info("<${hexOf(old)}>")),
            "2 0 obj\n<< /Keywords <${hexOf(TOKEN_A)}> >>\nendobj\nxref\n2 1\n0000000000 00000 n \ntrailer\n<< /Size 4 /Root 1 0 R /Info 2 0 R /Prev 0 >>\nstartxref\n0\n%%EOF\n".latin1(),
        )
        assertNoRawToken(bytes)
        assertKit(assertNotNull(read(bytes, PlayedPictures())), "https://familie.example.org", KitImport.ServerFrom.TOKEN)
    }

    @Test
    fun aTokenWithoutAServerTakesTheFirstAddressField() = runTest {
        val bytes = pdf(info("($TOKEN_NO_SRV)"), fields("familie.example.org", "", ""))
        assertKit(assertNotNull(read(bytes, PlayedPictures())), "https://familie.example.org", KitImport.ServerFrom.FIELD)
    }

    @Test
    fun aTokensServerWinsOverTheField() = runTest {
        val bytes = pdf(info("($TOKEN_A)"), fields("other.example.net", "", ""))
        assertKit(assertNotNull(read(bytes, PlayedPictures())), "https://familie.example.org", KitImport.ServerFrom.TOKEN)
    }

    @Test
    fun onlyTheFirstAddressFieldIsRead() = runTest {
        val bytes = pdf(info("($TOKEN_NO_SRV)"), fields("", "familie.example.org", "other.example.net"))
        assertKit(assertNotNull(read(bytes, PlayedPictures())), null, KitImport.ServerFrom.NONE)
        val unreadable = pdf(info("($TOKEN_NO_SRV)"), fields("familie.example.org/login", "", ""))
        assertKit(assertNotNull(read(unreadable, PlayedPictures())), null, KitImport.ServerFrom.NONE)
    }

    @Test
    fun aFieldBelowAParentIsFoundByItsQualifiedName() {
        val bytes = pdf(
            "1 0 obj\n<< /Type /Catalog /AcroForm << /Fields [3 0 R] >> >>\nendobj\n" +
                "3 0 obj\n<< /T (kit) /Kids [4 0 R 5 0 R] >>\nendobj\n" +
                "4 0 obj\n<< /T (address1) /V (first.example) /Parent 3 0 R >>\nendobj\n" +
                "5 0 obj\n<< /Kids [6 0 R] >>\nendobj\n6 0 obj\n<< /T (address1) /V (second.example) >>\nendobj\n" +
                "7 0 obj\n<< /T (address1) /V (not.in.the.form.example) >>\nendobj\n",
        )
        assertEquals("first.example", KitPdf.parse(bytes).field("kit.address1"))
        assertNull(KitPdf.parse(bytes).field("address1"), "a field's name is its fully qualified one")
    }

    @Test
    fun anEncryptedPdfSkipsKeywordsAndFieldsAndGoesToItsQr() = runTest {
        val bytes = pdf(info("<${hexOf(TOKEN_A)}>"), fields("familie.example.org", "", ""), trailer = "<< /Size 9 /Root 1 0 R /Info 2 0 R /Encrypt 8 0 R >>")
        assertNull(KitPdf.parse(bytes).keywords())
        assertNull(KitPdf.parse(bytes).field("address1"))
        val played = PlayedPictures(qrOn = mapOf(1 to TOKEN_NO_SRV))
        assertKit(assertNotNull(read(bytes, played)), null, KitImport.ServerFrom.NONE)
        assertEquals(listOf("pages", "page 1@2400"), played.asked)
    }

    @Test
    fun aFileOverTheCapIsRefusedUnread() = runTest {
        val bytes = ByteArray(KitImport.FILE_CAP + 1)
        "%PDF-1.7 ($TOKEN_A)".latin1().copyInto(bytes)
        val played = PlayedPictures(qrOn = mapOf(1 to TOKEN_A))
        assertNull(read(bytes, played))
        assertEquals(emptyList(), played.asked)
    }

    @Test
    fun anObjectStreamThatInflatesPastTheCapIsSkipped() {
        val bytes = pdf(objStm(OBJSTM_BOMB_A), trailer = "<< /Size 4 /Root 1 0 R /Info 2 0 R >>")
        assertTrue(KitPdf.parse(bytes).keywords()!!.startsWith("veyqkit:"), "the control: under the default cap it reads")
        assertNull(KitPdf.parse(bytes, inflateCap = 4096).keywords())
    }

    @Test
    fun theQrIsLookedForOnTheFirstThreePagesAtTheWebsWidth() = runTest {
        val played = PlayedPictures(pages = 5, qrOn = mapOf(4 to TOKEN_A))
        assertNull(read(pdf(), played))
        assertEquals(listOf("pages", "page 1@2400", "page 2@2400", "page 3@2400"), played.asked)
    }

    @Test
    fun aForeignQrIsPassedAndTheNextPageRead() = runTest {
        val played = PlayedPictures(pages = 5, qrOn = mapOf(1 to "https://example.org", 2 to TOKEN_A))
        assertKit(assertNotNull(read(pdf(), played)), "https://familie.example.org", KitImport.ServerFrom.TOKEN)
        assertEquals(listOf("pages", "page 1@2400", "page 2@2400"), played.asked)
        assertTrue(played.drawn.all { l -> l.pixels.all { it == 0.toByte() } }, "a drawn page left unzeroed")
    }

    @Test
    fun aPictureGoesStraightToItsQr() = runTest {
        val played = PlayedPictures(picture = TOKEN_NO_SRV)
        val png = byteArrayOf(0x89.toByte()) + "PNG\r\n".latin1()
        assertKit(assertNotNull(read(png, played)), null, KitImport.ServerFrom.NONE)
        assertEquals(listOf("picture@2400"), played.asked)
    }

    @Test
    fun aFileThatIsNeitherReadsNothing() = runTest {
        assertNull(read("not a kit".latin1(), PlayedPictures(picture = null)))
        assertNull(read("%PDF-1.7 broken << (".latin1(), PlayedPictures(pages = null)))
    }

    // ---- the played platform and the PDFs ----

    private suspend fun read(bytes: ByteArray, played: PlayedPictures) = KitImport.read(p, bytes, played, played::qr)

    private fun assertKit(found: KitImport.Found, server: String?, from: KitImport.ServerFrom) {
        assertEquals("alex.beispiel@example.org", found.kit.email)
        assertEquals(KEY_HEX, hex(found.kit.secretKey))
        assertEquals(server, found.kit.server)
        assertEquals(from, found.serverFrom)
    }

    private fun assertNoRawToken(bytes: ByteArray) =
        assertTrue("veyqkit:" !in CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() }.concatToString(), "the premise: no raw token")

    /** A renderer whose page n is one pixel of value n, and a decoder that answers [qrOn]'s text for it. */
    class PlayedPictures(
        private val pages: Int? = 5,
        private val qrOn: Map<Int, String> = emptyMap(),
        private val picture: String? = null,
    ) : KitPictures {
        val asked = mutableListOf<String>()
        val drawn = mutableListOf<Luma>()

        override suspend fun pdfPages(): Int? = pages.also { asked += "pages" }
        override suspend fun pdfPage(page: Int, width: Int): Luma? {
            asked += "page $page@$width"
            return Luma(1, 1, byteArrayOf(page.toByte())).also { drawn += it }
        }
        override suspend fun picture(longSide: Int): Luma? {
            asked += "picture@$longSide"
            return Luma(1, 1, byteArrayOf(-1))
        }

        fun qr(luma: Luma): String? = luma.pixels[0].toInt().let { if (it == -1) picture else qrOn[it] }
    }

    companion object {
        /** The web's fixture kit: alex.beispiel@example.org, the Secret Key of the bytes 0..31, made at familie.example.org. */
        const val TOKEN_A = "veyqkit:eyJ2IjozLCJlbWFpbCI6ImFsZXguYmVpc3BpZWxAZXhhbXBsZS5vcmciLCJzayI6IjAwMEc0MC1SNDBNMzAtRTIwOTE4LTVHUjM4RS0xVzgxMjQtR0syR0FILUM1UlIzNC1EMVA3MFgtM1JGR0NDIiwic3J2IjoiaHR0cHM6Ly9mYW1pbGllLmV4YW1wbGUub3JnIn0"
        /** The same kit with no server. */
        const val TOKEN_NO_SRV = "veyqkit:eyJ2IjozLCJlbWFpbCI6ImFsZXguYmVpc3BpZWxAZXhhbXBsZS5vcmciLCJzayI6IjAwMEc0MC1SNDBNMzAtRTIwOTE4LTVHUjM4RS0xVzgxMjQtR0syR0FILUM1UlIzNC1EMVA3MFgtM1JGR0NDIn0"
        const val KEY_TEXT = "000G40-R40M30-E20918-5GR38E-1W8124-GK2GAH-C5RR34-D1P70X-3RFGCC"
        const val KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

        /**
         * zlib (Python's `zlib.compress`) of the object stream `2 0 << /Keywords <hex of TOKEN_A> >>` (`/First 4`), and of
         * the same followed by 6000 spaces (6436 bytes inflated): written here, since commonTest has no deflate.
         */
        const val OBJSTM_INFO_A = "789c3d50516ec53008bb4a6fb02618dc48d5bbc04ec192bedf49dbc7b4dbcff44953952230b681beeddb796e6fefd7efcfe7d7fade4e4638075b7cc4202c2b435ac7888c271313868c19dd89e01e5df9b010be949ba71f41ba2bf7c2cd0c9dbbeae48176e3f5753f54af7e3397abc5d21b2f7d6634f9be74138dc4824b6b57346b6eb80074fd170b87770786fa9e8a82315d041ceee22f2b5c6c1e9a29a32659917ef354358e8a909b78aefe26de945eca417e504d6a4dbb684bf1513d48f0e6d52c43f7628c7bdfff7b296f9a413d6190a7f6991c56b7194e6bf7fda86b4eccba97e67cd55975777af4d28b4b7a97ed8fedf1f803941a6584"
        const val OBJSTM_BOMB_A = "789ced90cb6dc4300c445b5107b1c4216901861b48158ae4bd06480e41bacfd00b6c03b9f22dd6023fc35f2b5b398ef2f67efdfe7c7eadef72b8997af76a1fd61d32c2c290866ec31e3e30211836ada9c37cb346bb8b31be688b0eddcd5d95b6465c44d07ca3df7d47bde3f16bbad31ff922caae628bfffeacefc32afb3eeb0e54772c286b6d7c45aa0a2e008ddfe5118736053af31e7c19c6540ab0ab52bf24e254fbce9986c524cb86de3a7ac57bbc6037ea94f995bac97a831dd80ff4b15ae52edc927a440e06fcd6c52c9df772ebf7beaf7bd1ae9c813926604fee33bd4bdca6ab4bbdefe7bce6c48c7b71cea7dfc3afea6a2dead9c57a976c6739cf9224499224499224499224499224499224c9bff903ef7653b1"

        fun String.latin1() = ByteArray(length) { this[it].code.toByte() }
        fun hexOf(s: String) = hex(s.encodeToByteArray())
        fun token(json: String) = "veyqkit:" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())

        /** The document information as object 2, its `Keywords` written as given. */
        fun info(keywords: String) = "2 0 obj\n<< /Producer (veyq test) /Keywords $keywords >>\nendobj\n"

        /** The catalog (object 1) with three address fields (objects 3, 4, 5), as both writers lay them out. */
        fun fields(vararg values: String) = "1 0 obj\n<< /Type /Catalog /Pages 6 0 R /AcroForm << /Fields [3 0 R 4 0 R 5 0 R] >> >>\nendobj\n" +
            values.withIndex().joinToString("") { (i, v) -> "${3 + i} 0 obj\n<< /FT /Tx /T (address${i + 1}) /V ($v) /Rect [0 0 100 20] >>\nendobj\n" }

        /** Object 3 as a Flate object stream holding object 2, its bytes [zlibHex], its length direct. */
        fun objStm(zlibHex: String): ByteArray {
            val data = unhex(zlibHex)
            return concat("3 0 obj\n<< /Type /ObjStm /N 1 /First 4 /Filter /FlateDecode /Length ${data.size} >>\nstream\n".latin1(), data, "\nendstream\nendobj\n".latin1())
        }

        /** A PDF of [parts] (text or bytes) with an xref table nobody follows and [trailer]. */
        fun pdf(vararg parts: Any, trailer: String = "<< /Size 6 /Root 1 0 R /Info 2 0 R >>"): ByteArray = concat(
            "%PDF-1.7\n%âãÏÓ\n".latin1(),
            *parts.map { if (it is ByteArray) it else (it as String).latin1() }.toTypedArray(),
            "xref\n0 1\n0000000000 65535 f \ntrailer\n$trailer\nstartxref\n0\n%%EOF\n".latin1(),
        )
    }
}
