package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.DirectoryWalk
import me.raddatz.veyq.mobile.blob.LevelUploads
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The platform's drop and *Add here* over the two interfaces an application implements (ADR 0156, inbox #1215): a page
 * that takes files ([PageIntake]) and a level's upload ([LevelUploads]). The platform judges by the page's own answer,
 * sorts by its [ShareAnswer], says the rest in the page's words and hands back what it did not keep — naming no
 * application. The real pages (Memories' gallery and bucket) are held in their module and in the composition's tests.
 */
class PageIntakeTest {
    private fun file(name: String, type: String?) = UploadSource.of(name, 4, type, null) { _, n -> ByteArray(n) }

    /** A page keeping images only, its intake [intake], what it uploaded recorded. */
    private class Page(var intake: PageIntake.Intake = PageIntake.Intake.Takes, val keeps: Int? = null) : PageIntake {
        val uploaded = mutableListOf<String>()
        override val node = NodeAt("s", "p", "PAGE", "Page")
        override val answer = ShareAnswer(ShareAnswer.How.QUEUE, { ShareAnswer.Intake { type, _, _ -> type?.startsWith("image/") == true } }) { true }
        override fun dropIntake() = intake
        override fun upload(sources: List<UploadSource>): Int { uploaded += sources.map { it.name }; return keeps ?: sources.size }
        override fun foldersNotTaken(names: List<String>) = names.map { Notice(Msg.MEMORY_DROP_FOLDER, it) }
        override fun filesNotTaken(count: Int) = if (count > 0) Notice(Msg.MEMORY_NOT_MEDIA, count) else null
        override fun offersShare(inbox: ShareInbox) = inbox.waiting.value != null
        override fun takeShare(inbox: ShareInbox) = inbox.queue(answer) { upload(it) }
    }

    @Test
    fun aPageKeepsWhatItsAnswerTakesAndSaysTheRestInItsWords() {
        val page = Page()
        val left = mutableListOf<String>()
        val items = listOf(
            Dropped.File(file("beach.jpg", "image/jpeg")), Dropped.File(file("notes.pdf", "application/pdf")),
            Dropped.Folder("/x/Holiday", null, emptyList()), Dropped.NotLocal("cloud.jpg"),
        )
        val outcome = assertIs<DropOutcome.Uploaded>(DropTarget.Page(page).drop(items) { left += it.name })
        assertEquals(1, outcome.queued)
        assertEquals(listOf("beach.jpg"), page.uploaded)
        assertEquals(listOf("notes.pdf"), left, "what the answer did not keep is handed back")
        assertEquals(
            listOf(Notice(Msg.MEMORY_DROP_FOLDER, "Holiday"), Notice(Msg.DEPOT_DROP_NOT_LOCAL, "cloud.jpg"), Notice(Msg.MEMORY_NOT_MEDIA, 1)),
            outcome.notices, "the folders first, then what the drop could not read, then what the page did not keep",
        )
    }

    @Test
    fun aRefusingPageKeepsNothingAndSaysWhy() {
        val why = Notice(Msg.MEMORY_BUCKETS_DROP_NOT_OWN)
        val refusing = Page(PageIntake.Intake.Refused(why))
        val left = mutableListOf<String>()
        val refused = assertIs<DropOutcome.Refused>(DropTarget.Page(refusing).drop(listOf(Dropped.File(file("a.jpg", "image/jpeg")))) { left += it.name })
        assertEquals(why, refused.notice)
        assertEquals(emptyList(), refusing.uploaded)
        assertEquals(emptyList(), left, "a refusal hands nothing back: the drop is the caller's still")
        val closed = assertIs<DropOutcome.Refused>(DropTarget.Page(Page(PageIntake.Intake.NotHere)).drop(listOf(Dropped.File(file("a.jpg", "image/jpeg")))))
        assertEquals(Notice(Msg.DROP_NOT_HERE), closed.notice)
    }

    @Test
    fun whatThePageDidNotQueueIsHandedBack() {
        val page = Page(keeps = 0)
        val left = mutableListOf<String>()
        val outcome = assertIs<DropOutcome.Uploaded>(DropTarget.Page(page).drop(listOf(Dropped.File(file("a.jpg", "image/jpeg")))) { left += it.name })
        assertEquals(0, outcome.queued)
        assertEquals(listOf("a.jpg"), left)
    }

    /** A level's upload recording each call. */
    private class Level(val download: String? = null) : LevelUploads {
        val calls = mutableListOf<String>()
        override fun upload(scopeId: String, parentId: String?, label: String, sources: List<UploadSource>, left: ((UploadSource) -> Unit)?): Int {
            calls += "upload $label ${sources.map { it.name }}"
            return sources.size
        }
        override suspend fun screen(scopeId: String, parentId: String?, label: String, sources: List<UploadSource>, left: ((UploadSource) -> Unit)?, deletedName: String): LevelUploads.Screened {
            calls += "screen $label ${sources.map { it.name }}"
            return LevelUploads.Screened(sources.size, null)
        }
        override suspend fun screenFolder(scopeId: String, parentId: String?, label: String, shownName: String?, path: String, found: List<DirectoryWalk.Found>, deletedName: String): LevelUploads.Screened {
            calls += "screenFolder ${DirectoryWalk.rootName(shownName, path)}"
            return LevelUploads.Screened(found.size, null)
        }
        override suspend fun uploadFolder(scopeId: String, parentId: String?, shownName: String?, path: String, found: List<DirectoryWalk.Found>): Int {
            calls += "uploadFolder ${DirectoryWalk.rootName(shownName, path)}"
            return found.size
        }
    }

    @Test
    fun aLevelScreensItsFilesThenEachFolder() = runTest {
        val level = Level()
        val anyFile = ShareAnswer(ShareAnswer.How.QUEUE, { ShareAnswer.Intake { _, _, _ -> true } }) { true }
        val walk = listOf(DirectoryWalk.Found("a.txt", DirectoryWalk.Kind.FILE, file("a.txt", "text/plain")))
        val items = listOf(Dropped.File(file("x.pdf", "application/pdf")), Dropped.Folder("/u/Trip", null, walk), Dropped.Folder("/u/Gone", null, null))
        val outcome = assertIs<DropOutcome.Uploaded>(Drops.ontoLevel("s", "f", "Docs", items, level, anyFile, {}, ""))
        assertEquals(listOf("screen Docs [x.pdf]", "screenFolder Trip"), level.calls)
        assertEquals(2, outcome.queued)
        assertEquals(1, outcome.notices.size, "a folder whose walk did not read is said")
    }

    @Test
    fun addHereOffersTheOpenPageOnlyWhereItTakesWhatWaits() {
        val page = Page()
        val inbox = ShareInbox()
        val onPage = Shell(application = null, openObject = ObjectRef("s", "p", "PAGE", ApplicationId.MEMORIES, "Page"), space = "s")
        assertNull(AddHere.at(inbox, onPage, page) { error("no level asked") }, "nothing waits")
        inbox.receive(ShareInbox.Incoming(listOf(file("a.jpg", "image/jpeg"), file("b.pdf", "application/pdf")), 0))
        val here = assertIs<AddHere.Page>(AddHere.at(inbox, onPage, page) { error("no level asked") })
        assertSame(page, here.intake)
        assertSame(page.answer, here.answer)
        val elsewhere = onPage.copy(openObject = ObjectRef("s", "other", "PAGE", ApplicationId.MEMORIES, "Other"))
        assertNull(AddHere.at(inbox, elsewhere, page) { error("no level asked") }, "a page that is not the open object offers nothing")
        val taken = here.take(inbox, "Page", null) {}
        assertEquals(listOf("a.jpg"), page.uploaded)
        assertEquals(1, taken?.refused)
    }
}
