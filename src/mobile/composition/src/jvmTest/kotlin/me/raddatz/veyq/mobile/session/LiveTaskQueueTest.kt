package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.tasks.TaskOutcome
import me.raddatz.veyq.mobile.words.Msg
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Inbox #966 against a running dev instance, as the OWNER of a throwaway with a member (`VEYQ_LIVE_THROWAWAY_ENV`): the
 * owner makes a group and proposes the member; the member declines, which queues the group's rotation on the server (the
 * proposal held a grant). The owner's core claims the job, commits the next epoch over the directory read after the
 * claim, re-keys and completes it — a second pass finds nothing — and a fresh session holds the group at the new epoch.
 * Where the member is added without a proposal, the owner removes them from the tenant instead, which queues the same
 * rotation for the group they shared. The group is deleted after.
 */
class LiveTaskQueueTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> LiveHttp.client(block) }
    private fun device() = ThisDevice(MemoryDeviceStore(), SoftwareDeviceKeys(p), "live task queue device", p)

    @Test
    fun aQueuedRotationIsCompletedByTheOwnersCore() {
        Assume.assumeTrue("VEYQ_LIVE_THROWAWAY_ENV unset", System.getenv("VEYQ_LIVE_THROWAWAY_ENV") != null)
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertTrue(t.hasMember, "the throwaway has no member: create it with --with-member")
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        runTest(timeout = 6.minutes) {
            val owner = VeyqAccount.signIn(p, origin, t.email(), t.env.getValue("VEYQ_PASSWORD"), t.env.getValue("VEYQ_SECRET_KEY"), device(), client)
            val member = VeyqAccount.signIn(p, origin, t.email("VEYQ_USER2"), t.env.getValue("VEYQ_USER2_PASSWORD"), t.env.getValue("VEYQ_USER2_SECRET_KEY"), device(), client)
            val tenant = owner.tenants.first { it.domain == domain }
            var groupId: String? = null
            try {
                val session = owner.open(tenant).also { it.rekeyRules = AppKinds.rules }
                val made = session.groups.create("Task queue ${System.currentTimeMillis()}")
                assertTrue(made.done, "the group was not made: ${made.notice}")
                val g = assertNotNull(made.scopeId).also { groupId = it }
                val added = session.groups.add(g, t.env.getValue("VEYQ_USER2_USERNAME"))
                assertTrue(added.done, "the member was not added: ${added.notice}")
                val proposal = added.notice?.msg == Msg.GROUPS_INVITATION_SENT
                println("[live] the member was ${if (proposal) "proposed" else "added"}")
                if (proposal) {
                    val ms = member.open(member.tenants.first { it.domain == domain })
                    assertTrue(ms.groups.decline(g).done, "the member's decline failed")
                } else {
                    val removed = session.members.remove(member.userId, revokeLinks = false, newLock = false)
                    assertTrue(removed.done, "the member was not removed: ${removed.notice}")
                }

                val owners = owner.open(tenant).also { it.rekeyRules = AppKinds.rules }
                val before = assertNotNull(owners.scopeEpoch(g), "the group is not open")
                val pass = owners.tasks.drain()
                println("[live] pass: $pass")
                assertEquals(1, pass.rotated, "the queued rotation was not completed")
                assertEquals(TaskOutcome(0, 0), owners.tasks.drain(), "the job was offered again")

                val fresh = owner.open(tenant)
                assertEquals(before + 1, fresh.scopeEpoch(g), "the group is not at its next epoch")
            } finally {
                groupId?.let { id -> runCatching { owner.open(tenant).groups.delete(id) } }
                runCatching { member.removeThisDevice() }
                runCatching { owner.removeThisDevice() }
            }
        }
    }
}
