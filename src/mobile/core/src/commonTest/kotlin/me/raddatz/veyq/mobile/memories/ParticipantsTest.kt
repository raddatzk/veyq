package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.memories.place.MemoryFacts
import me.raddatz.veyq.mobile.memories.place.PlayedMeta
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OfflineAsOf
import me.raddatz.veyq.mobile.session.Person
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who was there (ADR 0144 decision 1): the web's `memoryParticipants.test.ts` and `nodeMeta.test.ts`' `editParticipantsOf`
 * cases ported — normalizing, the merge, the sub-object's edit, the write that MERGES on a `409` instead of conflicting —,
 * the header's people line, the picker that ticks only the scope's current members, and the page's gate (a member's, on a
 * Memory, not in a personal scope, online).
 */
class ParticipantsTest {
    private val a = "aaaaaaaa-0000-4000-8000-000000000001"
    private val b = "bbbbbbbb-0000-4000-8000-000000000002"
    private val c = "cccccccc-0000-4000-8000-000000000003"
    private val d = "dddddddd-0000-4000-8000-000000000004"
    private val date = """{"mode":"single","from":"2026-05-01","to":"2026-05-01"}"""

    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)
    private fun obj(s: String) = json(s) as JsonObject

    // ---- normalizeParticipants ----

    @Test
    fun keepsUniqueNonEmptyStringIdsInOrderAndDropsEverythingElse() {
        assertEquals(listOf(a, b, c), Participants.normalize(json("""{"userIds":["$a","$b","$a","",7,null,"$c"]}""")))
    }

    @Test
    fun isEmptyForAnythingThatIsNotAUserIdsObject() {
        for (raw in listOf(null, "null", "\"x\"", "5", "[]", """{"userIds":"a"}""", """{"ids":["$a"]}""")) {
            assertEquals(emptyList(), Participants.normalize(raw?.let(::json)), "$raw")
        }
    }

    @Test
    fun capsTheListAtMax() {
        val many = (0 until Participants.MAX + 20).joinToString(",") { "\"u$it\"" }
        assertEquals(Participants.MAX, Participants.normalize(json("""{"userIds":[$many]}""")).size)
    }

    // ---- mergeParticipants ----

    @Test
    fun theMergeKeepsAPeersAdditionAndAddsTheEditors() = assertEquals(listOf(a, b, c), Participants.merge(listOf(a, b), listOf(a, c), listOf(a)))

    @Test
    fun anIdTheEditorRemovedStaysRemovedThoughAPeersListStillHoldsIt() = assertEquals(listOf(a), Participants.merge(listOf(a, b), listOf(a), listOf(a, b)))

    @Test
    fun theMergeRemovesNoIdTheEditorNeverHad() = assertEquals(listOf(d), Participants.merge(listOf(a, d), emptyList(), listOf(a)))

    @Test
    fun theMergedListIsCapped() {
        val stored = (0 until Participants.MAX).map { "u$it" }
        assertEquals(Participants.MAX, Participants.merge(stored, listOf("extra"), emptyList()).size)
    }

    // ---- editParticipantsOf ----

    private suspend fun edited(meta: String, merge: (List<String>) -> List<String>): JsonObject? = Participants.edit(merge)(obj(meta))

    @Test
    fun theEditHandsMergeOnlyTheStoredNonEmptyStringsDeduplicatedInOrder() = runTest {
        var handed: List<String>? = null
        val out = edited("""{"v":1,"participants":{"userIds":["a","",7,null,"a","b"]}}""") { handed = it; it + "c" }
        assertEquals(listOf("a", "b"), handed)
        assertEquals(obj("""{"v":1,"participants":{"userIds":["a","b","c"]}}"""), out)
    }

    @Test
    fun theEditWritesNothingWhereTheMergeReturnsTheStoredList() = runTest {
        assertNull(edited("""{"v":1,"participants":{"userIds":["a","b"]}}""") { it })
    }

    @Test
    fun anEmptyResultDropsTheSubObjectKeyKeepingEverySibling() = runTest {
        assertEquals(listOf("v", "date"), edited("""{"v":1,"date":"D","participants":{"userIds":["a"]}}""") { emptyList() }?.keys?.toList())
    }

    @Test
    fun theEditKeepsAnUnknownFieldInsideTheSubObjectAndReplacesOnlyUserIds() = runTest {
        assertEquals(
            obj("""{"v":1,"participants":{"userIds":["b"],"future":"kept"}}"""),
            edited("""{"v":1,"participants":{"userIds":["a"],"future":"kept"}}""") { listOf("b") },
        )
    }

    @Test
    fun aMalformedValueReadsAsNoIdsAndAWriteStartsACleanSubObject() = runTest {
        for (p in listOf(null, "null", "\"xy\"", "5", "{}", """{"userIds":"a"}""", """["x","y"]""")) {
            var handed: List<String>? = null
            val meta = if (p == null) """{"v":1}""" else """{"v":1,"participants":$p}"""
            val out = assertNotNull(edited(meta) { handed = it; listOf("u") }, "$p")
            assertEquals(emptyList(), handed, "$p reads as no ids")
            // an object keeps its own fields; a string's characters or an array's elements never become keys
            val expected = if (p != null && p.startsWith("{")) JsonObject(obj(p) + ("userIds" to json("""["u"]"""))) else obj("""{"userIds":["u"]}""")
            assertEquals(expected, out["participants"], "$p")
        }
    }

    // ---- setMemoryParticipants (read-modify-write, ADR 0077 + ADR 0083) ----

    private val m = PlayedMeta()

    private suspend fun write(meta: String, wanted: List<String>, openedWith: List<String>, server: PlayedMeta.Server, revision: Long = 3) =
        Participants.write(NodeMetaWrite.State(m.seal(meta), revision), m.open, m.sealMeta, wanted, openedWith, server::send)

    @Test
    fun aWriteStoresOnlyOpaqueIdsNextToTheDateWhichSurvives() = runTest {
        val server = PlayedMeta.Server()
        val (outcome, state) = write("""{"v":1,"date":$date}""", listOf(a, b), emptyList(), server)
        assertEquals(MemoryFacts.Outcome.Written, outcome)
        val after = m.opened(server.sent.single().first)
        assertEquals(obj("""{"userIds":["$a","$b"]}"""), after["participants"], "ids and nothing else (ADR 0083 decision 4)")
        assertEquals(obj(date), after["date"])
        assertEquals(3L, server.sent.single().second, "sent at the revision it read")
        assertEquals(4L, state.metaRevision)
    }

    @Test
    fun aWriteKeepsAnUnknownSiblingInsideTheSubObjectAndInTheBlob() = runTest {
        val server = PlayedMeta.Server()
        write("""{"v":1,"future":{"x":1},"participants":{"userIds":["$a"],"flag":true}}""", listOf(a, b), listOf(a), server)
        val after = m.opened(server.sent.single().first)
        assertEquals(obj("""{"x":1}"""), after["future"])
        assertEquals(obj("""{"userIds":["$a","$b"],"flag":true}"""), after["participants"])
    }

    @Test
    fun anEmptyListDropsTheSubObjectAndKeepsTheDate() = runTest {
        val server = PlayedMeta.Server()
        write("""{"v":1,"date":$date,"participants":{"userIds":["$a"]}}""", emptyList(), listOf(a), server)
        val after = m.opened(server.sent.single().first)
        assertFalse("participants" in after)
        assertEquals(obj(date), after["date"])
    }

    @Test
    fun nothingIsWrittenWhereTheListIsAlreadyWhatIsWanted() = runTest {
        val server = PlayedMeta.Server()
        val (outcome, state) = write("""{"v":1,"participants":{"userIds":["$a","$b"]}}""", listOf(a, b), listOf(a, b), server, revision = 5)
        assertEquals(MemoryFacts.Outcome.Unchanged, outcome)
        assertTrue(server.sent.isEmpty())
        assertEquals(5L, state.metaRevision)
    }

    /** Bite (a): written through the date's same-field edit, the peer's change to `participants` is a conflict — red here. */
    @Test
    fun on409APeersAddedParticipantSurvivesAndTheEditorsAdditionLands() = runTest {
        val peers = m.seal("""{"v":1,"date":$date,"participants":{"userIds":["$a","$d"]}}""")
        val server = PlayedMeta.Server(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peers, 8)))
        val (outcome, state) = write("""{"v":1,"participants":{"userIds":["$a"]}}""", listOf(a, c), listOf(a), server, revision = 7)
        assertEquals(MemoryFacts.Outcome.Written, outcome, "a concurrent add is merged, never a conflict")
        assertEquals(listOf(7L, 8L), server.sent.map { it.second })
        val after = m.opened(server.sent.last().first)
        assertEquals(listOf(a, d, c), Participants.read(after))
        assertEquals(obj(date), after["date"], "the peer's date kept")
        assertEquals(9L, state.metaRevision)
    }

    /** Bite (b): a merge that drops `openedWith` brings the removed id back — red here. */
    @Test
    fun on409TheEditorsOwnRemovalWinsOverThePeersListThatStillHoldsTheId() = runTest {
        val peers = m.seal("""{"v":1,"participants":{"userIds":["$a","$b","$d"]}}""")
        val server = PlayedMeta.Server(NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(peers, 2)))
        write("""{"v":1,"participants":{"userIds":["$a","$b"]}}""", listOf(a), listOf(a, b), server, revision = 1)
        assertEquals(2, server.sent.size, "the removal sent, and sent again merged on the peer's list")
        assertEquals(listOf(a, d), Participants.read(m.opened(server.sent.last().first)), "B removed by the editor, D added by the peer")
    }

    // ---- the header's people line ----

    private val anna = Person(a, "anna", "Anna Berg", avatarRevision = 2)
    private val ben = Person(b, "ben", "ben")
    private val cleo = Person(c, "cleo", "Cleo")
    private val dora = Person(d, "dora", "Dora")

    @Test
    fun theLineDrawsUpToThreeAvatarsAndPlusNInTheThirdSlot() {
        val members = listOf(anna, ben, cleo, dora)
        val one = assertNotNull(Participants.line(listOf(a), members, edits = false))
        assertEquals(listOf(Participants.Entry(a, "Anna Berg", false, 2)), one.avatars)
        assertEquals(0, one.more)
        val three = assertNotNull(Participants.line(listOf(a, b, c), members, edits = false))
        assertEquals(listOf(a, b, c), three.avatars.map { it.userId })
        assertEquals(0, three.more)
        val five = assertNotNull(Participants.line(listOf(a, b, c, d, "gone"), members, edits = false))
        assertEquals(listOf(a, b), five.avatars.map { it.userId }, "two avatars, then the third slot is +N")
        assertEquals(3, five.more)
        assertEquals(5, five.people.size, "the list a tap opens holds everyone")
    }

    @Test
    fun anIdNoCurrentMemberHoldsIsAFormerMemberAndNobodyIsFormerBeforeTheMembersRead() {
        val line = assertNotNull(Participants.line(listOf(a, "gone"), listOf(anna), edits = false))
        assertEquals(Participants.Entry("gone", null, former = true), line.people[1])
        val unread = assertNotNull(Participants.line(listOf(a, "gone"), null, edits = false))
        assertFalse(unread.named)
        assertTrue(unread.people.none { it.former || it.name != null }, "no name and no former member while the members read")
    }

    @Test
    fun anEmptyListIsTheAddChipForWhoMayEditAndNothingForAnyoneElse() {
        assertNull(Participants.line(emptyList(), listOf(anna), edits = false))
        val chip = assertNotNull(Participants.line(emptyList(), listOf(anna), edits = true))
        assertTrue(chip.add)
        assertFalse(assertNotNull(Participants.line(listOf(a), listOf(anna), edits = true)).add)
    }

    // ---- the picker ----

    private fun picker(opened: List<String>, members: List<Person>?, saved: MutableList<Pair<List<String>, List<String>>> = mutableListOf()) =
        ParticipantsPicker(opened, { members }) { wanted, openedWith -> saved += wanted to openedWith; MemoryFacts.Outcome.Written }

    /** Bite (c): a picker that ticks any id hands the server one the scope's members never returned — red here. */
    @Test
    fun onlyAnIdTheMembersListReturnedCanBeTicked() = runTest {
        val p = picker(emptyList(), listOf(anna, ben))
        assertFalse(p.toggle(a), "nothing is ticked before the members read")
        p.load()
        assertFalse(p.toggle("stranger"), "an id outside the scope's members is refused")
        assertTrue(p.toggle(a))
        assertEquals(listOf(a), p.current.pick)
        assertFalse(p.current.ticked("stranger"))
    }

    @Test
    fun aFormerMemberStaysTickedAndGreyedUntilUntickedThenIsGone() = runTest {
        val saved = mutableListOf<Pair<List<String>, List<String>>>()
        val p = picker(listOf(a, "gone"), listOf(anna), saved)
        p.load()
        assertEquals(listOf("gone"), p.current.former)
        assertTrue(p.toggle("gone"), "a former member can be unticked")
        assertEquals(emptyList(), p.current.former)
        assertFalse(p.toggle("gone"), "and not ticked again: no current member holds it")
        p.save()
        assertEquals(listOf(listOf(a) to listOf(a, "gone")), saved, "the pick, with what the picker opened with")
        assertTrue(p.current.saved)
    }

    @Test
    fun atTheCapNoOneMoreIsTicked() = runTest {
        val many = (0 until Participants.MAX).map { "u$it" }
        val p = picker(many, many.map { Person(it, it, it) } + anna)
        p.load()
        assertTrue(p.current.full)
        assertFalse(p.toggle(a), "the 101st is refused")
        assertTrue(p.toggle("u0"), "an untick is always allowed")
        assertTrue(p.toggle(a))
    }

    @Test
    fun theSearchHidesRowsByNameAnyCaseAndAccentAndKeepsThePick() = runTest {
        val p = picker(listOf(b), listOf(Person(a, "zoe", "Zoë Müller"), ben, cleo))
        p.load()
        p.search("  zoe mu ")
        assertEquals(listOf(a), p.current.visible.map { it.userId })
        assertEquals(listOf(b), p.current.pick, "a hidden pick stays ticked")
        p.search("nobody")
        assertTrue(p.current.noMatches)
    }

    @Test
    fun membersThatDidNotReadAreSaidAndReadAgain() = runTest {
        var answer: List<Person>? = null
        val p = ParticipantsPicker(emptyList(), { answer }) { _, _ -> MemoryFacts.Outcome.Written }
        p.load()
        assertTrue(p.current.failed)
        assertFalse(p.current.canSave)
        answer = listOf(anna)
        p.load()
        assertFalse(p.current.failed)
        assertTrue(p.current.canSave)
    }

    // ---- the page's gate ----

    private class Door(var participants: List<String>, override val personal: Boolean = false) : GalleryModel.Door {
        override val revisions = MutableStateFlow<Any?>(Any())
        override val uploaded = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val reach = MutableStateFlow(true)
        override val online get() = reach
        var offline: OfflineAsOf? = null
        var membersRead = 0
        val sets = mutableListOf<Pair<List<String>, List<String>>>()
        override suspend fun gallery() = MemoryMedia.Gallery(emptyList(), 1, 0, offline)
        override suspend fun scopeNodes() = emptyList<NodeRef>()
        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? = null
        override fun reader(item: MemoryMedia.Item): RangeReader? = null
        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
        override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
        override fun upload(sources: List<UploadSource>) = 0
        override suspend fun facts() = MemoryFacts.Facts(null, null, participants)
        override suspend fun members(): List<Person> = listOf(Person("a", "anna", "Anna")).also { membersRead++ }
        override suspend fun setParticipants(wanted: List<String>, openedWith: List<String>): MemoryFacts.Outcome {
            sets += wanted to openedWith
            participants = wanted
            return MemoryFacts.Outcome.Written
        }
    }

    private fun TestScope.open(door: Door, kind: String = "MEMORY", access: MemoryRights.Access = MemoryRights.Access.Member()) =
        GalleryModel(NodeAt("s", "m", kind, "Ostsee"), door, backgroundScope, backgroundScope, access).also { runCurrent() }

    @Test
    fun aMembersMemoryShowsItsPeopleAndOffersThePickerWhereTheMetaMayBeWritten() = runTest {
        val door = Door(listOf("a", "gone"))
        val g = open(door)
        val line = assertNotNull(g.current.people)
        assertEquals(listOf("Anna", null), line.people.map { it.name })
        assertTrue(line.people[1].former)
        assertTrue(line.edits)
        val picker = assertNotNull(g.participantsPicker())
        picker.load()
        picker.toggle("gone")
        picker.save()
        runCurrent()
        assertEquals(listOf(listOf("a") to listOf("a", "gone")), door.sets)
        assertEquals(listOf("a"), g.current.people?.people?.map { it.userId }, "the line follows the save")
        assertEquals(2, door.membersRead, "once for the line, once as the picker opened; not again for the line after the save")
    }

    @Test
    fun noPeopleOnAChapterInAPersonalScopeForAGuestOrOffline() = runTest {
        assertNull(open(Door(listOf("a")), kind = "CHAPTER").current.people, "a chapter has no participants")
        val personal = open(Door(listOf("a"), personal = true))
        assertNull(personal.current.people, "a personal scope has none")
        assertNull(personal.participantsPicker())
        val guest = open(Door(listOf("a")), access = MemoryRights.Access.Guest(me.raddatz.veyq.mobile.session.ShareLinks.READ))
        assertNull(guest.current.people, "a guest is shown no participants")
        val readonly = open(Door(listOf("a")), access = MemoryRights.Access.Member(readonly = true))
        assertNotNull(readonly.current.people, "a read-only member sees them")
        assertNull(readonly.participantsPicker(), "and edits none")
        val offlineDoor = Door(listOf("a")).also { it.reach.value = false; it.offline = OfflineAsOf(1L) }
        val offline = open(offlineDoor)
        assertNull(offline.current.people, "offline the participants are not shown (no member list is kept)")
        assertNull(offline.participantsPicker())
        assertEquals(0, offlineDoor.membersRead, "and no member list is asked for")
    }
}
