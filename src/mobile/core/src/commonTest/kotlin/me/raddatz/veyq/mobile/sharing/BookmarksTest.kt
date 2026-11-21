package me.raddatz.veyq.mobile.sharing

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.api.models.ReportSubmissionItemsInner
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ReportSheet
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.testPrimitives
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bookmarks in the apps and *Open a link* (ADR 0150 decisions 4, 6, 8): the meta as the web seals it, its padding, the
 * dialog, the page in place or on its own, the two reports, *Back* that asks, and *Keep* only for a signed-in person.
 */
class BookmarksTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val p = testPrimitives
    private val suite = V1(p)
    private val LINK = "https://photos.example.org:8443/s/" + "Ab3_-".padEnd(43, 'x') + "#k=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"

    /** Sealed by the web's `sealNodeMeta(bookmarkMetaObject(...))` (vitest, 2026-11-19): what a web-made bookmark holds. */
    private object WebFixture {
        const val KEY = "AwoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dw="
        const val SCOPE = "0c6f4a1e-2b8d-4c3a-9f10-7e5d2a1b3c4d"
        const val NODE = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        const val META = "A/B4oT7FcIwK0hpqURKd7boIV58VNLlSYYSoAeaJJriVKhe2d5L/X9EbxcrhMZ1Y82ajv7rcQ64W/APwiJ4WGs5luDZOer3WYBVEKO/LIPLvS+GfMsYiI8AVfSo0rlhKkTdCInSOApKK4RTc/M0RS3pGCev3lM3MOKlMSt7Sxf48oTbFZs1RCVtg19/nWi/eZhuTR5lMxUYBIYtBheoPHl5Cr9aWawazDVUBFvK80MjGnvgwLzMDXurd/fqMni3gSXEXaUKwn0S/3iEQO/tpiVqYFD9mfUOcUgAKXIzWRcaXJPzXpVYicInGSNJ7D8BAPYwmz1n97AgPFgM3c2UgHvQ141cWc09q7ioTb9J60dLr6l2SFAEZ1tdVCbksE+WAqa221sW5zXHCwKbB8GGwo5NIItmJ8HJulft+xK95vD7jNbnCofNFBh127UeN+Q517G+I/4l7I4xEPJFlw8DckXtLJzgkWQNOxWKNWV2M8HKsmWMg/gUwx1me4vMAh6k+odRPt3izIWX3M1e3lUKfsd8DZfNL06PpdKAfciJSaeD4vxuG8a0tpD/mt+lwtCe84ciW16LEB1mPQBjSw3+iL4AL62xRnyjse5WCQO4kwZHEll2g/sspWdR+1r38GnNbHVYKz8ucv74uC1u0U9hHSytu2XwjsaDkdMAzyTGnrPf3nnNr4N5NWlP1I2ttZhC4aefiNhB+jZrSWTprUE5UIj9biZoIWLVhXRDwFEg="
        const val PASSWORD = "Geheimnis-ü"
    }

    private fun link(text: String = LINK) = (ReceivedLink.parse(text) as ReceivedLink.Parsed.Ok).link

    @Test
    fun aMetaTheWebSealedOpens() {
        val envelope = NodeMetaWrite.openForWrite(suite, WebFixture.SCOPE, WebFixture.NODE, unb64(WebFixture.KEY), WebFixture.META)
        val b = assertNotNull(BookmarkMeta.of(envelope), "the web's bookmark does not read")
        assertEquals(LINK, b.link.link)
        assertEquals(WebFixture.PASSWORD, b.password)
        // the web pads the same way: the plaintext it sealed is a multiple of the pad
        assertEquals(0, BookmarkMeta.plaintextOf(envelope).size % BookmarkMeta.PAD)
    }

    @Test
    fun aMetaTheCoreSealsIsPaddedAndHoldsWhatTheSchemaAllows() {
        val links = listOf(LINK, "http://veyq.localhost:8081/s/" + "z".repeat(43) + "#k=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc")
        val schemaLink = Regex("^https?://[^/?#@]+/s/[A-Za-z0-9_-]{43}#k=[A-Za-z0-9_-]{43}$")
        for (l in links) for (pw in listOf(null, "x", "ü".repeat(200), "p".repeat(BookmarkMeta.MAX_PASSWORD_LENGTH))) {
            val envelope = BookmarkMeta.envelope(link(l), pw)
            assertEquals(0, BookmarkMeta.plaintextOf(envelope).size % BookmarkMeta.PAD, "a meta of ${l.take(20)}/${pw?.length} is not padded")
            val sub = envelope[BookmarkMeta.KEY] as JsonObject
            assertEquals(1, sub["v"]!!.jsonPrimitive.content.toInt())
            assertTrue(schemaLink.matches(sub["link"]!!.jsonPrimitive.content))
            assertTrue(sub["pad"]!!.jsonPrimitive.content.all { it == ' ' })
            assertEquals(pw, sub["password"]?.jsonPrimitive?.content)
            assertEquals(setOf("v", "link", "pad") + (if (pw != null) setOf("password") else emptySet()), sub.keys)
            // sealed under a key and read back
            val key = p.randomBytes(32)
            val sealed = BookmarkMeta.seal(suite, WebFixture.SCOPE, WebFixture.NODE, key, link(l), pw)
            val back = BookmarkMeta.of(NodeMetaWrite.openForWrite(suite, WebFixture.SCOPE, WebFixture.NODE, key, sealed))
            assertEquals(l, back?.link?.link)
            assertEquals(pw, back?.password)
        }
    }

    @Test
    fun aMetaThatIsNoKeptLinkOpensNothing() {
        fun env(sub: Map<String, Any?>) = JsonObject(mapOf("v" to JsonPrimitive(1), "bookmark" to JsonObject(sub.mapValues { (_, v) -> JsonPrimitive(v as String?) })))
        assertNull(BookmarkMeta.of(null))
        assertNull(BookmarkMeta.of(JsonObject(mapOf("v" to JsonPrimitive(1)))))
        assertNull(BookmarkMeta.of(env(mapOf("link" to "$LINK&report"))))
        assertNull(BookmarkMeta.of(env(mapOf("link" to LINK, "password" to ""))))
        assertNotNull(BookmarkMeta.of(env(mapOf("link" to LINK))))
    }

    @Test
    fun theDialogChecksTheLinkAsTypedNamesItByItsHostAndCreates() = runTest {
        var made: Triple<String, String, String?>? = null
        val dialog = BookmarkCreate({ name, l, pw -> made = Triple(name, l.link, pw); NodeCreate.Result.Done(NodeCreate.Created("n1", ByteArray(32), 0, 0)) }, groupSpace = true)
        dialog.link("https://photos.example.org/x/y")
        assertEquals(Msg.LINK_PROBLEM_NOT_A_SHARE, dialog.state.value.problem)
        assertFalse(dialog.state.value.canSave)
        dialog.link("  $LINK  ")
        assertNull(dialog.state.value.problem)
        assertEquals("photos.example.org:8443", dialog.state.value.nameShown)
        dialog.password("pw")
        assertTrue(dialog.save())
        assertEquals(Triple("photos.example.org:8443", LINK, "pw"), made)
        assertEquals("n1", dialog.state.value.created)
    }

    @Test
    fun reportNameSendsTheNameOnlyNeverTheBookmarksKey() = runTest {
        // the wire's item for a kept link is NAME whatever is asked; any other kind as asked
        assertEquals(ReportSubmissionItemsInner.Kind.NAME, NodeReport.itemKind(NodeReport.scopeOf(AppKinds.BOOKMARK_KIND, NodeReport.Scope.WHOLE)))
        assertEquals(ReportSubmissionItemsInner.Kind.NODE, NodeReport.itemKind(NodeReport.scopeOf("FOLDER", NodeReport.Scope.WHOLE)))
        // and the sheet offers no whole, sending the name though the whole was asked
        val sent = mutableListOf<NodeReport.Scope>()
        val sheet = ReportSheet(NodeAt("s", "n", AppKinds.BOOKMARK_KIND, "Link"), object : ReportSheet.Door {
            override val origin = "https://a.example"
            override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String): NodeReport.Sent {
                sent += scope
                return NodeReport.Sent(NodeReport.Outcome.SENT, "r")
            }
        }, nameOnly = true)
        assertFalse(sheet.offersName, "a kept link's sheet offers no choice of the whole")
        sheet.whole(true)
        sheet.category(NodeReport.CATEGORIES.first().token)
        sheet.reason("spam")
        sheet.goodFaith(true)
        sheet.send()
        assertEquals(listOf(NodeReport.Scope.NAME), sent)
        // a download and a copy leave a kept link out (and name it, NodeExport's walk)
        assertFalse(AppKinds.exports("x").exports(AppKinds.BOOKMARK_KIND))
        assertFalse(AppKinds.copies("x") { "u" }.copies(AppKinds.BOOKMARK_KIND))
    }

    private fun screenOf(share: PlayedShare, tenant: me.raddatz.veyq.mobile.session.TenantSession? = null) =
        GuestScreen(GuestView.over(link(share.link), share.client(), MemoryDeviceStore(), MemoryGuestSessions()), tenant)

    @Test
    fun openALinkRefusesWhatItDoesNotOpenAndOffersTheBrowserForAReview() {
        val opened = mutableListOf<Pair<String, Boolean>>()
        val share = PlayedShare(p)
        val model = OpenLink { l, report -> opened += l.host to report; GuestView.over(l, share.client(), MemoryDeviceStore(), MemoryGuestSessions()) }
        model.text("not a link")
        assertEquals(Msg.LINK_PROBLEM_NOT_A_URL, model.state.value.problem)
        assertNull(model.open())
        val review = "${share.origin}/s/${share.shareId}#moderation"
        model.text(review)
        assertEquals(Msg.LINK_PROBLEM_MODERATION, model.state.value.problem)
        assertEquals(review, model.state.value.browser)
        model.text(share.link + "&report")
        assertTrue(model.state.value.canOpen)
        assertNotNull(model.open())
        assertEquals(listOf("photos.example.org" to true), opened)
        assertEquals("", model.state.value.text, "the text that holds the key was kept after it opened")
    }

    @Test
    fun backAtTheRootAsksFirstThenLetsTheLinkGo() = runTest {
        val share = PlayedShare(p)
        val screen = screenOf(share)
        screen.load()
        assertIs<GuestPage.Level>(screen.view.page.value)
        assertFalse(screen.keepOffered, "Keep offered to someone not signed in")
        screen.back()
        assertTrue(screen.state.value.leaving, "Back at the root left without asking")
        screen.stay()
        assertFalse(screen.state.value.leaving)
        assertNotNull(screen.view.gates.head)
        screen.back()
        val head = screen.view.gates.head!!
        screen.leave()
        assertTrue(screen.state.value.left)
        assertTrue(head.kShare.all { it == 0.toByte() }, "the key outlived the left link")
    }

    @Test
    fun anInstanceTheAppDoesNotOpenOffersTheLinkToTheBrowser() = runTest {
        val share = PlayedShare(p, major = 99)
        val screen = screenOf(share)
        screen.load()
        assertEquals(share.link, screen.browserLink)
    }

    @Test
    fun aBookmarksPageShowsTheShareInPlaceWhileEmbeddingIsOnElseOnItsOwn() = runTest {
        val share = PlayedShare(p, password = "pw")
        fun page(embedding: Boolean?, kept: BookmarkMeta.Bookmark?) = BookmarkPage(NodeAt("s", "n", AppKinds.BOOKMARK_KIND, "Link"), object : BookmarkPage.Door {
            override suspend fun bookmark() = kept
            override fun embedding() = embedding
            override fun guest(link: ReceivedLink.ShareLink, password: String?) =
                GuestView.over(link, share.client(), MemoryDeviceStore(), MemoryGuestSessions(), handedPassword = password)
            override fun reportName() = error("unused")
        })
        val kept = BookmarkMeta.Bookmark(link(share.link), "pw")
        val inPlace = page(true, kept).also { it.load() }
        val shown = assertIs<BookmarkPage.State.InPlace>(inPlace.state.value)
        // the kept password was handed in memory: the share opened past its password gate
        assertIs<GuestPage.Level>(shown.screen.view.page.value)
        assertIs<BookmarkPage.State.OnItsOwn>(page(false, kept).also { it.load() }.state.value)
        assertIs<BookmarkPage.State.OnItsOwn>(page(null, kept).also { it.load() }.state.value)
        assertIs<BookmarkPage.State.Unreadable>(page(true, null).also { it.load() }.state.value)
        val own = assertNotNull(page(false, kept).also { it.load() }.openOwnPage())
        own.load()
        assertIs<GuestPage.Level>(own.view.page.value)
        assertTrue(assertNotNull(page(false, kept).also { it.load() }.reportShared()).reportAsked)
        share.assertNoKeyInAnyRequest()
    }
}
