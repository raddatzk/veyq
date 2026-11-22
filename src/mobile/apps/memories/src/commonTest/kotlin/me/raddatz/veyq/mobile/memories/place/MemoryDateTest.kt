package me.raddatz.veyq.mobile.memories.place

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A Memory's date (uc.memory.date, uc.memory.concurrent-curation; ADR 0143 D5): the web's `memoryDate.test.ts` case for
 * case over real seals and a server the test plays — what reads as a date, the `auto` range over trustworthy capture
 * times only, the editor's save (W1 a peer's place survives, W3 a peer's date re-opens the editor, W6 an unopenable blob
 * written never) and the self-heal (W2 a peer's manual date never overwritten on the conflict's blob).
 */
class MemoryDateTest {
    private val m = PlayedMeta()
    private val lisbon = """{"name":"Lisboa","nameSource":"manual","derived":{"lat":38.72,"lng":-9.14},"derivedMode":"fixed"}"""

    /** A day's noon as epoch ms, and that day back: the zone's rule is `LocalDays`', the range's is which times count. */
    private fun noon(day: String) = kotlin.time.Instant.parse("${day}T12:00:00Z").toEpochMilliseconds()
    private val utcDay: (Long) -> String = { kotlin.time.Instant.fromEpochMilliseconds(it).toString().substring(0, 10) }

    private fun date(json: String) = MemoryDate.normalize(Json.parseToJsonElement(json))

    @Test
    fun aDateReadsOnlyWithOneOfTheFourModesAndIsoDays() {
        assertEquals(MemoryDate(MemoryDate.Mode.RANGE, "2026-05-01", "2026-05-04"), date("""{"mode":"range","from":"2026-05-01","to":"2026-05-04"}"""))
        assertEquals(MemoryDate(MemoryDate.Mode.ONGOING, "2026-05-01"), date("""{"mode":"ongoing","from":"2026-05-01","to":"May"}"""))
        assertNull(date("""{"mode":"someday"}"""))
        assertNull(date("""{"from":"2026-05-01"}"""))
        assertNull(MemoryDate.normalize(null))
        assertEquals("""{"mode":"single","from":"2026-05-01","to":"2026-05-01"}""", MemoryDate(MemoryDate.Mode.SINGLE, "2026-05-01", "2026-05-01").toJson().toString())
        assertEquals("""{"mode":"auto"}""", MemoryDate.NONE.toJson().toString(), "absent ends left out, as JSON.stringify leaves undefined out")
    }

    /** The range case: min to max over trustworthy capture times only — a file date never stretches it. */
    @Test
    fun theAutoRangeRunsOverTrustworthyCaptureTimesOnlyNeverAFileDate() {
        val items = listOf(
            m.item("""{"v":1,"capturedAt":${noon("2026-03-10")},"capturedAtSource":"exif"}"""),
            m.item("""{"v":1,"capturedAt":${noon("2026-03-02")},"capturedAtSource":"exif"}"""), // the earliest
            m.item("""{"v":1,"capturedAt":${noon("2020-01-01")},"capturedAtSource":"file"}"""), // a file date: out
            m.item("""{"v":1,"capturedAt":${noon("2030-01-01")}}"""), // no source: out
            m.item("""{"v":1,"capturedAt":${noon("2019-01-01")},"capturedAtSource":"file","capturedAtOverride":${noon("2026-03-20")}}"""), // corrected: the latest
            m.item(null),
        )
        assertEquals(MemoryDate(MemoryDate.Mode.AUTO, "2026-03-02", "2026-03-20"), MemoryDate.autoRange(items, utcDay))
    }

    @Test
    fun aVideosContainerTimeIsTrustworthyASourceThisBuildDoesNotKnowIsNot() {
        val items = listOf(
            m.item("""{"v":1,"capturedAt":${noon("2026-03-02")},"capturedAtSource":"container"}"""),
            m.item("""{"v":1,"capturedAt":${noon("2026-03-05")},"capturedAtSource":"exif"}"""),
            m.item("""{"v":1,"capturedAt":${noon("2026-04-30")},"capturedAtSource":"satellite"}"""),
        )
        assertEquals(MemoryDate(MemoryDate.Mode.AUTO, "2026-03-02", "2026-03-05"), MemoryDate.autoRange(items, utcDay))
    }

    @Test
    fun theRangeIsEmptyAutoWithoutTrustworthyDatedMediaAndAMovedTierCountsNothing() {
        assertEquals(MemoryDate.NONE, MemoryDate.autoRange(listOf(m.item("""{"v":1,"capturedAt":1,"capturedAtSource":"file"}"""), m.item(null)), utcDay))
        // a tier sealed for another medium does not open under this one's id: it gives nothing
        val donor = m.item("""{"v":1,"capturedAt":${noon("2020-01-01")},"capturedAtSource":"exif"}""")
        assertNull(me.raddatz.veyq.mobile.memories.MemoryMedia.openMeta(m.suite, m.scopeId, "another", m.key, donor.wrappedMeta))
    }

