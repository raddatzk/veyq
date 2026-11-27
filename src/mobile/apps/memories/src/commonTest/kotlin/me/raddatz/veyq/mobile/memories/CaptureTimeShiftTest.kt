package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.OfflineAsOf
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A selection's capture-time shift (uc.memory.buckets § Correct a selection's capture time). The arithmetic: the web's
 * `captureTimeShift.test.ts`, one case here per case there, the same inputs and answers. The run: from the Contributions
 * view's selection over a gallery the test plays — every write exact, at the revision the selection was read at, a peer's
 * change refused and named, never shifted from the peer's value; the Undo putting back what was written and refusing what
 * a peer changed since; only the anchor written to the minute; nothing offered offline or without the meta's right.
 */
class CaptureTimeShiftTest {
    /** `YYYY-MM-DDTHH:MM:SSZ` as epoch ms (the web's `Date.parse`), by H. Hinnant's `days_from_civil`. */
    private fun at(iso: String): Long {
        val y0 = iso.substring(0, 4).toLong(); val m = iso.substring(5, 7).toLong(); val d = iso.substring(8, 10).toLong()
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val days = era * 146097 + yoe * 365 + yoe / 4 - yoe / 100 + doy - 719468
        val secs = iso.substring(11, 13).toLong() * 3600 + iso.substring(14, 16).toLong() * 60 + iso.substring(17, 19).toLong()
        return (days * 86400 + secs) * 1000
    }
    private fun meta(capturedAt: String?, override: String? = null) =
        MemoryMedia.Meta(null, null, capturedAt?.let(::at), override?.let(::at), null, null, null, null, null)

    private fun input(id: String, m: MemoryMedia.Meta?) = CaptureTimeShift.Input(id, m)
    private val a = input("a", meta("2024-06-01T10:00:00Z"))
    private val b = input("b", meta("2024-06-01T10:20:00Z"))
    private val c = input("c", meta("2024-06-01T11:00:00Z", "2024-06-01T12:00:00Z")) // already corrected

    // ---- the web's planCaptureTimeShift cases ----

    @Test
    fun movesEveryDatedMediumByTheAnchorsOffsetForward() {
        val plan = assertNotNull(CaptureTimeShift.plan(b.meta, at("2024-06-01T11:32:00Z"), listOf(a, b, c)))
        assertEquals(72 * 60 * 1000L, plan.offsetMs) // +1 h 12 min
        assertEquals(
            listOf(
                CaptureTimeShift.Step.Set("a", at("2024-06-01T11:12:00Z")),
                CaptureTimeShift.Step.Set("b", at("2024-06-01T11:32:00Z")),
                // from its EFFECTIVE time (the override), not its original
                CaptureTimeShift.Step.Set("c", at("2024-06-01T13:12:00Z")),
            ),
            plan.steps,
        )
        assertEquals(0, plan.undated)
    }

    @Test
    fun andBackwardANegativeOffsetTheOrderAmongThemKept() {
        val plan = assertNotNull(CaptureTimeShift.plan(a.meta, at("2024-05-31T22:00:00Z"), listOf(a, b, c)))
        assertEquals(-12 * 3_600_000L, plan.offsetMs)
        val times = plan.steps.map { (it as CaptureTimeShift.Step.Set).overrideMs }
        assertEquals(listOf(at("2024-05-31T22:00:00Z"), at("2024-05-31T22:20:00Z"), at("2024-06-01T00:00:00Z")), times)
        assertEquals(times.sorted(), times, "the selection keeps its order")
    }

    @Test
    fun aMediumWhoseShiftedTimeIsItsOwnOriginalDropsTheOverrideAsAResetDoes() {
        // c reads 12:00 (corrected from 11:00); shifting everything by -1 h lands c back on its original
        val plan = assertNotNull(CaptureTimeShift.plan(c.meta, at("2024-06-01T11:00:00Z"), listOf(a, c)))
        assertEquals(listOf(CaptureTimeShift.Step.Set("a", at("2024-06-01T09:00:00Z")), CaptureTimeShift.Step.Clear("c")), plan.steps)
    }

