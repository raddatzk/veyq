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
            assertTrue(pot.check.complete, "${ref.name}'s ledger check: missing ${pot.check.missing} altered ${pot.check.altered} unreadable ${pot.check.unreadable}")
            println("[${ref.name}] members=${pot.members?.map { it.displayName }}")
            assertTrue(pot.members?.isNotEmpty() == true, "${ref.name}: the space's members did not read")
            if (pot.currencyFixed) assertEquals(PotSession.WriteOutcome.INVALID, pot.setBaseCurrency("CHF", 2), "${ref.name}: a fixed currency moved")
            if (System.getenv("VEYQ_LIVE_POT_SETTINGS") == ref.name && !pot.currencyFixed) {
                // The currency switched and back, an own category added and renamed (it stays: categories are not removed).
                val before = pot.meta?.base
                assertEquals(PotSession.WriteOutcome.WRITTEN, pot.setBaseCurrency("CHF", 2))
                assertEquals("CHF", pot.meta?.base?.currency)
                assertEquals(PotSession.WriteOutcome.WRITTEN, pot.setBaseCurrency(before?.currency ?: "EUR", before?.minorUnits ?: 2))
                val count = pot.meta!!.categories.size
                assertEquals(PotSession.WriteOutcome.WRITTEN, pot.addCategory("App category"))
                val id = pot.meta!!.categories.last().first
                assertEquals(count + 1, pot.meta!!.categories.size)
                assertEquals(PotSession.WriteOutcome.WRITTEN, pot.renameCategory(id, "App category renamed"))
                assertEquals("App category renamed", pot.meta!!.ownName(id))
                println("[${ref.name}] the settings written: ${pot.meta}")
            }
            if (System.getenv("VEYQ_LIVE_POT_PEOPLE") == ref.name && ledger != null) {
                // A person's life: added, renamed, deactivated (settled: no entry names them), removed by its erasure.
                assertEquals(PotSession.WriteOutcome.WRITTEN, pot.addPerson("App person"))
                val id = pot.ledger!!.participants.single { it.name == "App person" }.id
                assertEquals(PotSession.WriteOutcome.WRITTEN, pot.rename(id, "App person renamed"))
                assertEquals(PotSession.WriteOutcome.WRITTEN, pot.setActive(id, false))
                assertEquals(false, pot.ledger!!.participants.single { it.id == id }.active)
                assertEquals(PotSession.WriteOutcome.WRITTEN, pot.erase(id))
                assertTrue(pot.ledger!!.participants.single { it.id == id }.erased)
                assertTrue(pot.check.complete, "the check after the erasure: ${pot.check.missing}")
                println("[${ref.name}] a person added, renamed, deactivated and removed")
            }
            if (System.getenv("VEYQ_LIVE_POT_WRITE") == ref.name && ledger != null) {
                val people = ledger.participants.filter { it.active && !it.erased }
                assertTrue(people.isNotEmpty(), "${ref.name} has no participant to pay")
                val before = ledger.entries.size
                // Through the editor's form, as the apps save: 12.34 paid by the first person, split equally.
                val form = PotView(pot).blankForm("2026-11-08")!!.copy(title = "App check", payers = listOf(EntryForm.Payer(people.first().id, "12.34")))
                val outcome = pot.saveEntry(null, form, null)
                assertEquals(PotSession.WriteOutcome.WRITTEN, outcome)
                val after = pot.ledger!!
                assertEquals(before + 1, after.entries.size)
                assertTrue(after.entries.any { (it.content as? Expense)?.title == "App check" && it.status == Ledger.Status.LIVE })
                println("[${ref.name}] after the add: balances=${after.balances.mapValues { it.value.toString() }}")
            }
        }
        if (System.getenv("VEYQ_LIVE_RATES") == "1") {
            // The tenant's exchange-rate service, asked once for today's table (a request to that third party).
            val pot = PotSession.open(tenant, pots.first().scopeId, pots.first().nodeId)!!
            println("[rates] offered=${pot.ratesOffered} url=${tenant.serviceUrl(ExchangeRates.CAPABILITY)}")
            if (pot.ratesOffered) {
                val other = if (pot.meta?.base?.currency == "USD") "EUR" else "USD"
                val filled = pot.rateFor(other, java.time.LocalDate.now().toString())
                println("[rates] 1 $other in ${pot.meta?.base?.currency} = $filled")
                assertTrue(filled != null && Money.RATE.matches(filled.rate), "no rate for $other")
            }
        }
        if (System.getenv("VEYQ_LIVE_POT_CREATE") == "1") {
            // A Pot created at the root of the first Pot's space, with a name and a member; the same name again is taken;
            // then it goes to the space's trash.
            val scopeId = pots.first().scopeId
            val name = "App pot ${System.currentTimeMillis()}"
            val member = PotSession.open(tenant, scopeId, pots.first().nodeId)!!.members!!.first()
            val people = listOf(PotCreate.NewPerson.name("Zoë"), PotCreate.NewPerson.member(member.userId, member.displayName))
            val made = PotCreate.create(tenant, scopeId, null, name, Ledger.BaseCurrency("EUR", 2), people)
            assertEquals(PotCreate.Outcome.CREATED, made.outcome)
            tenant.forgetTree()
            val pot = PotSession.open(tenant, scopeId, made.nodeId!!)!!
            assertEquals(name, pot.name)
            assertEquals(Ledger.BaseCurrency("EUR", 2), pot.meta?.base)
            assertEquals(listOf("Zoë", member.displayName), pot.ledger!!.participants.map { it.name })
            assertEquals(member.userId, pot.ledger!!.participants[1].member)
            assertTrue(pot.check.complete)
            // The token folds the name: another spelling of the same slug collides too.
            assertEquals(PotCreate.Outcome.NAME_TAKEN, PotCreate.create(tenant, scopeId, null, name.uppercase(), Ledger.BaseCurrency("EUR", 2), emptyList()).outcome)
            assertTrue(account.respond { me.raddatz.veyq.mobile.api.apis.ContentApi(tenant.base, account.http).deleteNode(scopeId, made.nodeId!!) }.success)
            // A Pot the WEB created at the root collides too: the app's token is the web's, byte for byte.
            val webPot = tenant.trees()[scopeId]!!.first { it.kind == PotSession.KIND && it.parentId == null && it.id != made.nodeId }
            assertEquals(PotCreate.Outcome.NAME_TAKEN, PotCreate.create(tenant, scopeId, null, webPot.name, Ledger.BaseCurrency("EUR", 2), emptyList()).outcome)
            println("[$name] created with ${pot.ledger!!.participants.size} people, its name refused again, and deleted")
        }
        account.signOut()
    }
}
