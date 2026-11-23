package me.raddatz.veyq.mobile.sharing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.navigation.HeaderAction
import me.raddatz.veyq.mobile.navigation.LevelTool
import me.raddatz.veyq.mobile.navigation.MoveSheet
import me.raddatz.veyq.mobile.navigation.NameAnswer
import me.raddatz.veyq.mobile.navigation.SelectionAction
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.testPrimitives
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A READ_WRITE link's guest moves within the link's folders (inbox #1063; the web's `move.ts` `guestMover` over
 * `placeShareNodeAsGuest`): its level's rows offer Move where the guest's rights grant it, the level offers Select, and the
 * member's [MoveSheet] walks the link's folders from its root and places through the guest's write — over a played
 * instance ([PlayedGuestContent]) whose placement write re-parents the row as the server does.
 */
class GuestMoveTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val p = testPrimitives

    private class Fixture(val share: PlayedShare, val content: PlayedGuestContent, val folder: String, val file: String, val memory: String, val pot: String)

    private fun fixture(role: String): Fixture {
        val share = PlayedShare(p, role = role)
        val content = PlayedGuestContent(share)
        val folder = share.uuid().also { share.add(it, share.rootId, share.rootKey, "FOLDER", "Archive") }
        val (file, _) = content.file(share.rootId, share.rootKey, "notes.txt", "hello guest".encodeToByteArray())
        val (memory, _) = content.memory(share.rootId, share.rootKey, "Trip", "a photo's bytes".encodeToByteArray())
        val pot = content.pot(share.rootId, share.rootKey, "Dinner")
        return Fixture(share, content, folder, file, memory, pot)
    }

    private suspend fun opened(share: PlayedShare): GuestView =
        GuestView.over((ReceivedLink.open(share.link) as ReceivedLink.Parsed.Ok).link, share.client(), MemoryDeviceStore(), MemoryGuestSessions()).also { it.load() }

    private fun level(v: GuestView) = assertIs<GuestPage.Level>(v.page.value).model

    @Test
    fun aReadWriteGuestsRowsOfferMoveWhereItsRightsGrantItAndAReadGuestsNone() = runTest {
        val r = fixture("READ")
        val read = level(opened(r.share))
        for (row in read.rows) assertTrue(read.move.rowPlan(row.nodeId).isEmpty, "a READ guest's ${row.kind} offers a move")
        assertEquals(emptyList(), read.move.tools, "a READ guest's level offers Select")
        assertNull(read.move.sheet(r.file))

        val f = fixture("READ_WRITE")
        val v = opened(f.share)
        val level = level(v)
        // a folder, a file and a Memory move; a Pot does not (Expenses offers a guest no move), nor the link's root
        for (id in listOf(f.folder, f.file, f.memory)) assertEquals(listOf(HeaderAction.MOVE), level.move.rowPlan(id).menu, "row $id offers no move")
        assertTrue(level.move.rowPlan(f.pot).isEmpty, "a guest's Pot offers a move")
        assertTrue(!GuestMove.moves(v.access, f.share.rootId), "the link's root moves")
        assertEquals(listOf(LevelTool.SELECT), level.move.tools)
    }

    @Test
    fun theSheetWalksTheLinksFoldersFromItsRootAndMovesAsTheNamedGuest() = runTest {
        val f = fixture("READ_WRITE")
        val v = opened(f.share)
        val sheet = assertNotNull(level(v).move.sheet(f.file))
        sheet.load()
        val s = sheet.current
        // it opens at the file's place, the link's root, named as the link — never the space's root above it
        assertEquals(f.share.rootId, s.at)
        assertEquals(listOf<String?>(f.share.rootId), s.trail.map { it.nodeId })
        assertEquals(Piece.Plain(f.share.rootName), s.trail.single().name)
        assertEquals(NodeMove.Block.CURRENT, s.block)
        // a guest's places are the link's folders only: neither the Memory nor the Pot
        assertEquals(listOf(f.folder), s.places.map { it.node.nodeId })
        sheet.go(f.folder)
        assertEquals(listOf(f.share.rootId, f.folder), sheet.current.trail.map { it.nodeId })
        assertNull(sheet.current.block)
        // "Move here": the guest not named yet is asked at the write, then the one placement write is sent as it
        val access = assertNotNull(v.access)
        val chose = CoroutineScope(Dispatchers.Default).async { sheet.choose() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { access.naming.asking.first { it } } }
        assertEquals(NameAnswer.DONE, access.naming.name("Ben"))
        val done = chose.await()
        assertNotNull(done.done, "the move was not said: ${done.problem}")
        assertNotNull(done.undo, "a guest's move offers no undo")
        val (moved, body) = f.content.placed.single()
        assertEquals(f.file to f.folder, moved to body["parentId"]!!.jsonPrimitive.content)
        assertEquals("tok-" + f.share.identities.single(), f.share.requests.last { it.method == "PATCH" }.header("X-Contributor-Token"))
        // the link read anew: the file lists in the folder, its key and name opened under the folder's key
        withContext(Dispatchers.Default) { withTimeout(5_000) { v.page.first { p -> (p as? GuestPage.Level)?.model?.rows?.none { it.nodeId == f.file } == true } } }
        v.open(f.folder)
        assertEquals(listOf("notes.txt"), level(v).rows.map { it.name })
        sheet.undo()
        assertEquals(f.share.rootId, f.content.placed.last().second["parentId"]!!.jsonPrimitive.content, "the undo did not move it back")
        // the undo's read of the link anew settles off this thread before the requests are searched
        v.close()
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(300) }
        f.share.assertNoKeyInAnyRequest()
    }

    @Test
    fun aGuestWhoDeclinesToNameItselfMovesNothingAndIsToldNothing() = runTest {
        val f = fixture("READ_WRITE")
        val v = opened(f.share)
        val sheet = assertNotNull(level(v).move.sheet(f.file))
        sheet.load()
        sheet.go(f.folder)
        val access = assertNotNull(v.access)
        val chose = CoroutineScope(Dispatchers.Default).async { sheet.choose() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { access.naming.asking.first { it } } }
        access.naming.decline()
        val s = chose.await()
        assertNull(s.problem)
        assertNull(s.done)
        assertTrue(f.content.placed.isEmpty())
    }

    @Test
    fun aSelectionMovesOnlyTheRowsTheGuestMovesAndSaysWhatItCannotReach() = runTest {
        val f = fixture("READ_WRITE")
        val v = opened(f.share)
        v.access!!.nameGuest("Ben")
        val level = level(v)
        level.move.selectStart()
        level.move.selectToggle(f.file)
        level.move.selectToggle(f.pot)
        val picking = assertNotNull(level.move.selection.value)
        val move = picking.entries.single()
        assertEquals(SelectionAction.MOVE, move.action)
        assertTrue(move.enabled)
        assertNotNull(move.cannot, "the Pot the move cannot reach was not said")
        val sheet = assertNotNull(level.move.selectionSheet())
        assertEquals(listOf(f.file), sheet.nodes.map { it.nodeId })
        sheet.load()
        sheet.go(f.folder)
        assertNotNull(sheet.choose().done)
        assertEquals(listOf(f.file), f.content.placed.map { it.first })
        level.move.selectCancel()
        assertNull(level.move.selection.value)
    }

    @Test
    fun aLostRaceReadsTheLinkAnewAndPlacesOnceMore() = runTest {
        val f = fixture("READ_WRITE")
        val v = opened(f.share)
        val access = assertNotNull(v.access)
        val session = access.nameGuest("Ben")
        f.content.placementRefusals += 409 to "stale_placement"
        val outcome = GuestMove.place(access, session, f.file, f.folder, reread = { v.reload(); v.access?.tree }, waitsMs = emptyList())
        assertEquals(NodeMove.Outcome.MOVED, outcome)
        assertEquals(2, f.content.placed.size)
        // twice lost: said as a conflict, nothing more sent
        f.content.placementRefusals += 409 to "stale_placement"
        f.content.placementRefusals += 409 to "stale_placement"
        assertEquals(NodeMove.Outcome.CONFLICT, GuestMove.place(v.access!!, session, f.memory, f.folder, reread = { v.reload(); v.access?.tree }, waitsMs = emptyList()))
        assertEquals(4, f.content.placed.size)
        // a node no longer served, and the link's root, whose entry is under a key no guest holds: nothing is sent
        assertEquals(NodeMove.Outcome.GONE, GuestMove.place(v.access!!, session, "nope", f.folder, reread = { null }))
        assertEquals(NodeMove.Outcome.NOT_ALLOWED, GuestMove.place(v.access!!, session, f.share.rootId, f.folder, reread = { null }))
        assertEquals(NodeMove.Outcome.NOT_ALLOWED, GuestMove.outcomeOf(422, "illegal_scope"))
        assertEquals(NodeMove.Outcome.NAME_TAKEN, GuestMove.outcomeOf(409, "slug_taken"))
        assertEquals(NodeMove.Outcome.GONE, GuestMove.outcomeOf(403, "share_password_incorrect"))
    }

    @Test
    fun aSiblingsNameAtTheDestinationIsSaidBeforeAnythingIsSent() = runTest {
        val f = fixture("READ_WRITE")
        // a file of the same name already in the folder
        val v0 = opened(f.share)
        val folderNode = assertNotNull(v0.access?.tree?.node(f.folder))
        f.content.file(f.folder, folderNode.key.copyOf(), "notes.txt", "other".encodeToByteArray())
        val v = opened(f.share)
        val access = assertNotNull(v.access)
        val session = access.nameGuest("Ben")
        assertEquals(NodeMove.Outcome.NAME_TAKEN, GuestMove.place(access, session, f.file, f.folder, reread = { null }))
        assertTrue(f.content.placed.isEmpty())
    }
}
