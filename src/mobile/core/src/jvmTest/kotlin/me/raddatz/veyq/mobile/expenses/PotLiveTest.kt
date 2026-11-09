package me.raddatz.veyq.mobile.expenses

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import java.io.File
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
        System.getenv("VEYQ_LIVE_TRASH")?.let { potName ->
            // The space's trash: a fresh Pot trashed is listed under its name; with a Pot of its name created meanwhile its
            // restore is refused by name and goes through under a new one (sealed in the same request); trashed again and
            // purged, a second purge finds it gone and the list no longer holds it. The Pot of the old name stays trashed.
            val trash = me.raddatz.veyq.mobile.session.NodeTrash
            val scopeId = pots.first { it.name == potName }.scopeId
            val n = System.currentTimeMillis() % 100_000
            val name = "App trash view $n"
            val nodeId = PotCreate.create(tenant, scopeId, null, name, Ledger.BaseCurrency("EUR", 2), emptyList()).nodeId!!
            tenant.forgetTree()
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, trash.delete(tenant, scopeId, nodeId))
            val listed = trash.list(tenant, scopeId)!!.single { it.id == nodeId }
            assertEquals(name, listed.name)
            assertEquals(PotSession.KIND, listed.kind)
            val holder = PotCreate.create(tenant, scopeId, null, name, Ledger.BaseCurrency("EUR", 2), emptyList()).nodeId!!
            tenant.forgetTree()
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Restored.NAME_TAKEN, trash.restoreFromTrash(tenant, listed, PotCreate.tokenInput(name)))
            val renamed = "$name restored"
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Restored.RESTORED, trash.restoreFromTrash(tenant, listed, PotCreate.tokenInput(renamed), renamed))
            assertEquals(renamed, account.open(tenant.tenant).objects().single { it.nodeId == nodeId }.name)
            assertTrue(trash.list(tenant, scopeId)!!.none { it.id == nodeId })
            // Its new name is taken now: the token was derived from the name sealed with it.
            assertEquals(PotCreate.Outcome.NAME_TAKEN, PotCreate.create(tenant, scopeId, null, renamed, Ledger.BaseCurrency("EUR", 2), emptyList()).outcome)
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, trash.delete(tenant, scopeId, nodeId))
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Purged.PURGED, trash.purge(tenant, scopeId, nodeId))
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Purged.GONE, trash.purge(tenant, scopeId, nodeId))
            assertTrue(trash.list(tenant, scopeId)!!.none { it.id == nodeId })
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, trash.delete(tenant, scopeId, holder))
            println("[$potName] \"$name\" listed in the trash, restored as \"$renamed\" past a Pot of its name, purged")
        }
        System.getenv("VEYQ_LIVE_MOVE")?.let { folderName ->
            // A fresh Pot moved into the root folder [folderName] and back: each time a fresh session opens it there under its
            // name and reads its content (its own key unchanged, only re-wrapped); a move to where it is is refused before
            // sending, as is one onto a Pot of its name; the Pot and the one of its name are trashed (they stay there).
            val move = me.raddatz.veyq.mobile.session.NodeMove
            val fold = { kind: String, name: String -> if (kind == PotSession.KIND) PotCreate.tokenInput(name) else if (kind == "FOLDER") me.raddatz.veyq.mobile.crypto.Naming.slugify(name) else null }
            val allows = me.raddatz.veyq.mobile.session.NodeMove.PlacementAllows { kind, parent -> kind == PotSession.KIND && (parent == null || parent == "FOLDER") }
            val folder = tenant.objects().let { tenant.nodes() }.first { it.kind == "FOLDER" && it.parentId == null && it.name == folderName }
            val scopeId = folder.scopeId
            val name = "App move ${System.currentTimeMillis() % 100_000}"
            val nodeId = PotCreate.create(tenant, scopeId, null, name, Ledger.BaseCurrency("EUR", 2), emptyList()).nodeId!!
            tenant.forgetTree()
            assertEquals(me.raddatz.veyq.mobile.session.NodeMove.Outcome.MOVED, move.move(tenant, scopeId, nodeId, folder.nodeId, fold, allows).outcome)
            val there = account.open(tenant.tenant)
            assertEquals(folder.nodeId, there.node(scopeId, nodeId)!!.parentId)
            assertEquals(name, there.objects().single { it.nodeId == nodeId }.name)
            assertTrue(PotSession.open(there, scopeId, nodeId) != null, "the moved Pot's content does not open")
            assertEquals(me.raddatz.veyq.mobile.session.NodeMove.Outcome.BLOCKED, move.move(tenant, scopeId, nodeId, folder.nodeId, fold, allows).outcome)
            assertEquals(me.raddatz.veyq.mobile.session.NodeMove.Outcome.MOVED, move.move(tenant, scopeId, nodeId, null, fold, allows).outcome)
            val back = account.open(tenant.tenant)
            assertEquals(null, back.node(scopeId, nodeId)!!.parentId)
            assertTrue(PotSession.open(back, scopeId, nodeId) != null, "the Pot moved back does not open")
            // A Pot of its name in the folder: the move is refused by name before a request is sent.
            val holder = PotCreate.create(tenant, scopeId, folder.nodeId, name, Ledger.BaseCurrency("EUR", 2), emptyList()).nodeId!!
            tenant.forgetTree()
            val taken = move.move(tenant, scopeId, nodeId, folder.nodeId, fold, allows)
            assertEquals(me.raddatz.veyq.mobile.session.NodeMove.Outcome.NAME_TAKEN, taken.outcome)
            assertEquals(holder, taken.taken?.id)
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, scopeId, holder))
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, scopeId, nodeId))
            println("[$folderName] \"$name\" moved in and out, opened there each time; a move to its place and onto its name refused")
        }
        System.getenv("VEYQ_LIVE_LINKS")?.let { potName ->
            // Two links on a Pot: a READ link with a label, listed newest first under that label with the very address the
            // mint handed out (its recovery copy opens), and a READ_WRITE link with a password and an end, listed as such.
            // The first link's address goes to VEYQ_LIVE_LINKS_OUT, for the web's guest page to open. Both links stay.
            val links = me.raddatz.veyq.mobile.session.ShareLinks
            val ref = pots.first { it.name == potName }
            val n = System.currentTimeMillis() % 100_000
            val read = links.mint(tenant, ref.scopeId, ref.nodeId, links.READ, "App link $n", "", null)
            assertEquals(me.raddatz.veyq.mobile.session.ShareLinks.Outcome.CREATED, read.outcome, read.refusal)
            val end = java.time.Instant.now().plus(java.time.Duration.ofDays(3)).toString()
            val guarded = links.mint(tenant, ref.scopeId, ref.nodeId, links.READ_WRITE, "", "app-link-password-$n", end)
            assertEquals(me.raddatz.veyq.mobile.session.ShareLinks.Outcome.CREATED, guarded.outcome, guarded.refusal)
            val listed = links.list(account.open(tenant.tenant), ref.scopeId, ref.nodeId)!!
            val first = listed.single { it.label == "App link $n" }
            assertEquals(read.url, first.url)
            assertEquals(links.READ, first.role)
            assertTrue(first.mine && first.enabled && !first.passwordRequired && first.expiresAt == null)
            val second = listed.single { it.url == guarded.url }
            assertEquals(links.READ_WRITE, second.role)
            assertTrue(second.passwordRequired && second.expiresAt != null && second.label.isEmpty())
            assertTrue(listed.indexOf(second) < listed.indexOf(first), "not newest first")
            System.getenv("VEYQ_LIVE_LINKS_OUT")?.let { File(it).writeText(read.url!!) }
            println("[$potName] READ link \"App link $n\" and a READ_WRITE link with a password and an end minted and listed")
        }
        System.getenv("VEYQ_LIVE_LINK_OFF")?.let { folderName ->
            // A fresh Pot in the root folder [folderName] with an entry and a receipt, and two links A and B. A is switched off:
            // the Pot gets a new key — a fresh session opens it, its entry and the receipt's bytes, a write there goes through
            // at once (nothing due any more) — and B is wrapped anew. A's and B's addresses go to VEYQ_LIVE_LINKS_OUT
            // ("A\nB"), for the web's guest page: A must open nothing, B the Pot. The Pot and its links stay.
            val links = me.raddatz.veyq.mobile.session.ShareLinks
            val folder = tenant.nodes().first { it.kind == "FOLDER" && it.parentId == null && it.name == folderName }
            val scopeId = folder.scopeId
            val name = "App link off ${System.currentTimeMillis() % 100_000}"
            val nodeId = PotCreate.create(tenant, scopeId, folder.nodeId, name, Ledger.BaseCurrency("EUR", 2), listOf(PotCreate.NewPerson.name("Ana"))).nodeId!!
            tenant.forgetTree()
            val pot = PotSession.open(tenant, scopeId, nodeId)!!
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(5_000) { (it % 5).toByte() }
            val receiptId = pot.newId()
            val form = PotView(pot).blankForm(java.time.LocalDate.now().toString())!!
                .copy(title = "Before the re-key", payers = listOf(EntryForm.Payer(pot.ledger!!.participants.first().id, "3")))
            assertEquals(PotSession.WriteOutcome.WRITTEN, pot.saveEntry(pot.newId(), form, null, listOf(PotSession.PendingReceipt(receiptId, png, "image/png", "r.png", null))))
            val a = links.mint(tenant, scopeId, nodeId, links.READ, "A", "", null)
            val b = links.mint(tenant, scopeId, nodeId, links.READ, "B", "", null)
            assertEquals(me.raddatz.veyq.mobile.session.ShareLinks.Outcome.CREATED, b.outcome, b.refusal)
            val aId = a.url!!.substringAfter("/s/").substringBefore('#')
            val fold = { kind: String, n: String -> if (kind == PotSession.KIND) PotCreate.tokenInput(n) else null }
            val closed = links.switchOff(tenant, scopeId, nodeId, aId, me.raddatz.veyq.mobile.AppKinds.rekeys, fold) { java.util.UUID.randomUUID().toString() }
            assertEquals(me.raddatz.veyq.mobile.session.ShareLinks.Closed.CLOSED, closed)
            val fresh = account.open(tenant.tenant)
            val again = PotSession.open(fresh, scopeId, nodeId)!!
            assertEquals(listOf("Before the re-key"), again.ledger!!.entries.map { (it.content as? Expense)?.title }, "the entry does not open under the new key")
            assertTrue(again.openReceipt(receiptId)!!.contentEquals(png), "the receipt does not open under the new key")
            val after = PotView(again).blankForm(java.time.LocalDate.now().toString())!!
                .copy(title = "After the re-key", payers = listOf(EntryForm.Payer(again.ledger!!.participants.first().id, "4")))
            assertEquals(PotSession.WriteOutcome.WRITTEN, again.saveEntry(again.newId(), after, null))
            val listed = links.list(fresh, scopeId, nodeId)!!
            assertTrue(!listed.single { it.label == "A" }.enabled && listed.single { it.label == "B" }.enabled)
            System.getenv("VEYQ_LIVE_LINKS_OUT")?.let { File(it).writeText(a.url + "\n" + b.url) }
            println("[$folderName] \"$name\": link A switched off and the Pot re-keyed; entry, receipt and a new write open; B stays on")
        }
        System.getenv("VEYQ_LIVE_COPY")?.let { potName ->
            // The Pot copied beside itself: a new Pot under the next free name whose fresh session reads the same live entries
            // with the same balances person by person, and every copied receipt to the byte; then trashed.
            val ref = pots.first { it.name == potName }
            val source = PotSession.open(tenant, ref.scopeId, ref.nodeId)!!
            val parent = tenant.node(ref.scopeId, ref.nodeId)?.parentId
            val copied = PotCopy.duplicate(source, tenant, ref.scopeId, parent, "Deleted people") { java.util.UUID.randomUUID().toString() }
            assertEquals(PotCopy.Outcome.COPIED, copied.outcome)
            val copy = PotSession.open(account.open(tenant.tenant), ref.scopeId, copied.nodeId!!)!!
            val a = source.ledger!!; val b = copy.ledger!!
            val titles = { l: Ledger.Pot -> l.entries.filter { it.status == Ledger.Status.LIVE }.map { (it.content as? Expense)?.title ?: (it.content as? Refund)?.title ?: "transfer" }.sorted() }
            assertEquals(titles(a), titles(b))
            val byName = { l: Ledger.Pot -> l.participants.filter { !it.erased }.associate { it.name to l.balances[it.id].toString() } }
            assertEquals(byName(a), byName(b))
            assertEquals(source.meta!!.base, copy.meta!!.base)
            copy.readReceipts()
            val kept = a.entries.filter { it.status == Ledger.Status.LIVE }.flatMap { it.content!!.receipts }
                .mapNotNull { r -> source.receipts.firstOrNull { it.receiptId == r && !it.dropped && !it.blocked } }
            assertEquals(kept.size, copy.receipts.count { !it.dropped })
            assertEquals(kept.map { source.openReceipt(it.receiptId)!!.toList().hashCode() }.sorted(),
                copy.receipts.filter { !it.dropped }.map { copy.openReceipt(it.receiptId)!!.toList().hashCode() }.sorted())
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, ref.scopeId, copied.nodeId!!))
            println("[$potName] copied as \"${copied.name}\": ${b.entries.size} entries, ${kept.size} receipts, balances equal; trashed")
        }
        System.getenv("VEYQ_LIVE_COVER")?.let { potName ->
            // A JPEG set as the Pot's cover: a fresh session opens it to the byte and its meta names it; with
            // VEYQ_LIVE_COVER_KEEP the cover stays, for the web's header and rows, else it is removed again and opens nothing.
            val ref = pots.first { it.name == potName }
            val image = java.awt.image.BufferedImage(320, 180, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().run { color = java.awt.Color(0x4f, 0x46, 0xe5); fillRect(0, 0, 320, 180); color = java.awt.Color.WHITE; fillOval(110, 40, 100, 100); dispose() }
            }
            val jpeg = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "jpg", it) }.toByteArray()
            val cover = me.raddatz.veyq.mobile.session.NodeCover
            assertEquals(me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE, cover.set(tenant, ref.scopeId, ref.nodeId, jpeg, me.raddatz.veyq.mobile.session.NodeCover.Crop(0.0, 0.0, 1.0, 1.0)))
            val fresh = account.open(tenant.tenant)
            val opened = cover.open(fresh, fresh.node(ref.scopeId, ref.nodeId)!!)
            assertTrue(opened != null && opened.contentEquals(jpeg), "the cover does not open to the bytes set")
            if (System.getenv("VEYQ_LIVE_COVER_KEEP") == null) {
                assertEquals(me.raddatz.veyq.mobile.session.NodeCover.Outcome.DONE, cover.remove(tenant, ref.scopeId, ref.nodeId))
                val again = account.open(tenant.tenant)
                assertEquals(null, cover.open(again, again.node(ref.scopeId, ref.nodeId)!!))
            }
            println("[$potName] a ${jpeg.size}-byte cover set and opened by a fresh session; ${if (System.getenv("VEYQ_LIVE_COVER_KEEP") == null) "removed" else "kept"}")
        }
        System.getenv("VEYQ_LIVE_EXPORT")?.let { potName ->
            // The Pot downloaded as the web's ZIP: the JDK reads it, the manifest names every payload by its SHA-256, the
            // content carries every live entry and the CSV a line each. With VEYQ_LIVE_EXPORT_OUT the archive is saved there,
            // for the web's import (the check that the web reads what the app writes).
            val ref = pots.first { it.name == potName }
            val session = PotSession.open(tenant, ref.scopeId, ref.nodeId)!!
            val content = PotExport.content(session, "Deleted people")!!
            val out = java.io.ByteArrayOutputStream()
            val written = me.raddatz.veyq.mobile.export.NodeExport.write(
                ref.name, ref.kind, content, me.raddatz.veyq.mobile.session.VeyqAccount.MAJOR.toString(), System.currentTimeMillis(),
                { out.write(it) },
            )
            val file = File(System.getenv("VEYQ_LIVE_EXPORT_OUT") ?: File.createTempFile("pot", ".zip").path).apply { writeBytes(out.toByteArray()) }
            java.util.zip.ZipFile(file).use { zip ->
                val manifest = kotlinx.serialization.json.Json.parseToJsonElement(zip.getInputStream(zip.getEntry(".veyq/manifest.json")).readBytes().decodeToString()).jsonObject
                assertEquals(2, manifest["version"]!!.jsonPrimitive.int)
                val root = manifest["root"]!!.jsonObject
                val pot = root["content"]!!.jsonObject
                val live = session.ledger!!.entries.count { it.status == Ledger.Status.LIVE }
                assertEquals(live, pot["entries"]!!.jsonArray.size)
                val refs = pot["entries"]!!.jsonArray.flatMap { e -> e.jsonObject["receipts"]?.jsonArray.orEmpty().map { it.jsonObject["file"]!!.jsonObject } } +
                    pot["table"]!!.jsonObject
                for (r in refs) {
                    val bytes = zip.getInputStream(zip.getEntry(r["path"]!!.jsonPrimitive.content)).readBytes()
                    assertEquals(r["size"]!!.jsonPrimitive.long, bytes.size.toLong())
                    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    assertEquals(r["sha256"]!!.jsonPrimitive.content, sha)
                }
                val csv = zip.getInputStream(zip.getEntry(pot["table"]!!.jsonObject["path"]!!.jsonPrimitive.content)).readBytes().decodeToString()
                assertEquals(live + 1, csv.split("\r\n").count { it.isNotEmpty() })
            }
            println("[$potName] downloaded: ${written.payloads} payloads, ${written.bytes} bytes, ${file.length()} bytes of ZIP at ${file.path}")
        }
        System.getenv("VEYQ_LIVE_REPORT")?.let { potName ->
            // A fresh Pot beside [potName] reported (it stays, trashed, and the report stays open for the dev moderators): its
            // own key, its name's and its meta's field keys all open here under the contexts a report names them by, the name
            // alone is one key, and the server takes the report with a receipt.
            val report = me.raddatz.veyq.mobile.session.NodeReport
            val scopeId = pots.first { it.name == potName }.scopeId
            val nodeId = PotCreate.create(tenant, scopeId, null, "App report ${System.currentTimeMillis() % 100_000}", Ledger.BaseCurrency("EUR", 2), emptyList()).nodeId!!
            tenant.forgetTree()
            val node = tenant.trees()[scopeId]!!.single { it.id == nodeId }
            assertEquals(listOf("NODE", "NODE_NAME", "NODE_META"), report.keysOf(tenant, scopeId, node, me.raddatz.veyq.mobile.session.NodeReport.Scope.WHOLE)!!.map { it.role })
            assertEquals(listOf("NODE_NAME"), report.keysOf(tenant, scopeId, node, me.raddatz.veyq.mobile.session.NodeReport.Scope.NAME)!!.map { it.role })
            assertTrue(report.published(tenant) != null, "the dev instance publishes no moderation key")
            val sent = report.report(tenant, scopeId, nodeId, me.raddatz.veyq.mobile.session.NodeReport.Scope.WHOLE, "TERMS_BREACH", "App check: a report sent by the app's live test.", "")
            assertEquals(me.raddatz.veyq.mobile.session.NodeReport.Outcome.SENT, sent.outcome)
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, scopeId, nodeId))
            println("[$potName] a fresh Pot reported: reference ${sent.reference}, received ${sent.receivedAt}")
        }
        System.getenv("VEYQ_LIVE_LINK_CHANGES")?.let { folderName ->
            // A fresh Pot in the root folder [folderName] with an entry. Link A is edited in place (label, password removed,
            // role, end) and keeps its address; then renewed: a new address, the old one gone, the settings carried, and the
            // Pot re-keyed — a fresh session still reads the entry. Link B's password is carried by its renew; B is then
            // switched off and deleted. A's old and new addresses go to VEYQ_LIVE_LINKS_OUT for the web's guest page.
            val links = me.raddatz.veyq.mobile.session.ShareLinks
            val changes = me.raddatz.veyq.mobile.session.ShareLinkChanges
            val ok = me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.DONE
            val now = { System.currentTimeMillis() }
            val uuid = { java.util.UUID.randomUUID().toString() }
            val fold = { kind: String, n: String -> if (kind == PotSession.KIND) PotCreate.tokenInput(n) else null }
            val folder = tenant.nodes().first { it.kind == "FOLDER" && it.parentId == null && it.name == folderName }
            val scopeId = folder.scopeId
            val name = "App link changes ${System.currentTimeMillis() % 100_000}"
            val nodeId = PotCreate.create(tenant, scopeId, folder.nodeId, name, Ledger.BaseCurrency("EUR", 2), listOf(PotCreate.NewPerson.name("Ana"))).nodeId!!
            tenant.forgetTree()
            val pot = PotSession.open(tenant, scopeId, nodeId)!!
            val form = PotView(pot).blankForm(java.time.LocalDate.now().toString())!!
                .copy(title = "Kept across a renew", payers = listOf(EntryForm.Payer(pot.ledger!!.participants.first().id, "5")))
            assertEquals(PotSession.WriteOutcome.WRITTEN, pot.saveEntry(pot.newId(), form, null))
            val end = java.time.Instant.now().plus(java.time.Duration.ofDays(5)).toString()
            val a = links.mint(tenant, scopeId, nodeId, links.READ, "A", "a-link-password-1", end)
            assertEquals(me.raddatz.veyq.mobile.session.ShareLinks.Outcome.CREATED, a.outcome, a.refusal)
            val aId = a.url!!.substringAfter("/s/").substringBefore('#')
            val edited = changes.edit(tenant, scopeId, nodeId, aId, me.raddatz.veyq.mobile.session.ShareLinkChanges.Edit("A edited", "", end, links.READ_WRITE), now())
            assertEquals(ok, edited.outcome)
            val afterEdit = links.list(tenant, scopeId, nodeId)!!.single { it.shareId == aId }
            assertEquals("A edited", afterEdit.label)
            assertEquals(links.READ_WRITE, afterEdit.role)
            assertTrue(!afterEdit.passwordRequired && afterEdit.expiresAt != null)
            assertEquals(a.url, afterEdit.url)
            val renewed = changes.renew(tenant, scopeId, nodeId, aId, me.raddatz.veyq.mobile.AppKinds.rekeys, fold, uuid, now())
            assertEquals(ok, renewed.outcome)
            val listed = links.list(account.open(tenant.tenant), scopeId, nodeId)!!
            assertTrue(listed.none { it.shareId == aId }, "the renewed link is still listed")
            val aNew = listed.single { it.url == renewed.url }
            assertEquals("A edited", aNew.label)
            assertEquals(links.READ_WRITE, aNew.role)
            assertTrue(!aNew.passwordRequired && aNew.expiresAt != null && aNew.enabled)
            val fresh = account.open(tenant.tenant)
            assertEquals(listOf("Kept across a renew"), PotSession.open(fresh, scopeId, nodeId)!!.ledger!!.entries.map { (it.content as? Expense)?.title }, "the entry does not open after the renew's re-key")
            val b = links.mint(tenant, scopeId, nodeId, links.READ, "B", "b-link-password-1", null)
            val bId = b.url!!.substringAfter("/s/").substringBefore('#')
            val bRenewed = changes.renew(tenant, scopeId, nodeId, bId, me.raddatz.veyq.mobile.AppKinds.rekeys, fold, uuid, now())
            assertEquals(ok, bRenewed.outcome)
            val bNew = links.list(tenant, scopeId, nodeId)!!.single { it.url == bRenewed.url }
            assertTrue(bNew.passwordRequired && bNew.label == "B", "B's password or label was not carried")
            assertEquals(me.raddatz.veyq.mobile.session.ShareLinks.Closed.CLOSED, links.switchOff(tenant, scopeId, nodeId, bNew.shareId, me.raddatz.veyq.mobile.AppKinds.rekeys, fold, uuid))
            assertEquals(ok, changes.delete(tenant, scopeId, nodeId, bNew.shareId, me.raddatz.veyq.mobile.AppKinds.rekeys, fold, uuid, now()).outcome)
            assertTrue(links.list(tenant, scopeId, nodeId)!!.none { it.shareId == bNew.shareId }, "the deleted link is still listed")
            System.getenv("VEYQ_LIVE_LINKS_OUT")?.let { File(it).writeText(a.url + "\n" + renewed.url) }
            println("[$folderName] \"$name\": A edited in place and renewed, B's password carried, B switched off and deleted")
        }
        System.getenv("VEYQ_LIVE_RENAME")?.let { potName ->
            // A fresh Pot renamed (read back by a fresh session), its old name free and its new one taken, a sibling's name
            // refused before anything is sent, then trashed (it stays in the trash).
            val ref = pots.first { it.name == potName }
            val fold = { kind: String, name: String -> if (kind == PotSession.KIND) PotCreate.tokenInput(name) else null }
            val n = System.currentTimeMillis() % 100_000
            val nodeId = PotCreate.create(tenant, ref.scopeId, null, "App rename $n", Ledger.BaseCurrency("EUR", 2), emptyList()).nodeId!!
            tenant.forgetTree()
            val renamed = me.raddatz.veyq.mobile.session.NodeRename.rename(tenant, ref.scopeId, nodeId, "  App renamed $n ", fold, System.currentTimeMillis())
            assertEquals(me.raddatz.veyq.mobile.session.NodeRename.Outcome.RENAMED, renamed.outcome)
            assertEquals(emptyList(), renamed.links!!.failed, "a link's name was not re-sealed")
            assertEquals("App renamed $n", account.open(tenant.tenant).objects().single { it.nodeId == nodeId }.name)
            assertEquals(PotCreate.Outcome.NAME_TAKEN, PotCreate.create(tenant, ref.scopeId, null, "App renamed $n", Ledger.BaseCurrency("EUR", 2), emptyList()).outcome)
            // A second rename builds on the placement revision the first one left.
            assertEquals(me.raddatz.veyq.mobile.session.NodeRename.Outcome.RENAMED, me.raddatz.veyq.mobile.session.NodeRename.rename(tenant, ref.scopeId, nodeId, "App renamed twice $n", fold, System.currentTimeMillis()).outcome)
            // The name of a Pot beside it (folding the same) is refused by name before a request is sent.
            val sibling = tenant.objects().first { it.scopeId == ref.scopeId && it.nodeId != nodeId && it.kind == PotSession.KIND && tenant.node(it.scopeId, it.nodeId)?.parentId == null }
            val taken = me.raddatz.veyq.mobile.session.NodeRename.rename(tenant, ref.scopeId, nodeId, sibling.name.uppercase(), fold, System.currentTimeMillis())
            assertEquals(me.raddatz.veyq.mobile.session.NodeRename.Outcome.NAME_TAKEN, taken.outcome)
            assertEquals(sibling.nodeId, taken.taken?.id)
            assertEquals(me.raddatz.veyq.mobile.session.NodeTrash.Deleted.TRASHED, me.raddatz.veyq.mobile.session.NodeTrash.delete(tenant, ref.scopeId, nodeId))
            println("[$potName] \"App rename $n\" renamed twice, its name taken, a sibling's refused; trashed")
        }
        System.getenv("VEYQ_LIVE_RENAME_TARGET")?.let { target ->
            // "<from>|<to>": the root Pot named <from> (created beside the first Pot when there is none) renamed to <to>,
            // with what the re-seal of its links did — the web's link to it then shows <to> to its guests.
            val (from, to) = target.split('|')
            val fold = { kind: String, name: String -> if (kind == PotSession.KIND) PotCreate.tokenInput(name) else null }
            val scopeId = pots.first().scopeId
            val nodeId = tenant.objects().firstOrNull { it.scopeId == scopeId && it.name == from }?.nodeId
                ?: PotCreate.create(tenant, scopeId, null, from, Ledger.BaseCurrency("EUR", 2), emptyList()).nodeId!!.also { tenant.forgetTree() }
            if (from != to) {
                val r = me.raddatz.veyq.mobile.session.NodeRename.rename(tenant, scopeId, nodeId, to, fold, System.currentTimeMillis())
                assertEquals(me.raddatz.veyq.mobile.session.NodeRename.Outcome.RENAMED, r.outcome)
                println("[rename] \"$from\" -> \"$to\": links resealed=${r.links?.resealed?.size} closed=${r.links?.closed?.size} skipped=${r.links?.skipped?.size} failed=${r.links?.failed?.size}")
            } else {
                println("[rename] \"$from\" is there ($nodeId)")
            }
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
