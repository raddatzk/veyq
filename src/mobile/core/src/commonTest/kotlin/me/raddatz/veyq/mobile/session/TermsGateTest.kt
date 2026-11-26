package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The member's terms gate in the way in (uc.account.accept-terms, ADR 0035 § The apps' gate; the web's `router.ts` gate,
 * `TermsAcceptView` and `activeSpace.termsStale`), over a door the test plays: the gate decided before the shell shows, in
 * place of it, the offer behind it; *I agree* recorded and the shell shown; a moved pair read anew and shown again; a failed
 * accept kept; a read that fails or terms the member accepted show none (fail-open); each tenant its own gate.
 */
class TermsGateTest {
    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key
    }

    private class Door : SessionModel.Door<Acc, String> {
        val accounts = HashMap<String, Acc>()
        /** Each tenant's terms as read, by tenant id; absent: the read fails. */
        val terms = HashMap<String, MemberTerms.Read>()
        /** What an accept answers, and each accept asked (tenant, revision, operator revision). */
        var answer = MemberTerms.Answer.ACCEPTED
        val accepts = mutableListOf<Triple<String, Int, Int>>()
        /** Holds an accept until completed. */
        var held: CompletableDeferred<Unit>? = null
        /** Holds a terms read until completed. */
        var readHeld: CompletableDeferred<Unit>? = null
        val records = mutableListOf<TrustedDevice>()
        val signedOut = mutableListOf<String>()

        override val primitives = testPrimitives
        override fun trusted() = records.toList()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String) = accounts.getValue(server)
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray) = accounts.getValue(server)
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String) = accounts.getValue(record.server)
        override suspend fun resume(record: TrustedDevice) = accounts.getValue(record.server)
        override suspend fun trust(account: Acc, password: String) {}
        override suspend fun stay(account: Acc, level: StaySignedIn) = TrustChange.DONE
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.DONE
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedBy(account: Acc): TrustedDevice? = null
        override suspend fun open(account: Acc, tenant: TenantRef) = tenant.tenantId
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) { signedOut += account.key }
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = setOf(StaySignedIn.LOGIN)
        override fun declined(account: Acc) = false
        override fun setDeclined(account: Acc, declined: Boolean) {}
        override suspend fun terms(session: String): MemberTerms.Read? {
            readHeld?.await()
            return terms[session] ?: throw Unreachable("the terms did not read")
        }
        override suspend fun acceptTerms(session: String, revision: Int, operatorRevision: Int): MemberTerms.Answer {
            accepts += Triple(session, revision, operatorRevision)
            held?.await()
            if (answer == MemberTerms.Answer.ACCEPTED) terms[session]?.let { terms[session] = read(it.revision, it.operatorRevision, accepted = true) }
            return answer
        }
    }

    private val a = Acc("a.example#u1", listOf(TenantRef("ta", "A", "a.example"), TenantRef("ta2", "A2", "a2.example")))
    private val b = Acc("b.example#u2", listOf(TenantRef("tb", "B", "b.example")))

    private fun door() = Door().apply {
        accounts["https://a.example"] = a
        accounts["https://b.example"] = b
    }

    private fun model(door: Door, language: String = "en") =
        SessionModel(door, Hidden({}, emptySet()), RecentUses({}, emptyList()), language = { language })

    private fun gateOf(m: SessionModel<Acc, String>): SessionModel.TermsGate? = (m.current as Gate.Signed).terms

    @Test
    fun terms_not_accepted_stand_in_place_of_the_shell_and_the_offer_waits_behind_them() = runTest {
        val d = door()
        d.terms["ta"] = read(3, 1, accepted = false)
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        val g = assertNotNull(gateOf(m), "the gate stands over the tenant just opened")
        assertEquals(shownEn(3, 1), g.shown)
        assertFalse(g.working)
        assertNull(g.error)
        assertNull((m.current as Gate.Signed).offer, "the offer to stay signed in waits behind the terms")

        assertTrue(m.acceptTerms())
        assertEquals(listOf(Triple("ta", 3, 1)), d.accepts, "the pair it showed is the pair accepted")
        val after = m.current as Gate.Signed
        assertNull(after.terms)
        assertEquals(SessionModel.Offer(a.key, listOf(StaySignedIn.LOGIN)), after.offer, "the offer shows once the terms are agreed to")
        assertFalse(m.acceptTerms(), "no gate stands: nothing is accepted")
        assertEquals(1, d.accepts.size)
    }

    @Test
    fun a_tenant_without_terms_or_with_its_pair_accepted_or_whose_terms_do_not_read_shows_no_gate() = runTest {
        for (setup in listOf<(Door) -> Unit>(
            { it.terms["ta"] = read(0, 1, accepted = false) }, // no terms set: revision 0 never gates
            { it.terms["ta"] = read(2, 1, accepted = true) }, // the current pair accepted
            { }, // the read fails: fail-open, as the web's
        )) {
            val d = door().also(setup)
            val m = model(d)
            m.start()
            assertNotNull(m.signIn("a.example", "e", "pw", "sk"))
            assertNull(gateOf(m))
            assertFalse(m.acceptTerms())
            assertEquals(emptyList(), d.accepts)
        }
    }

    @Test
    fun the_terms_are_shown_in_the_viewers_language_the_other_one_where_it_has_none() = runTest {
        val d = door()
        d.terms["ta"] = MemberTerms.Read(
            revision = 2, operatorRevision = 1, accepted = false,
            terms = mapOf("de" to "## Regeln", "en" to "## Rules"),
            section = mapOf("en" to "## On this server"),
            record = mapOf("de" to "### Deine Adresse", "en" to "### Your address"),
            origin = "https://a.example/",
        )
        val m = model(d, language = "de")
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        assertEquals(
            MemberTerms.TermsShown(2, 1, true, "## Regeln", "## On this server", "### Deine Adresse", "https://a.example/privacy#guest-write-record"),
            gateOf(m)!!.shown,
        )
    }

    @Test
    fun a_pair_that_moved_while_shown_is_read_anew_and_shown_with_why() = runTest {
        val d = door()
        d.terms["ta"] = read(3, 1, accepted = false)
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        // the space owner saved new terms meanwhile
        d.terms["ta"] = read(4, 1, accepted = false)
        d.answer = MemberTerms.Answer.CHANGED
        assertFalse(m.acceptTerms())
        assertEquals(SessionModel.TermsGate(shownEn(4, 1), error = Msg.TERMS_REVISION_CHANGED), gateOf(m))
        // the newer pair agreed to
        d.answer = MemberTerms.Answer.ACCEPTED
        assertTrue(m.acceptTerms())
        assertEquals(Triple("ta", 4, 1), d.accepts.last())
        assertNull(gateOf(m))

        // a pair that moved and was accepted elsewhere meanwhile (another device): the newer read lets the member in
        val d2 = door()
        d2.terms["ta"] = read(3, 1, accepted = false)
        val m2 = model(d2)
        m2.start()
        m2.signIn("a.example", "e", "pw", "sk")
        d2.terms["ta"] = read(4, 1, accepted = true)
        d2.answer = MemberTerms.Answer.CHANGED
        assertTrue(m2.acceptTerms())
        assertNull(gateOf(m2))
    }

    @Test
    fun a_failed_accept_keeps_the_gate_and_says_so_and_one_under_way_is_not_asked_twice() = runTest {
        val d = door()
        d.terms["ta"] = read(3, 2, accepted = false)
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        d.answer = MemberTerms.Answer.FAILED
        assertFalse(m.acceptTerms())
        assertEquals(SessionModel.TermsGate(shownEn(3, 2), error = Msg.TERMS_ACCEPT_FAILED), gateOf(m))

        // a pair that moved whose re-read fails: the gate kept as it was, saying the accept failed
        d.answer = MemberTerms.Answer.CHANGED
        d.terms.remove("ta")
        assertFalse(m.acceptTerms())
        assertEquals(SessionModel.TermsGate(shownEn(3, 2), error = Msg.TERMS_ACCEPT_FAILED), gateOf(m))

        d.terms["ta"] = read(3, 2, accepted = false)
        d.answer = MemberTerms.Answer.ACCEPTED
        d.held = CompletableDeferred()
        val first = async { m.acceptTerms() }
        runCurrent()
        assertEquals(SessionModel.TermsGate(shownEn(3, 2), working = true), gateOf(m), "under way, its error cleared")
        assertFalse(m.acceptTerms(), "a second tap while one is under way asks nothing")
        d.held!!.complete(Unit)
        assertTrue(first.await())
        assertEquals(3, d.accepts.size)
        assertNull(gateOf(m))
    }

    @Test
    fun the_gate_is_each_tenants_own_and_a_sign_out_leaves_it_with_its_account() = runTest {
        val d = door()
        d.terms["ta"] = read(1, 1, accepted = false)
        d.terms["ta2"] = read(5, 1, accepted = true)
        d.terms["tb"] = read(2, 1, accepted = false)
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        assertNotNull(gateOf(m))
        // another tenant of the same server whose terms the member accepted: no gate there
        m.switchTo(a, a.tenants[1])
        assertEquals("ta2", (m.current as Gate.Signed).tenantId)
        assertNull(gateOf(m))
        // and back: the first tenant's terms are read anew at its open, still not accepted
        m.switchTo(a, a.tenants[0])
        assertNotNull(gateOf(m))
        // a second server's sign-in: its own gate; accepting it records that tenant's pair alone
        m.addServer()
        m.signIn("b.example", "e", "pw", "sk")
        assertEquals("tb", (m.current as Gate.Signed).tenantId)
        assertEquals(shownEn(2, 1), gateOf(m)!!.shown)
        // the gate's sign-out: the account leaves, the one held before opens with its own gate
        val out = m.signOut(b)
        assertTrue(out.wasCurrent)
        assertEquals(listOf(b.key), d.signedOut)
        assertEquals("ta", (m.current as Gate.Signed).tenantId)
        assertEquals(shownEn(1, 1), gateOf(m)!!.shown)
        assertTrue(m.acceptTerms())
        assertEquals(listOf(Triple("ta", 1, 1)), d.accepts)
    }

    @Test
    fun an_accept_that_ends_after_its_tenant_closed_shows_nothing_over_the_one_open_now() = runTest {
        val d = door()
        d.terms["ta"] = read(1, 1, accepted = false)
        d.terms["ta2"] = read(1, 1, accepted = true)
        val m = model(d)
        m.start()
        m.signIn("a.example", "e", "pw", "sk")
        d.held = CompletableDeferred()
        val accept = async { m.acceptTerms() }
        runCurrent()
        m.switchTo(a, a.tenants[1])
        d.held!!.complete(Unit)
        assertFalse(accept.await())
        assertEquals(Gate.Signed(a.key, "ta2", offer = SessionModel.Offer(a.key, listOf(StaySignedIn.LOGIN))), m.current)
    }

    @Test
    fun the_gate_is_decided_before_the_shell_shows() = runTest {
        val d = door()
        d.terms["ta"] = read(1, 1, accepted = false)
        d.readHeld = CompletableDeferred()
        val m = model(d)
        m.start()
        val signing = async { m.signIn("a.example", "e", "pw", "sk") }
        runCurrent()
        assertFalse(m.current is Gate.Signed, "no shell is shown while the terms are read")
        d.readHeld!!.complete(Unit)
        assertNotNull(signing.await())
        assertNotNull(gateOf(m))
    }

    private companion object {
        fun read(revision: Int, operatorRevision: Int, accepted: Boolean) = MemberTerms.Read(
            revision, operatorRevision, accepted,
            terms = mapOf("en" to "## Terms $revision"), section = mapOf("en" to "## Operator $operatorRevision"),
            record = mapOf("en" to "### Record"), origin = "https://a.example",
        )

        fun shownEn(revision: Int, operatorRevision: Int) = MemberTerms.TermsShown(
            revision, operatorRevision, revision > 0, "## Terms $revision", "## Operator $operatorRevision", "### Record",
            "https://a.example/privacy#guest-write-record",
        )
    }
}
