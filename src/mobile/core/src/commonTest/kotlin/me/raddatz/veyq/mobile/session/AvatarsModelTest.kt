package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When the members' pictures are read ([AvatarsModel], ADR 0129 decision 1): synced when the tenant opens, again after
 * the people were read anew with a change, never twice for an unchanged set — over a [MemberAvatars] the test plays,
 * counting its syncs. Its bites, each measured red: [AvatarsModel.peopleRead] without its unchanged-set check,
 * [AvatarsModel.load] without its sync.
 */
class AvatarsModelTest {
    private class Played : AvatarsModel.Avatars {
        val synced = ArrayList<Map<String, Int>>()
        val held = HashMap<String, ByteArray>()
        override suspend fun sync(people: Collection<Person>): Boolean {
            synced += people.associate { it.userId to it.avatarRevision }
            people.forEach { p -> if (p.avatarRevision > 0) held[p.userId] = byteArrayOf(p.avatarRevision.toByte()) else held.remove(p.userId) }
            return true
        }
        override fun pictures(): Map<String, ByteArray> = held.toMap()
    }

    private val ana = Person("u-ana", "ana", "Ana", avatarRevision = 1)
    private val ben = Person("u-ben", "ben", "Ben")

    @Test
    fun thePicturesAreSyncedWhenTheTenantOpens() = runTest {
        val played = Played()
        val model = AvatarsModel(played) { listOf(ana, ben) }
        assertTrue(model.state.value.isEmpty(), "the letters show until the first sync")
        model.load()
        assertEquals(listOf(mapOf("u-ana" to 1, "u-ben" to 0)), played.synced)
        assertEquals(setOf("u-ana"), model.state.value.keys)
        assertContentEquals(byteArrayOf(1), model.state.value["u-ana"])
    }

    @Test
    fun theyAreSyncedAgainAfterThePeopleChange() = runTest {
        val played = Played()
        val model = AvatarsModel(played) { listOf(ana, ben) }
        model.load()
        // Ben set a picture
        model.peopleRead(listOf(ana, ben.copy(avatarRevision = 1)))
        // Ana removed hers
        model.peopleRead(listOf(ana.copy(avatarRevision = 0), ben.copy(avatarRevision = 1)))
        // Cleo joined
        model.peopleRead(listOf(ana.copy(avatarRevision = 0), ben.copy(avatarRevision = 1), Person("u-cleo", "cleo", "Cleo", 2)))
        assertEquals(4, played.synced.size)
        assertEquals(setOf("u-ben", "u-cleo"), model.state.value.keys)
    }

    @Test
    fun anUnchangedSetIsNotSyncedTwice() = runTest {
        val played = Played()
        val model = AvatarsModel(played) { listOf(ana, ben) }
        model.load()
        model.peopleRead(listOf(ben, ana))
        model.load()
        assertEquals(1, played.synced.size, "the same people, read anew or in another order, ask nothing")
    }

    @Test
    fun aSyncThatThrowsShowsWhatIsHeld() = runTest {
        val played = object : AvatarsModel.Avatars {
            override suspend fun sync(people: Collection<Person>): Boolean = throw IllegalStateException("offline")
            override fun pictures(): Map<String, ByteArray> = emptyMap()
        }
        val model = AvatarsModel(played) { listOf(ana) }
        model.load()
        assertTrue(model.state.value.isEmpty(), "a failed sync shows the letters, never an error")
    }
}
