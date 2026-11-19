package me.raddatz.veyq.mobile.memories

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.memories.banner.Banner
import me.raddatz.veyq.mobile.memories.banner.BannerWrite
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.ShareLinks
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
 * A Memory left due is re-keyed by the app (ADR 0149 decision 2), on a running dev instance as a throwaway with a member
 * (`VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`; [Throwaway]): the owner makes a Memory in the tenant's space with a photo and an
 * image banner, then closes a link on it without re-keying (the server marks it due, as a rotation does). The MEMBER's
 * upload into it then meets `node_rekey_pending`, pulls the Memory forward on its own device — `MemoryRekey` moving the
 * banner's wraps, the photo's sealed tiers — and lands; nothing is due after, and a fresh session of the owner reads every
 * medium to the byte and the banner's picture. The Memory stays in the throwaway, erased with it.
 */
class MemoryRekeyLiveTest {
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Test
    fun aMemberUploadPullsADueMemoryForward_theWebsRekeyNoLongerNeeded() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertTrue(t.hasMember, "the throwaway has no member: create it with --with-member")
        val domain = t.env.getValue("VEYQ_TENANT_DOMAIN")
        val root = File(System.getProperty("veyq.repoRoot"))
        runTest(timeout = 6.minutes) {
            suspend fun session(who: String): TenantSession {
                val a = VeyqAccount.signIn(
                    BouncyCastlePrimitives, origin, t.email(who), t.env.getValue("${who}_PASSWORD"), t.env.getValue("${who}_SECRET_KEY"), liveRun.device(),
                ) { block -> HttpClient(OkHttp, block) }
                return a.open(a.tenants.first { it.domain == domain }).also { it.rekeyRules = AppKinds.rules }
            }
            val owner = session("VEYQ")
            val scopeId = owner.spaces().first { it.kind == "TENANT" }.scopeId
            val name = "App rekey ${System.currentTimeMillis() % 100_000}"
            val made = assertNotNull(MemoryCreate.create(owner, scopeId, null, MemoryCreate.MEMORY, name).node, "the Memory was not made")
            val photo = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
            fun source(b: ByteArray) = ByteSource(b.size.toLong()) { from, length -> b.copyOfRange(from.toInt(), minOf(b.size.toLong(), from + length).toInt()) }
            val first = MediaUpload.upload(
                owner, scopeId, made.nodeId, MediaUpload.Original(source(photo), "image/jpeg", "Before.jpg", 1_000_000),
                MediaUpload.Derived(photo, photo, 4032, 3024), System.currentTimeMillis(),
            )
            assertEquals(MediaUpload.Outcome.DONE, first.outcome, first.code)
            val picked = BannerWrite.Picked(UploadSource.of("banner.jpg", photo.size.toLong(), "image/jpeg", null) { from, length ->
                photo.copyOfRange(from.toInt(), minOf(photo.size.toLong(), from + length).toInt())
            }, Banner.Medium.IMAGE, "image/jpeg", photo)
            assertEquals(BannerWrite.Outcome.Done, BannerWrite.set(owner, scopeId, made.nodeId, Banner.Slots.NONE, picked, emptyList()))

            // a link closed without its re-key: the Memory is due, as after a member's removal
            val link = ShareLinks.mint(owner, scopeId, made.nodeId, ShareLinks.READ, "App rekey", "", null)
            assertEquals(ShareLinks.Outcome.CREATED, link.outcome, link.refusal)
            val shareId = link.url!!.substringAfter("/s/").substringBefore('#')
            assertTrue(owner.account.respond { me.raddatz.veyq.mobile.api.apis.SharingApi(owner.base, owner.account.http).revokeShare(shareId) }.success)
            val rekeyApi = me.raddatz.veyq.mobile.api.apis.RekeyApi(owner.base, owner.account.http)
            suspend fun due(): Boolean = owner.account.call { rekeyApi.listRekeyNodes(scopeId) }.nodes.first { it.id == made.nodeId }.due
            assertTrue(due(), "the close left the Memory due")

            // the member's upload meets the lag, pulls the Memory forward on its own device and lands
            val member = session("VEYQ_USER2")
            val second = MediaUpload.upload(
                member, scopeId, made.nodeId, MediaUpload.Original(source(photo), "image/jpeg", "After.jpg", 2_000_000),
                MediaUpload.Derived(photo, photo, 4032, 3024), System.currentTimeMillis(),
            )
            assertEquals(MediaUpload.Outcome.DONE, second.outcome, second.code)
            assertTrue(!due(), "the Memory is still due")

            val fresh = session("VEYQ")
            val node = assertNotNull(fresh.node(scopeId, made.nodeId))
            val gallery = assertNotNull(MemoryMedia.list(fresh, scopeId, made.nodeId))
            assertEquals(setOf(first.mediaId, second.mediaId), gallery.items.map { it.mediaId }.toSet())
            for (item in gallery.items) {
                assertTrue(MemoryMedia.read(fresh, item, item.original)!!.contentEquals(photo), "${item.name}'s original to the byte")
                assertEquals(4032, item.meta?.width, "${item.name}'s listed tier opens under the new key")
            }
            assertTrue(Banner.picture(fresh, node)!!.contentEquals(photo), "the banner's picture opens after the re-key")
            println("[live] \"$name\": due after the close, pulled forward by the member's upload, every medium and the banner open")
        }
    }
}
