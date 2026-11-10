package me.raddatz.veyq.mobile.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `ExifReader.xmpValue`: an attribute, a plain element, and an `rdf:Alt` list — each spanning lines, as XMP writers lay them out. */
class XmpValueTest {
    private val packet = """
        <rdf:Description rdf:about=""
            xmp:CreatorTool="Darktable 5.2"
            xmlns:dc="http://purl.org/dc/elements/1.1/">
          <xmp:Rating>
            4
          </xmp:Rating>
          <dc:title>
            <rdf:Alt>
              <rdf:li xml:lang="de">Am See
                bei Nacht</rdf:li>
              <rdf:li xml:lang="x-default">At the lake
                by night &amp; fog</rdf:li>
            </rdf:Alt>
          </dc:title>
          <dc:description>
            <rdf:Alt>
              <rdf:li xml:lang="de">nur eine Sprache</rdf:li>
            </rdf:Alt>
          </dc:description>
          <dc:subject><rdf:Bag><b>nested</b></rdf:Bag></dc:subject>
        </rdf:Description>
    """.trimIndent()

    @Test
    fun anAttributeIsRead() = assertEquals("Darktable 5.2", ExifReader.xmpValue(packet, "xmp", "CreatorTool"))

    @Test
    fun anElementSpanningLinesIsRead() = assertEquals("\n    4\n  ", ExifReader.xmpValue(packet, "xmp", "Rating"))

    @Test
    fun theDefaultLanguageIsChosenAcrossLineBreaks() =
        assertEquals("At the lake\n        by night & fog", ExifReader.xmpValue(packet, "dc", "title"))

    @Test
    fun withoutADefaultTheFirstItemIsTaken() = assertEquals("nur eine Sprache", ExifReader.xmpValue(packet, "dc", "description"))

    @Test
    fun markupThatIsNoListIsNoValue() = assertNull(ExifReader.xmpValue(packet, "dc", "subject"))

    @Test
    fun anAbsentNameIsNull() = assertNull(ExifReader.xmpValue(packet, "dc", "creator"))
}
