package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.media.Derivation
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.b64url
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoriesGuest
import me.raddatz.veyq.mobile.memories.MemoryCreate
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.ShareLinks
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.Throwaway
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import me.raddatz.veyq.mobile.session.LiveHttp

/**
 * A received link opened by the core as its guest on a running dev instance, as a throwaway (ADR 0150; `VEYQ_LIVE`,
 * `VEYQ_LIVE_THROWAWAY_ENV`): the owner makes a Memory with a photo and a READ_WRITE link with a password on it; the core
 * opens the link **signed out** — the instance, the password, the terms, the note —, reads the photo through the link,
 * names its guest and uploads a photo, which the owner then lists as a guest's; the owner keeps the link as a bookmark in
 * the personal space and reads it back. Every request the guest's client sent is searched for `K_share`.
 *
 * What it made — the bookmark and the Memory (the link and the guest's photo with it) — is trashed and purged however it
 * ends, so the case holds on a throwaway it ran on before: the bookmark's name is the link's host (`BookmarkCreate`'s
 * default), which a kept bookmark would hold against the next run (inbox #1205).
 */
class ReceivedLinkLiveTest {
    init { me.raddatz.veyq.mobile.TestApplications.install(me.raddatz.veyq.mobile.memories.MemoriesKinds) }

    private val runs = mutableMapOf<String, LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.values.forEach { it.removeAll() }

    @Test
    fun aLinkOpensSignedOutAsItsGuest_readsUploadsAndIsKept() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val root = File(System.getProperty("veyq.repoRoot"))
        runTest(timeout = 5.minutes) {
            suspend fun session(who: String): TenantSession {
                val run = runs.getOrPut(who) { LiveRun(who, t.file) }
                val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(who), t.env.getValue("${who}_PASSWORD"), t.env.getValue("${who}_SECRET_KEY"), run.device()) { block -> LiveHttp.client(block) }
                return a.open(a.tenants.first { it.domain == domain }).also { it.rekeyRules = AppKinds.rules }
            }
            val owner = session("VEYQ")
            val scopeId = owner.spaces().first { it.kind == "TENANT" }.scopeId
            val personal = owner.spaces().first { it.kind == "PERSONAL" }.scopeId
            // what the case made, (scope, node), purged at its end
            val made = mutableListOf<Pair<String, String>>()
            var passed = false
            try {
                val memory = assertNotNull(MemoryCreate.create(owner, scopeId, null, MemoryCreate.MEMORY, "Received ${System.currentTimeMillis() % 100_000}").node).nodeId
                made += scopeId to memory
                val photo = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
                fun source(b: ByteArray) = ByteSource(b.size.toLong()) { from, length -> b.copyOfRange(from.toInt(), minOf(b.size.toLong(), from + length).toInt()) }
                MediaUpload.upload(owner, scopeId, memory, MediaUpload.Original(source(photo), "image/jpeg", "Owner.jpg", 1), Derivation.Derived(photo, photo, 4032, 3024), System.currentTimeMillis())
                val minted = ShareLinks.mint(owner, scopeId, memory, ShareLinks.READ_WRITE, "", "live password 1", null)
                val url = assertNotNull(minted.url, "no link minted: ${minted.refusal}")

                // the guest: no account, the platform's client with every request recorded
                val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
                val recorder = createClientPlugin("Record") {
                    onRequest { request, body -> sent += "${request.url.buildString()}\n${request.headers.entries()}\n${(body as? TextContent)?.text ?: ""}" }
                }
                val link = (ReceivedLink.open(url) as ReceivedLink.Parsed.Ok).link
                val view = GuestView.open(BouncyCastlePrimitives, link, { block -> LiveHttp.client { block(); install(recorder) } }, MemoryDeviceStore(), MemoryGuestSessions(), { "en" })
                view.load()
                assertEquals(ShareGates.State.Locked(false), view.gates.state.value)
                view.gates.submitPassword("live password 1")
                while (true) when (view.gates.state.value) {
                    is ShareGates.State.Terms -> view.gates.acceptTerms()
                    is ShareGates.State.Note -> view.gates.acceptNote()
                    else -> break
                }
                assertIs<ShareGates.State.Ready>(view.gates.state.value, "the gates did not open: ${view.gates.state.value}")
                view.enter()
                val gallery = assertIs<MemoriesGuest.Gallery>(view.page.value)
                assertTrue(gallery.model.rights.upload)
                val access = assertNotNull(view.access)
                val door = MemoriesGuest.GuestGalleryDoor(access, access.tree!!.root)
                val item = assertNotNull(door.gallery()).items.single()
                assertContentEquals(photo, door.read(item, item.original), "the guest read another photo than the owner uploaded")
                access.nameGuest("Live guest")
                val prepared = MediaUpload.prepare(MediaUpload.Original(source(photo), "image/jpeg", "Guest.jpg", 2), System.currentTimeMillis()) { Derivation.Derived(photo, photo, 4032, 3024) }
                val up = MemoriesGuest.send(access, access.session.value!!, scopeId, access.tree!!.root.key, access.rootEpoch, null, MediaUpload.Original(source(photo), "image/jpeg", "Guest.jpg", 2), prepared) {}
                assertEquals(MediaUpload.Outcome.DONE, up.outcome, "the guest's upload: ${up.code}")
                val listed = assertNotNull(MemoryMedia.list(session("VEYQ"), scopeId, memory)).items
                assertEquals(2, listed.size)
                assertTrue(listed.any { it.ownerKey?.startsWith("share:") == true }, "the owner does not see the guest's photo as a guest's")

                // no request of the visit held the key
                val key = link.key()
                val canaries = listOf(b64url(key), b64(key), b64(key).trimEnd('='), hex(key))
                assertTrue(sent.size >= 6)
                for (r in sent) for (c in canaries) if (r.contains(c)) fail("K_share reached the server: ${r.lineSequence().first()}")
                view.close()

                // the owner keeps the link in the personal space and reads it back
                val created = BookmarkCreate.into(owner, personal, null).create(link.host, link, "live password 1")
                val nodeId = assertIs<NodeCreate.Result.Done>(created, "the bookmark under the link's host: $created").created.nodeId
                made += personal to nodeId
                val kept = assertNotNull(BookmarkMeta.open(owner.account.suite, assertNotNull(owner.node(personal, nodeId))))
                assertEquals(link.link, kept.link.link)
                assertEquals("live password 1", kept.password)
                println("[live] a READ_WRITE link opened signed out: password, gates, the owner's photo read, the guest named and its photo listed as a guest's; ${sent.size} requests, none with K_share; kept as a bookmark in the personal space")
                passed = true
            } finally {
                // the bookmark first, then the Memory: each trashed and purged for good, so a next run finds neither
                val left = made.asReversed().mapNotNull { (scope, node) ->
                    val deleted = NodeTrash.delete(owner, scope, node)
                    val purged = NodeTrash.purge(owner, scope, node)
                    "$node: $deleted, $purged".takeUnless { deleted == NodeTrash.Deleted.TRASHED && purged == NodeTrash.Purged.PURGED }
                }
                // a failure of the case itself stays the one reported
                if (passed) assertTrue(left.isEmpty(), "what the case made was not purged: $left")
                println("[live] purged what the case made: ${made.size - left.size} of ${made.size} nodes")
            }
        }
    }
}
