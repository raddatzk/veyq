package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.sharing.ReceivedLink
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Inviting and joining on a running dev instance as a throwaway owner (`VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`; [Throwaway]):
 * the owner mints a link invite with a label (the pending list opens it), renames it, re-issues it (the old link no longer
 * opens), and a NEW account joins through the new link with the core's [TenantJoin] — the account born on the device, its
 * kit written, the invite opened with the code the link carries and checked before anything is sent; the owner's roster then
 * lists it, and its own fresh sign-in opens the tenant's scope under the key its self-grant carries. A mailed invite
 * answers its code, lists the address and is revoked. The joined account is erased at the end (it is a throwaway too).
 */
class InvitesLiveTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val runs = mutableMapOf<String, LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.values.forEach { it.removeAll() }

    @Test
    fun anOwnerInvitesByLinkAndANewAccountJoinsThroughIt() {
        assumeTrue("VEYQ_LIVE and VEYQ_LIVE_THROWAWAY_ENV", System.getenv("VEYQ_LIVE") != null && System.getenv("VEYQ_LIVE_THROWAWAY_ENV") != null)
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val p = BouncyCastlePrimitives
        val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> LiveHttp.client(block) }
        runTest(timeout = 6.minutes) {
            val ownerAccount = VeyqAccount.signIn(p, origin, t.email(), t.env.getValue("VEYQ_PASSWORD"), t.env.getValue("VEYQ_SECRET_KEY"), runs.getOrPut("VEYQ") { LiveRun("VEYQ", t.file) }.device(), client)
            val owner = ownerAccount.open(ownerAccount.tenants.first { it.domain == domain })

            val first = owner.invites.inviteByLink("  Oma  ")
            assertTrue(first.done, "the link invite: ${first.notice.msg}")
            val firstLink = assertNotNull(first.address)
            assertTrue(firstLink.contains("/tenant/accept#"))
            val pending = owner.members.read().invites.single { it.link }
            assertEquals("Oma", pending.label, "the label opens for the owner, trimmed")
            assertEquals(Notice(Msg.MEMBERS_LABEL_SAVED), owner.invites.setLabel(pending.inviteId, "Opa").notice)
            val renamed = owner.members.read().invites.single { it.inviteId == pending.inviteId }
            assertEquals("Opa", renamed.label)

            val again = owner.invites.reissue(renamed)
            assertTrue(again.done, "the re-issue: ${again.notice.msg}")
            val link = assertNotNull(again.address)
            assertNotEquals(firstLink, link)
            assertEquals("Opa", owner.members.read().invites.single { it.link }.label, "the label is sealed anew for the new invite")

            // the joiner is erased at the end, so its devices go with it: its run is not one [takeOffTheDevices] signs in to
            val run = LiveRun("JOINER", t.file)
            val stale = TenantJoin.of(p, run.device(), client, assertNotNull(ReceivedLink.inviteOf(firstLink)))
            stale.load()
            assertTrue(stale.current.phase in setOf(TenantJoin.Phase.INVALID, TenantJoin.Phase.EXPIRED, TenantJoin.Phase.REDEEMED), "the old link: ${stale.current}")
            stale.close()

            val stamp = System.currentTimeMillis() % 1_000_000_000
            val email = "tw-join-$stamp@veyq.localhost"
            val password = "join-live-$stamp-pass"
            // the optional picture at the join (the JVM has no WebP encoder: a test one hands bytes a WebP's header starts)
            val picture = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 app-join-picture".encodeToByteArray()
            val join = TenantJoin.of(p, run.device(), client, assertNotNull(ReceivedLink.inviteOf(link))) { _, _, _, _ -> picture.copyOf() }
            join.load()
            assertTrue(join.choosePicture(ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4)), "the picture chosen")
            assertTrue(join.current.picture && join.current.asksPicture)
            assertEquals(TenantJoin.Phase.REGISTER, join.current.phase, "the link carries its code: straight to the account step, ${join.current}")
            assertTrue(join.current.linkInvite && join.current.role == TenantJoin.Role.MEMBER)
            join.register("joiner$stamp", "Jo", "Iner", email, password)
            assertEquals(TenantJoin.Phase.KIT, join.current.phase, "the account built: ${join.current.error}")
            val kit = assertNotNull(join.kit)
            var bytes = 0
            assertTrue(kit.saveInto("en", 20_000) { bytes = it.size })
            assertTrue(bytes > 1000, "a kit was written")
            kit.confirm(true)
            join.finishRegistration()
            assertEquals(TenantJoin.Phase.DONE, join.current.phase, "joined: ${join.current.error}")
            assertTrue(join.current.canOpen)
            // *Open the space*: the app's own sign-in with what the join holds; the tenant opens under the self-grant's key
            val model = SessionModel.accounts(p, run.device(), client, me.raddatz.veyq.mobile.navigation.Hidden({}, emptySet()))
            model.start()
            val opened = assertNotNull(join.openSpace(model), "the joined account opens: ${join.current.error} ${model.current}")
            join.close()
            assertEquals(domain, opened.tenant.domain)
            assertNotNull(opened.session.scopeKey(opened.tenant.tenantId), "the tenant scope opened under the key the invite carried")

            val roster = owner.members.read()
            val joined = roster.rows.single { it.username == "joiner$stamp" }
            assertEquals("Jo Iner", joined.realName, "the name sealed at the join opens for the owner")
            owner.avatars.sync(assertNotNull(owner.readPeople()).values)
            kotlin.test.assertContentEquals(picture, owner.avatars.pictureOf(joined.userId), "the picture sealed at the join opens for the owner")
            assertTrue(roster.invites.none { it.link }, "the link invite is spent")

            val mailed = owner.invites.inviteByMail("tw-mailed-$stamp@veyq.localhost", null)
            assertTrue(mailed.done, "the mailed invite: ${mailed.notice.msg}")
            assertEquals(11, assertNotNull(mailed.code).length, "a code of two groups of five")
            val row = owner.members.read().invites.single { it.email == "tw-mailed-$stamp@veyq.localhost" }
            assertEquals(Notice(Msg.MEMBERS_INVITE_REVOKED), owner.invites.revoke(row.inviteId).notice)
            assertTrue(owner.members.read().invites.none { it.inviteId == row.inviteId })

            // the joined account is a throwaway too: erased (its only tenant is the throwaway's, owned by another)
            val erasure = opened.account.erasure()
            erasure.load()
            erasure.erase(password)
            assertTrue(erasure.current.erased, "the joined account was not erased: ${erasure.current.refusal}")
            println("[live] joined as $email through a link invite and erased it")
        }
    }
}
