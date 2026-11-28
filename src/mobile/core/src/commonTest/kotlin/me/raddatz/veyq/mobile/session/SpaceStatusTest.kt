package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.CompromiseRotation
import me.raddatz.veyq.mobile.api.models.MyTenant
import me.raddatz.veyq.mobile.api.models.ScopeKind
import me.raddatz.veyq.mobile.api.models.TenantStatus
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Members panel's and a group page's status lines (the web's `TenantMembersPanel.vue`/`GroupMembersPanel.vue`,
 * [SpaceStatus]) and when the switcher's and the drawer's faces move their round ([SpaceFaces]). Bites, each measured red:
 * [SpaceStatus.lines] without its owner gate on the stuck notice, with re-keying winning over resealing, and with the
 * close's cause read as a rotation's; [SpaceStatus.cause] with `<=` for `<`; [Resealing.during] without its `finally`;
 * [SpaceFaces.load] without its unchanged check.
 */
class SpaceStatusTest {
    @Test
    fun anOwnerIsToldOfAStuckRotationAndOnlyAFailedOneOffersStartAgain() {
        assertEquals(
            listOf(SpaceStatusLine(Msg.MEMBERS_COMPROMISE_ROTATION_FAILED, warn = true, requeue = true)),
            SpaceStatus.lines(owner = true, stuck = CompromiseRotation.failed, resealing = false, rekeying = false, cause = null),
        )
        assertEquals(
            listOf(SpaceStatusLine(Msg.MEMBERS_COMPROMISE_ROTATION_STALLED, warn = true, requeue = false)),
            SpaceStatus.lines(owner = true, stuck = CompromiseRotation.stalled, resealing = false, rekeying = false, cause = null),
        )
        assertEquals(emptyList(), SpaceStatus.lines(owner = false, stuck = CompromiseRotation.failed, resealing = false, rekeying = true, cause = SpaceStatus.Cause.ROTATION))
    }

    @Test
    fun resealingWinsOverReKeyingAndAMemberSeesResealingToo() {
        assertEquals(
            listOf(SpaceStatusLine(Msg.SPACE_LOCK_RESEALING, warn = false)),
            SpaceStatus.lines(owner = true, stuck = null, resealing = true, rekeying = true, cause = SpaceStatus.Cause.ROTATION),
        )
        assertEquals(
            listOf(SpaceStatusLine(Msg.SPACE_LOCK_RESEALING, warn = false)),
            SpaceStatus.lines(owner = false, stuck = null, resealing = true, rekeying = false, cause = null),
        )
    }

    @Test
    fun aClosesReKeyIsSaidAsTheClosedLinksAndARotationsAsTheSpaces() {
        assertEquals(
            listOf(SpaceStatusLine(Msg.SHARE_CLOSED_REKEYING, warn = false)),
            SpaceStatus.lines(owner = true, stuck = null, resealing = false, rekeying = true, cause = SpaceStatus.Cause.CLOSE),
        )
        assertEquals(
            listOf(
                SpaceStatusLine(Msg.MEMBERS_COMPROMISE_ROTATION_STALLED, warn = true),
                SpaceStatusLine(Msg.ROTATION_STATUS_HINT_REKEYING, warn = false),
            ),
            SpaceStatus.lines(owner = true, stuck = CompromiseRotation.stalled, resealing = false, rekeying = true, cause = SpaceStatus.Cause.ROTATION),
        )
        assertEquals(SpaceStatus.Cause.ROTATION, SpaceStatus.cause(listOf(3, 2), currentEpoch = 3))
        assertEquals(SpaceStatus.Cause.CLOSE, SpaceStatus.cause(listOf(3, 3), currentEpoch = 3))
        assertNull(SpaceStatus.cause(emptyList(), currentEpoch = 3))
    }

    @Test
    fun aScopeIsResealingWhileItsStagingRunsAndNotAfterEvenAFailedOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        val run = async { Resealing.during("s-1") { gate.await() } }
        testScheduler.runCurrent()
        assertTrue(Resealing.has("s-1"))
        gate.complete(Unit)
        run.await()
        assertFalse(Resealing.has("s-1"))
        runCatching { Resealing.during("s-2") { error("staging failed") } }
        assertFalse(Resealing.has("s-2"), "a failed staging leaves no hint behind")
    }

    @Test
    fun theFacesMoveTheirRoundOnlyWhenAPictureOrAMarkChanged() = runTest {
        var read: SpaceFaces.Read? = SpaceFaces.Read(mapOf("t-1" to byteArrayOf(1)), setOf("g-1"))
        val faces = SpaceFaces { read }
        faces.load()
        assertEquals(1, faces.held.value.round)
        assertTrue(faces.held.value.marked("g-1"))
        read = SpaceFaces.Read(mapOf("t-1" to byteArrayOf(1)), setOf("g-1"))
        faces.load()
        assertEquals(1, faces.held.value.round, "an unchanged read asks the hosts for nothing")
        read = SpaceFaces.Read(mapOf("t-1" to byteArrayOf(2)), setOf("g-1"))
        faces.load()
        assertEquals(2, faces.held.value.round, "a picture replaced under its id moves the round")
        read = null
        faces.load()
        assertEquals(2, faces.held.value.round, "a read that fails keeps what was held")
        assertEquals(2.toByte(), faces.held.value.picture("t-1")?.single())
    }

    @Test
    fun anotherTenantsPictureIsOpenedUnderTheCallersOwnGrantFromItsSwitcherRow() {
        val row = MyTenant(
            tenantId = "t-2", name = "Club", domain = "club.example", role = MyTenant.Role.MEMBER, status = TenantStatus.entries.first(),
            iconEpoch = 4, wrappedGrant = "d3JhcA==", sealedIcon = "c2VhbA==", wrappedIconValueKey = "a3Y=", grantedEpoch = 7,
            granterUserId = "u-o", grantSignature = "c2ln", granterIdentityPublicKey = "cGs=",
        )
        val s = SpaceFaces.summaryOf(row)!!
        assertEquals(ScopeKind.TENANT, s.kind)
        assertEquals(7, s.grantedEpoch)
        assertEquals(7, s.currentEpoch)
        assertEquals(4, s.iconEpoch)
        assertEquals("u-o", s.granterUserId)
        assertNull(SpaceFaces.summaryOf(row.copy(wrappedGrant = null)), "no grant on the row, nothing to open: the initial")
    }
}