    @Test
    fun anUndatedMediumIsSkippedAndCountedNeverGivenATime() {
        val none = input("n", null)
        val empty = input("e", MemoryMedia.Meta(800, 600, null, null, null, null, null, null, null)) // a tier, but no time in it
        val plan = assertNotNull(CaptureTimeShift.plan(a.meta, at("2024-06-01T11:00:00Z"), listOf(a, none, empty)))
        assertEquals(
            listOf(CaptureTimeShift.Step.Set("a", at("2024-06-01T11:00:00Z")), CaptureTimeShift.Step.SkipUndated("n"), CaptureTimeShift.Step.SkipUndated("e")),
            plan.steps,
        )
        assertEquals(2, plan.undated)
    }

    @Test
    fun noShiftIsOfferedForAnUndatedAnchorAClearedFieldOrAnEditThatMovedNothing() {
        assertNull(CaptureTimeShift.plan(null, at("2024-06-01T11:00:00Z"), listOf(a, b)), "an undated anchor")
        assertNull(CaptureTimeShift.plan(a.meta, null, listOf(a, b)), "the field cleared")
        assertNull(CaptureTimeShift.plan(a.meta, at("2024-06-01T10:00:00Z"), listOf(a, b)), "nothing moved")
        assertNotNull(CaptureTimeShift.plan(a.meta, at("2024-06-01T10:01:00Z"), listOf(a, b)), "control: one minute is a shift")
    }

    @Test
    fun theOffsetIsTheDifferenceTheEditorShowedTheAnchorsSecondsAreNotPartOfItAndStayItsOwn() {
        // the anchor read 10:03:27; the minute-granular editor showed 10:03, the member set 11:15
        val anchor = input("x", meta("2024-06-01T10:03:27Z"))
        val plan = assertNotNull(CaptureTimeShift.plan(anchor.meta, at("2024-06-01T11:15:00Z"), listOf(anchor, a)))
        assertEquals(72 * 60_000L, plan.offsetMs)
        assertEquals(listOf(CaptureTimeShift.Step.Set("x", at("2024-06-01T11:15:27Z")), CaptureTimeShift.Step.Set("a", at("2024-06-01T11:12:00Z"))), plan.steps)
    }

    @Test
    fun plansOnlyTheSelectionHandedIn() {
        val plan = assertNotNull(CaptureTimeShift.plan(a.meta, at("2024-06-01T11:00:00Z"), listOf(a, c)))
        assertEquals(listOf("a", "c"), plan.steps.map { it.mediaId }) // b, not selected, is not in the plan
    }

    // ---- the web's offsetParts case ----

    @Test
    fun writesAnOffsetOutAsItsSignDaysHoursAndMinutes() {
        assertEquals(CaptureTimeShift.Parts(false, 0, 1, 12), CaptureTimeShift.parts(72 * 60_000L))
        assertEquals(CaptureTimeShift.Parts(true, 1, 2, 5), CaptureTimeShift.parts(-(26 * 60 + 5) * 60_000L))
        assertEquals(CaptureTimeShift.Parts(false, 365, 0, 0), CaptureTimeShift.parts(365 * 24 * 3_600_000L))
        // and as the save says it: the parts that are not zero, after the sign (MediaLightbox's shiftOffsetText)
        assertEquals(Joined(listOf("+", Notice(Msg.MEMORY_SHIFT_HOURS, 1), " ", Notice(Msg.MEMORY_SHIFT_MINUTES, 12)), ""), CaptureTimeShift.offsetText(72 * 60_000L))
        assertEquals(Joined(listOf("−", Notice(Msg.MEMORY_SHIFT_DAYS, 1)), ""), CaptureTimeShift.offsetText(-24 * 3_600_000L))
    }

