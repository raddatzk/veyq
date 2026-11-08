package me.raddatz.veyq.mobile.expenses

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The seeded principal's Pots on a running dev instance (`VEYQ_LIVE`): each opens, its settings read, its revisions open
 * and its balances sum to zero. With `VEYQ_LIVE_POT_WRITE=<pot name>` it also adds one expense to that Pot — a record the
 * web then shows, the proof that both clients read and write one format — and checks it counts.
 */
class PotLiveTest {
    @Test
    fun theSeededPotsReadAndOneExpenseIsAdded() = runTest(timeout = 2.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.defaultTenant!!)
        val pots = tenant.objects().filter { it.kind == "EXPENSES_POT" }
        assertTrue(pots.isNotEmpty(), "no Pot in the seeded tenant")
        for (ref in pots) {
            val pot = PotSession.open(tenant, ref.scopeId, ref.nodeId)!!
            val ledger = pot.ledger
            println("[${ref.name}] meta=${pot.meta} participants=${ledger?.participants?.map { it.name }} entries=${ledger?.entries?.map { "${it.status}:${(it.content as? Expense)?.title}" }}")
            println("[${ref.name}] balances=${ledger?.balances?.mapValues { it.value.toString() }} settle=${ledger?.settleUp}")
            if (ledger != null) {
                assertEquals(Big.ZERO, ledger.balances.values.fold(Big.ZERO) { a, b -> a + b })
                assertTrue(ledger.entries.none { it.status == Ledger.Status.UNREADABLE }, "a revision of ${ref.name} did not open")
            }
            if (System.getenv("VEYQ_LIVE_POT_WRITE") == ref.name && ledger != null) {
                val people = ledger.participants.filter { it.active && !it.erased }
                assertTrue(people.isNotEmpty(), "${ref.name} has no participant to pay")
                val before = ledger.entries.size
                val outcome = pot.addExpense("App check", 1234, people.first().id, people.map { it.id }, "2026-11-08", "other")
                assertEquals(PotSession.AddOutcome.ADDED, outcome)
                val after = pot.ledger!!
                assertEquals(before + 1, after.entries.size)
                assertTrue(after.entries.any { (it.content as? Expense)?.title == "App check" && it.status == Ledger.Status.LIVE })
                println("[${ref.name}] after the add: balances=${after.balances.mapValues { it.value.toString() }}")
            }
        }
        account.signOut()
    }
}
