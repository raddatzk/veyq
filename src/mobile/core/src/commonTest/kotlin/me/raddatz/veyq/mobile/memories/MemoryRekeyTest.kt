package me.raddatz.veyq.mobile.memories

import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.api.memories.models.MediaRekeyPage
import me.raddatz.veyq.mobile.api.memories.models.MediaRekeyStage
import me.raddatz.veyq.mobile.api.memories.models.RekeyBucketName
import me.raddatz.veyq.mobile.api.memories.models.RekeyMedium
import me.raddatz.veyq.mobile.api.models.ApplicationBlobRekey
import me.raddatz.veyq.mobile.api.models.ApplicationBlobRekeyPage
import me.raddatz.veyq.mobile.api.models.CommitNodeRekeyRequest
import me.raddatz.veyq.mobile.api.models.NodeRekeyParts
import me.raddatz.veyq.mobile.api.models.NodeRekeyStage
import me.raddatz.veyq.mobile.api.models.RekeyBlob
import me.raddatz.veyq.mobile.api.models.RekeyNode
import me.raddatz.veyq.mobile.api.models.RekeyNodeList
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.OpenRefused
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.session.NodeRekey
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Memories' part of a node's re-key (ADR 0109 point 2, ADR 0149 decision 2) through the REAL crypto. The first cases are
 * the web's `rekey.test.ts` twinned over a played transport; the last runs core's whole driver (`NodeRekey`) over a played
 * server on the cross-surface fixture's meta (ADR 0143 D5, ADR 0144): every plaintext byte for byte after, every `K_blob`
 * kept with only its wrap moved, the banner's wraps moved inside the one meta core seals, a field no surface knows carried.
 */
class MemoryRekeyTest {
    private val p = testPrimitives
    private val suite = V1(p)
    private val scope = "7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c"
    private val node = "4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d"
    private val live = "2a9b1d4a-6e8b-4c1f-8d3e-4a5b6c7c3f0e"
    private val trashed = "3b0c2e5b-7f9c-4d20-8e4f-5b6c7d8d4a1f"
    private val sub = "5b6c7c3f-0e2a-4b1d-8a6e-8b2c1f2d3e4a"
    private val primary = "6c7d8d4a-1f3b-4c2e-9b7f-9c3d2a3e4f5b"

    private fun metaContext(mediaId: String) = Context("media-meta", "scopeId" to scope, "mediaId" to mediaId)
    private fun exifContext(mediaId: String) = Context("media-exif", "scopeId" to scope, "mediaId" to mediaId)
    private fun bucketContext(bucketId: String) = Context("bucket-name", "scopeId" to scope, "nodeId" to node, "bucketId" to bucketId)
    private fun blobContext(blobId: String, role: String, owner: String = node) =
        Context("blob-key", "scopeId" to scope, "ownerId" to owner, "blobId" to blobId, "role" to role)
    private fun field(c: Context, key: ByteArray, text: String) = b64(suite.sealField(c, key, text.encodeToByteArray()))
    private fun opened(c: Context, key: ByteArray, sealed: String) = suite.openField(c, key, unb64(sealed)).decodeToString()

    /** Two pages: the live medium with its EXIF and the buckets on the first, the trashed one (the listing holds both) on the second. */
    private fun pages(oldKey: ByteArray) = listOf(
        MediaRekeyPage(
            media = listOf(RekeyMedium(live, 3, field(metaContext(live), oldKey, """{"v":1,"t":1}"""), field(exifContext(live), oldKey, """{"Make":"x"}"""))),
            buckets = listOf(
                RekeyBucketName(sub, field(bucketContext(sub), oldKey, "Tag 1")),
                // a contributor's primary: the server's copy of the guest's name — never staged, even where it would open here
                RekeyBucketName(primary, field(bucketContext(primary), oldKey, "Guest"), shareId = "A".repeat(43), identityId = "8d9e0f1a-2b3c-4d4e-8f5a-6b7c8d9e0f1a"),
            ),
            more = true, cursor = "1",
        ),
        MediaRekeyPage(
            media = listOf(RekeyMedium(trashed, 0, field(metaContext(trashed), oldKey, """{"v":1,"capturedAt":5}"""), null)),
            buckets = listOf(RekeyBucketName(sub, field(bucketContext(sub), oldKey, "Tag 1"))),
            more = false, cursor = null,
        ),
    )

