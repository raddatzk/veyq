package me.raddatz.veyq.mobile.sharing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import me.raddatz.veyq.mobile.session.ClientWindow
import me.raddatz.veyq.mobile.session.LinkForm
import me.raddatz.veyq.mobile.session.LocalDays
import me.raddatz.veyq.mobile.session.ShareLinks
import java.io.File
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The platform's share-link rules against `contracts/platform/share-links/cases.v1.json` (ADR 0150 decision 2, ADR 0155
 * decision 7.2): a received link ([ReceivedLink]), a share page's report fragment, a link's last end day ([LinkDays], through
 * the apps' [LinkForm]), an instance's embeddability ([ShareInstance]) and the client window ([ClientWindow]). The web draws
 * the same rules through `@veyq/core-js/sharing`, whose `sharing.test.ts` and the web's own tests read the same file.
 */
@OptIn(ExperimentalTime::class)
class ShareLinkVectorsTest {
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

    private fun JsonObject.str(name: String): String? = this[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    @Test
    fun aSharePagesFragmentAsksForItsReportOrNot() {
        val all = cases["report"]!!.jsonArray.map { it as JsonObject }
        assertTrue(all.size >= 8)
        for (c in all) {
            val fragment = c.str("fragment")!!
            assertEquals(c["asks"]!!.jsonPrimitive.boolean, ReceivedLink.asksForReport(fragment), fragment)
            assertEquals(c.str("without"), ReceivedLink.withoutReport(fragment), fragment)
        }
    }

    /** Each case in its own zone: the device's zone is what `LocalDays` reads on every target (`TimeZone.getDefault()` here). */
    @Test
    fun theLastEndDayCountsFromWhenTheLinkWasMade() {
        val all = cases["lastDay"]!!.jsonArray.map { it as JsonObject }
        assertTrue(all.size >= 8)
        val zone = TimeZone.getDefault()
        try {
            for (c in all) {
                val rule = c.str("rule")!!
                TimeZone.setDefault(TimeZone.getTimeZone(c.str("zone")!!))
                val maxDays = c["maxDays"]!!.jsonPrimitive.intOrNull
                val madeAt = Instant.parse(c.str("madeAt")!!).toEpochMilliseconds()
                val now = Instant.parse(c.str("now")!!).toEpochMilliseconds()
                val expected = c.str("lastDay")
                assertEquals(expected, LinkDays.lastEndDay(maxDays, madeAt)?.let(LocalDays::iso), rule)
                // the apps' form, editing a link made at madeAt and opened at now (a new one where both are the same instant)
                val rules = ShareLinks.Rules(on = true, contribute = true, passwordRequired = false, maxDays = maxDays, dropBox = true)
                val editing = ShareLinks.Link(
                    "s", ShareLinks.READ, enabled = true, mine = true, createdAt = c.str("madeAt")!!, expiresAt = null, label = "",
                    hasNote = false, passwordRequired = false, url = null, reissueRequired = false, stopped = false, deletable = true,
                )
                val form = LinkForm.start(rules, editing.takeIf { madeAt != now }, false, false, false, now, null)
                assertEquals(expected, form.lastDay?.let(LocalDays::iso), "$rule (the form)")
                if (form.lastDay != null) {
                    // nothing past the last day is taken, and a link past its lifetime keeps its last day (no empty range)
                    assertEquals(form.lastDay, form.withEndDay(form.lastDay!! + 30).endDay, rule)
                    assertEquals(form.lastDay, form.endDay, rule)
                }
            }
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    private fun majorOf(setup: JsonElement?): Int? =
        ((setup as? JsonObject)?.get("version") as? JsonObject)?.get("major")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.intOrNull

    private fun capabilitiesOf(setup: JsonElement?): List<String>? =
        ((setup as? JsonObject)?.get("capabilities") as? JsonArray)?.map { ((it as? JsonObject)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }

    @Test
    fun anInstanceOpensAndEmbedsByOneReading() {
        val all = cases["embed"]!!.jsonArray.map { it as JsonObject }
        assertTrue(all.size >= 10)
        val words = mapOf(
            ShareInstance.Refusal.UNREACHABLE to "unreachable", ShareInstance.Refusal.OTHER_VERSION to "otherVersion",
            ShareInstance.Refusal.NO_LINKS to "noLinks",
        )
        for (c in all) {
            val rule = c.str("rule")!!
            val majors = c["majors"]!!.jsonArray.map { it.jsonPrimitive.int }
            val setup = c["setup"]
            val major = majorOf(setup)
            val capabilities = capabilitiesOf(setup)
            assertEquals(c.str("app"), ShareInstance.refusal(major, capabilities, majors::contains)?.let(words::getValue), "$rule (app)")
            assertEquals(c.str("page"), ShareInstance.embedding(c["embedding"]!!.jsonPrimitive.boolean, major, capabilities, majors::contains), "$rule (page)")
        }
    }

    @Test
    fun theWindowKeepsTwoReleasedMajorsBack() {
        for (c in cases["window"]!!.jsonArray.map { it as JsonObject }) {
            val major = c["major"]!!.jsonPrimitive.int
            assertEquals(c["older"]!!.jsonArray.map { it.jsonPrimitive.int }, ClientWindow.olderMajorsInWindow(major), "major $major")
        }
    }
}
