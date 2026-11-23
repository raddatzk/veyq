package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.media.Derivation
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.memories.place.MemoryFacts
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Memory's participants and a medium's capture time against a running dev instance (ADR 0144 decisions 1–3;
 * `VEYQ_LIVE`, skipped without one). The dev principal makes a Memory with *I was there* in its tenant's own space — born
 * with its maker listed, at its first revision. **Two sessions at once:** each holds the Memory's tree, the second adds a
 * participant, and the first then adds another from its stale tree — the `409` merges, so both are kept; then one session
 * removes a participant from a stale tree while the other adds one — the removal and the addition both hold. **A capture
 * time:** a photo (the web's own EXIF fixture) corrected to a minute and reset to its original.
 *
 * The ids added beside the maker are made up: the write's merge is under test, and the server cannot tell an id (the list
 * is sealed); only the app's picker limits the pick to the scope's members. The Memory is trashed and purged and every
 * device taken off the account however the case ends.
 */
class CurationLiveTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    private val client: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> HttpClient(OkHttp, block) }

    private val root = File(System.getProperty("veyq.repoRoot"))
    private fun fixture(name: String) = File(root, "src/web/src/features/memories/__fixtures__/$name").readBytes()
    private fun source(bytes: ByteArray) = ByteSource(bytes.size.toLong()) { from, length ->
        bytes.copyOfRange(from.toInt(), minOf(bytes.size.toLong(), from + length).toInt())
    }

    @Test
    fun participantsMergeAcrossTwoSessionsAndACaptureTimeIsCorrectedAndReset() = runBlocking {
        val origin = liveRun.origin
        org.junit.Assume.assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        val p = BouncyCastlePrimitives
        val env = liveRun.env
        suspend fun signIn(label: String, device: ThisDevice = liveRun.device(label)) =
            VeyqAccount.signIn(p, origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), device, client)
        val one = signIn("live curation test")
        val tenant = one.open(one.defaultTenant!!)
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { System.currentTimeMillis() }
        shell.load(openLastUsed = false)
        val spaces = shell.state.first { it != null }!!.lists.spaces
        val scopeId = assertNotNull(spaces.firstOrNull { it.kind == "TENANT" }?.scopeId, "no tenant space")
        assertTrue(shell.offersWasThere(scopeId), "a tenant space offers I was there")
        val me = one.userId
        val n = System.currentTimeMillis() % 100_000
        val made = shell.createMemory(scopeId, null, "Curation live $n", wasThere = true)
        val memory = assertNotNull(made.node, "the Memory was not made: ${made.notice}").nodeId
        try {
            // born with its maker listed, in its first meta: one revision, no second write
            tenant.forgetTree()
            val born = assertNotNull(tenant.node(scopeId, memory))
            assertEquals(listOf(me), MemoryFacts.read(tenant.account.suite, scopeId, memory, born.key, born.wrappedMeta).participants)
            assertEquals(0L, born.metaRevision, "sealed at birth, never written again")

            // two sessions add different participants at once: both kept
            val two = signIn("live curation second session")
            val other = two.open(two.defaultTenant!!)
            val x = "11111111-aaaa-4aaa-8aaa-${n.toString().padStart(12, '0')}"
            val y = "22222222-bbbb-4bbb-8bbb-${n.toString().padStart(12, '0')}"
            val z = "33333333-cccc-4ccc-8ccc-${n.toString().padStart(12, '0')}"
            assertNotNull(tenant.node(scopeId, memory), "the first session holds the tree")
            assertNotNull(other.node(scopeId, memory), "the second session holds the tree")
            assertEquals(MemoryFacts.Outcome.Written, Participants.set(other, scopeId, memory, listOf(me, x), listOf(me)))
            assertEquals(MemoryFacts.Outcome.Written, Participants.set(tenant, scopeId, memory, listOf(me, y), listOf(me)), "a stale tree's add merges")
            other.forgetTree()
            assertEquals(listOf(me, x, y), MemoryFacts.of(other, scopeId, memory)?.participants, "both additions kept")

            // one removes from a stale tree while the other adds: the removal and the addition both hold
            assertNotNull(other.node(scopeId, memory), "the second session's tree, read now")
            tenant.forgetTree()
            assertEquals(MemoryFacts.Outcome.Written, Participants.set(tenant, scopeId, memory, listOf(me, x, y, z), listOf(me, x, y)))
            assertEquals(MemoryFacts.Outcome.Written, Participants.set(other, scopeId, memory, listOf(me, y), listOf(me, x, y)), "a stale tree's removal merges")
            tenant.forgetTree()
            assertEquals(listOf(me, y, z), MemoryFacts.of(tenant, scopeId, memory)?.participants, "x removed, z kept")
            two.signOut()

            // a capture time corrected and reset
            val up = MediaUpload.upload(
                tenant, scopeId, memory, MediaUpload.Original(source(fixture("exif-sample.jpg")), "image/jpeg", "Munich.jpg", 1_000_000),
                Derivation.Derived(null, null, null, null), System.currentTimeMillis(),
            )
            assertEquals(MediaUpload.Outcome.DONE, up.outcome, up.code)
            suspend fun photo() = assertNotNull(MemoryMedia.list(tenant, scopeId, memory)?.items?.singleOrNull(), "the photo is listed")
            val original = assertNotNull(photo().meta?.capturedAt, "the photo's own capture time")
            val corrected = 1_700_000_040_000L + 17_345
            assertEquals(CaptureTime.Outcome.Done(CaptureTime.minute(corrected)), CaptureTime.set(tenant, photo(), corrected))
            val after = photo()
            assertEquals(CaptureTime.minute(corrected), after.meta?.capturedAtOverride)
            assertEquals(CaptureTime.minute(corrected), after.meta?.effectiveCapturedAt)
            assertEquals(original, after.meta?.capturedAt, "the original kept beside it")
            assertEquals(CaptureTime.Outcome.Done(null), CaptureTime.reset(tenant, after))
            val reset = photo()
            assertNull(reset.meta?.capturedAtOverride)
            assertEquals(original, reset.meta?.effectiveCapturedAt, "the original counts again")
        } finally {
            runCatching { NodeTrash.delete(tenant, scopeId, memory) }
            runCatching { NodeTrash.purge(tenant, scopeId, memory) }
            runCatching { one.signOut() }
        }
    }
}
