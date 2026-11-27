package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Settings' Members and the owner's terms editor on a running dev instance as a throwaway with its member (`VEYQ_LIVE`,
 * `VEYQ_LIVE_THROWAWAY_ENV`; [Throwaway]): the owner's roster offers the web's acts, the member's none; the owner makes the
 * member a co-owner and takes it back (a fresh session reads each role), the last owner's demotion is refused as such; the
 * owner edits the terms through the core's draft (a language removed, the other's text set) and a fresh session reads the
 * new revision, the member's editor is read-only. With `VEYQ_LIVE_MEMBERS_REMOVE=1` it ends by removing the member, who
 * then reads no roster (the throwaway's member is gone: its tenant is erased with it).
 */
class MembersLiveTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val runs = mutableMapOf<String, LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.values.forEach { it.removeAll() }

    @Test
    fun anOwnerChangesAMembersRoleEditsTheTermsAndRemovesTheMember() {
        assumeTrue("VEYQ_LIVE and VEYQ_LIVE_THROWAWAY_ENV", System.getenv("VEYQ_LIVE") != null && System.getenv("VEYQ_LIVE_THROWAWAY_ENV") != null)
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assumeTrue("a throwaway with its member (--with-member)", t.hasMember)
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        runTest(timeout = 4.minutes) {
            suspend fun session(who: String): TenantSession {
                val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(who), t.env.getValue("${who}_PASSWORD"), t.env.getValue("${who}_SECRET_KEY"), runs.getOrPut(who) { LiveRun(who, t.file) }.device()) { block -> LiveHttp.client(block) }
                return a.open(a.tenants.first { it.domain == domain })
            }
            val owner = session("VEYQ")
            val member = session("VEYQ_USER2")
            val ownerId = owner.account.userId
            val memberId = member.account.userId

            val roster = owner.members.read()
            assertNull(roster.said)
            assertTrue(roster.owner)
            val them = roster.rows.single { it.userId == memberId }
            val me = roster.rows.single { it.userId == ownerId }
            assertEquals(listOf(true, false, true), listOf(them.promote, them.demote, them.remove))
            assertEquals(listOf(false, true, false), listOf(me.promote, me.demote, me.remove))
            assertTrue(me.realName != null || me.title.startsWith("@"))
            val asMember = member.members.read()
            assertFalse(asMember.owner)
            assertTrue(asMember.rows.none { it.promote || it.demote || it.remove })

            assertEquals(Notice(Msg.MEMBERS_LAST_OWNER), owner.members.setRole(ownerId, owner = false).notice, "the last owner stays")
            assertEquals(Notice(Msg.MEMBERS_PROMOTED), owner.members.setRole(memberId, owner = true).notice)
            assertTrue(session("VEYQ_USER2").members.read().owner, "a fresh session of the member reads the co-ownership")
            assertEquals(Notice(Msg.MEMBERS_DEMOTED), owner.members.setRole(memberId, owner = false).notice)
            assertFalse(session("VEYQ_USER2").members.read().owner)

            val editing = assertNotNull(MemberTerms.editing(owner, "en"))
            assertTrue(editing.owner)
            val n = System.currentTimeMillis() % 100_000
            val draft = editing.draft.withLanguage("en").withLanguage("de").without("de").withText("en", "## App terms $n\n\nBe kind.")
            val saved = MemberTerms.save(owner, draft)
            val next = assertNotNull(saved.draft)
            assertEquals(Notice(Msg.TERMS_SAVED, next.revision), saved.notice)
            assertEquals(listOf("en"), next.texts.map { it.lang }, "the removed language is gone")
            val read = assertNotNull(MemberTerms.editing(session("VEYQ"), "de"))
            assertEquals(next.revision, read.draft.revision)
            assertEquals("## App terms $n\n\nBe kind.", read.shown.terms, "German falls back to the English text")
            assertFalse(assertNotNull(MemberTerms.editing(member, "en")).owner, "the member reads them read-only")
            assertEquals(Notice(Msg.TERMS_NOT_OWNER), MemberTerms.save(member, draft).notice)

            if (System.getenv("VEYQ_LIVE_MEMBERS_REMOVE") == "1") {
                assertEquals(Notice(Msg.MEMBERS_REMOVED), owner.members.remove(memberId, revokeLinks = true).notice)
                assertTrue(owner.members.read().rows.none { it.userId == memberId })
            }
        }
    }
}