    @Test
    fun everyMemoriesFieldIsReSealedUnderTheNewKey_aContributorsPrimaryIsLeftToTheServer() = runTest {
        val oldKey = p.randomBytes(32)
        val newKey = p.randomBytes(32)
        val kBanner = p.randomBytes(32)
        val bannerBlob = "0b1c2d3e-4f50-4617-8899-aabbccddeeff"
        val listed = pages(oldKey)
        val staged = mutableListOf<MediaRekeyStage>()
        val meta = Json.parseToJsonElement(
            """{"v":1,"banner":{"medium":"image","blob":{"blobId":"$bannerBlob","chunkCount":1,"byteSize":1,"wrappedBlobKey":"${b64(suite.seal(blobContext(bannerBlob, "BANNER"), oldKey, kBanner))}"}}}""",
        ) as JsonObject
        val moved = MemoryRekey.move(suite, scope, node, 0, oldKey, newKey, meta, { since -> listed[since?.toInt() ?: 0] }, { staged += it })

        // a medium's blob wraps are core's blob service's (ADR 0113): Memories stages its listed tier and EXIF only
        val media = staged.flatMap { it.media.orEmpty() }
        assertEquals(listOf(live, trashed), media.map { it.mediaId }, "the live medium and the trashed one, every page")
        val m = media.first()
        assertEquals(3L, m.metaRevision)
        assertEquals("""{"v":1,"t":1}""", opened(metaContext(live), newKey, m.wrappedMeta!!))
        assertFailsWith<OpenRefused>("not under the old key") { opened(metaContext(live), oldKey, m.wrappedMeta!!) }
        assertEquals("""{"Make":"x"}""", opened(exifContext(live), newKey, m.wrappedExif!!))
        assertEquals("""{"v":1,"capturedAt":5}""", opened(metaContext(trashed), newKey, media[1].wrappedMeta!!), "the trashed medium's tier, byte for byte")
        assertEquals(null, media[1].wrappedExif)

        val buckets = staged.flatMap { it.buckets.orEmpty() }
        assertEquals(listOf(sub), buckets.map { it.bucketId }, "staged once; the contributor's primary is the server's copy")
        assertEquals("Tag 1", opened(bucketContext(sub), newKey, buckets.single().sealedName))

        val wrap = (((moved!!["banner"] as JsonObject)["blob"] as JsonObject)["wrappedBlobKey"] as JsonPrimitive).content
        assertContentEquals(kBanner, suite.open(blobContext(bannerBlob, "BANNER"), newKey, unb64(wrap)), "the banner's K_blob kept, its wrap moved")
        assertFailsWith<OpenRefused> { suite.open(blobContext(bannerBlob, "BANNER"), oldKey, unb64(wrap)) }
        assertEquals(listOf(0), staged.map { it.fromEpoch }.distinct())
    }

    @Test
    fun aFieldThatDoesNotOpenFailsTheReKey_nothingStaged() = runTest {
        val oldKey = p.randomBytes(32)
        val staged = mutableListOf<MediaRekeyStage>()
        val foreign = MediaRekeyPage(listOf(RekeyMedium(live, 0, field(metaContext(live), p.randomBytes(32), "{}"))), emptyList(), more = false)
        assertFailsWith<OpenRefused> { MemoryRekey.move(suite, scope, node, 0, oldKey, p.randomBytes(32), null, { foreign }, { staged += it }) }
        assertEquals(emptyList(), staged)
    }

    @Test
    fun aMetaWithoutABannerIsLeftAsItIs_andAnEmptyNodeStagesNothing() = runTest {
        val staged = mutableListOf<MediaRekeyStage>()
        val meta = Json.parseToJsonElement("""{"v":1,"banner":{"medium":"later-shape"},"x":[1]}""") as JsonObject
        val moved = MemoryRekey.move(suite, scope, node, 2, p.randomBytes(32), p.randomBytes(32), meta, { MediaRekeyPage(emptyList(), emptyList(), false) }, { staged += it })
        assertEquals(meta.toString(), moved.toString())
        assertEquals(emptyList(), staged)
    }

    // ---- core's driver over the cross-surface fixture

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun retire() = played.forEach { it.route = null }

