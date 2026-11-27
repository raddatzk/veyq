package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Groups on a running dev instance as a throwaway with its member (`VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`; [Throwaway]):
 * the owner creates a group (where `core.group-space` is on), renames it, adds the member by username; the member's fresh
 * session lists the group under the name opened on its device and reads its page read-only; the owner makes them a
 * co-owner and back, removes them by the group's removal-rotation (the group's epoch moves, the member lists it no more),
 * then deletes the group and restores it, and deletes it again.
 */
class GroupsLiveTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val runs = mutableMapOf<String, LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.values.forEach { it.removeAll() }

    @Test
    fun anOwnerMakesAGroupAddsAMemberAndRemovesThemByRotation() {
        assumeTrue("VEYQ_LIVE and VEYQ_LIVE_THROWAWAY_ENV", System.getenv("VEYQ_LIVE") != null && System.getenv("VEYQ_LIVE_THROWAWAY_ENV") != null)
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assumeTrue("a throwaway with its member (--with-member)", t.hasMember)
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        runTest(timeout = 6.minutes) {
            suspend fun session(who: String): TenantSession {
                val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(who), t.env.getValue("${who}_PASSWORD"), t.env.getValue("${who}_SECRET_KEY"), runs.getOrPut(who) { LiveRun(who, t.file) }.device()) { block -> LiveHttp.client(block) }
                return a.open(a.tenants.first { it.domain == domain })
            }
            val owner = session("VEYQ")
            val listed = owner.groups.list()
            assumeTrue("core.group-space is on", listed.canCreate)
            val n = System.currentTimeMillis() % 100_000
            val made = owner.groups.create("Chor $n")
            assertTrue(made.done, "created: ${made.notice?.msg}")
            val scopeId = assertNotNull(made.scopeId)
            assertEquals("Chor $n", owner.groups.list().rows.single { it.scopeId == scopeId }.name, "the name opens on the device")
            assertEquals("Chor $n", owner.spaces().single { it.scopeId == scopeId }.name, "the shell's space list names it")
            assertTrue(owner.groups.rename(scopeId, "Chor $n neu").done)
            assertEquals("Chor $n neu", owner.groups.page(scopeId).name)

            val memberUser = session("VEYQ_USER2")
            val username = owner.members.read().rows.single { it.userId == memberUser.account.userId }.username
            val added = owner.groups.add(scopeId, username)
            assertTrue(added.done, "added: ${added.notice?.msg}")
            val page = owner.groups.page(scopeId)
            val them = page.members.single { it.userId == memberUser.account.userId }
            if (them.pendingConsent) {
                val asked = session("VEYQ_USER2").groups.invitations().rows.single { it.scopeId == scopeId }
                assertEquals("Chor $n neu", asked.name, "the invitee opens the group's own name")
                assertTrue(session("VEYQ_USER2").groups.accept(scopeId).done)
            }
            val member = session("VEYQ_USER2")
            assertEquals("Chor $n neu", member.groups.list().rows.single { it.scopeId == scopeId }.name, "the member opens the name")
            val read = member.groups.page(scopeId)
            assertTrue(read.members.none { it.promote || it.demote || it.remove || it.rescind } && read.readonlyNote != null)

            assertTrue(owner.groups.setRole(scopeId, them.userId, owner = true).done)
            assertTrue(owner.groups.setRole(scopeId, them.userId, owner = false).done)
            val before = owner.scopeKey(scopeId)!!.second
            val removed = owner.groups.remove(scopeId, them.userId, revokeLinks = false)
            assertTrue(removed.done, "removed: ${removed.notice?.msg}")
            assertEquals(before + 1, owner.scopeKey(scopeId)!!.second, "the removal rotated the group")
            assertTrue(owner.groups.page(scopeId).members.none { it.userId == them.userId })
            assertTrue(session("VEYQ_USER2").groups.list().rows.none { it.scopeId == scopeId }, "the removed member lists it no more")

            assertTrue(owner.groups.delete(scopeId).done)
            assertTrue(owner.groups.list().rows.single { it.scopeId == scopeId }.deleted)
            assertTrue(owner.groups.restore(scopeId).done)
            assertTrue(!owner.groups.list().rows.single { it.scopeId == scopeId }.deleted)
            assertTrue(owner.groups.delete(scopeId).done)
            println("[live] group $scopeId made, member added and removed by rotation, deleted")
        }
    }
}
