package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.WebpEncoder
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The rest of the web's Members panel and group page on a running dev instance as a throwaway with its member
 * (`VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`; [Throwaway]): the owner sets the tenant's icon and the member opens the same bytes,
 * then it is removed; the owner gives the space a new lock (the epoch moves on, a fresh session of the member still opens
 * the tenant and its roster) and turns off every link (none was on); the roster carries no badge on the caller's own row. A
 * group: its icon set and opened by its member, its new lock (the epoch moves on, the member still reads its page), its
 * links turned off, the group deleted. The JVM has no WebP encoder: a test encoder hands fixed bytes a WebP's header starts.
 * With `VEYQ_LIVE_MEMBERS_REMOVE=1` it ends by removing the member with the new lock as the removal's second option.
 */
class MembersPageRestLiveTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val runs = mutableMapOf<String, LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.values.forEach { it.removeAll() }

    private val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 app-live-icon".encodeToByteArray()
    private val encoder = WebpEncoder { _, _, _, _ -> webp }
    private val rgba = ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4) { 0x7f }

    @Test
    fun anOwnerSetsTheIconsGivesNewLocksAndTurnsOffTheLinks() {
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
            val memberId = session("VEYQ_USER2").account.userId
            val tenantId = owner.tenant.tenantId

            // the tenant's icon: set by the owner, opened by the member to the byte, removed
            assertEquals(Notice(Msg.SPACE_ICON_SAVED), owner.icons(encoder).set(tenantId, rgba).notice)
            assertContentEquals(webp, session("VEYQ_USER2").icons().icon(tenantId), "the member opens the owner's icon")
            assertEquals(Notice(Msg.SPACE_ICON_REMOVED), owner.icons().clear(tenantId).notice)
            assertNull(owner.icons().icon(tenantId))

            val roster = owner.members.read()
            assertTrue(roster.ownerActs)
            assertTrue(roster.tenantName.isNotEmpty(), "the identity header's name")
            assertNull(roster.rows.single { it.userId == owner.account.userId }.badge, "no badge on one's own row")

            // the space's new lock: a fresh epoch, the member re-granted
            val before = owner.scopeKey(tenantId)!!.second
            val locked = owner.members.newLock()
            assertTrue(locked.done, "locked: ${locked.notice.msg}")
            assertTrue(locked.notice.msg in setOf(Msg.SPACE_LOCK_DONE, Msg.ROTATION_STATUS_REKEYING))
            assertEquals(before + 1, owner.scopeKey(tenantId)!!.second, "the new lock is the next epoch")
            val member = session("VEYQ_USER2")
            assertEquals(before + 1, member.scopeKey(tenantId)?.second, "the member opens the tenant under the new lock")
            assertNull(member.members.read().said)

            assertEquals(Notice(Msg.SPACE_LINKS_REVOKED_NONE), owner.members.revokeAllLinks().notice, "no link was on")

            // a group's picture, new lock and links
            if (owner.groups.list().canCreate) {
                val n = System.currentTimeMillis() % 100_000
                val scopeId = assertNotNull(owner.groups.create("Bild $n").scopeId)
                val username = roster.rows.single { it.userId == memberId }.username
                assertTrue(owner.groups.add(scopeId, username).done)
                if (owner.groups.page(scopeId).members.single { it.userId == memberId }.pendingConsent) assertTrue(session("VEYQ_USER2").groups.accept(scopeId).done)
                val owner = session("VEYQ") // a fresh session opens the new group's key
                assertEquals(Notice(Msg.SPACE_ICON_SAVED), owner.icons(encoder).set(scopeId, rgba).notice)
                assertContentEquals(webp, session("VEYQ_USER2").icons().icon(scopeId), "the member opens the group's icon")
                val page = owner.groups.page(scopeId)
                assertTrue(page.wholeActs)
                val g = owner.scopeKey(scopeId)!!.second
                assertTrue(owner.groups.newLock(scopeId).done)
                assertEquals(g + 1, owner.scopeKey(scopeId)!!.second, "the group's new lock")
                assertContentEquals(webp, owner.icons().icon(scopeId), "the icon's key moved with the lock")
                assertNull(session("VEYQ_USER2").groups.page(scopeId).said, "the member still reads the group")
                assertEquals(Notice(Msg.SPACE_LINKS_REVOKED_NONE), owner.groups.revokeAllLinks(scopeId).notice)
                assertTrue(owner.groups.delete(scopeId).done)
                assertTrue(!owner.groups.page(scopeId).wholeActs, "not while the group is being deleted")
            }

            if (System.getenv("VEYQ_LIVE_MEMBERS_REMOVE") == "1") {
                val e = owner.scopeKey(tenantId)!!.second
                assertEquals(Notice(Msg.MEMBERS_REMOVED), owner.members.remove(memberId, revokeLinks = false, newLock = true).notice)
                assertEquals(e + 1, owner.scopeKey(tenantId)!!.second, "the removal's new lock")
            }
            println("[live] icons, new locks and links turned off on $domain")
        }
    }
}
