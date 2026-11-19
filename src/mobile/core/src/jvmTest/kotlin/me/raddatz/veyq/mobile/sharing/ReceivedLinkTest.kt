package me.raddatz.veyq.mobile.sharing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The core's port of the web's link check against `contracts/platform/share-links/cases.v1.json` (ADR 0150 decision 2):
 * the web's `bookmarkLink.test.ts` reads the same file, so both clients accept, normalize and refuse alike.
 */
class ReceivedLinkTest {
    private val cases = Json.parseToJsonElement(File(System.getProperty("veyq.repoRoot"), "contracts/platform/share-links/cases.v1.json").readText()).jsonObject

    private fun problemOf(name: String) = when (name) {
        "empty" -> ReceivedLink.Problem.EMPTY
        "tooLong" -> ReceivedLink.Problem.TOO_LONG
        "notAUrl" -> ReceivedLink.Problem.NOT_A_URL
        "notHttps" -> ReceivedLink.Problem.NOT_HTTPS
        "credentials" -> ReceivedLink.Problem.CREDENTIALS
        "notAShare" -> ReceivedLink.Problem.NOT_A_SHARE
        "noKey" -> ReceivedLink.Problem.NO_KEY
        else -> error("unknown problem $name")
    }

    @Test
    fun theFileIsTheFormatThisTestReads() {
        assertEquals("veyq-share-links-v1", cases["format"]!!.jsonPrimitive.content)
        assertEquals(ReceivedLink.MAX_LINK_LENGTH, cases["maxLinkLength"]!!.jsonPrimitive.int)
    }

    @Test
    fun everyCaseParsesAsTheWebParsesIt() {
        val all = cases["parse"]!!.jsonArray.map { it as JsonObject }
        assertTrue(all.size > 20)
        for (c in all) {
            val input = c["input"]!!.jsonPrimitive.content
            val label = input.take(80)
            val parsed = ReceivedLink.parse(input)
            if ("link" in c) {
                val ok = assertIs<ReceivedLink.Parsed.Ok>(parsed, label)
                assertEquals(c["link"]!!.jsonPrimitive.content, ok.link.link, label)
                assertEquals(c["origin"]!!.jsonPrimitive.content, ok.link.origin, label)
                assertEquals(c["shareId"]!!.jsonPrimitive.content, ok.link.shareId, label)
                assertEquals(c["host"]!!.jsonPrimitive.content, ok.link.host, label)
                assertEquals(c["report"]?.jsonPrimitive?.boolean == true, ok.report, label)
                assertEquals(32, ok.link.key().size)
                assertTrue(!ok.link.toString().contains(ok.link.link.substringAfter("#k=")), "a link's string form names its key")
            } else {
                val expected = problemOf(c["problem"]!!.jsonPrimitive.content)
                assertEquals(ReceivedLink.Parsed.Refused(expected), parsed, label)
                // the app's Open a link refuses a moderator's review as its own problem, everything else as the web does
                val moderation = c["moderation"]?.jsonPrimitive?.boolean == true
                assertEquals(
                    ReceivedLink.Parsed.Refused(if (moderation) ReceivedLink.Problem.MODERATION else expected), ReceivedLink.open(input), label,
                )
            }
        }
    }

    @Test
    fun aKeptLinkReadsBackOnlyAsItsNormalizedForm() {
        for (c in cases["kept"]!!.jsonArray.map { it as JsonObject }) {
            val input = c["input"]!!.jsonPrimitive.content
            val expected = c["link"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(expected, ReceivedLink.kept(input)?.link, input)
        }
        assertEquals(null, ReceivedLink.kept(null))
    }
}
