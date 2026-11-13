package me.raddatz.veyq.mobile.memories

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.navigation.Home
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.testDevice
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Dev tooling, not a test of the product: makes and removes a Memory of the seeded principal for a device pass of the
 * apps' Memories (`cr-memories-on-every-app` phase 1), where the phones then read what is in it. Runs only with
 * `VEYQ_LIVE` and one of:
 * - `VEYQ_DEV_MEMORY_FILL=<directory>`: an "App memory <n>" made at the default space's top by `ShellModel.createMemory`
 *   and every file of the directory uploaded into it by `MediaUpload.upload`, in name order, as a platform uploads it —
 *   the original read from the file a range at a time (a video of hundreds of MiB is never held whole), its tiers the
 *   sidecars `<file>.preview.jpg` and `<file>.thumb.jpg` where they exist (made beforehand, e.g. by ffmpeg); the Memory's
 *   name and each upload's outcome printed.
 * - `VEYQ_DEV_MEMORY_PURGE=<name>`: that Memory trashed and purged.
 * The principal is `VEYQ_ENV_FILE`'s, or this checkout's `.run/.env`.
 */
class DevMemoryTest {
    @Test
    fun makeOrRemoveAMemoryForADevicePass() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val fill = System.getenv("VEYQ_DEV_MEMORY_FILL")?.let(::File)
        val purge = System.getenv("VEYQ_DEV_MEMORY_PURGE")
        if (fill == null && purge == null) return@runBlocking
        val env = (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            testDevice(BouncyCastlePrimitives),
        ) { block -> HttpClient(OkHttp, block) }
        try {
            val tenant = account.open(account.defaultTenant!!)
            if (purge != null) {
                val memory = assertNotNull(tenant.nodes().firstOrNull { it.kind == "MEMORY" && it.name == purge }, "no Memory $purge")
                NodeTrash.delete(tenant, memory.scopeId, memory.nodeId)
                NodeTrash.purge(tenant, memory.scopeId, memory.nodeId)
                println("dev memory: $purge trashed and purged")
                return@runBlocking
            }
            val shell = me.raddatz.veyq.mobile.navigation.ShellModel(
                tenant, me.raddatz.veyq.mobile.navigation.Hidden({}, emptySet()), me.raddatz.veyq.mobile.navigation.RecentUses({}, emptyList()),
            ) { System.currentTimeMillis() }
            shell.load(openLastUsed = false)
            val scopeId = assertNotNull(Home.defaultSpace(shell.state.first { it != null }!!.lists.spaces)?.scopeId, "no space")
            val name = "App memory ${System.currentTimeMillis() % 100_000}"
            val memory = assertNotNull(shell.createMemory(scopeId, null, name).node, "the Memory was not made")
            println("dev memory: made $name")
            val files = fill!!.listFiles()!!.filter { it.isFile && !it.name.endsWith(".preview.jpg") && !it.name.endsWith(".thumb.jpg") }.sortedBy { it.name }
            for (f in files) {
                val sidecar = { suffix: String -> File(f.parentFile, f.name + suffix).takeIf { it.isFile }?.readBytes() }
                val raf = RandomAccessFile(f, "r")
                val source = ByteSource(raf.length()) { from, length ->
                    synchronized(raf) {
                        val n = minOf(length.toLong(), raf.length() - from).toInt()
                        ByteArray(n).also { raf.seek(from); raf.readFully(it) }
                    }
                }
                val up = raf.use {
                    MediaUpload.upload(
                        tenant, scopeId, memory.nodeId,
                        MediaUpload.Original(source, null, f.name, f.lastModified()),
                        MediaUpload.Derived(sidecar(".preview.jpg"), sidecar(".thumb.jpg"), null, null),
                        System.currentTimeMillis(),
                    )
                }
                println("dev memory: ${f.name} (${f.length()} B) ${up.outcome}")
                assertEquals(MediaUpload.Outcome.DONE, up.outcome, up.code)
            }
        } finally {
            account.signOut()
        }
    }
}