    // ---- the run, from the Contributions view ----

    private val memory = NodeAt("s", "m", "MEMORY", "Ostsee 2026")
    private val me = "u-me"

    private fun blob(id: String) = MemoryMedia.Blob(id, 1, 29L + V1.FRAME_OVERHEAD, "", null)

    /** The server the test plays: each medium's listed tier and its revision; a write states the revision it read. */
    private inner class Server {
        val metas = linkedMapOf(
            "p1" to meta("2024-06-01T10:03:27Z"),
            "p2" to meta("2024-06-01T10:20:00Z"),
            "p3" to meta(null),
            "p4" to meta("2024-06-01T11:00:00Z", "2024-06-01T12:00:00Z"),
        )
        val revisions = metas.keys.associateWith { 1L }.toMutableMap()
        val exact = mutableListOf<Triple<String, Long?, Long>>()
        val minute = mutableListOf<Pair<String, Long?>>()
        var offline: OfflineAsOf? = null

        fun item(id: String) = MemoryMedia.Item(
            "s", "m", id, blob("o-$id"), blob("t-$id"), blob("p-$id"), metas.getValue(id), "image/jpeg", "$id.jpg", "user:$me",
            false, ByteArray(0), metaRevision = revisions.getValue(id),
        )

        /** A peer's correction of [id] lands between the read and the write. */
        fun peerCorrects(id: String, overrideIso: String) {
            val m = metas.getValue(id)
            metas[id] = MemoryMedia.Meta(null, null, m.capturedAt, at(overrideIso), null, null, null, null, null)
            revisions[id] = revisions.getValue(id) + 1
        }

        private fun write(item: MemoryMedia.Item, overrideMs: Long?): CaptureTime.Outcome {
            if (revisions.getValue(item.mediaId) != item.metaRevision) return CaptureTime.Outcome.Conflict(metas.getValue(item.mediaId).capturedAtOverride)
            val m = metas.getValue(item.mediaId)
            metas[item.mediaId] = MemoryMedia.Meta(null, null, m.capturedAt, overrideMs, null, null, null, null, null)
            revisions[item.mediaId] = item.metaRevision + 1
            return CaptureTime.Outcome.Done(overrideMs)
        }

        val gallery = object : GalleryModel.Door {
            override suspend fun gallery() = MemoryMedia.Gallery(metas.keys.map(::item), 1, 0, offline)
            override val online get() = MutableStateFlow(true)
            override suspend fun scopeNodes() = emptyList<NodeRef>()
            override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob) = ByteArray(0)
            override fun reader(item: MemoryMedia.Item) = null
            override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
            override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
            override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
            override fun upload(sources: List<UploadSource>) = 0
            override fun upload(sources: List<UploadSource>, filed: suspend (mediaId: String) -> Unit) = 0
            override val revisions = MutableStateFlow<Any?>(Any())
            override val uploaded = MutableSharedFlow<String>()
            override suspend fun captureTime(item: MemoryMedia.Item, overrideMs: Long?) =
                write(item, overrideMs?.let(CaptureTime::minute)).also { minute += item.mediaId to overrideMs }
            override suspend fun shiftCaptureTime(item: MemoryMedia.Item, overrideMs: Long?) =
                write(item, overrideMs).also { exact += Triple(item.mediaId, overrideMs, item.metaRevision) }
        }

