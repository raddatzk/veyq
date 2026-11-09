package me.raddatz.veyq.mobile.expenses

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A folder downloaded and copied whole (the web's `exportSubtree.ts`, `copySubtree.ts`), on a running dev instance
 * (`VEYQ_LIVE`), with `VEYQ_LIVE_FOLDER="<root folder>/<folder>"` — e.g. one `VEYQ_LIVE_SUBTREE` made: a Pot with an entry and
 * a receipt, a folder holding a Pot. The download is read back (every folder a directory without content, every Pot its
 * content, every payload's SHA-256); `VEYQ_LIVE_EXPORT_OUT=<file>` keeps it, for the web's import. The copy goes beside the
 * original ("<folder> (2)"), and a fresh session reads every Pot of it as the original's; it is trashed after unless
 * `VEYQ_LIVE_COPY_KEEP` is set.
 */
class FolderLiveTest {
    @Test
    fun aFolderIsDownloadedAndCopiedWhole() = runTest(timeout = 10.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val spec = System.getenv("VEYQ_LIVE_FOLDER") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.defaultTenant!!)
        val (rootName, folderName) = spec.split('/', limit = 2)
        val nodes = tenant.nodes()
        val parent = nodes.first { it.kind == "FOLDER" && it.parentId == null && it.name == rootName }
        val folder = nodes.first { it.kind == "FOLDER" && it.parentId == parent.nodeId && it.name == folderName }
        val scopeId = folder.scopeId
        fun below(id: String): List<me.raddatz.veyq.mobile.navigation.NodeRef> =
            nodes.filter { it.parentId == id }.flatMap { listOf(it) + below(it.nodeId) }
        val pots = below(folder.nodeId).filter { it.kind == PotSession.KIND }
        assertTrue(pots.isNotEmpty(), "the folder holds no Pot to prove anything with")

        // 1) the download
        val walked = NodeExport.walk(tenant, scopeId, folder.nodeId, AppKinds.exports("Deleted people"))!!
        val out = java.io.ByteArrayOutputStream()
        NodeExport.write(walked.root, VeyqAccount.MAJOR.toString(), System.currentTimeMillis(), { java.security.MessageDigest.getInstance("SHA-256").digest(it) }) { out.write(it) }
        val file = File(System.getenv("VEYQ_LIVE_EXPORT_OUT") ?: File.createTempFile("folder", ".zip").path).apply { writeBytes(out.toByteArray()) }
        java.util.zip.ZipFile(file).use { zip ->
            val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(NodeExport.MANIFEST_PATH)).readBytes().decodeToString()).jsonObject
            val root = manifest["root"]!!.jsonObject
            assertEquals("FOLDER", root["kind"]!!.jsonPrimitive.content)
            assertTrue("content" !in root)
            fun all(n: kotlinx.serialization.json.JsonObject): List<kotlinx.serialization.json.JsonObject> =
                listOf(n) + (n["children"]?.jsonArray?.flatMap { all(it.jsonObject) } ?: emptyList())
            val written = all(root)
            assertEquals(pots.size, written.count { it["kind"]!!.jsonPrimitive.content == PotSession.KIND }, "a Pot is missing from the archive")
            for (n in written) {
                assertTrue(zip.getEntry(n["path"]!!.jsonPrimitive.content + "/") != null, "no directory for ${n["path"]}")
                assertEquals(n["kind"]!!.jsonPrimitive.content == "FOLDER", "content" !in n)
            }
            // every payload the manifest names, to its SHA-256
            val refs = Regex("\"path\":\\s*\"([^\"]+)\",\\s*\"size\"[^}]*\"sha256\":\\s*\"([0-9a-f]{64})\"").findAll(manifest.toString()).toList()
            for (r in refs) {
                val bytes = zip.getInputStream(zip.getEntry(r.groupValues[1])).readBytes()
                val hex = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals(r.groupValues[2], hex, "${r.groupValues[1]} is not what the manifest names")
            }
            println("[$spec] downloaded: ${written.size} nodes, ${refs.size} payloads, ${walked.skipped.size} left out → $file")
        }

        // 2) the copy, beside the original
        val copied = SubtreeCopy.duplicate(tenant, scopeId, folder.nodeId, parent.nodeId, AppKinds.copies("Deleted people") { java.util.UUID.randomUUID().toString() })
        assertEquals(SubtreeCopy.Outcome.COPIED, copied.outcome)
        assertTrue(Regex(Regex.escape(folderName) + " \\(\\d+\\)").matches(copied.name!!), "a copy beside its original takes the next free name, not ${copied.name}")
        val fresh = account.open(tenant.tenant)
        val again = fresh.nodes()
        fun belowIn(id: String): List<me.raddatz.veyq.mobile.navigation.NodeRef> =
            again.filter { it.parentId == id }.flatMap { listOf(it) + belowIn(it.nodeId) }
        val copiedPots = belowIn(copied.nodeId!!).filter { it.kind == PotSession.KIND }
        assertEquals(pots.map { it.name }.sorted(), copiedPots.map { it.name }.sorted(), "the copy holds other Pots")
        for (orig in pots) {
            val a = PotSession.open(fresh, scopeId, orig.nodeId)!!
            val b = PotSession.open(fresh, scopeId, copiedPots.first { it.name == orig.name && it.nodeId !in pots.map { p -> p.nodeId } }.nodeId)!!
            fun titles(s: PotSession) = s.ledger!!.entries.filter { it.status == Ledger.Status.LIVE }.mapNotNull { (it.content as? Expense)?.title }.sorted()
            assertEquals(titles(a), titles(b), "${orig.name}'s copy reads other entries")
        }
        if (System.getenv("VEYQ_LIVE_COPY_KEEP") == null) assertEquals(NodeTrash.Deleted.TRASHED, NodeTrash.delete(tenant, scopeId, copied.nodeId!!))
        println("[$spec] copied as \"${copied.name}\": ${copiedPots.size} Pots read as the original's; ${if (System.getenv("VEYQ_LIVE_COPY_KEEP") == null) "trashed" else "kept"}")
    }
}
