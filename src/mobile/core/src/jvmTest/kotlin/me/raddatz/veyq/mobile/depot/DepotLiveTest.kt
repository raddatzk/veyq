package me.raddatz.veyq.mobile.depot

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Depot's files on a running dev instance (`VEYQ_LIVE`), as the web's `fileUploadJob` and download write and read them:
 * `VEYQ_LIVE_DEPOT=<root folder>` uploads a text file of three frames ("App file <n>.txt") there, reads it back in a fresh
 * session to the byte with its sealed type, name and modified time, checks a second upload of the name is answered as the
 * file it is, replaces its content (the old blob gone) and reads that back; the file is trashed after unless
 * `VEYQ_LIVE_DEPOT_KEEP` is set (for the web's file page). `VEYQ_LIVE_DEPOT_READ="<root folder>/<file>"` reads a file the web
 * uploaded and prints its size and SHA-256, to hold against the original.
 */
class DepotLiveTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun aFileIsUploadedReadReplacedAndReadAgain() = runTest(timeout = 10.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.defaultTenant!!)

        System.getenv("VEYQ_LIVE_DEPOT_READ")?.let { spec ->
            val (rootName, fileName) = spec.split('/', limit = 2)
            val nodes = tenant.nodes()
            val parent = nodes.first { it.kind == "FOLDER" && it.parentId == null && it.name == rootName }
            val node = nodes.first { it.kind == DepotFiles.FILE && it.parentId == parent.nodeId && it.name == fileName }
            val file = DepotFiles.list(tenant).getValue(node.nodeId)
            val out = java.io.ByteArrayOutputStream()
            DepotFiles.read(tenant, file) { out.write(it) }
            println("[$spec] ${out.size()} bytes (listed ${file.size}), type ${file.type}, name ${file.uploadedName}, sha256 ${sha(out.toByteArray())}")
            assertEquals(file.size, out.size().toLong())
        }

        val rootName = System.getenv("VEYQ_LIVE_DEPOT") ?: return@runTest
        val parent = tenant.nodes().first { it.kind == "FOLDER" && it.parentId == null && it.name == rootName }
        val scopeId = parent.scopeId
        val n = System.currentTimeMillis() % 100_000
        val name = "App file $n.txt"
        val line = "veyq app file $n — a line of text, numbered %06d\n"
        val content = buildString { var i = 0; while (length < 600_000) append(line.format(i++)) }.encodeToByteArray()
        val modified = 1_762_000_000_000L
        fun source(bytes: ByteArray): suspend (Long, Int) -> ByteArray = { from, length -> bytes.copyOfRange(from.toInt(), from.toInt() + length) }

        val up = DepotFiles.upload(tenant, scopeId, parent.nodeId, name, content.size.toLong(), "text/plain", modified, AppKinds::tokenInput, source(content))
        assertEquals(DepotFiles.Outcome.DONE, up.outcome)
        suspend fun reread(): Pair<DepotFiles.File, ByteArray> {
            val fresh = account.open(tenant.tenant)
            val file = DepotFiles.list(fresh).getValue(up.nodeId!!)
            val out = java.io.ByteArrayOutputStream()
            DepotFiles.read(fresh, file) { out.write(it) }
            return file to out.toByteArray()
        }
        val (first, bytes) = reread()
        assertEquals(sha(content), sha(bytes), "the file does not read back to the byte")
        assertEquals(3, first.chunkCount, "600 kB is three frames")
        assertEquals(content.size.toLong(), first.size)
        assertEquals("text/plain", first.type)
        assertEquals(name, first.uploadedName)
        assertEquals(modified, first.modifiedAt)
        // the name again: the file it is, nothing sent
        val again = DepotFiles.upload(tenant, scopeId, parent.nodeId, name.uppercase(), 1, "text/plain", null, AppKinds::tokenInput, source(byteArrayOf(1)))
        assertEquals(DepotFiles.Outcome.EXISTS, again.outcome)
        assertEquals(up.nodeId, again.existing)

        val replacement = "replaced by the app at $n\n".repeat(5).encodeToByteArray()
        val rep = DepotFiles.replace(tenant, first, replacement.size.toLong(), "text/plain", name, modified + 1000, first.blobId, source(replacement))
        assertEquals(DepotFiles.Outcome.DONE, rep.outcome)
        val (second, bytes2) = reread()
        assertEquals(sha(replacement), sha(bytes2))
        assertNotEquals(first.blobId, second.blobId, "a replace attaches a new blob")
        // a replace over a content that changed meanwhile is answered as such
        val stale = DepotFiles.replace(tenant, first, 1, "text/plain", name, null, first.blobId, source(byteArrayOf(2)))
        assertEquals(DepotFiles.Outcome.STALE, stale.outcome)
        assertEquals(second.blobId, stale.currentBlobId)

        val keep = System.getenv("VEYQ_LIVE_DEPOT_KEEP") != null
        if (!keep) assertTrue(NodeTrash.delete(tenant, scopeId, up.nodeId!!) == NodeTrash.Deleted.TRASHED)
        println("[$rootName] \"$name\": uploaded in ${first.chunkCount} frames, read back, replaced (blob ${first.blobId} → ${second.blobId}), stale replace refused; ${if (keep) "kept" else "trashed"}")
    }

    /**
     * `VEYQ_LIVE_FOLDER_UPLOAD=<root folder>` uploads a directory made here ("App up", nested, a case clash, a file of three
     * frames) there twice — the second beside the first as "App up (2)" — and checks a fresh session finds every folder and
     * file where the plan put it, each file to the byte; both stay, for the web's level.
     */
    @Test
    fun aDirectoryIsUploadedAsFoldersAndFiles() = runTest(timeout = 10.minutes) {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runTest
        val rootName = System.getenv("VEYQ_LIVE_FOLDER_UPLOAD") ?: return@runTest
        val env = File(System.getProperty("veyq.repoRoot"), ".run/.env").readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        val tenant = account.open(account.defaultTenant!!)
        val parent = tenant.nodes().first { it.kind == "FOLDER" && it.parentId == null && it.name == rootName }
        val big = ByteArray(600_000) { (it * 13).toByte() }
        val files = linkedMapOf(
            "readme.txt" to "hello".encodeToByteArray(), "Docs/plan.txt" to "plan".encodeToByteArray(),
            "Docs/PLAN.TXT" to "PLAN".encodeToByteArray(), "Docs/Deep/big.bin" to big,
        )
        val entries = files.map { (path, bytes) ->
            FolderUpload.Entry(path, bytes.size.toLong(), "application/octet-stream", 1_700_000_000_000L) { from, n -> bytes.copyOfRange(from.toInt(), from.toInt() + n) }
        }
        val names = mutableListOf<String>()
        repeat(2) {
            val r = FolderUpload.upload(tenant, parent.scopeId, parent.nodeId, "App up", entries, AppKinds::tokenInput)
            assertEquals(FolderUpload.Outcome.DONE, r.outcome, "${r.failedFolders} ${r.failedFiles}")
            assertEquals(4, r.uploaded)
            names += r.name
        }
        assertEquals("App up (2)", names[1], "the second goes beside the first")
        val fresh = account.open(tenant.tenant)
        val nodes = fresh.nodes()
        val top = nodes.first { it.parentId == parent.nodeId && it.name == names[0] && it.kind == "FOLDER" }
        fun child(of: String, name: String) = nodes.first { it.parentId == of && it.name == name }
        val docs = child(top.nodeId, "Docs")
        val listed = DepotFiles.list(fresh)
        suspend fun bytes(id: String): ByteArray {
            val o = java.io.ByteArrayOutputStream()
            DepotFiles.read(fresh, listed.getValue(id)) { o.write(it) }
            return o.toByteArray()
        }
        assertEquals("hello", bytes(child(top.nodeId, "readme.txt").nodeId).decodeToString())
        assertEquals("plan", bytes(child(docs.nodeId, "plan.txt").nodeId).decodeToString())
        assertEquals("PLAN", bytes(child(docs.nodeId, "PLAN (2).TXT").nodeId).decodeToString())
        assertEquals(sha(big), sha(bytes(child(child(docs.nodeId, "Deep").nodeId, "big.bin").nodeId)))
        println("[$rootName] uploaded \"${names[0]}\" and \"${names[1]}\": every folder and file where planned, to the byte")
    }
}
