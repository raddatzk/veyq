package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.media.Derivation
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.Throwaway
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.rotation.OpenedScope
import me.raddatz.veyq.mobile.session.rotation.ScopeRotation
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * An open session keeps working across another device's new lock of its space (inbox #1021), on a running dev instance as
 * a throwaway with a member (`VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`; [Throwaway]): session A — the owner, its tenant open —
 * makes a Memory with a photo and reads it; session B, another device of the same account, gives the tenant's space a new
 * lock (`ScopeRotation`, the rotation's scope-level move, its node re-keys held back: the Memory stays due). A, never
 * opened anew, then reads the space and the Memory's gallery under the new key, and its upload into the Memory pulls the
 * Memory's re-key forward and lands. A fresh session reads both photos to the byte. The Memory stays in the throwaway,
 * erased with it.
 */
class ScopeRelockLiveTest {
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Test
    fun anOpenSessionReadsAndUploadsAcrossAnotherDevicesNewLock() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertTrue(t.hasMember, "the throwaway has no member: create it with --with-member")
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val root = File(System.getProperty("veyq.repoRoot"))
        runTest(timeout = 6.minutes) {
            suspend fun session(): TenantSession {
                val a = VeyqAccount.signIn(
                    BouncyCastlePrimitives, origin, t.email(), t.env.getValue("VEYQ_PASSWORD"), t.env.getValue("VEYQ_SECRET_KEY"), liveRun.device(),
                ) { block -> HttpClient(OkHttp, block) }
                return a.open(a.tenants.first { it.domain == domain }).also { it.rekeyRules = AppKinds.rules }
            }
            val a = session()
            val scopeId = a.spaces().first { it.kind == "TENANT" }.scopeId
            val name = "App relock ${System.currentTimeMillis() % 100_000}"
            val made = assertNotNull(MemoryCreate.create(a, scopeId, null, MemoryCreate.MEMORY, name).node, "the Memory was not made")
            val photo = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
            fun source(b: ByteArray) = ByteSource(b.size.toLong()) { from, length -> b.copyOfRange(from.toInt(), minOf(b.size.toLong(), from + length).toInt()) }
            val first = MediaUpload.upload(
                a, scopeId, made.nodeId, MediaUpload.Original(source(photo), "image/jpeg", "Before.jpg", 1_000_000),
                Derivation.Derived(photo, photo, 4032, 3024), System.currentTimeMillis(),
            )
            assertEquals(MediaUpload.Outcome.DONE, first.outcome, first.code)
            a.forgetTree()
            assertTrue(a.nodes().any { it.nodeId == made.nodeId }, "A does not list the Memory before the new lock")
            val before = assertNotNull(a.scopeKey(scopeId)).second

            // B, another device of the account, gives the space a new lock; the nodes' re-keys are held back
            val b = session()
            val tenantId = b.tenant.tenantId
            val opener: suspend (String) -> OpenedScope? = { id -> b.scopeKey(id)?.let { (k, e) -> OpenedScope(id, tenantId, b.scopeKind(id)!!, e.toLong(), k.copyOf()) } }
            val members = assertNotNull(b.account.members(b.base, tenantId, scopeId))
            val epoch = ScopeRotation(b.account, AppKinds.scopeRekeyParticipants, AppKinds::tokenInput).commitRotation(scopeId, members, opener, opener)
            assertEquals(before + 1L, epoch)
            val rekeyApi = me.raddatz.veyq.mobile.api.apis.RekeyApi(a.base, a.account.http)
            suspend fun due(): Boolean = a.account.call { rekeyApi.listRekeyNodes(scopeId) }.nodes.first { it.id == made.nodeId }.due
            assertTrue(due(), "the new lock left the Memory due")

            // A, never opened anew: its live poll sees the tree move, and the read that follows opens the new key
            val moved = a.treeMoved(scopeId)
            println("[live] the new lock moved the tree's revision: $moved")
            a.forgetTree()
            assertTrue(a.nodes().any { it.nodeId == made.nodeId }, "A reads the space empty after the new lock")
            assertEquals(epoch.toInt(), a.scopeKey(scopeId)?.second, "A still holds the key from before the new lock")
            val listed = assertNotNull(MemoryMedia.list(a, scopeId, made.nodeId), "A's gallery could not be loaded")
            assertEquals(listOf(first.mediaId), listed.items.map { it.mediaId })

            // A's upload meets the due Memory, pulls its re-key forward on this device and lands
            val second = MediaUpload.upload(
                a, scopeId, made.nodeId, MediaUpload.Original(source(photo), "image/jpeg", "After.jpg", 2_000_000),
                Derivation.Derived(photo, photo, 4032, 3024), System.currentTimeMillis(),
            )
            assertEquals(MediaUpload.Outcome.DONE, second.outcome, second.code)
            assertTrue(!due(), "the upload did not pull the Memory's re-key")

            val fresh = session()
            val gallery = assertNotNull(MemoryMedia.list(fresh, scopeId, made.nodeId))
            assertEquals(setOf(first.mediaId, second.mediaId), gallery.items.map { it.mediaId }.toSet())
            for (item in gallery.items) assertTrue(MemoryMedia.read(fresh, item, item.original)!!.contentEquals(photo), "${item.name}'s original to the byte")
            println("[live] \"$name\": epoch $before -> $epoch under an open session; read, listed and uploaded into without a re-open")
        }
    }
}
