package me.raddatz.veyq.mobile.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `Xmp.value`: an attribute, a plain element, and an `rdf:Alt` list — each spanning lines, as XMP writers lay them out. */
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
          <xmp:CreateDate><rdf:Bag><b>nested</b></rdf:Bag></xmp:CreateDate>
        </rdf:Description>
    """.trimIndent()

    @Test
    fun anAttributeIsRead() = assertEquals("Darktable 5.2", Xmp.value(packet, "CreatorTool"))

    @Test
    fun anElementSpanningLinesIsRead() = assertEquals("\n    4\n  ", Xmp.value(packet, "Rating"))

    @Test
    fun theDefaultLanguageIsChosenAcrossLineBreaks() =
        assertEquals("At the lake\n        by night & fog", Xmp.value(packet, "title"))

    @Test
    fun withoutADefaultTheFirstItemIsTaken() = assertEquals("nur eine Sprache", Xmp.value(packet, "description"))

    @Test
    fun markupThatIsNoListIsNoValue() = assertNull(Xmp.value(packet, "CreateDate"))

    @Test
    fun anAbsentNameIsNull() = assertNull(Xmp.value(packet, "DateCreated"))
}
