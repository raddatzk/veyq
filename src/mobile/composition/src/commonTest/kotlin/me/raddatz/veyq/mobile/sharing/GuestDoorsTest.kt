package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.depot.replace

import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.depot.DepotGuest

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.depot.DepotFiles
import me.raddatz.veyq.mobile.depot.FileModel
import me.raddatz.veyq.mobile.expenses.GuestPotDoor
import me.raddatz.veyq.mobile.expenses.PotGuest
import kotlinx.coroutines.async
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.expenses.PotView
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.MemoriesGuest
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each kind's guest door (ADR 0150 decision 5): a link's node opened as the member's own page of its kind with a guest's
 * rights — over a played instance whose link serves one node of every kind ([PlayedGuestContent]).
 */
class GuestDoorsTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val p = testPrimitives

    private class Fixture(val share: PlayedShare, val content: PlayedGuestContent, val file: String, val memory: String, val pot: String)

    private fun fixture(role: String): Fixture {
        val share = PlayedShare(p, role = role)
        val content = PlayedGuestContent(share)
        val (file, _) = content.file(share.rootId, share.rootKey, "notes.txt", "hello guest".encodeToByteArray())
        val (memory, _) = content.memory(share.rootId, share.rootKey, "Trip", "a photo's bytes".encodeToByteArray())
        val pot = content.pot(share.rootId, share.rootKey, "Dinner")
        return Fixture(share, content, file, memory, pot)
    }

    private fun view(share: PlayedShare, sessions: GuestSessions = MemoryGuestSessions()) =
        GuestView.over((ReceivedLink.open(share.link) as ReceivedLink.Parsed.Ok).link, share.client(), MemoryDeviceStore(), sessions)

    @Test
    fun eachKindOfALinkOpensAsItsOwnApplicationsPage() = runTest {
        val f = fixture("READ")
        val v = view(f.share)
        v.load()
        val level = assertIs<GuestPage.Level>(v.page.value, "the root folder is not core's level")
        assertEquals(setOf("notes.txt" to "FILE", "Trip" to "MEMORY", "Dinner" to "EXPENSES_POT"), level.model.rows.map { it.name to it.kind }.toSet())
        v.open(f.file)
        assertIs<DepotGuest.File>(v.page.value, "a file did not open as Depot's file page")
        assertTrue(v.back())
        v.open(f.memory)
        assertIs<MemoriesGuest.Gallery>(v.page.value, "a Memory did not open as its gallery")
        v.back()
        v.open(f.pot)
        assertIs<PotGuest.Pot>(v.page.value, "a Pot did not open as its page")
        v.back()
        assertTrue(v.atRoot)
        assertFalse(v.back(), "Back at the root stayed inside the link instead of asking")
        // a kind without a guest answer is the share page's "not shown here", never a half view
        val unknown = PlayedShare(p, rootKind = "SOMETHING_NEW")
        val u = view(unknown)
        u.load()
        assertEquals(ShareGates.State.Unavailable(false), u.gates.state.value)
    }

    @Test
    fun aReadGuestsMemoryOffersNoUploadNoMarksNoContributionsAndReadsItsMedia() = runTest {
        val f = fixture("READ")
        val v = view(f.share)
        v.load()
        v.open(f.memory)
        val gallery = assertIs<MemoriesGuest.Gallery>(v.page.value).model
        val rights = gallery.rights
        assertTrue(rights.download && rights.navigate)
        assertFalse(rights.upload, "a READ guest is offered an upload")
        assertFalse(rights.mark, "a READ guest is offered marks")
        assertFalse(rights.buckets, "a READ guest is offered the contributions")
        assertFalse(rights.mediaTrash || rights.editMeta || rights.setBanner || rights.createChapter)
        // the guest's media read through the link, a blob opened under the node's key
        val access = assertNotNull(v.access)
        val door = MemoriesGuest.GuestGalleryDoor(access, access.tree!!.node(f.memory)!!)
        val listed = assertNotNull(door.gallery()).items.single()
        assertContentEquals("a photo's bytes".encodeToByteArray(), door.read(listed, listed.original))
        assertEquals(0, door.upload(emptyList()))
        f.share.assertNoKeyInAnyRequest()
        gallery.close()
    }

    @Test
    fun aReadWriteGuestsMemoryOffersUploadAndOwnDeletesOnly() = runTest {
        val f = fixture("READ_WRITE")
        val v = view(f.share)
        v.load()
        v.open(f.memory)
        val rights = assertIs<MemoriesGuest.Gallery>(v.page.value).model.rights
        assertTrue(rights.upload)
        assertEquals(me.raddatz.veyq.mobile.memories.MemoryRights.DeleteMedia.OWN, rights.deleteMedia)
        assertFalse(rights.mark || rights.buckets || rights.mediaTrash)
    }

    @Test
    fun aReadWriteGuestsFileOffersReplaceAndAReadGuestsDoesNot() = runTest {
        for ((role, replaces) in listOf("READ_WRITE" to true, "READ" to false)) {
            val f = fixture(role)
            val v = view(f.share)
            v.load()
            v.open(f.file)
            val model = assertIs<DepotGuest.File>(v.page.value).model
            val header = withContext(kotlinx.coroutines.Dispatchers.Default) { withTimeout(10_000) { model.state.first { it.header != null }.header!! } }
            assertEquals(replaces, header.own.any { it == me.raddatz.veyq.mobile.navigation.HeaderAction.REPLACE }, "$role's file offers replace = ${!replaces}")
            assertTrue(header.facts.hasContent)
            model.close()
        }
    }

    @Test
    fun aReadWriteGuestReplacesAFilesContentAsItsNamedIdentity() = runTest {
        val f = fixture("READ_WRITE")
        val v = view(f.share)
        v.load()
        val access = assertNotNull(v.access)
        access.nameGuest("Ben")
        val door = me.raddatz.veyq.mobile.depot.DepotGuest.GuestFileDoor(access)
        val node = access.tree!!.node(f.file)!!
        val file = assertNotNull(door.file(me.raddatz.veyq.mobile.navigation.NodeAt(node.scopeId, node.id, node.kind, node.name)))
        val chunks = ArrayList<ByteArray>()
        door.read(file, 0, Int.MAX_VALUE) { chunks += it }
        assertEquals("hello guest", chunks.single().decodeToString())
        val job = door.replaceJob(file, source("new.txt", "new content"), file.blobId)
        job.run {}
        assertEquals(1, f.content.completed.size)
        f.share.assertNoKeyInAnyRequest()
    }

    /**
     * #1035: a file page's Replace queues on the link's own queue — no tenant's, so a signed-out guest replaces as on the
     * web's share page —, only for a named READ_WRITE guest who chose Replace; the page reads the file anew after.
     */
    @Test
    fun aGuestsReplaceQueuesOnTheLinksOwnQueueOnceNamedAndChosen() = runTest {
        suspend fun opened(role: String, queue: me.raddatz.veyq.mobile.blob.UploadQueue?): Triple<Fixture, GuestView, FileModel> {
            val f = fixture(role)
            val v = GuestView.over((ReceivedLink.open(f.share.link) as ReceivedLink.Parsed.Ok).link, f.share.client(), MemoryDeviceStore(), MemoryGuestSessions(), queue = queue)
            v.load()
            v.open(f.file)
            val model = assertIs<DepotGuest.File>(v.page.value).model
            withContext(kotlinx.coroutines.Dispatchers.Default) { withTimeout(10_000) { model.state.first { it.header != null } } }
            return Triple(f, v, model)
        }
        // a READ link's file takes no replace, nor does a READ_WRITE one without a queue
        val (_, readView, readFile) = opened("READ", me.raddatz.veyq.mobile.blob.UploadQueue())
        assertFalse(readFile.chooseReplace())
        assertFalse(readView.access!!.replace(readFile, source("x.txt", "x")))
        val (_, bare, bareFile) = opened("READ_WRITE", null)
        bare.access!!.nameGuest("Ben")
        assertTrue(bareFile.chooseReplace())
        assertFalse(bare.access!!.replace(bareFile, source("x.txt", "x")), "a replace was queued without the link's queue")

        val queue = me.raddatz.veyq.mobile.blob.UploadQueue()
        val (f, v, file) = opened("READ_WRITE", queue)
        val access = assertNotNull(v.access)
        // before the guest named itself nothing is queued (the web asks the name first)
        assertTrue(file.chooseReplace())
        assertFalse(access.replace(file, source("new.txt", "new content")), "an unnamed guest's replace was queued")
        assertTrue(queue.state.value.batches.isEmpty())
        access.nameGuest("Ben")
        assertTrue(access.replace(file, source("new.txt", "new content")), "a named guest's chosen replace was not queued")
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            withTimeout(10_000) { queue.state.first { s -> s.batches.isNotEmpty() && s.batches.all { b -> b.finished } } }
        }
        assertEquals(1, f.content.completed.size, "the replace's content was not completed")
        assertFalse(access.replace(file, source("again.txt", "again")), "a choice was used twice")
        // the settle's re-read of the file (the page following the replace) ends before the requests are searched
        file.close()
        v.close()
        withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(300) }
        f.share.assertNoKeyInAnyRequest()
    }

    @Test
    fun aReadGuestsPotOffersNoEntryAndAReadWriteGuestsOnlyOnceNamed() = runTest {
        for (role in listOf("READ", "READ_WRITE")) {
            val f = fixture(role)
            val v = view(f.share)
            v.load()
            val access = assertNotNull(v.access)
            val session = PotSession.open(GuestPotDoor(access), access.tree!!.node(f.pot)!!)
            assertFalse(session.canWrite, "$role guest may write entries before naming itself (or ever, READ)")
            assertFalse(session.canChangeSettings, "a guest is offered the Pot's settings")
            assertNull(session.members, "a guest read the space's members")
            if (role == "READ_WRITE") {
                access.nameGuest("Ada")
                val door = GuestPotDoor(access)
                assertTrue(door.writesEntries)
            } else {
                assertFalse(GuestPotDoor(access).writesEntries)
            }
        }
    }

    /**
     * ADR 0154 decision 1, the web's rule (`potSession.ts`, a guest names itself first): a READ_WRITE link's guest not named
     * yet is offered *Add entry* — a READ one is not —, and its write asks its name through the core's one naming flow
     * ([GuestNaming]); declined, nothing is sent ([PotSession.WriteOutcome.UNNAMED]); named, the write goes on as it.
     */
    @Test
    fun aReadWriteUnnamedGuestIsOfferedAddEntryAndAskedItsNameAtTheWrite() = runTest {
        val read = assertNotNull(view(fixture("READ").share).also { it.load() }.access)
        assertFalse(PotGuest.offersEntries(read), "a READ guest is offered Add entry")
        assertFalse(read.naming.offered, "a READ guest is offered to name itself")
        val f = fixture("READ_WRITE")
        val v = view(f.share)
        v.load()
        val access = assertNotNull(v.access)
        val door = GuestPotDoor(access)
        assertTrue(door.offersEntries, "an unnamed READ_WRITE guest is not offered Add entry")
        assertTrue(access.naming.offered, "a READ_WRITE guest is not offered to name itself")
        assertFalse(door.writesEntries)
        val session = PotSession.open(door, access.tree!!.node(f.pot)!!)
        assertEquals(session.offersEntries, me.raddatz.veyq.mobile.expenses.PotView(session).shown().offersEntries)
        // what the wire carries is the core's offer, never `canWrite`: Expenses' `PotViewGuestTest`
        val requests = f.share.requests.size
        // the write asks the name; declined, it is not made
        val declined = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { session.write(emptyList()) }
        withContext(kotlinx.coroutines.Dispatchers.Default) { withTimeout(5_000) { access.naming.asking.first { it } } }
        access.naming.decline()
        assertEquals(PotSession.WriteOutcome.UNNAMED, declined.await())
        assertFalse(access.naming.asking.value)
        assertEquals(requests, f.share.requests.size, "a declined guest's write reached the server")
        // asked again: an empty name keeps the dialog open, a name goes on as the guest
        val named = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { door.named() }
        withContext(kotlinx.coroutines.Dispatchers.Default) { withTimeout(5_000) { access.naming.asking.first { it } } }
        assertTrue(access.naming.name("  ").keepsOpen, "an empty name closed the dialog")
        assertTrue(access.naming.asking.value)
        assertEquals(me.raddatz.veyq.mobile.navigation.NameAnswer.DONE, access.naming.name("Ada"))
        assertTrue(named.await(), "the named guest's write did not go on")
        assertTrue(door.writesEntries)
        // named, a write asks nothing again
        assertTrue(door.named())
        assertFalse(access.naming.asking.value)
    }

    @Test
    fun aDropBoxsGuestNamesItselfAndAddsAFileIntoItsOwnFolder() = runTest {
        val share = PlayedShare(p, role = "WRITE_ONLY", rootKind = "DEPOT_DROPBOX")
        val content = PlayedGuestContent(share)
        content.dropKey()
        val sessions = MemoryGuestSessions()
        val v = view(share, sessions)
        v.load()
        val drop = assertIs<DepotGuest.DropBox>(v.page.value).model
        assertTrue(drop.name("Ben"))
        val session = assertNotNull(drop.session.value)
        assertNotNull(session.uploaderKey, "the uploader's K_up was not kept")
        // its session went to the credential store, under a name that is not the link's id
        assertFalse(sessions.names.single().contains(share.shareId))
        val identity = share.requests.last { it.path.endsWith("/identities") }.json()
        assertNotNull(identity["wrappedIdentityKey"], "K_up was not wrapped to the drop key")
        val done = drop.send(source("photo.txt", "dropped"))
        assertEquals(DepotFiles.Outcome.DONE, done.outcome)
        assertEquals(1, content.dropFolders)
        assertEquals("FILE", content.created.single()["kind"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(listOf("photo.txt"), drop.sent.value)
        drop.send(source("second.txt", "again"))
        assertEquals(1, content.dropFolders, "the guest's folder was made twice")
        share.assertNoKeyInAnyRequest()
    }

    @Test
    fun aMemorysUploaderNamesItselfAndSendsUnderItsOwnKey() = runTest {
        val share = PlayedShare(p, role = "WRITE_ONLY", rootKind = "MEMORY")
        val content = PlayedGuestContent(share)
        content.dropKey()
        val v = view(share)
        v.load()
        val uploader = assertIs<MemoriesGuest.Uploader>(v.page.value).model
        assertTrue(uploader.name("Ada"))
        val bytes = "a picture".encodeToByteArray()
        val original = MediaUpload.Original(ByteSource(bytes.size.toLong()) { from, length -> bytes.copyOfRange(from.toInt(), (from + length).toInt()) }, "image/jpeg", "a.jpg", null)
        val prepared = MediaUpload.Prepared("image/jpeg", "sha", Derivation.Derived(null, null, null, null), null, null)
        val r = uploader.send(original, prepared, {}, "a.jpg")
        assertEquals(MediaUpload.Outcome.DONE, r.outcome)
        assertEquals(1, uploader.sent.value.size)
        assertEquals(1, content.completed.size)
        share.assertNoKeyInAnyRequest()
    }

    /** #1034: a READ_WRITE folder's guest adds a folder and files at the level, as the web's share page offers; a READ guest nothing. */
    @Test
    fun aReadWriteFoldersGuestAddsAFolderAndFilesAtTheLevel() = runTest {
        val read = fixture("READ")
        val r = view(read.share)
        r.load()
        val readLevel = assertIs<GuestPage.Level>(r.page.value).model
        assertFalse(readLevel.writes, "a READ guest's level offers a write")
        assertEquals(emptyList(), readLevel.creates, "a READ guest's New offers something")
        assertEquals(0, readLevel.upload(listOf(source("x.txt", "x"))))

        val f = fixture("READ_WRITE")
        val queue = me.raddatz.veyq.mobile.blob.UploadQueue()
        val v = GuestView.over((ReceivedLink.open(f.share.link) as ReceivedLink.Parsed.Ok).link, f.share.client(), MemoryDeviceStore(), MemoryGuestSessions(), queue = queue)
        v.load()
        val level = assertIs<GuestPage.Level>(v.page.value).model
        assertTrue(level.writes, "a READ_WRITE guest's level offers no write")
        assertEquals(listOf(me.raddatz.veyq.mobile.AppKinds.Create.FOLDER, me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FILES, me.raddatz.veyq.mobile.AppKinds.Create.UPLOAD_FOLDER), level.creates, "a READ_WRITE guest's New: a folder, files, a folder uploaded (owner 2026-11-22)")
        // nothing is written before the guest named itself (the web's `requestGuest`): the write asks the name through the
        // core's one naming flow (ADR 0154 decision 1); declined, nothing is made or queued
        val access = assertNotNull(v.access)
        val declinedFolder = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { level.createFolder("Neu") }
        withContext(kotlinx.coroutines.Dispatchers.Default) { withTimeout(5_000) { access.naming.asking.first { it } } }
        access.naming.decline()
        assertNotNull(declinedFolder.await().notice, "a declined guest's folder was not said")
        val declinedUpload = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { level.uploadAsking(listOf(source("x.txt", "x"))) }
        withContext(kotlinx.coroutines.Dispatchers.Default) { withTimeout(5_000) { access.naming.asking.first { it } } }
        access.naming.decline()
        assertEquals(0, declinedUpload.await())
        assertEquals(0, level.upload(listOf(source("x.txt", "x"))))
        assertTrue(f.content.created.isEmpty())
        // named at the write: the folder the write asked for is made as the guest
        val namedFolder = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { level.createFolder("Neu") }
        withContext(kotlinx.coroutines.Dispatchers.Default) { withTimeout(5_000) { access.naming.asking.first { it } } }
        assertEquals(me.raddatz.veyq.mobile.navigation.NameAnswer.DONE, access.naming.name("Ben"))
        assertEquals(me.raddatz.veyq.mobile.navigation.NameAnswer.DONE, namedFolder.await(), "the guest's new folder was refused")
        assertFalse(access.naming.asking.value, "a named guest was asked again")
        val made = f.content.created.single()
        assertEquals("FOLDER" to f.share.rootId, made["kind"]!!.jsonPrimitive.content to made["parentId"]!!.jsonPrimitive.content)
        // the link read anew: the level on screen lists the folder, opened under the key the guest minted
        val after = assertIs<GuestPage.Level>(v.page.value).model
        assertTrue(after.rows.any { it.name == "Neu" && it.kind == "FOLDER" }, "the new folder is not listed after the write")
        assertTrue(v.atRoot)
        assertEquals(me.raddatz.veyq.mobile.words.Msg.FOLDER_NAME_TAKEN, after.createFolder("Neu").problem?.msg, "a sibling's name was not said")
        // inside it: a file uploaded as the guest, its node made under the folder's key and its content completed
        val folder = after.rows.single { it.name == "Neu" }.nodeId
        v.open(folder)
        val inner = assertIs<GuestPage.Level>(v.page.value).model
        assertEquals(1, inner.upload(listOf(source("notes2.txt", "guest text"))))
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            withTimeout(10_000) { queue.state.first { s -> s.batches.isNotEmpty() && s.batches.all { b -> b.finished } } }
            withTimeout(10_000) { v.page.first { p -> (p as? GuestPage.Level)?.model?.rows?.any { it.name == "notes2.txt" } == true } }
        }
        val file = f.content.created.last()
        assertEquals("FILE" to folder, file["kind"]!!.jsonPrimitive.content to file["parentId"]!!.jsonPrimitive.content)
        assertEquals(1, f.content.completed.size, "the file's content was not completed")
        assertEquals(listOf(f.share.rootId, folder), v.stack.value.map { it.nodeId }, "the reload lost the guest's place")
        // a folder uploaded whole as the guest (owner 2026-11-22): its folders made top-down under the level, each file into
        // its own folder, a link said and left out, the guest named already so nothing asked
        val here = assertIs<GuestPage.Level>(v.page.value).model
        val before = f.content.created.size
        val up = me.raddatz.veyq.mobile.depot.DepotGuest.uploadFolder(
            here, null, "/home/ben/Trip",
            listOf(
                me.raddatz.veyq.mobile.depot.FolderUpload.Found("a.txt", me.raddatz.veyq.mobile.depot.FolderUpload.Kind.FILE, source("a.txt", "a")),
                me.raddatz.veyq.mobile.depot.FolderUpload.Found("sub", me.raddatz.veyq.mobile.depot.FolderUpload.Kind.DIRECTORY),
                me.raddatz.veyq.mobile.depot.FolderUpload.Found("sub/b.txt", me.raddatz.veyq.mobile.depot.FolderUpload.Kind.FILE, source("b.txt", "b")),
                me.raddatz.veyq.mobile.depot.FolderUpload.Found("elsewhere", me.raddatz.veyq.mobile.depot.FolderUpload.Kind.LINK),
            ),
        )
        assertEquals(2, up.queued, "a guest's folder upload queued not its two files")
        assertEquals(listOf(me.raddatz.veyq.mobile.words.Msg.DEPOT_FOLDER_LINK_SKIPPED), up.said.map { it.msg }, "the link left out was not said")
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            withTimeout(10_000) { queue.state.first { s -> s.batches.size == 2 && s.batches.all { b -> b.finished } } }
        }
        val tree = f.content.created.drop(before).map { it["nodeId"]!!.jsonPrimitive.content to (it["kind"]!!.jsonPrimitive.content to it["parentId"]!!.jsonPrimitive.content) }
        val (top, topAt) = tree[0]
        assertEquals("FOLDER" to folder, topAt, "the top folder is not made in the level")
        val sub = tree.single { it.second == ("FOLDER" to top) }.first
        assertEquals(setOf("FILE" to top, "FILE" to sub), tree.filter { it.second.first == "FILE" }.map { it.second }.toSet(), "a file not in its own folder")
        assertEquals(3, f.content.completed.size, "a folder upload's files were not completed")
        // the link read anew lists the folder made; nothing held nothing, so nothing was taken back
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            withTimeout(10_000) { v.page.first { p -> (p as? GuestPage.Level)?.model?.rows?.any { it.nodeId == top } == true } }
        }
        v.close()
        withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(300) }
        f.share.assertNoKeyInAnyRequest()
    }

    /** #1034: a guest's Memory plays its music with the player the host handed the link, as the member's. */
    @Test
    fun aGuestsMemoryHasTheHandedMusicPlayer() = runTest {
        val f = fixture("READ")
        val player = me.raddatz.veyq.mobile.audio.MusicPlayer({ _ -> NoDeck }, null, me.raddatz.veyq.mobile.audio.randomIntOf { p.randomBytes(it) })
        val music = me.raddatz.veyq.mobile.audio.PageMusic(player) { "Track $it" }
        val v = GuestView.over((ReceivedLink.open(f.share.link) as ReceivedLink.Parsed.Ok).link, f.share.client(), MemoryDeviceStore(), MemoryGuestSessions(), music = music)
        v.load()
        v.open(f.memory)
        val gallery = assertIs<MemoriesGuest.Gallery>(v.page.value).model
        assertTrue(gallery.music === music, "the guest's Memory plays no music")
        gallery.close()
        player.stop()
    }

    private object NoDeck : me.raddatz.veyq.mobile.audio.Deck {
        override suspend fun load(reader: me.raddatz.veyq.mobile.blob.RangeReader, mime: String) = 0L
        override suspend fun play() {}
        override fun pause() {}
        override fun seek(positionMs: Long) {}
        override suspend fun positionMs() = 0L
        override fun ramp(to: Double, overMs: Long) {}
        override fun clear() {}
    }

    private fun source(name: String, text: String): UploadSource {
        val bytes = text.encodeToByteArray()
        return object : UploadSource(name, bytes.size.toLong(), "text/plain", null) {
            override suspend fun read(from: Long, length: Int) = bytes.copyOfRange(from.toInt(), minOf(bytes.size, (from + length).toInt()))
        }
    }
}