    @Test
    fun theHeaderShowsAManualDateAsSealedElseTheSealedAutoRangeElseTheLiveOne() {
        val live = MemoryDate(MemoryDate.Mode.AUTO, "2026-01-01", "2026-01-02")
        assertEquals(live, MemoryDate.effective(null, live))
        assertEquals(live, MemoryDate.effective(MemoryDate.NONE, live))
        val sealed = MemoryDate(MemoryDate.Mode.AUTO, "2025-01-01", "2025-01-01")
        assertEquals(sealed, MemoryDate.effective(sealed, live))
        val manual = MemoryDate(MemoryDate.Mode.ONGOING, "2024-01-01")
        assertEquals(manual, MemoryDate.effective(manual, live))
    }

    private suspend fun save(date: MemoryDate, base: NodeMetaWrite.State, server: PlayedMeta.Server) =
        MemoryFacts.write(base, m.open, m.sealMeta, MemoryFacts.dateEdit(date, MemoryFacts.listedField(m.open, base, MemoryDate.FIELD)), server::send)

    @Test
    fun aSaveSealsTheDateAtTheRevisionItWasBuiltFromAndKeepsASiblingPlace() = runTest {
        val server = PlayedMeta.Server()
        val d = MemoryDate(MemoryDate.Mode.SINGLE, "2026-05-01", "2026-05-01")
        val (out, state) = save(d, NodeMetaWrite.State(m.seal("""{"v":1,"location":$lisbon}"""), 4), server)
        assertEquals(MemoryFacts.Outcome.Written, out)
        assertEquals(4L, server.sent.single().second)
        val sent = m.opened(server.sent.single().first)
        assertEquals(d, MemoryDate.of(sent))
        assertEquals(Json.parseToJsonElement(lisbon), sent["location"])
        assertEquals(5L, state.metaRevision)
    }

