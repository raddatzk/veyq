package me.raddatz.veyq.mobile.expenses

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.async
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
    fun theSeededPotsReadAndOneExpenseIsAdded() = runTest(timeout = 10.minutes) {
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
        System.getenv("VEYQ_LIVE_RECEIPT")?.let { potName ->
            // An entry saved with a receipt (uploaded to the store before the revision), read back to the byte, then dropped.
            val ref = pots.first { it.name == potName }
            val pot = PotSession.open(tenant, ref.scopeId, ref.nodeId)!!
            val people = pot.ledger!!.participants.filter { it.active && !it.erased }
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(300_000) { (it % 7).toByte() }
            val receiptId = pot.newId()
            val form = PotView(pot).blankForm(java.time.LocalDate.now().toString())!!.copy(title = "Receipt check", payers = listOf(EntryForm.Payer(people.first().id, "1")))
            val entryId = pot.newId()
            assertEquals(
                PotSession.WriteOutcome.WRITTEN,
                pot.saveEntry(entryId, form, null, listOf(PotSession.PendingReceipt(receiptId, png, "image/png", "scan.png", 1_700_000_000_000))),
            )
            val held = pot.receipts.single { it.receiptId == receiptId }
            assertEquals(entryId, held.entryId)
            assertEquals("scan.png" to "image/png", held.name to held.type)
            assertEquals(png.size.toLong(), held.size)
            val back = pot.openReceipt(receiptId)!!
            assertTrue(back.contentEquals(png), "the receipt's bytes came back changed")
            assertEquals(Receipts.Kind.IMAGE, Receipts.kindOf(back))
            assertEquals(listOf(receiptId), (pot.ledger!!.entries.single { it.id == entryId }.content as Expense).receipts)
            // Saved without it: the revision drops it, and it is never held again.
            val edited = PotView(pot).editing(entryId)!!
            assertEquals(PotSession.WriteOutcome.WRITTEN, pot.saveEntry(entryId, edited.form.copy(receipts = emptyList()), edited.basedOn))
            assertTrue(pot.receipts.single { it.receiptId == receiptId }.dropped)
            println("[$potName] a receipt of ${png.size} bytes (${held.listed.chunkCount} frames) uploaded, read back and dropped")
        }
        System.getenv("VEYQ_LIVE_POLL")?.let { potName ->
            // Two devices on one Pot: what one writes, the other's live poll picks up — a new entry, then its delete.
            val ref = pots.first { it.name == potName }
            val watcher = PotSession.open(tenant, ref.scopeId, ref.nodeId)!!
            val writer = PotSession.open(tenant, ref.scopeId, ref.nodeId)!!
            val count = watcher.ledger!!.entries.size
            assertEquals(false, watcher.poll(), "a poll of an unchanged Pot moved it")
            assertEquals(count, watcher.ledger!!.entries.size)
            val people = writer.ledger!!.participants.filter { it.active && !it.erased }
            val form = PotView(writer).blankForm(java.time.LocalDate.now().toString())!!.copy(title = "Poll check", payers = listOf(EntryForm.Payer(people.first().id, "2")))
            val entryId = writer.newId()
            assertEquals(PotSession.WriteOutcome.WRITTEN, writer.saveEntry(entryId, form, null))
            assertEquals(true, watcher.poll())
            val seen = watcher.ledger!!.entries.single { it.id == entryId }
            assertEquals(Ledger.Status.LIVE, seen.status)
            assertEquals(PotSession.WriteOutcome.WRITTEN, writer.deleteEntry(entryId, PotView(writer).editing(entryId)!!.basedOn))
            assertEquals(true, watcher.poll())
            assertEquals(Ledger.Status.DELETED, watcher.ledger!!.entries.single { it.id == entryId }.status)
            // Two reads at once are one after the other: both end where the server is.
            kotlinx.coroutines.coroutineScope {
                val a = async { watcher.refresh() }
                val b = async { watcher.refresh() }
                a.await(); b.await()
            }
            assertEquals(writer.ledger!!.entries.map { it.id to it.status }.toSet(), watcher.ledger!!.entries.map { it.id to it.status }.toSet())
            println("[$potName] the watcher's poll saw the entry written and deleted on the other session")
        }
        System.getenv("VEYQ_LIVE_SWITCH")?.let { potName ->
            // Expenses switched off for the tenant (uc.platform.switch-application-off), then back on: off, a member reads
            // the Pot read only — every entry still opens — and the server refuses a write, as the app no longer offers one.
            val ref = pots.first { it.name == potName }
            val switches = me.raddatz.veyq.mobile.api.apis.TenantsApi(tenant.base, account.http)
            suspend fun switch(off: Boolean) = assertTrue(
                account.respond { switches.setTenantCapabilitySwitch(tenant.tenant.tenantId, "expenses", me.raddatz.veyq.mobile.api.models.TenantCapabilitySwitchUpdate(off)) }.success,
                "the switch did not move",
            )
            switch(true)
            try {
                val off = account.open(tenant.tenant)
                assertTrue("expenses" in off.applicationsOff())
                val pot = PotSession.open(off, ref.scopeId, ref.nodeId)!!
                val view = PotView(pot)
                assertTrue(!view.canWrite && !pot.canChangeSettings, "a switched-off Pot offered a write")
                assertTrue(view.entries.all { view.editing(it.id, today = "2026-11-08") != null }, "an entry did not open for the reader")
                val people = pot.ledger!!.participants.filter { it.active && !it.erased }
                val form = view.blankForm("2026-11-08")!!.copy(title = "Switched off", payers = listOf(EntryForm.Payer(people.first().id, "1")))
                assertEquals(PotSession.WriteOutcome.REFUSED, pot.saveEntry(null, form, null))
                // A UI pass looks at the apps meanwhile (each re-opened, since a tenant's set is read when it opens).
                System.getenv("VEYQ_LIVE_SWITCH_HOLD_S")?.toLongOrNull()?.let { s ->
                    println("[$potName] Expenses stays off for $s s")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thread.sleep(s * 1000) }
                }
            } finally {
                switch(false)
            }
            assertTrue("expenses" !in account.open(tenant.tenant).applicationsOff())
            println("[$potName] switched off: read only, every entry opens, a write refused; switched back on")
        }
        System.getenv("VEYQ_LIVE_NODE_ACTIONS")?.let { potName ->
            // Core's node actions on a Pot: pinned and unpinned (read back by a fresh session each time), and a fresh Pot
            // moved to the trash, restored under its name and trashed again (it stays there).
            val ref = pots.first { it.name == potName }
            // No pin before the set is read: then it is refused.
            if (tenant.isFavorite(ref.scopeId, ref.nodeId) == null) assertTrue(!tenant.setFavorite(ref.scopeId, ref.nodeId, true))
            tenant.favorites()
            val was = tenant.isFavorite(ref.scopeId, ref.nodeId)!!
            assertTrue(tenant.setFavorite(ref.scopeId, ref.nodeId, !was))
            val fresh = account.open(tenant.tenant).also { it.favorites() }
            assertEquals(!was, fresh.isFavorite(ref.scopeId, ref.nodeId))
            assertTrue(fresh.setFavorite(ref.scopeId, ref.nodeId, was))
            assertEquals(was, account.open(tenant.tenant).also { it.favorites() }.isFavorite(ref.scopeId, ref.nodeId))
            val name = "App trash ${System.currentTimeMillis() % 100_000}"
            val created = PotCreate.create(tenant, ref.scopeId, null, name, Ledger.BaseCurrency("EUR", 2), emptyList())
            val nodeId = created.nodeId!!
            tenant.forgetTree()
            val before = tenant.trees()[ref.scopeId]!!.single { it.id == nodeId }
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, ref.scopeId, nodeId))
            assertTrue(tenant.objects().none { it.nodeId == nodeId }, "a trashed Pot is still listed")
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Restored.RESTORED, me.raddatz.veyq.mobile.session.NodeTrash.restore(tenant, before, PotCreate.tokenInput(name)))
            assertEquals(name, tenant.objects().single { it.nodeId == nodeId }.name)
            // Its name is taken again: its token was derived as a create derives it.
            assertEquals(PotCreate.Outcome.NAME_TAKEN, PotCreate.create(tenant, ref.scopeId, null, name, Ledger.BaseCurrency("EUR", 2), emptyList()).outcome)
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, ref.scopeId, nodeId))
            // Deleting what is in the trash already is no error (the contract's 204), nor a second entry there.
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, ref.scopeId, nodeId))
            println("[$potName] pinned and unpinned; \"$name\" trashed, restored, its name taken, trashed again")
        }
        if (System.getenv("VEYQ_LIVE_AVATARS") == "1") {
            // The tenant's profile pictures: every member with a revision fetched and opened, the rest the letter; a second
            // sync asks nothing new (each revision settled).
            val withPicture = tenant.people.values.filter { it.avatarRevision > 0 }
            tenant.avatars.sync(tenant.people.values)
            val opened = tenant.avatars.pictures()
            println("[avatars] ${tenant.people.size} members, ${withPicture.size} with a revision, ${opened.size} opened: ${opened.mapValues { it.value.size }}")
            assertTrue(opened.keys.all { id -> withPicture.any { it.userId == id } }, "a picture for a member without a revision")
            assertTrue(!tenant.avatars.sync(tenant.people.values), "a second sync moved")
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
