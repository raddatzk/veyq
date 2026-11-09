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
