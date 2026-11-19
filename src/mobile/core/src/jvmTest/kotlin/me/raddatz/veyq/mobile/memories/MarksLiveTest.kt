package me.raddatz.veyq.mobile.memories

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
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Item marks and contributions on a running dev instance as a throwaway with a member (ADR 0149 decisions 4 and 6;
 * `VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`): the owner and the member mark the same photo of a Memory in the tenant's space —
 * each session then counts two and calls only its own mark its own —; the member makes a sub-bucket, moves its own photo
 * into it, and the owner reads the bucket by its name and the photo in it. Everything stays in the throwaway, erased with it.
 */
class MarksLiveTest {
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Test
    fun twoMembersMarkOnePhoto_andASubBucketIsMadeAndFilled() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertTrue(t.hasMember, "the throwaway has no member: create it with --with-member")
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val root = File(System.getProperty("veyq.repoRoot"))
        runTest(timeout = 5.minutes) {
            suspend fun session(who: String): TenantSession {
                val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(who), t.env.getValue("${who}_PASSWORD"), t.env.getValue("${who}_SECRET_KEY"), liveRun.device()) { block -> HttpClient(OkHttp, block) }
                return a.open(a.tenants.first { it.domain == domain }).also { it.rekeyRules = AppKinds.rules }
            }
            val owner = session("VEYQ")
            val member = session("VEYQ_USER2")
            val scopeId = owner.spaces().first { it.kind == "TENANT" }.scopeId
            val memory = assertNotNull(MemoryCreate.create(owner, scopeId, null, MemoryCreate.MEMORY, "App marks ${System.currentTimeMillis() % 100_000}").node).nodeId
            val photo = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
            fun source(b: ByteArray) = ByteSource(b.size.toLong()) { from, length -> b.copyOfRange(from.toInt(), minOf(b.size.toLong(), from + length).toInt()) }
            val ownersPhoto = MediaUpload.upload(owner, scopeId, memory, MediaUpload.Original(source(photo), "image/jpeg", "Owner.jpg", 1), MediaUpload.Derived(photo, photo, 4032, 3024), System.currentTimeMillis()).mediaId!!
            val membersPhoto = MediaUpload.upload(member, scopeId, memory, MediaUpload.Original(source(photo), "image/jpeg", "Member.jpg", 1), MediaUpload.Derived(photo, photo, 4032, 3024), System.currentTimeMillis()).mediaId!!

            for (s in listOf(owner, member)) {
                val m = ItemMarks.of(s, scopeId, memory)
                assertTrue(m.load())
                assertEquals(ItemMarks.Toggled.DONE, m.toggle(ownersPhoto))
            }
            for ((who, s) in listOf("owner" to session("VEYQ"), "member" to session("VEYQ_USER2"))) {
                val m = ItemMarks.of(s, scopeId, memory).also { assertTrue(it.load()) }
                assertEquals(2, m.count(ownersPhoto), "$who counts both marks")
                assertTrue(m.markedByMe(ownersPhoto), "$who's own mark")
                assertEquals(0, m.count(membersPhoto))
            }

            assertEquals(Buckets.Outcome.DONE, Buckets.create(member, scopeId, memory, "Sunsets", null))
            val sub = Buckets.list(member, scopeId, memory).buckets.single { !it.isPrimary }
            assertEquals(Buckets.Outcome.DONE, Buckets.move(member, scopeId, memory, listOf(membersPhoto), sub.bucketId))
            val fresh = session("VEYQ")
            val listed = Buckets.list(fresh, scopeId, memory)
            val node = assertNotNull(fresh.node(scopeId, memory))
            assertEquals("Sunsets", Buckets.name(fresh.account.suite, scopeId, memory, node.key, listed, listed.buckets.single { it.bucketId == sub.bucketId }))
            val items = assertNotNull(MemoryMedia.list(fresh, scopeId, memory)).items
            assertEquals(listOf(membersPhoto), Buckets.mediaOf(sub.bucketId, items).map { it.mediaId })
            println("[live] marks counted two in each session, mine only my own; the member's sub-bucket named and filled for the owner")
        }
    }
}