    @Test
    fun w1OnAStaleRevisionOnlyTheDateIsAppliedAgainAPeersPlaceSurvives() = runTest {
        val peers = m.seal("""{"v":1,"location":$lisbon}""")
        val server = PlayedMeta.Server(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peers, 5)))
        val d = MemoryDate(MemoryDate.Mode.SINGLE, "2026-05-01", "2026-05-01")
        val (out, state) = save(d, NodeMetaWrite.State(null, 4), server)
        assertEquals(MemoryFacts.Outcome.Written, out)
        assertEquals(listOf(4L, 5L), server.sent.map { it.second })
        val retried = m.opened(server.sent[1].first)
        assertEquals(d, MemoryDate.of(retried))
        assertEquals(Json.parseToJsonElement(lisbon), retried["location"])
        assertEquals(6L, state.metaRevision)
    }

    /** The concurrent case for the editor (W3): a peer changed the DATE itself — no retry, the editor re-opens on theirs. */
    @Test
    fun w3ASameFieldConflictMakesNoRetryAndSurfacesThePeersDate() = runTest {
        val opener = m.seal("""{"v":1,"date":{"mode":"single","from":"2026-01-01","to":"2026-01-01"}}""")
        val peers = m.seal("""{"v":1,"date":{"mode":"single","from":"2026-02-02","to":"2026-02-02"}}""")
        val server = PlayedMeta.Server(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peers, 2)))
        val (out, _) = save(MemoryDate.NONE, NodeMetaWrite.State(opener, 1), server)
        assertEquals(MemoryFacts.Outcome.Conflict(MemoryFacts.Facts(MemoryDate(MemoryDate.Mode.SINGLE, "2026-02-02", "2026-02-02"), null)), out)
        assertEquals(1, server.sent.size)
    }

    @Test
    fun w6ASaveOverABlobThatDoesNotOpenSendsNothing() = runTest {
        val otherKeys = m.seal("""{"v":1,"location":$lisbon}""", key = ByteArray(32) { 9 })
        val server = PlayedMeta.Server()
        assertEquals(MemoryFacts.Outcome.Unreadable, save(MemoryDate.NONE, NodeMetaWrite.State(otherKeys, 1), server).first)
        assertEquals(0, server.sent.size)
        // the control: the same save over an ABSENT blob is a fresh one and is sent
        assertEquals(MemoryFacts.Outcome.Written, save(MemoryDate.NONE, NodeMetaWrite.State(null, 0), server).first)
        assertEquals(1, server.sent.size)
    }

    private suspend fun reconcile(next: MemoryDate, base: NodeMetaWrite.State, server: PlayedMeta.Server): MemoryFacts.Outcome {
        val edit = MemoryDate.reconcile(next)
        return MemoryFacts.write(base, m.open, m.sealMeta, { edit(it) }, server::send).first
    }

    private val dated = MemoryDate(MemoryDate.Mode.AUTO, "2026-03-02", "2026-03-02")

    /**
     * The concurrent case for the self-heal (W2): it starts from a stale `auto` tree and meets a `409` whose blob holds a
     * peer's MANUAL date — its whole decision runs again there, so the manual date is never overwritten: no second write.
     */
    @Test
    fun w2TheSelfHealRechecksAManualDateOnTheConflictsBlobAndWritesNothing() = runTest {
        val peers = m.seal("""{"v":1,"date":{"mode":"single","from":"2025-12-24","to":"2025-12-24"}}""")
        val server = PlayedMeta.Server(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peers, 3)))
        assertEquals(MemoryFacts.Outcome.Unchanged, reconcile(dated, NodeMetaWrite.State(null, 2), server))
        assertEquals(1, server.sent.size, "the first attempt wrote (the stale tree said auto); the second never went")
    }

    @Test
    fun theSelfHealWritesTheRangeFromAnAutoBlobAndNothingWhereItIsRightOrManual() = runTest {
        val server = PlayedMeta.Server()
        assertEquals(MemoryFacts.Outcome.Written, reconcile(dated, NodeMetaWrite.State(null, 7), server))
        assertEquals(7L, server.sent.single().second)
        assertEquals(dated, MemoryDate.of(m.opened(server.sent.single().first)))
        val right = m.seal("""{"v":1,"date":{"mode":"auto","from":"2026-03-02","to":"2026-03-02"}}""")
        assertEquals(MemoryFacts.Outcome.Unchanged, reconcile(dated, NodeMetaWrite.State(right, 8), server))
        val manual = m.seal("""{"v":1,"date":{"mode":"range","from":"2026-01-01","to":"2026-01-09"}}""")
        assertEquals(MemoryFacts.Outcome.Unchanged, reconcile(dated, NodeMetaWrite.State(manual, 8), server))
        assertEquals(MemoryFacts.Outcome.Unchanged, reconcile(MemoryDate.NONE, NodeMetaWrite.State(null, 8), server), "absent meta is the empty auto already")
        assertEquals(1, server.sent.size)
    }

    @Test
    fun w6AnUnopenableBlobStopsTheSelfHealWithoutAWrite() = runTest {
        val server = PlayedMeta.Server()
        val otherKeys = m.seal("""{"v":1,"location":$lisbon}""", key = ByteArray(32) { 9 })
        assertEquals(MemoryFacts.Outcome.Unreadable, reconcile(dated, NodeMetaWrite.State(otherKeys, 1), server))
        assertEquals(0, server.sent.size)
    }

    @Test
    fun theDateEditorSavesEachModeAndRefusesARangeEndingBeforeItStarts() = runTest {
        val saved = mutableListOf<MemoryDate>()
        val auto = MemoryDate(MemoryDate.Mode.AUTO, "2026-03-02", "2026-03-05")
        val editor = DateEditor(null, auto) { saved += it; MemoryFacts.Outcome.Written }
        assertEquals(MemoryDate.Mode.AUTO, editor.current.mode)
        assertEquals(EditorSave.Saved, editor.save())
        assertEquals(auto, saved.last(), "auto seals the live range")
        editor.mode(MemoryDate.Mode.RANGE)
        editor.from("2026-05-04")
        editor.to("2026-05-01")
        assertEquals(true, editor.current.rangeInvalid)
        assertNull(editor.save(), "a range ending before it starts is not saved (E4)")
        editor.to("2026-05-04")
        editor.save()
        assertEquals(MemoryDate(MemoryDate.Mode.RANGE, "2026-05-04", "2026-05-04"), saved.last())
        editor.mode(MemoryDate.Mode.SINGLE)
        editor.save()
        assertEquals(MemoryDate(MemoryDate.Mode.SINGLE, "2026-05-04", "2026-05-04"), saved.last())
        editor.mode(MemoryDate.Mode.ONGOING)
        editor.save()
        assertEquals(MemoryDate(MemoryDate.Mode.ONGOING, "2026-05-04"), saved.last())
        // a peer's date written meanwhile: the fields re-open on it
        val peer = MemoryDate(MemoryDate.Mode.SINGLE, "2024-02-02", "2024-02-02")
        val beaten = DateEditor(null, auto) { MemoryFacts.Outcome.Conflict(MemoryFacts.Facts(peer, null)) }
        beaten.mode(MemoryDate.Mode.ONGOING)
        beaten.from("2026-01-01")
        assertEquals(EditorSave.Reopen(MemoryFacts.Facts(peer, null)), beaten.save())
        assertEquals(DateEditor.State(MemoryDate.Mode.SINGLE, "2024-02-02", "2024-02-02"), beaten.current)
    }
}
