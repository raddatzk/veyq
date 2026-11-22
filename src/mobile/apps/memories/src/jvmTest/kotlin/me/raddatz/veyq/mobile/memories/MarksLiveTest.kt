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
    /** Each account's devices taken off that account of the throwaway (never the seeded principal's `.run/.env`). */
    private val runs = mutableMapOf<String, LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.values.forEach { it.removeAll() }

    @Test
    fun twoMembersMarkOnePhoto_andASubBucketIsMadeAndFilled() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertTrue(t.hasMember, "the throwaway has no member: create it with --with-member")
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val root = File(System.getProperty("veyq.repoRoot"))
        runTest(timeout = 5.minutes) {
            suspend fun session(who: String): TenantSession {
                val run = runs.getOrPut(who) { LiveRun(who, t.file) }
                val a = VeyqAccount.signIn(BouncyCastlePrimitives, origin, t.email(who), t.env.getValue("${who}_PASSWORD"), t.env.getValue("${who}_SECRET_KEY"), run.device()) { block -> HttpClient(OkHttp, block) }
                return a.open(a.tenants.first { it.domain == domain }).also { it.rekeyRules = AppKinds.rules }
            }
            val owner = session("VEYQ")
            val member = session("VEYQ_USER2")
            val scopeId = owner.spaces().first { it.kind == "TENANT" }.scopeId
            val memory = assertNotNull(MemoryCreate.create(owner, scopeId, null, MemoryCreate.MEMORY, "App marks ${System.currentTimeMillis() % 100_000}").node).nodeId
            val photo = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
            fun source(b: ByteArray) = ByteSource(b.size.toLong()) { from, length -> b.copyOfRange(from.toInt(), minOf(b.size.toLong(), from + length).toInt()) }
            val ownersPhoto = MediaUpload.upload(owner, scopeId, memory, MediaUpload.Original(source(photo), "image/jpeg", "Owner.jpg", 1), Derivation.Derived(photo, photo, 4032, 3024), System.currentTimeMillis()).mediaId!!
            val membersPhoto = MediaUpload.upload(member, scopeId, memory, MediaUpload.Original(source(photo), "image/jpeg", "Member.jpg", 1), Derivation.Derived(photo, photo, 4032, 3024), System.currentTimeMillis()).mediaId!!

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
            assertEquals(listOf(membersPhoto), Buckets.mediaOf(sub.bucketId, items, member.account.userId).map { it.mediaId })

            // #991, the server's rule on another member's root (ADR 0034 point 6): a member's sub-bucket under the owner's
            // primary is taken, a move across roots refused; the member's picker calls only their own root theirs
            val ownersPrimary = listed.buckets.single { it.isPrimary && it.ownerKey == Buckets.ownKey(owner.account.userId) }
            assertEquals(Buckets.Outcome.DONE, Buckets.create(member, scopeId, memory, "Curated", ownersPrimary.bucketId), "a member curates the owner's root")
            assertEquals(Buckets.Outcome.REFUSED, Buckets.move(member, scopeId, memory, listOf(ownersPhoto), sub.bucketId), "never across roots")
            val seen = Buckets.list(member, scopeId, memory)
            val rows = Buckets.rows(seen, emptyMap(), { null }, 0, member.account.userId)
            val membersPrimary = seen.buckets.single { it.isPrimary && it.ownerKey == Buckets.ownKey(member.account.userId) }.bucketId
            assertEquals(setOf(membersPrimary, sub.bucketId), rows.filter { it.own }.map { it.bucketId }.toSet())
            val curated = seen.buckets.single { it.parentBucketId == ownersPrimary.bucketId }.bucketId
            assertEquals(listOf(curated), Buckets.moveTargets(rows, ownersPrimary.bucketId).map { it.bucketId }, "the owner's root only")
            println("[live] marks counted two in each session, mine only my own; the member's sub-bucket named and filled for the owner; the member's sub-bucket under the owner's primary taken, a move across roots refused")
        }
    }
}
