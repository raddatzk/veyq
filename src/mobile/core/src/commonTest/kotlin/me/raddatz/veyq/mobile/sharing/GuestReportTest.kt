package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.memories.MemoriesGuest

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.navigation.ReportSheet
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A viewing link's guest reports what it sees (#1033; the web's share page: `reportNodes` from the views, a medium's
 * `ITEM`, `&report` landing on the root), never the link itself — which the server refuses for a `READ` / `READ_WRITE`
 * link (`404 report_item_not_found`); an upload link's guest reports the link. Over a played instance that publishes a
 * moderation key: each report's sealed part is opened as the moderators open it, so the keys it hands over are checked,
 * not only its address.
 */
class GuestReportTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val p = testPrimitives

    /** The played instance's moderation: its epoch's seed, and every report received (the request's JSON). */
    private class Moderation(share: PlayedShare) {
        val seed: ByteArray = share.suite.generateDropKey()
        val received = mutableListOf<kotlinx.serialization.json.JsonObject>()

        init {
            share.routes += { r, rec ->
                fun answer(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                when (r.url.encodedPath) {
                    "/api/moderation/key" -> answer("""{"epoch":4,"kemPublicKey":"${b64(share.suite.dropKeyPublic(seed))}","signPublicKey":"${b64(ByteArray(32))}"}""")
                    "/api/shares/${share.shareId}/reports" -> {
                        val o = rec.json()
                        received += o
                        val kind = o["items"]!!.jsonArray.single().jsonObject["kind"]!!.jsonPrimitive.content
                        // the server's rule: a viewing link's guest is never offered the link as an item
                        if (kind == "LINK" && share.role != "WRITE_ONLY") answer("""{"code":"report_item_not_found"}""", HttpStatusCode.NotFound)
                        else answer("""{"reportId":"0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f","receivedAt":"2026-11-19T10:00:00Z"}""")
                    }
                    else -> null
                }
            }
        }

        /** The one report's item: its kind, its id, and the keys its share hands over by role (opened as the moderators do). */
        fun item(suite: me.raddatz.veyq.mobile.crypto.V1, report: kotlinx.serialization.json.JsonObject): Triple<String, String?, Map<String, ByteArray>> {
            val body = suite.openAs(Context("moderation-report", "epoch" to 4L), suite.xwingKey(seed), unb64(report["sealedReport"]!!.jsonPrimitive.content))
            val reportKey = body.copyOfRange(4, 4 + u32(body, 0))
            val item = report["items"]!!.jsonArray.single().jsonObject
            val kind = item["kind"]!!.jsonPrimitive.content
            val id = item["id"]?.jsonPrimitive?.content
            val context = if (kind == "LINK") Context("report-link", "shareId" to linkId) else Context("report-share", "itemKind" to kind, "itemId" to id!!)
            val keys = suite.open(context, reportKey, unb64(item["sealedShare"]!!.jsonPrimitive.content))
            val out = LinkedHashMap<String, ByteArray>()
            var at = 4
            repeat(u32(keys, 0)) {
                fun next(): ByteArray = keys.copyOfRange(at + 4, at + 4 + u32(keys, at)).also { at += 4 + it.size }
                val role = next().decodeToString()
                next()
                out[role] = next()
            }
            return Triple(kind, id, out)
        }

        var linkId = ""

        private fun u32(b: ByteArray, at: Int) =
            ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
    }

    private fun view(share: PlayedShare) =
        GuestView.over((ReceivedLink.open(share.link) as ReceivedLink.Parsed.Ok).link, share.client(), MemoryDeviceStore(), MemoryGuestSessions())

    private suspend fun sent(sheet: ReportSheet): ReportSheet.State {
        sheet.category("ILLEGAL_CONTENT")
        sheet.reason("not allowed")
        sheet.goodFaith(true)
        return sheet.send()
    }

    @Test
    fun aViewingLinksReportFromABookmarkReportsItsRootWholeNeverTheLink() = runTest {
        for (role in listOf("READ", "READ_WRITE")) {
            val share = PlayedShare(p, role = role)
            val content = PlayedGuestContent(share)
            content.memory(share.rootId, share.rootKey, "Trip", "a photo".encodeToByteArray())
            val moderation = Moderation(share)
            // a bookmark's *Report shared content* (and `&report`): the report dialog asked once the gates passed
            val screen = GuestScreen(view(share), reportAsked = true)
            screen.load()
            assertTrue(screen.state.value.reporting)
            val sheet = assertNotNull(screen.reportSheet())
            assertEquals(share.rootId, sheet.node.nodeId)
            assertFalse(sheet.offersName, "the root's name is the link's: no name of its own to report apart")
            assertEquals(NodeReport.Outcome.SENT, assertNotNull(sent(sheet).sent).outcome, "$role's report was refused")
            val (kind, id, keys) = moderation.item(share.suite, moderation.received.single())
            assertEquals("NODE" to share.rootId, kind to id, "$role's guest reported something else than the root")
            assertContentEquals(share.rootKey, keys["NODE"], "the root's key was not handed over")
            val linkName = share.suite.fieldKeyOf(Context("share-memory-name"), share.kShare, unb64(screen.view.access!!.head.share.sealedName!!))
            assertContentEquals(linkName, keys["LINK_NAME"], "the root's name is the link's name")
            assertFalse("SHARE" in keys, "K_share was handed to the moderators of a viewing link")
            share.assertNoKeyInAnyRequest()
        }
    }

    @Test
    fun aViewingGuestReportsANodeWholeOrItsNameOnly() = runTest {
        val share = PlayedShare(p, role = "READ")
        val content = PlayedGuestContent(share)
        val (memory, memoryKey) = content.memory(share.rootId, share.rootKey, "Trip", "a photo".encodeToByteArray())
        val moderation = Moderation(share)
        val v = view(share)
        v.load()
        val sheet = assertNotNull(GuestReport.nodeSheet(v, memory))
        assertTrue(sheet.offersName, "a node below the root has a name of its own")
        sheet.whole(false)
        assertEquals(NodeReport.Outcome.SENT, sent(sheet).sent?.outcome)
        val (kind, id, keys) = moderation.item(share.suite, moderation.received.single())
        assertEquals("NAME" to memory, kind to id)
        assertEquals(setOf("NODE_NAME"), keys.keys, "a name's report handed over more than the name")
        // the name's key opens the name as the tree sealed it
        val row = share.children.single { it["id"]!!.jsonPrimitive.content == memory }
        val binding = arrayOf<Pair<String, Any?>>("scopeId" to share.scopeId, "nodeId" to memory, "epoch" to 0L, "placementRevision" to 1L)
        assertContentEquals(
            share.suite.fieldKeyOf(Context("node-name", *binding), share.rootKey, unb64(row["encryptedName"]!!.jsonPrimitive.content)), keys["NODE_NAME"],
        )
        // whole: the Memory's own key
        val whole = assertNotNull(GuestReport.nodeSheet(v, memory))
        sent(whole)
        val (_, _, wholeKeys) = moderation.item(share.suite, moderation.received.last())
        assertContentEquals(memoryKey, wholeKeys["NODE"])
        assertTrue("NODE_NAME" in wholeKeys)
        share.assertNoKeyInAnyRequest()
    }

    @Test
    fun aViewingGuestReportsAMediumAsAnItem() = runTest {
        val share = PlayedShare(p, role = "READ")
        val content = PlayedGuestContent(share)
        val (memory, _) = content.memory(share.rootId, share.rootKey, "Trip", "a photo".encodeToByteArray())
        val moderation = Moderation(share)
        val v = view(share)
        v.load()
        v.open(memory)
        val gallery = assertIs<MemoriesGuest.Gallery>(v.page.value).model
        val rows = withContext(kotlinx.coroutines.Dispatchers.Default) { withTimeout(10_000) { gallery.state.first { it.rows != null }.rows!! } }
        val mediaId = rows.single().mediaId
        val sheet = assertNotNull(gallery.reportSheet(mediaId), "a viewing guest is offered no medium's report")
        assertEquals(NodeReport.Outcome.SENT, sent(sheet).sent?.outcome)
        val (kind, id, keys) = moderation.item(share.suite, moderation.received.single())
        assertEquals("ITEM" to mediaId, kind to id)
        assertTrue("BLOB" in keys, "the medium's blob key was not handed over")
        assertFalse("NODE" in keys, "a medium's report handed over the Memory")
        gallery.close()
        share.assertNoKeyInAnyRequest()
    }

    @Test
    fun anUploadLinksGuestReportsTheLink() = runTest {
        val share = PlayedShare(p, role = "WRITE_ONLY", rootKind = "DEPOT_DROPBOX")
        PlayedGuestContent(share).dropKey()
        val moderation = Moderation(share).also { it.linkId = share.shareId }
        val screen = GuestScreen(view(share), reportAsked = true)
        screen.load()
        val sheet = assertNotNull(screen.reportSheet())
        assertEquals(NodeReport.Outcome.SENT, sent(sheet).sent?.outcome)
        val (kind, _, keys) = moderation.item(share.suite, moderation.received.single())
        assertEquals("LINK", kind)
        assertContentEquals(share.kShare, keys["SHARE"])
    }
}