        val door = object : Contributions.Door {
            override suspend fun list() = Buckets.Listed(emptyList(), null) to emptyMap<String, String?>()
            override val self = me
            override fun person(userId: String): String? = null
            override suspend fun create(name: String, parent: String?) = Buckets.Outcome.DONE
            override suspend fun rename(bucketId: String, name: String) = Buckets.Outcome.DONE
            override suspend fun delete(bucketId: String) = Buckets.Outcome.DONE
            override suspend fun move(mediaIds: List<String>, target: String?) = Buckets.Outcome.DONE
        }
    }

    private suspend fun TestScope.open(server: Server, access: MemoryRights.Access = MemoryRights.Access.Member()): Contributions {
        val g = GalleryModel(memory, server.gallery, backgroundScope, backgroundScope, access).also { runCurrent() }
        return Contributions(g, server.door).also { it.read() }
    }

    @Test
    fun theSelectionShiftsEveryDatedMediumExactlyAtTheRevisionItWasReadAt() = runTest {
        val s = Server()
        val c = open(s)
        assertEquals(setOf("p1", "p2", "p3", "p4"), c.current.media.map { it.mediaId }.toSet(), "the caller's own bucket")
        c.toggle("p3") // undated: never the anchor
        assertFalse(c.current.shifts, "one selected is no selection to shift")
        c.toggle("p1")
        c.toggle("p4")
        assertTrue(c.current.shifts)
        val shift = assertNotNull(c.shift())
        assertEquals(at("2024-06-01T10:03:27Z"), shift.current.anchorAtMs, "the anchor: the first selected with a capture time")
        assertEquals(Notice(Msg.MEMORY_SHIFT_INTRO, 2, "p1.jpg"), shift.current.intro)
        assertFalse(shift.propose(at("2024-06-01T10:03:00Z")), "the minute it showed: nothing moved")
        assertTrue(shift.propose(at("2024-06-01T11:15:40Z")), "seconds picked are the minute's")
        assertEquals(SelectionShift.Step.CHOOSE, shift.current.step)
        assertEquals(Notice(Msg.MEMORY_SHIFT_ALL, 3, CaptureTimeShift.offsetText(72 * 60_000L)), shift.current.all)
        assertEquals(Notice(Msg.MEMORY_SHIFT_UNDATED, 1), shift.current.undated)
        shift.all()
        assertEquals(
            setOf(Triple("p1", at("2024-06-01T11:15:27Z"), 1L), Triple("p4", at("2024-06-01T13:12:00Z"), 1L)),
            s.exact.toSet(), "each exact, its seconds kept, at the revision read; the undated one untouched",
        )
        assertEquals(emptyList<Pair<String, Long?>>(), s.minute)
        assertEquals(SelectionShift.Step.DONE, shift.current.step)
        assertEquals(Notice(Msg.MEMORY_SHIFT_DONE, 2), shift.current.done)
        assertEquals(emptyList<Notice>(), shift.current.refused)
        assertTrue(shift.current.undoable)
        assertEquals(at("2024-06-01T13:12:00Z"), c.current.media.first { it.mediaId == "p4" }.item.meta?.capturedAtOverride, "the gallery read anew")
        assertEquals(listOf("p3", "p1", "p4"), c.current.picked, "the selection stays")
    }

    /** Bite: a run that re-reads the gallery before it writes would shift p2 from the peer's value — red here. */
    @Test
    fun aPeersChangeMeanwhileIsRefusedAndNamedNeverShiftedFromThePeersValue() = runTest {
        val s = Server()
        val c = open(s)
        c.toggle("p1"); c.toggle("p2")
        val shift = assertNotNull(c.shift())
        s.peerCorrects("p2", "2024-06-01T18:00:00Z")
        assertTrue(shift.propose(at("2024-06-01T11:03:00Z")))
        shift.all()
        assertEquals(at("2024-06-01T18:00:00Z"), s.metas.getValue("p2").capturedAtOverride, "the peer's correction stands")
        assertEquals(at("2024-06-01T11:03:27Z"), s.metas.getValue("p1").capturedAtOverride)
        assertEquals(Notice(Msg.MEMORY_SHIFT_DONE, 1), shift.current.done)
        assertEquals(listOf(Notice(Msg.MEMORY_SHIFT_REFUSED, Joined(listOf("p2.jpg"), ", "), Notice(Msg.MEMORY_REFUSED_CHANGED))), shift.current.refused)
    }

    @Test
    fun theUndoPutsBackEachOverrideAndRefusesOneAPeerChangedSince() = runTest {
        val s = Server()
        val c = open(s)
        c.toggle("p4"); c.toggle("p1"); c.toggle("p2")
        val shift = assertNotNull(c.shift())
        assertEquals(at("2024-06-01T12:00:00Z"), shift.current.anchorAtMs, "the first selected: p4, by its correction")
        assertTrue(shift.propose(at("2024-06-01T13:00:00Z")))
        shift.all()
        s.peerCorrects("p2", "2024-06-02T08:00:00Z")
        shift.undo()
        assertNull(s.metas.getValue("p1").capturedAtOverride, "p1 had none: none again")
        assertEquals(at("2024-06-01T12:00:00Z"), s.metas.getValue("p4").capturedAtOverride, "p4 its own correction again")
        assertEquals(at("2024-06-02T08:00:00Z"), s.metas.getValue("p2").capturedAtOverride, "the peer's change is not undone")
        assertEquals(listOf(Notice(Msg.MEMORY_RESTORE_REFUSED, Joined(listOf("p2.jpg"), ", "), Notice(Msg.MEMORY_REFUSED_CHANGED))), shift.current.refused)
        assertFalse(shift.current.undoable)
        assertNull(shift.current.done)
        assertFalse(shift.current.closed, "a refused line is still to read")
    }

    @Test
    fun aCleanUndoClosesTheDialog() = runTest {
        val s = Server()
        val c = open(s)
        c.toggle("p4"); c.toggle("p1")
        val shift = assertNotNull(c.shift())
        assertTrue(shift.propose(at("2024-06-01T13:00:00Z")))
        shift.all()
        assertFalse(shift.current.closed)
        shift.undo()
        assertNull(s.metas.getValue("p1").capturedAtOverride)
        assertEquals(emptyList(), shift.current.refused)
        assertTrue(shift.current.closed, "a clean Undo left an empty dialog")
    }

    @Test
    fun onlyThisOneWritesTheAnchorAloneToTheMinuteAsTheDetailsDo() = runTest {
        val s = Server()
        val c = open(s)
        c.toggle("p1"); c.toggle("p2")
        val shift = assertNotNull(c.shift())
        assertTrue(shift.propose(at("2024-06-01T11:15:40Z")))
        shift.onlyThis()
        assertEquals(listOf<Pair<String, Long?>>("p1" to at("2024-06-01T11:15:00Z")), s.minute)
        assertEquals(emptyList<Triple<String, Long?, Long>>(), s.exact)
        assertTrue(shift.current.closed)
        assertEquals(at("2024-06-01T10:20:00Z"), s.metas.getValue("p2").capturedAt)
        assertNull(s.metas.getValue("p2").capturedAtOverride)
    }

    @Test
    fun noShiftIsOfferedOfflineWithoutTheMetasRightOrWithoutADatedMedium() = runTest {
        val undated = Server().also { it.metas["p1"] = meta(null); it.metas["p2"] = meta(null) }
        open(undated).let { c -> c.toggle("p1"); c.toggle("p2"); assertFalse(c.current.shifts); assertNull(c.shift()) }
        val readOnly = open(Server(), MemoryRights.Access.Member(readonly = true))
        readOnly.toggle("p1"); readOnly.toggle("p2")
        assertFalse(readOnly.current.shifts, "no meta write, no shift")
        val away = Server().also { it.offline = OfflineAsOf(1L) }
        open(away).let { c -> c.toggle("p1"); c.toggle("p2"); assertFalse(c.current.shifts, "offline") }
        // control: the same two online, as a member, dated
        open(Server()).let { c -> c.toggle("p1"); c.toggle("p2"); assertTrue(c.current.shifts) }
    }
}
