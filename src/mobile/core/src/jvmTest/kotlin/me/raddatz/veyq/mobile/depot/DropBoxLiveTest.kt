package me.raddatz.veyq.mobile.depot

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A drop box the web made and its guests filled, read by a member on a running dev instance (`VEYQ_LIVE`), with
 * `VEYQ_LIVE_DROPBOX="<root folder>/<drop box>"`: every guest's folder opens through the drop box's drop key and the
 * guest's identity key (core's `Tree`, `v1.json#drop_key`), labelled as the web labels it, and every file in it reads back
 * to its listed size; each file's SHA-256 is printed, to hold against what the guest dropped.
 */
class DropBoxLiveTest {
    private suspend fun signIn(): me.raddatz.veyq.mobile.session.TenantSession? {
        val origin = System.getenv("VEYQ_LIVE") ?: return null
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        return account.open(account.defaultTenant!!)
    }

    /**
     * A drop box re-keyed with the subtree above it (ADR 0114, the web's `replaceDropKey`). `VEYQ_LIVE_DROPBOX_REKEY_SETUP=<root
     * folder>` makes a folder there ("App dropre <n>") holding a drop box ("Box") with its link, written to
     * `VEYQ_LIVE_LINKS_OUT` for a web guest to drop through. `VEYQ_LIVE_DROPBOX_REKEY="<root folder>/<that folder>"` then
     * mints a READ link on the folder and switches it off: the whole subtree is re-keyed on this device — the drop box's drop
     * key replaced, its link's public half and every guest's K_up sealed anew, each guest's folder kept under its K_up —, so
     * nothing of the folder is due after, and a fresh session reads every guest's file; the same old link then still takes files
     * (drop through it again, and `VEYQ_LIVE_DROPBOX` reads the new folder too).
     */
    @Test
    fun aDropBoxIsReKeyedWithItsGuestsKept() = runTest(timeout = 5.minutes) {
        System.getenv("VEYQ_LIVE_DROPBOX_REKEY_SETUP")?.let { rootName ->
            val tenant = signIn() ?: return@runTest
            val parent = tenant.nodes().first { it.kind == "FOLDER" && it.parentId == null && it.name == rootName }
            val name = "App dropre ${System.currentTimeMillis() % 100_000}"
            val folder = (me.raddatz.veyq.mobile.session.FolderCreate.create(tenant, parent.scopeId, parent.nodeId, name) as me.raddatz.veyq.mobile.session.NodeCreate.Result.Done).created.nodeId
            val made = DropBoxes.create(tenant, parent.scopeId, folder, "Box", "", null)
            assertEquals(DropBoxes.Outcome.CREATED, made.outcome)
            System.getenv("VEYQ_LIVE_LINKS_OUT")?.let { File(it).writeText(made.url!! + "\n") }
            println("[$rootName] \"$name\" with its drop box \"Box\": ${made.url}")
        }
        System.getenv("VEYQ_LIVE_DROPBOX_REKEY")?.let { spec ->
            val tenant = signIn() ?: return@runTest
            val (rootName, folderName) = spec.split('/', limit = 2)
            val nodes = tenant.nodes()
            val parent = nodes.first { it.kind == "FOLDER" && it.parentId == null && it.name == rootName }
            val folder = nodes.first { it.kind == "FOLDER" && it.parentId == parent.nodeId && it.name == folderName }
            val box = nodes.first { it.kind == DepotFiles.DROPBOX && it.parentId == folder.nodeId }
            val before = DropBoxes.folders(tenant, box.scopeId, box.nodeId)
            assertTrue(before.isNotEmpty() && before.all { it.files.isNotEmpty() }, "drop through the link first")
            val links = me.raddatz.veyq.mobile.session.ShareLinks
            val minted = links.mint(tenant, folder.scopeId, folder.nodeId, links.READ, "", "", null)
            val closed = links.switchOff(
                tenant, folder.scopeId, folder.nodeId, minted.url!!.substringAfter("/s/").substringBefore('#'),
                me.raddatz.veyq.mobile.AppKinds.rekeys, me.raddatz.veyq.mobile.AppKinds::tokenInput,
            ) { java.util.UUID.randomUUID().toString() }
            val rekeyApi = me.raddatz.veyq.mobile.api.apis.RekeyApi(tenant.base, tenant.account.http)
            if (closed != me.raddatz.veyq.mobile.session.ShareLinks.Closed.CLOSED) {
                for (r in tenant.account.call { rekeyApi.listRekeyNodes(folder.scopeId) }.nodes.filter { it.due }) println("    due: ${r.id} ${r.kind} parent=${r.parentId} ownerOnly=${r.ownerOnly} identity=${r.shareIdentityId} drop=${r.sealedDropPrivateKey != null}")
            }
            assertEquals(me.raddatz.veyq.mobile.session.ShareLinks.Closed.CLOSED, closed, "the subtree was not re-keyed whole")
            // the folder's subtree, not the space: a node elsewhere may stay due for another device
            val rows = tenant.account.call { rekeyApi.listRekeyNodes(folder.scopeId) }.nodes
            val parentOf = rows.associate { it.id to it.parentId }
            fun under(id: String?): Boolean = id != null && (id == folder.nodeId || under(parentOf[id]))
            val due = rows.filter { it.due && under(it.id) }.map { it.id }
            assertEquals(emptyList(), due, "a node of the folder is still due")
            // a fresh session opens every guest's folder and file through the NEW drop key
            val fresh = tenant.account.open(tenant.tenant)
            val after = DropBoxes.folders(fresh, box.scopeId, box.nodeId)
            assertEquals(before.map { it.label to it.files.map { f -> f.name } }, after.map { it.label to it.files.map { f -> f.name } })
            val files = DepotFiles.list(fresh)
            for (f in after) for (n in f.files) {
                val file = files.getValue(n.id)
                val out = java.io.ByteArrayOutputStream()
                DepotFiles.read(fresh, file) { out.write(it) }
                assertEquals(file.size, out.size().toLong())
                println("    ${f.label}/${n.name}: sha256 " + MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) })
            }
            println("[$spec] the folder's link switched off: its subtree re-keyed whole, the drop box's guests kept (${after.size} folders)")
        }
    }

    /**
     * Links switched on again (the web's `reactivateShare`): `VEYQ_LIVE_LINK_REACTIVATE=<root folder>` makes a folder there
     * ("App reopen <n>") with a READ link and a drop box in it ("Box") with its link, switches both off — the folder's close
     * re-keys it, the drop box's re-keys nothing — and on again: each listed on, at its old address, which goes to
     * `VEYQ_LIVE_LINKS_OUT` (the folder's first), for the web's guest page — the folder's opens it, the drop box's takes files.
     */
    @Test
    fun aSwitchedOffLinkComesBack() = runTest(timeout = 5.minutes) {
        val rootName = System.getenv("VEYQ_LIVE_LINK_REACTIVATE") ?: return@runTest
        val tenant = signIn() ?: return@runTest
        val links = me.raddatz.veyq.mobile.session.ShareLinks
        val changes = me.raddatz.veyq.mobile.session.ShareLinkChanges
        val parent = tenant.nodes().first { it.kind == "FOLDER" && it.parentId == null && it.name == rootName }
        val name = "App reopen ${System.currentTimeMillis() % 100_000}"
        val folder = (me.raddatz.veyq.mobile.session.FolderCreate.create(tenant, parent.scopeId, parent.nodeId, name) as me.raddatz.veyq.mobile.session.NodeCreate.Result.Done).created.nodeId
        val read = links.mint(tenant, parent.scopeId, folder, links.READ, "reopened", "", null)
        val box = DropBoxes.create(tenant, parent.scopeId, folder, "Box", "", null)
        assertEquals(DropBoxes.Outcome.CREATED, box.outcome)
        val urls = mutableListOf<String>()
        for ((nodeId, url) in listOf(folder to read.url!!, box.nodeId!! to box.url!!)) {
            val id = url.substringAfter("/s/").substringBefore('#')
            val writeOnly = nodeId != folder
            assertEquals(
                me.raddatz.veyq.mobile.session.ShareLinks.Closed.CLOSED,
                links.switchOff(tenant, parent.scopeId, nodeId, id, me.raddatz.veyq.mobile.AppKinds.rekeys, me.raddatz.veyq.mobile.AppKinds::tokenInput) { java.util.UUID.randomUUID().toString() },
            )
            assertEquals(false, links.list(tenant, parent.scopeId, nodeId, writeOnly)!!.single { it.shareId == id }.enabled)
            val back = changes.reactivate(tenant, parent.scopeId, nodeId, id)
            assertEquals(me.raddatz.veyq.mobile.session.ShareLinkChanges.Outcome.DONE, back.outcome)
            val listed = links.list(tenant.account.open(tenant.tenant), parent.scopeId, nodeId, writeOnly)!!.single { it.shareId == id }
            assertTrue(listed.enabled, "switched on again")
            assertEquals(url, listed.url, "the same address")
            urls += url
        }
        System.getenv("VEYQ_LIVE_LINKS_OUT")?.let { File(it).writeText(urls.joinToString("\n") + "\n") }
        println("[$rootName] \"$name\": its READ link and its drop box's link switched off and on again: $urls")
    }

    /**
     * `VEYQ_LIVE_DROPBOX_CREATE=<root folder>`: the app makes a drop box there ("App drop <n>") with its first link (the
     * drop key born with it), mints a second that reuses that key, lists both as the drop box's links, switches the second
     * off (no re-key: a drop box's link opens no key) and writes the first's address to `VEYQ_LIVE_LINKS_OUT`, for a web
     * guest to drop through; `VEYQ_LIVE_DROPBOX` then reads what they dropped.
     */
    @Test
    fun theAppMakesADropBoxAndItsLinks() = runTest(timeout = 5.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val rootName = System.getenv("VEYQ_LIVE_DROPBOX_CREATE") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.defaultTenant!!)
        val parent = tenant.nodes().first { it.kind == "FOLDER" && it.parentId == null && it.name == rootName }
        val name = "App drop ${System.currentTimeMillis() % 100_000}"
        val made = DropBoxes.create(tenant, parent.scopeId, parent.nodeId, name, "", null)
        assertEquals(DropBoxes.Outcome.CREATED, made.outcome, "${made.minted}")
        val links = me.raddatz.veyq.mobile.session.ShareLinks
        val second = links.mint(tenant, parent.scopeId, made.nodeId!!, links.WRITE_ONLY, "", "", null)
        assertEquals(me.raddatz.veyq.mobile.session.ShareLinks.Outcome.CREATED, second.outcome, second.refusal)
        val listed = links.list(tenant, parent.scopeId, made.nodeId, writeOnly = true)!!
        assertEquals(2, listed.size, "the drop box lists its two links")
        assertTrue(listed.all { it.role == links.WRITE_ONLY })
        assertEquals(emptyList(), links.list(tenant, parent.scopeId, made.nodeId)!!, "a drop box's links are no READ links")
        val secondId = second.url!!.substringAfter("/s/").substringBefore('#')
        assertEquals(
            me.raddatz.veyq.mobile.session.ShareLinks.Closed.CLOSED,
            links.switchOff(tenant, parent.scopeId, made.nodeId, secondId, me.raddatz.veyq.mobile.AppKinds.rekeys, me.raddatz.veyq.mobile.AppKinds::tokenInput) { java.util.UUID.randomUUID().toString() },
        )
        System.getenv("VEYQ_LIVE_LINKS_OUT")?.let { File(it).writeText(made.url!! + "\n") }
        println("[$rootName] \"$name\": made with its link, a second minted on its drop key and switched off; ${made.url}")
    }

    @Test
    fun aMemberOpensEveryGuestsFolder() = runTest(timeout = 5.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val spec = System.getenv("VEYQ_LIVE_DROPBOX") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.defaultTenant!!)
        val (rootName, boxName) = spec.split('/', limit = 2)
        val nodes = tenant.nodes()
        val parent = nodes.first { it.kind == "FOLDER" && it.parentId == null && it.name == rootName }
        val box = nodes.first { it.kind == DepotFiles.DROPBOX && it.parentId == parent.nodeId && it.name == boxName }
        val folders = DropBoxes.folders(tenant, box.scopeId, box.nodeId)
        assertTrue(folders.isNotEmpty(), "no guest's folder opened")
        assertTrue(folders.all { it.node.shareIdentityId != null }, "a guest's folder carries its identity")
        val files = DepotFiles.list(tenant)
        for (f in folders) {
            println("[$spec] ${f.label} (${f.node.createdAt}): ${f.files.size} files")
            for (n in f.files) {
                val file = files.getValue(n.id)
                val out = java.io.ByteArrayOutputStream()
                DepotFiles.read(tenant, file) { out.write(it) }
                assertEquals(file.size, out.size().toLong(), "${n.name} does not read to its size")
                val sha = MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) }
                println("    ${n.name}: ${out.size()} bytes, ${file.type}, sha256 $sha")
            }
        }
        assertEquals(folders.map { it.label }.toSet().size, folders.size, "two guests' folders show one label")
    }
}
