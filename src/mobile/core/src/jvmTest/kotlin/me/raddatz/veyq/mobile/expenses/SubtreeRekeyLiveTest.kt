package me.raddatz.veyq.mobile.expenses

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
 * A link's close re-keys its root's whole subtree (ADR 0114), on a running dev instance (`VEYQ_LIVE`), with
 * `VEYQ_LIVE_SUBTREE=<root folder name>`: see the block. Its own class: the live Pot test's one method outgrew the JVM's.
 */
class SubtreeRekeyLiveTest {
    @Test
    fun aCloseReKeysTheSubtree_andAnInterruptedOneIsResumed() = runTest(timeout = 10.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        // `.run/.env` of this checkout, or VEYQ_ENV_FILE's (a worktree has none)
        val env = (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val device = testDevice(BouncyCastlePrimitives)
        val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(OkHttp, block) }
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            device, client,
        )
        val tenant = account.open(account.defaultTenant!!)
        System.getenv("VEYQ_LIVE_SUBTREE")?.let { folderName ->
            // A folder F in the root folder [folderName] holding a Pot P (an entry, a receipt) and a folder G holding a Pot Q,
            // with links A and B on F (ADR 0114). A's switch-off re-keys F's whole subtree on this device: nothing below F is
            // due after it, and a fresh session opens P's entry and receipt and Q. B's close is then INTERRUPTED — the link is
            // switched off and only F re-keyed, as an app closed mid-run leaves it: P, G and Q stay due, and a write into P
            // that may not pull its path forward is refused; the background run (`NodeRekey.resume`, any member's device)
            // finishes it, after which P opens, its receipt to the byte, and takes a write. F stays (in [folderName]).
            val links = me.raddatz.veyq.mobile.session.ShareLinks
            val rules = me.raddatz.veyq.mobile.AppKinds.rules
            tenant.rekeyRules = rules
            val uuid = { java.util.UUID.randomUUID().toString() }
            val parent = tenant.nodes().first { it.kind == "FOLDER" && it.parentId == null && it.name == folderName }
            val scopeId = parent.scopeId
            val n = System.currentTimeMillis() % 100_000
            suspend fun folder(at: String, name: String) =
                (me.raddatz.veyq.mobile.session.FolderCreate.create(tenant, scopeId, at, name) as me.raddatz.veyq.mobile.session.NodeCreate.Result.Done).created.nodeId
            val f = folder(parent.nodeId, "App subtree $n")
            val p = PotCreate.create(tenant, scopeId, f, "Pot P", Ledger.BaseCurrency("EUR", 2), listOf(PotCreate.NewPerson.name("Ana"))).nodeId!!
            val g = folder(f, "Inner")
            val q = PotCreate.create(tenant, scopeId, g, "Pot Q", Ledger.BaseCurrency("EUR", 2), listOf(PotCreate.NewPerson.name("Ben"))).nodeId!!
            tenant.forgetTree()
            val pot = PotSession.open(tenant, scopeId, p)!!
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(5_000) { (it % 7).toByte() }
            val receiptId = pot.newId()
            val form = PotView(pot).blankForm(java.time.LocalDate.now().toString())!!
                .copy(title = "Before the closes", payers = listOf(EntryForm.Payer(pot.ledger!!.participants.first().id, "3")))
            assertEquals(PotSession.WriteOutcome.WRITTEN, pot.saveEntry(pot.newId(), form, null, listOf(PotSession.PendingReceipt(receiptId, png, "image/png", "r.png", null))))
            val ours = setOf(f, p, g, q)
            val rekeyApi = me.raddatz.veyq.mobile.api.apis.RekeyApi(tenant.base, account.http)
            suspend fun due(): Set<String> = account.call { rekeyApi.listRekeyNodes(scopeId) }.nodes.filter { it.due && it.id in ours }.map { it.id }.toSet()
            suspend fun readsBack(title: String) {
                val fresh = account.open(tenant.tenant).also { it.rekeyRules = rules }
                val again = PotSession.open(fresh, scopeId, p)!!
                assertTrue(title in again.ledger!!.entries.map { (it.content as? Expense)?.title }, "P's entry does not open")
                again.readReceipts()
                assertTrue(again.openReceipt(receiptId)!!.contentEquals(png), "P's receipt does not open")
                assertTrue(PotSession.open(fresh, scopeId, q)!!.ledger!!.participants.any { it.name == "Ben" }, "Q does not open")
            }

            // 1) a whole close: A switched off, F's subtree re-keyed at once
            val a = links.mint(tenant, scopeId, f, links.READ, "A", "", null)
            assertEquals(me.raddatz.veyq.mobile.session.ShareLinks.Outcome.CREATED, a.outcome, a.refusal)
            val closed = links.switchOff(tenant, scopeId, f, a.url!!.substringAfter("/s/").substringBefore('#'), rules.kinds, rules.tokenInput, uuid)
            assertEquals(me.raddatz.veyq.mobile.session.ShareLinks.Closed.CLOSED, closed)
            assertEquals(emptySet(), due(), "a node below the closed link is still due")
            readsBack("Before the closes")

            // 2) an interrupted close: B switched off, only F re-keyed — P, G, Q stay due
            val b = links.mint(tenant, scopeId, f, links.READ, "B", "", null)
            val bId = b.url!!.substringAfter("/s/").substringBefore('#')
            assertTrue(account.respond { me.raddatz.veyq.mobile.api.apis.SharingApi(tenant.base, account.http).revokeShare(bId) }.success)
            assertEquals(ours, due(), "the close marks the whole subtree")
            val partial = me.raddatz.veyq.mobile.session.NodeRekey.rekeyPath(tenant, scopeId, listOf(f), subtree = false, rules.kinds, rules.tokenInput, uuid)
            assertEquals(me.raddatz.veyq.mobile.session.NodeRekey.Outcome.DONE, partial.outcome)
            assertEquals(setOf(p, g, q), due(), "the interruption leaves the rest due")
            // a write into P that may not pull (a guest's, or a device without the rules) waits, then gives up
            val blocked = account.open(tenant.tenant)
            val stuck = PotSession.open(blocked, scopeId, p)!!.also { it.rekeyWaitsMs = emptyList() }
            val tryWrite = PotView(stuck).blankForm(java.time.LocalDate.now().toString())!!
                .copy(title = "While due", payers = listOf(EntryForm.Payer(stuck.ledger!!.participants.first().id, "1")))
            assertEquals(PotSession.WriteOutcome.BUSY, stuck.saveEntry(stuck.newId(), tryWrite, null))
            if (System.getenv("VEYQ_LIVE_SUBTREE_LEAVE") != null) {
                // left for an app to finish: started, it resumes the scope in the background (`VEYQ_LIVE_SUBTREE_DUE` checks)
                println("[$folderName] \"App subtree $n\": an interrupted close left P, G and Q due, for an app's background run")
                return@let
            }
            // the background run any member's device makes finishes it: the core starts it when the tenant is shown (the
            // session's door, what every app opens a tenant through), never an app — and says so to the tenant's models
            val door = me.raddatz.veyq.mobile.session.AccountDoor(BouncyCastlePrimitives, device, client)
            val resumer = door.open(account, tenant.tenant)
            assertTrue(resumer.rekeyRules != null, "the tenant opened without the build's re-key rules")
            assertTrue(scopeId in resumer.rekeyingScopes, "the server does not serve the scope as re-keying")
            door.opened(resumer)
            val told = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5.minutes) { resumer.rekeyed.first { it > 0 } }
            }
            val resumed = "rekeyed $told"
            assertEquals(emptySet(), due(), "the resume left a node of the subtree due")
            readsBack("Before the closes")
            val writer = PotSession.open(account.open(tenant.tenant).also { it.rekeyRules = rules }, scopeId, p)!!
            val after = PotView(writer).blankForm(java.time.LocalDate.now().toString())!!
                .copy(title = "After the resume", payers = listOf(EntryForm.Payer(writer.ledger!!.participants.first().id, "2")))
            assertEquals(PotSession.WriteOutcome.WRITTEN, writer.saveEntry(writer.newId(), after, null))
            println("[$folderName] \"App subtree $n\": a close re-keyed F, P, G and Q; an interrupted one left P, G, Q due, a write waited, the resume at the tenant's open ($resumed) finished it")
        }
        System.getenv("VEYQ_LIVE_SUBTREE_DUE")?.let { folderName ->
            // After `VEYQ_LIVE_SUBTREE_LEAVE` and an app started: the app's background run left nothing of the space due,
            // and every Pot of the space opens in a fresh session.
            val scopeId = tenant.nodes().first { it.kind == "FOLDER" && it.parentId == null && it.name == folderName }.scopeId
            val rekeyApi = me.raddatz.veyq.mobile.api.apis.RekeyApi(tenant.base, account.http)
            val due = account.call { rekeyApi.listRekeyNodes(scopeId) }.nodes.filter { it.due }.map { it.id }
            assertEquals(emptyList(), due, "the app's background run left nodes due")
            assertTrue(scopeId !in tenant.rekeyingScopes, "the space is still served as re-keying")
            val pots = tenant.nodes().filter { it.scopeId == scopeId && it.kind == PotSession.KIND }
            for (node in pots) assertTrue(PotSession.open(tenant, scopeId, node.nodeId)?.ledger != null, "${node.name} does not open")
            println("[$folderName] nothing due, ${pots.size} Pots of the space open")
        }
    }
}
