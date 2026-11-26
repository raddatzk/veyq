package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.models.LocalizedMarkdown
import me.raddatz.veyq.mobile.api.models.TenantTermsUpdate
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
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
 * The member's terms gate on a running dev instance as a throwaway (`VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`; [Throwaway]):
 * the owner saves new terms (a new revision), the core's [MemberTerms] reads them stale, a sign-in through the real
 * [SessionModel] stands at the gate in German, an accept of a pair not current is told apart as a moved pair, *I agree* records
 * the current one and the gate passes; a second session reads it accepted. With the throwaway's member, the member is held
 * too. With `VEYQ_LIVE_TERMS_LEAVE=1` it ends by saving one more revision, so an app's pass meets the gate (the terms stay
 * in the throwaway, erased with it).
 */
class TermsLiveTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val runs = mutableMapOf<String, LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.values.forEach { it.removeAll() }

    @Test
    fun anOwnersNewTermsHoldEveryMemberAtTheGateUntilTheyAgree() {
        assumeTrue("VEYQ_LIVE and VEYQ_LIVE_THROWAWAY_ENV", System.getenv("VEYQ_LIVE") != null && System.getenv("VEYQ_LIVE_THROWAWAY_ENV") != null)
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        runTest(timeout = 4.minutes) {
            fun door(who: String) = AccountDoor(BouncyCastlePrimitives, runs.getOrPut(who) { LiveRun(who, t.file) }.device()) { block -> LiveHttp.client(block) }
            suspend fun session(who: String): TenantSession {
                val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(who), t.env.getValue("${who}_PASSWORD"), t.env.getValue("${who}_SECRET_KEY"), runs.getOrPut(who) { LiveRun(who, t.file) }.device()) { block -> LiveHttp.client(block) }
                return a.open(a.tenants.first { it.domain == domain })
            }
            suspend fun save(owner: TenantSession, n: Long): Int =
                owner.account.call {
                    TenantsApi(owner.origin, owner.account.http).updateTenantTerms(
                        owner.tenant.tenantId,
                        TenantTermsUpdate(LocalizedMarkdown(de = "## App-Bedingungen $n\n\nSei freundlich.", en = "## App terms $n\n\nBe kind.")),
                    )
                }.termsRevision

            val owner = session("VEYQ")
            val n = System.currentTimeMillis() % 100_000
            val revision = save(owner, n)
            val read = assertNotNull(MemberTerms.read(owner))
            assertEquals(revision, read.revision)
            assertTrue(read.stale, "the owner too agrees to their own new terms")
            assertEquals("## App-Bedingungen $n\n\nSei freundlich.", read.shown("de").terms)
            assertTrue(read.shown("en").operatorSection.isNotBlank(), "the operator section follows the space's text")

            // the real way in, over the real door: the gate stands over the tenant just opened, in German
            val model = SessionModel(door("VEYQ"), Hidden({}, emptySet()), RecentUses({}, emptyList()), language = { "de" })
            model.start()
            assertNotNull(model.signIn(origin, t.email(), t.env.getValue("VEYQ_PASSWORD"), t.env.getValue("VEYQ_SECRET_KEY")))
            val gated = model.current as SessionModel.Gate.Signed
            if (gated.tenantId == owner.tenant.tenantId) {
                val gate = assertNotNull(gated.terms)
                assertEquals(revision, gate.shown.revision)
                assertEquals("## App-Bedingungen $n\n\nSei freundlich.", gate.shown.terms)
                assertNull(gated.offer)
            }
            // a pair that is not the current one is a moved one, the server's 409 told apart (an operator revision the server
            // does not serve: a space revision below 1 is refused as malformed, 400, before the pair is compared)
            assertEquals(MemberTerms.Answer.CHANGED, MemberTerms.accept(owner, revision, read.operatorRevision + 1))
            if (gated.tenantId == owner.tenant.tenantId) {
                assertTrue(model.acceptTerms())
                assertNull((model.current as SessionModel.Gate.Signed).terms)
            } else {
                assertEquals(MemberTerms.Answer.ACCEPTED, MemberTerms.accept(owner, revision, read.operatorRevision))
            }
            assertFalse(assertNotNull(MemberTerms.read(session("VEYQ"))).stale, "a fresh session reads the pair accepted")
            assertEquals(revision, MemberTerms.page(owner, "en")?.revision)

            if (t.hasMember) {
                val member = session("VEYQ_USER2")
                assertTrue(assertNotNull(MemberTerms.read(member)).stale, "the member is held too")
                assertEquals(MemberTerms.Answer.ACCEPTED, MemberTerms.accept(member, revision, read.operatorRevision))
                assertFalse(assertNotNull(MemberTerms.read(member)).stale)
            }
            if (System.getenv("VEYQ_LIVE_TERMS_LEAVE") == "1") println("veyq-live: terms left at revision ${save(owner, n + 1)}")
        }
    }
}
