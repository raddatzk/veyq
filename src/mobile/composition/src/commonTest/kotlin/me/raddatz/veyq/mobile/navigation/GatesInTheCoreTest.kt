package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.blob.DirectoryWalk
import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.navigation.DropOutcome
import me.raddatz.veyq.mobile.navigation.DropTarget
import me.raddatz.veyq.mobile.navigation.Dropped

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.depot.DepotFiles
import me.raddatz.veyq.mobile.depot.DepotUploads
import me.raddatz.veyq.mobile.depot.FolderUpload
import me.raddatz.veyq.mobile.export.CountingSource
import me.raddatz.veyq.mobile.export.ImportDialog
import me.raddatz.veyq.mobile.export.ImportFixtures
import me.raddatz.veyq.mobile.export.ImportInput
import me.raddatz.veyq.mobile.export.PlayedImport
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.offline.removeAll
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The apps' gates held by the core alone (ADR 0154 decision 1), over a tenant the
 * test plays: a drop judged, sorted and uploaded by one entry ([ShellModel.drop]) — a folder's row a target only where
 * [ShellModel.dropTarget] says so, one import offered per drop, a gallery keeping its media and naming the rest —, and New
 * Folder's typed answer ([NameAnswer]: a name problem keeps the dialog open, a refusal and a thrown error close it, said).
 *
 * Bites (each one edit, seen red, then restored): `dropTarget` true for every FOLDER (`row.kind == NodeRef.FOLDER` alone)
 * → [a_drop_onto_a_guests_identity_folder_row_is_refused_and_keeps_nothing] red; `createFolder`'s taken name said as a
 * notice ([NameAnswer] collapsed back to one notice) → [a_name_problem_keeps_the_dialog_open_a_refusal_and_a_thrown_error_close_it] red.
 */
class GatesInTheCoreTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private class Opened(val t: PlayedTenant, val account: VeyqAccount, val tenant: TenantSession, val shell: ShellModel)

    /** A tenant whose space holds [build]'s nodes, signed in and its shell loaded at the space's top. */
    private suspend fun opened(build: PlayedTenant.(PlayedTenant.Scope) -> Unit): Opened {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        t.build(s)
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { 0L }
        shell.load(openLastUsed = false)
        shell.update { Shell.start(null, s.id) }
        return Opened(t, account, tenant, shell)
    }

    private fun plain(name: String) = UploadSource.of(name, 30, "text/plain", null) { from, n -> ByteArray(minOf(n.toLong(), 30 - from).toInt()) }

    private fun photo(name: String) = UploadSource.of(name, 9, "image/png", null) { from, n -> ByteArray(minOf(n.toLong(), 9 - from).toInt()) }

    /** Depot's uploads over a door that records what each would make, the import offered (the desktops'). */
    private class Door : DepotUploads.Door {
        val sent = mutableListOf<String>()
        val offered = mutableListOf<ImportInput>()
        val folders = mutableListOf<String>()
        val target = PlayedImport()
        override fun uploadJob(scopeId: String, parentId: String?, source: UploadSource, name: String) =
            DepotFiles.UploadJob(scopeId, parentId, source, name, { n, _, _, _, _ -> sent += n; DepotFiles.Uploaded(DepotFiles.Outcome.DONE, "n-$n") })
        override suspend fun prepareFolder(scopeId: String, parentId: String?, rootName: String, entries: List<FolderUpload.Entry>): FolderUpload.Report {
            folders += rootName
            return FolderUpload.Report(FolderUpload.Outcome.NOTHING, rootName)
        }
        override suspend fun file(scopeId: String, nodeId: String): DepotFiles.File? = null
        override fun replaceJob(file: DepotFiles.File, source: UploadSource, expectedBlobId: String?) = error("no replace here")
        override fun changed() {}
        override fun mayImport(scopeId: String, parentId: String?) = true
        override fun importDialog(scopeId: String, parentId: String?, target: String, input: ImportInput, asFile: (() -> Unit)?, closed: () -> Unit, deletedName: String): ImportDialog {
            offered += input
            return ImportDialog(input, target, scopeId, parentId, this.target, this.target.played(), PlayedImport.tokenInput, asFile, closed)
        }
    }

    private fun depot(door: Door) = DepotUploads(UploadQueue(), door, Dispatchers.Default, importOffered = true)

    @Test
    fun a_drop_onto_a_guests_identity_folder_row_is_refused_and_keeps_nothing() = runTest {
        lateinit var docs: PlayedTenant.Node
        lateinit var guest: PlayedTenant.Node
        val o = opened { s ->
            docs = node(s, "docs", "FOLDER", name = "Docs")
            guest = identityFolder(dropBox(s, "box"), "anna", "Anna")
        }
        val docsAt = NodeAt(docs.scope.id, docs.id, "FOLDER", "Docs")
        val guestAt = NodeAt(guest.scope.id, guest.id, "FOLDER", "Anna")
        assertTrue(o.shell.dropTarget(docsAt), "a member's folder row takes no drop")
        assertFalse(o.shell.dropTarget(guestAt), "a guest's identity folder row takes a drop by its kind")
        assertFalse(o.shell.takesUpload(guest.scope.id, guest.id), "an upload into a guest's identity folder is offered")
        assertFalse(o.shell.takesUpload("no-such-space", null), "an upload into a space this tenant does not hold is offered")

        val door = Door()
        val left = mutableListOf<String>()
        val refused = o.shell.drop(DropTarget.Row(guestAt), listOf(Dropped.File(plain("a.txt"))), depot(door), { left += it.name })
        assertIs<DropOutcome.Refused>(refused)
        assertEquals(listOf(Notice(Msg.DROP_NOT_HERE)), refused.notices)
        assertEquals(emptyList(), left, "a refused drop handed files back: they are the caller's still")
        assertEquals(emptyList(), door.sent)

        val taken = o.shell.drop(DropTarget.Row(docsAt), listOf(Dropped.File(plain("b.txt"))), depot(door))
        assertEquals(1, assertIs<DropOutcome.Uploaded>(taken).queued)
        o.account.retire()
    }

    @Test
    fun one_import_is_offered_per_drop_with_a_zip_and_a_download_folder_in_it() = runTest {
        val o = opened { s -> node(s, "docs", "FOLDER", name = "Docs") }
        val door = Door()
        val unpacked = ImportFixtures.cases().first { it.id == "unpacked" }.input as ImportInput.Folder
        val found = unpacked.files.map { (path, source) -> DirectoryWalk.Found(path, DirectoryWalk.Kind.FILE, source) }
        val items = listOf(
            Dropped.File(plain("a.txt")),
            Dropped.File(CountingSource("Trip.zip", ImportFixtures.webDownload())),
            Dropped.Folder("/x/Trip download", null, found),
            Dropped.NotLocal("Phone photo.jpg"),
            Dropped.Folder("/x/Gone", null, null),
        )
        val outcome = o.shell.drop(DropTarget.Level("My space"), items, depot(door))
        val offered = assertIs<DropOutcome.Offered>(outcome)
        assertEquals(1, door.offered.size, "more than one import offered for one drop")
        assertIs<ImportInput.Zip>(door.offered.single())
        assertEquals(1, offered.queued, "the plain file was not uploaded")
        assertEquals(listOf("Trip download"), door.folders, "the download folder after the offer was not the ordinary upload")
        assertEquals(listOf(Notice(Msg.DEPOT_DROP_NOT_LOCAL, "Phone photo.jpg"), Notice(Msg.DEPOT_FOLDER_UNREADABLE, "Gone")), offered.notices)
        offered.offer.cancel()

        // nothing is taken where no level is shown (an object open)
        o.shell.update { it.search(true) }
        assertIs<DropOutcome.Refused>(o.shell.drop(DropTarget.Level("Search"), listOf(Dropped.File(plain("c.txt"))), depot(door)))
        o.account.retire()
    }

    @Test
    fun the_gallery_drop_keeps_its_media_and_names_the_rest() = runTest {
        lateinit var memory: PlayedTenant.Node
        val o = opened { s -> memory = node(s, "m", name = "Summer") }
        val uploads = o.shell.mediaUploads(UploadQueue(), object : Derivation.Deriver {
            override fun derive(source: UploadSource, type: String, done: (Derivation.Derived) -> Unit) = Unit
        }, {}, {})
        val gallery = o.shell.galleryModel(assertNotNull(o.shell.galleryAt(memory.scope.id, memory.id)), uploads)
        assertNotNull(waitFor { gallery.takesUploads.takeIf { it } }, "the owner's Memory takes no upload")
        val left = mutableListOf<String>()
        val items = listOf(
            Dropped.File(photo("beach.png")),
            Dropped.File(plain("notes.pdf")),
            Dropped.Folder("/x/Holiday", null, emptyList()),
            Dropped.NotLocal("Cloud.jpg"),
        )
        val outcome = assertIs<DropOutcome.Uploaded>(o.shell.drop(DropTarget.Page(gallery), items, null, { left += it.name }))
        assertEquals(1, outcome.queued)
        assertEquals(
            listOf(Notice(Msg.MEMORY_DROP_FOLDER, "Holiday"), Notice(Msg.DEPOT_DROP_NOT_LOCAL, "Cloud.jpg"), Notice(Msg.MEMORY_NOT_MEDIA, 1)),
            outcome.notices,
        )
        assertEquals(listOf("notes.pdf"), left, "the file the gallery does not keep was not handed back")
        gallery.close()
        o.account.retire()
    }

    @Test
    fun a_name_problem_keeps_the_dialog_open_a_refusal_and_a_thrown_error_close_it() = runTest {
        var answer: ((io.ktor.client.request.HttpRequestData) -> Pair<Int, String>)? = null
        val o = opened { }
        o.t.route = { r ->
            if (r.method == io.ktor.http.HttpMethod.Post && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes"))) answer?.invoke(r) else null
        }
        val space = assertNotNull(o.shell.shell?.space)
        // an empty name and a sibling's: the dialog stays open, the word under its field
        val empty = o.shell.createFolderHere("  ")
        assertTrue(empty.keepsOpen)
        assertEquals(Notice(Msg.FOLDER_NAME_EMPTY), empty.problem)
        answer = { _ -> 409 to """{"code":"slug_taken"}""" }
        val taken = o.shell.createFolder(space, null, "Docs")
        assertTrue(taken.keepsOpen, "a taken name closed the dialog")
        assertEquals(Notice(Msg.FOLDER_NAME_TAKEN), taken.problem)
        assertNull(taken.notice)
        // a refusal: closes, said
        answer = { _ -> 422 to """{"code":"illegal_structure"}""" }
        val refused = o.shell.createFolder(space, null, "Docs")
        assertFalse(refused.keepsOpen)
        assertEquals(Notice(Msg.FOLDER_FAILED), refused.notice)
        // made: closes silently
        answer = { r ->
            val body = (r.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val id = Regex("\"nodeId\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
            201 to """{"nodeId":"$id"}"""
        }
        assertEquals(NameAnswer.DONE, o.shell.createFolder(space, null, "Docs"))
        // the drawer's New, from inside a folder: made at the space's top (no parent sent)
        var parent: String? = "unset"
        answer = { r ->
            val body = (r.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            parent = Regex("\"parentId\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            val id = Regex("\"nodeId\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
            201 to """{"nodeId":"$id"}"""
        }
        o.shell.enter(FolderRef("f-inside", "Inside"))
        assertEquals(NameAnswer.DONE, o.shell.createFolderAtTop("Top"))
        assertNull(parent, "the drawer's New made its folder under the shell's folder")
        // a thrown error: the core says it, nothing reaches the platform as an exception
        answer = { _ -> throw IllegalStateException("the network went") }
        val thrown = o.shell.createFolder(space, null, "Other")
        assertFalse(thrown.keepsOpen)
        assertEquals(Notice(Msg.FOLDER_FAILED), thrown.notice)
        o.account.retire()
    }

    /** [read]'s first non-null answer, the models' watches running on their own threads: at most five seconds. */
    private suspend fun <R : Any> waitFor(read: () -> R?): R? {
        repeat(500) {
            read()?.let { return it }
            kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.delay(10) }
        }
        return null
    }
}
