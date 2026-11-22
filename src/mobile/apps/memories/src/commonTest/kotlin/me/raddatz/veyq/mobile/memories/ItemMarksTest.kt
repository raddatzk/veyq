package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.memories.models.ItemFavoritesEntry
import me.raddatz.veyq.mobile.api.memories.models.PutItemFavoritesRequest
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Memory's item marks (ADR 0149 decision 4), the web's `itemFavorites.test.ts` and `itemFavoritesStore.test.ts` twinned
 * over real seals: a server the test plays holds one sealed set per member and flags the caller's own (`self`), never naming
 * a member. Two sessions marking one medium count 2, each `mine` only for itself; a toggle before the own set is read writes
 * nothing; a personal scope has no count; a failed write puts the set back and says so; a rotation's `stale_epoch` is sealed
 * once more under the key read anew.
 */
class ItemMarksTest {
    private val p = testPrimitives
    private val suite = V1(p)
    private val scope = "7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c"
    private val node = "4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d"
    private val photo = "2a9b1d4a-6e8b-4c1f-8d3e-4a5b6c7c3f0e"

    /** The server's sets, by member; the scope key and its epoch as both members hold them. */
    private inner class Server(var key: ByteArray = p.randomBytes(32), var epoch: Long = 3) {
        val sets = LinkedHashMap<String, PutItemFavoritesRequest>()
        val puts = mutableListOf<String>()
        /** The next put of each member answered with this status (a rotation, a refusal); null: taken. */
        var answer: Int? = null
    }

    private inner class Member(val server: Server, val name: String, personal: Boolean = false) {
        /** A member's Master Key: the same member's every session tags with it. */
        private val mk = p.sha256(name.encodeToByteArray())
        var listed = true
        /** The scope key this device holds; a rotation's `stale_epoch` reads the server's anew ([ItemMarks.Store.reopen]). */
        var held = server.key to server.epoch
        val marks = ItemMarks(scope, node, personal, suite, p, object : ItemMarks.Store {
            override suspend fun list(): List<ItemFavoritesEntry> {
                check(listed) { "the listing did not read" }
                return server.sets.map { (who, r) -> ItemFavoritesEntry(who == name, r.ciphertext, r.wrappedValueKey) }
            }
            override fun key() = held.first.copyOf() to held.second
            override fun selfKey() = me.raddatz.veyq.mobile.crypto.ItemMarks(suite).selfKey(mk)
            override suspend fun put(request: PutItemFavoritesRequest): Int {
                server.puts += name
                val answer = server.answer
                if (answer != null) {
                    server.answer = null
                    if (answer != 0) return answer
                }
                if (request.epoch?.toLong() != server.epoch) return 409
                server.sets[name] = request
                return 0
            }
            override suspend fun reopen(): Pair<ByteArray, Long> {
                held = server.key to server.epoch
                return server.key.copyOf() to server.epoch
            }
        })
    }

    @Test
    fun twoMembersMarkOnePhoto_theCountIsTwo_eachMineOnlyForItself() = runTest {
        val server = Server()
        val ana = Member(server, "ana")
        val ben = Member(server, "ben")
        assertTrue(ana.marks.load())
        assertEquals(ItemMarks.Toggled.DONE, ana.marks.toggle(photo))
        assertTrue(ben.marks.load())
        assertEquals(1, ben.marks.count(photo))
        assertFalse(ben.marks.markedByMe(photo), "another's mark is not mine, whatever the count")
        assertEquals(ItemMarks.Toggled.DONE, ben.marks.toggle(photo))
        assertTrue(ana.marks.load())
        assertEquals(2, ana.marks.count(photo))
        assertTrue(ana.marks.markedByMe(photo))
        assertTrue(ben.marks.markedByMe(photo))
        // Ana takes hers back: the count is one, hers is not marked, Ben's still is
        assertEquals(ItemMarks.Toggled.DONE, ana.marks.toggle(photo))
        assertTrue(ben.marks.load())
        assertEquals(1, ben.marks.count(photo))
        assertFalse(ana.marks.markedByMe(photo))
        assertTrue(ben.marks.markedByMe(photo))
        assertEquals(setOf(photo), ben.marks.markedByAnyone())
    }

    @Test
    fun aToggleBeforeTheOwnSetIsReadWritesNothing() = runTest {
        val server = Server()
        val ana = Member(server, "ana")
        assertTrue(ana.marks.load())
        ana.marks.toggle(photo)
        val again = Member(server, "ana").also { it.listed = false }
        assertFalse(again.marks.load(), "control: the listing did not read")
        assertEquals(ItemMarks.Toggled.NOT_LOADED, again.marks.toggle("other"))
        assertEquals(listOf("ana"), server.puts, "nothing written over the own set it never read")
        assertTrue(Member(server, "ana").marks.let { it.load(); it.markedByMe(photo) }, "the own set as it was")
    }

    @Test
    fun aPersonalScopeShowsNoCount() = runTest {
        val server = Server()
        val me = Member(server, "me", personal = true)
        me.marks.load()
        me.marks.toggle(photo)
        assertNull(me.marks.count(photo))
        assertTrue(me.marks.markedByMe(photo), "control: it is marked")
    }

    @Test
    fun aFailedWritePutsTheSetBack_andARotationIsSealedOnceMore() = runTest {
        val server = Server()
        val ana = Member(server, "ana")
        ana.marks.load()
        server.answer = 500
        assertEquals(ItemMarks.Toggled.SAVE_FAILED, ana.marks.toggle(photo))
        assertFalse(ana.marks.markedByMe(photo), "the heart back as it was")
        // a rotation between the read and the write: the put at the old epoch is refused stale, the one under the new key lands
        server.key = p.randomBytes(32)
        server.epoch = 4
        server.puts.clear()
        assertEquals(ItemMarks.Toggled.DONE, ana.marks.toggle(photo))
        assertEquals(listOf("ana", "ana"), server.puts)
        assertEquals(4, server.sets.getValue("ana").epoch)
        assertTrue(Member(server, "ana").marks.let { it.load(); it.markedByMe(photo) }, "the set opens under the new key")
    }

    @Test
    fun theTwelveThousandTwoHundredEightyFifthMarkIsRefused() = runTest {
        val server = Server()
        val ana = Member(server, "ana")
        ana.marks.load()
        // 12,284 ids fit the contract's 262,144 characters; one more does not
        val many = (0 until 12_284).map { i -> "00000000-0000-4000-8000-${i.toString().padStart(12, '0')}" }
        val request = ItemMarks.seal(suite, p, scope, node, server.key, server.epoch, p.randomBytes(32), many)
        assertTrue(request != null, "12,284 marks fit")
        assertNull(ItemMarks.seal(suite, p, scope, node, server.key, server.epoch, p.randomBytes(32), many + "00000000-0000-4000-8000-999999999999"))
    }
}