    @Test
    fun theDriverReKeysAMemory_theBannersWrapsMovedInTheOneMetaCoreSeals_everyOtherByteKept() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val memory = t.id("memory")
        val oldKey = p.randomBytes(32)
        val cover = t.id("cover")
        val banner = t.id("banner")
        val poster = t.id("poster")
        val original = t.id("original")
        val kCover = p.randomBytes(32)
        val kBanner = p.randomBytes(32)
        val kPoster = p.randomBytes(32)
        val kOriginal = p.randomBytes(32)
        fun wrap(id: String, role: String, k: ByteArray, owner: String = memory) =
            b64(suite.seal(Context("blob-key", "scopeId" to s.id, "ownerId" to owner, "blobId" to id, "role" to role), oldKey, k))
        // the node's meta as the web's `sealNodeMeta` writes it — the fixture's, its wraps real
        val webMeta = """{"v":1,"date":{"mode":"range","from":"2026-05-01","to":"2026-05-04"},""" +
            """"cover":{"source":{"kind":"upload","ref":{"blobId":"$cover","chunkCount":1,"byteSize":48231,""" +
            """"wrappedBlobKey":"${wrap(cover, "COVER", kCover)}","ownerId":"$memory","role":"COVER"}},"crop":{"x":0,"y":0.125,"width":1,"height":0.75}},""" +
            """"participants":{"userIds":["u1","u2"],"futureSub":1},""" +
            """"banner":{"medium":"video","blob":{"blobId":"$banner","chunkCount":3,"byteSize":9437184,"wrappedBlobKey":"${wrap(banner, "BANNER", kBanner)}"},""" +
            """"poster":{"blobId":"$poster","chunkCount":1,"byteSize":81234,"wrappedBlobKey":"${wrap(poster, "BANNER_POSTER", kPoster)}"},"type":"video/mp4",""" +
            """"sha256":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","frame":"fit","focal":{"x":0.5,"y":0.4375}},""" +
            """"futureField":{"a":[1,"x",null],"b":{"c":true},"n":2.5e-7}}"""
        val binding = arrayOf<Pair<String, Any>>("scopeId" to s.id, "nodeId" to memory, "epoch" to 0L, "placementRevision" to 0L)
        val listed = pages(oldKey).map { page ->
            // the twin's rows, re-bound to this scope and node
            fun reseal(c: (String) -> Context, id: String, sealed: String?, from: Context) = sealed?.let { field(c(id), oldKey, suite.openField(from, oldKey, unb64(it)).decodeToString()) }
            page.copy(
                media = page.media.map { m ->
                    m.copy(
                        wrappedMeta = reseal({ Context("media-meta", "scopeId" to s.id, "mediaId" to it) }, m.mediaId, m.wrappedMeta, metaContext(m.mediaId)),
                        wrappedExif = reseal({ Context("media-exif", "scopeId" to s.id, "mediaId" to it) }, m.mediaId, m.wrappedExif, exifContext(m.mediaId)),
                    )
                },
                buckets = page.buckets.map { b -> b.copy(sealedName = field(Context("bucket-name", "scopeId" to s.id, "nodeId" to memory, "bucketId" to b.bucketId), oldKey, "Tag 1")) },
            )
        }
        var committed: CommitNodeRekeyRequest? = null
        val nodeStages = mutableListOf<NodeRekeyStage>()
        val mediaStages = mutableListOf<MediaRekeyStage>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fun body(r: io.ktor.client.request.HttpRequestData) = (r.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        val base = "/api/scopes/${s.id}"
        t.route = { r ->
            val path = r.url.encodedPath
            when {
                path == "$base/rekey/nodes" -> 200 to json.encodeToString(
                    RekeyNodeList.serializer(),
                    RekeyNodeList(0, listOf(RekeyNode(
                        id = memory, kind = "MEMORY", trashed = false, ownerOnly = false, epoch = 0, scopeEpoch = 0, due = committed == null,
                        placementRevision = 0, metaRevision = 4,
                        encryptedName = b64(suite.sealField(Context("node-name", *binding), s.key, "Lisboa".encodeToByteArray())),
                        slugToken = "c2x1Zw==", wrappedContentKey = b64(suite.seal(Context("content-key", *binding), s.key, oldKey)),
                        wrappedMeta = b64(suite.sealField(Context("node-meta", "scopeId" to s.id, "nodeId" to memory), oldKey, webMeta.encodeToByteArray())),
                    ))),
                )
                path == "$base/rekey/nodes/$memory" -> 200 to json.encodeToString(
                    NodeRekeyParts.serializer(),
                    NodeRekeyParts(listOf(RekeyBlob(cover, "COVER", wrap(cover, "COVER", kCover)), RekeyBlob(banner, "BANNER", wrap(banner, "BANNER", kBanner))), emptyList(), emptyList()),
                )
                path == "$base/nodes/$memory/application-blobs/rekey" -> 200 to json.encodeToString(
                    ApplicationBlobRekeyPage.serializer(),
                    ApplicationBlobRekeyPage(listOf(ApplicationBlobRekey(original, "ORIGINAL", live, wrap(original, "ORIGINAL", kOriginal, owner = live))), more = false),
                )
                path == "$base/nodes/$memory/media-rekey" -> 200 to json.encodeToString(MediaRekeyPage.serializer(), listed[r.url.parameters["since"]?.toInt() ?: 0])
                path.startsWith("$base/nodes/$memory/media-rekey/") -> { mediaStages += json.decodeFromString(MediaRekeyStage.serializer(), body(r)); 204 to "" }
                path.startsWith("$base/rekey/nodes/$memory/") && path.endsWith("/parts") -> { nodeStages += json.decodeFromString(NodeRekeyStage.serializer(), body(r)); 204 to "" }
                path.startsWith("$base/rekey/nodes/$memory/") -> { committed = json.decodeFromString(CommitNodeRekeyRequest.serializer(), body(r)); 204 to "" }
                else -> null
            }
        }
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        var n = 0
        val result = NodeRekey.rekeyScope(tenant, s.id, AppKinds.rekeys, AppKinds::tokenInput) { t.id("rekey-${n++}") }
        assertEquals(NodeRekey.Outcome.DONE, result.outcome)
        assertEquals(1, result.committed)

        val commit = assertNotNull(committed)
        val newKey = suite.open(Context("content-key", "scopeId" to s.id, "nodeId" to memory, "epoch" to 0L, "placementRevision" to 1L), s.key, unb64(commit.wrappedContentKey))
        val after = Json.parseToJsonElement(
            suite.openField(Context("node-meta", "scopeId" to s.id, "nodeId" to memory), newKey, unb64(commit.wrappedMeta!!)).decodeToString(),
        ) as JsonObject
        val before = Json.parseToJsonElement(webMeta) as JsonObject
        assertEquals(before.keys.toList(), after.keys.toList(), "every field kept, in its place")
        for (k in before.keys - setOf("cover", "banner")) assertEquals(before.getValue(k).toString(), after.getValue(k).toString(), k)

        // the banner (bites a and d): both wraps moved in the meta core sealed, the K_blobs kept, every other field verbatim
        val b = after.getValue("banner") as JsonObject
        fun refWrap(o: JsonObject, f: String) = unb64(((o[f] as JsonObject)["wrappedBlobKey"] as JsonPrimitive).content)
        assertContentEquals(kBanner, suite.open(Context("blob-key", "scopeId" to s.id, "ownerId" to memory, "blobId" to banner, "role" to "BANNER"), newKey, refWrap(b, "blob")), "the banner's wrap moved")
        assertContentEquals(kPoster, suite.open(Context("blob-key", "scopeId" to s.id, "ownerId" to memory, "blobId" to poster, "role" to "BANNER_POSTER"), newKey, refWrap(b, "poster")), "the poster's wrap moved")
        fun withoutWraps(o: JsonObject) = JsonObject(o.mapValues { (_, v) -> if (v is JsonObject && "wrappedBlobKey" in v) JsonObject(v - "wrappedBlobKey") else v }).toString()
        assertEquals(withoutWraps(before.getValue("banner") as JsonObject), withoutWraps(b))
        val ref = ((after.getValue("cover") as JsonObject)["source"] as JsonObject)["ref"] as JsonObject
        assertContentEquals(kCover, suite.open(Context("blob-key", "scopeId" to s.id, "ownerId" to memory, "blobId" to cover, "role" to "COVER"), newKey, unb64((ref["wrappedBlobKey"] as JsonPrimitive).content)))

        // every K_blob kept: the attachments' and the media's wraps, staged by core, open under the new key to the same key
        val wraps = nodeStages.flatMap { it.blobs.orEmpty() }.associate { it.blobId to unb64(it.wrappedBlobKey) }
        assertContentEquals(kOriginal, suite.open(Context("blob-key", "scopeId" to s.id, "ownerId" to live, "blobId" to original, "role" to "ORIGINAL"), newKey, wraps.getValue(original)))
        assertContentEquals(kBanner, suite.open(Context("blob-key", "scopeId" to s.id, "ownerId" to memory, "blobId" to banner, "role" to "BANNER"), newKey, wraps.getValue(banner)))

        // Memories' rows: the live and the trashed medium byte for byte, the sub-bucket once, the primary never
        val media = mediaStages.flatMap { it.media.orEmpty() }
        assertEquals(listOf(live, trashed), media.map { it.mediaId })
        assertEquals("""{"v":1,"capturedAt":5}""", suite.openField(Context("media-meta", "scopeId" to s.id, "mediaId" to trashed), newKey, unb64(media[1].wrappedMeta!!)).decodeToString())
        assertEquals(listOf(sub), mediaStages.flatMap { it.buckets.orEmpty() }.map { it.bucketId })
        account.retire()
    }
}
