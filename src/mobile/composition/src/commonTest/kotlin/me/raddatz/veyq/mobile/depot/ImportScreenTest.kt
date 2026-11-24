package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.blob.DirectoryWalk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.export.CountingSource
import me.raddatz.veyq.mobile.export.ImportDialog
import me.raddatz.veyq.mobile.export.ImportFixtures
import me.raddatz.veyq.mobile.export.ImportInput
import me.raddatz.veyq.mobile.export.PlayedImport
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every Depot upload recognises a download (ADR 0152 decision 1; the web's `screenUpload`/`screenDirectory`): a pick of
 * three files with one download offers one import and queues the other two; a download where the member may not create (a
 * guest's folder) is a file; a phone, whose import is off, uploads it as a file; a folder holding the manifest is offered,
 * one without it uploaded as its tree; *Upload as a file* queues the offered file after all; a share's file is handed back
 * once its dialog is gone. And the dialog itself: read, planned, imported into a target the test plays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportScreenTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private fun plain(name: String) = UploadSource.of(name, 30, "text/plain", null) { from, n -> ByteArray(minOf(n.toLong(), 30 - from).toInt()) }
    private fun download() = CountingSource("Trip.zip", ImportFixtures.webDownload())

    private class Door(val mayImport: Boolean, val dispatcher: kotlinx.coroutines.CoroutineDispatcher) : DepotUploads.Door {
        val sent = mutableListOf<String>()
        val offered = mutableListOf<ImportInput>()
        var folderUploaded: String? = null
        val target = PlayedImport()
        override fun uploadJob(scopeId: String, parentId: String?, source: UploadSource, name: String) =
            DepotFiles.UploadJob(scopeId, parentId, source, name, { n, _, _, _, _ -> sent += n; DepotFiles.Uploaded(DepotFiles.Outcome.DONE, "n-$n") })
        override suspend fun prepareFolder(scopeId: String, parentId: String?, rootName: String, entries: List<FolderUpload.Entry>): FolderUpload.Report {
            folderUploaded = rootName
            return FolderUpload.Report(FolderUpload.Outcome.NOTHING, rootName)
        }
        override suspend fun file(scopeId: String, nodeId: String): DepotFiles.File? = null
        override fun replaceJob(file: DepotFiles.File, source: UploadSource, expectedBlobId: String?) = error("no replace here")
        override fun changed() {}
        override fun mayImport(scopeId: String, parentId: String?) = mayImport
        override fun importDialog(scopeId: String, parentId: String?, target: String, input: ImportInput, asFile: (() -> Unit)?, closed: () -> Unit, deletedName: String): ImportDialog {
            offered += input
            return ImportDialog(input, target, scopeId, parentId, this.target, this.target.played(), PlayedImport.tokenInput, asFile, closed, dispatcher = dispatcher)
        }
    }

    private class Model(val uploads: DepotUploads, val door: Door)

    private fun TestScope.model(mayImport: Boolean = true, offered: Boolean = true): Model {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val door = Door(mayImport, dispatcher)
        return Model(DepotUploads(UploadQueue(dispatcher), door, dispatcher, importOffered = offered), door)
    }

    @Test
    fun aPickOfThreeFilesWithOneDownloadOffersOneImportAndQueuesTwo() = runTest {
        val m = model()
        val zip = download()
        val screened = m.uploads.screen("s", "level", "Level", listOf(plain("a.txt"), zip, plain("b.txt")))
        advanceUntilIdle()
        assertNotNull(screened.offer)
        assertEquals(2, screened.queued)
        assertSame(zip, (m.door.offered.single() as ImportInput.Zip).file)
        assertEquals(listOf("a.txt", "b.txt"), m.door.sent)
    }

    @Test
    fun aDownloadWhereTheMemberMayNotCreate_aGuestsFolder_isAFile() = runTest {
        // lever: drop `door.mayImport(...)` from DepotUploads.screen
        val m = model(mayImport = false)
        val screened = m.uploads.screen("s", "guest-folder", "Anna", listOf(download()))
        advanceUntilIdle()
        assertNull(screened.offer)
        assertEquals(listOf("Trip.zip"), m.door.sent)
        assertEquals(emptyList(), m.door.offered)
    }

    @Test
    fun aPhoneWhoseImportIsOffUploadsADownloadAsAFile() = runTest {
        val m = model(offered = false)
        val screened = m.uploads.screen("s", null, "Space", listOf(download()))
        advanceUntilIdle()
        assertNull(screened.offer)
        assertEquals(listOf("Trip.zip"), m.door.sent)
    }

    @Test
    fun uploadAsAFileQueuesTheOfferedFileAfterAll() = runTest {
        val m = model()
        val offer = assertNotNull(m.uploads.screen("s", null, "Space", listOf(download())).offer)
        advanceUntilIdle()
        assertTrue(offer.current.canAsFile)
        offer.uploadAsFile()
        advanceUntilIdle()
        assertEquals(listOf("Trip.zip"), m.door.sent)
        assertTrue(offer.current.closed)
    }

    @Test
    fun aSharedDownloadIsHandedBackOnceItsDialogIsGone() = runTest {
        val m = model()
        val left = mutableListOf<String>()
        val offer = assertNotNull(m.uploads.screen("s", null, "Space", listOf(download()), left = { left += it.name }).offer)
        advanceUntilIdle()
        offer.cancel()
        advanceUntilIdle()
        assertEquals(listOf("Trip.zip"), left)
        assertEquals(emptyList(), m.door.sent)
    }

    @Test
    fun aFolderHoldingTheManifestIsOffered_oneWithoutItUploadedAsItsTree() = runTest {
        val m = model()
        val files = ImportFixtures.cases().first { it.id == "unpacked" }.input as ImportInput.Folder
        val found = files.files.map { (path, source) -> DirectoryWalk.Found(path, DirectoryWalk.Kind.FILE, source) }
        val offered = m.uploads.screenFolder("s", null, "Space", "Trip download", "/x/Trip download", found)
        assertNotNull(offered.offer)
        assertNull(m.door.folderUploaded)
        val plainFolder = listOf(DirectoryWalk.Found("a.txt", DirectoryWalk.Kind.FILE, plain("a.txt")))
        val uploaded = m.uploads.screenFolder("s", null, "Space", "Photos", "/x/Photos", plainFolder)
        assertNull(uploaded.offer)
        assertEquals("Photos", m.door.folderUploaded)
        assertNull(offered.offer!!.current.takeIf { it.canAsFile }, "a folder offers no Upload as a file")
    }

    @Test
    fun theDialogReadsPlansAndImports() = runTest {
        val m = model()
        val offer = assertNotNull(m.uploads.screen("s", "level", "Level", listOf(download())).offer)
        // the ShellModel's door starts it; the test's door leaves that to the test
        offer.start()
        advanceUntilIdle()
        val ready = offer.current
        assertEquals(ImportDialog.Step.READY, ready.step)
        assertEquals(7, ready.nodes)
        assertEquals("Trip", ready.root)
        assertEquals(Notice(Msg.IMPORT_SUMMARY, 7, me.raddatz.veyq.mobile.words.Bytes(ready.bytes), "Level"), ready.summary)
        assertEquals(Msg.IMPORT_SUBMIT, ready.importWord)
        assertTrue(ready.canImport)
        offer.import()
        advanceUntilIdle()
        assertEquals(ImportDialog.Step.DONE, offer.current.step)
        assertEquals(listOf(Notice(Msg.IMPORT_IMPORTED, "Trip")), offer.current.said)
        assertEquals(7, m.door.target.made.size)
    }

    @Test
    fun theDialogSaysARefusalInTheWebsWords() = runTest {
        val m = model()
        val bad = ImportFixtures.cases().first { it.id == "newer_version" }.input
        val dialog = m.door.importDialog("s", null, "Space", bad, null, {}, "Deleted people")
        dialog.start()
        advanceUntilIdle()
        assertEquals(ImportDialog.Step.INVALID, dialog.current.step)
        assertEquals(listOf(Notice(Msg.IMPORT_REFUSED_NEWER_VERSION)), dialog.current.said)
    }
}
