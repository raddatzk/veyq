package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.api.memories.models.MediaRekeyPage
import me.raddatz.veyq.mobile.api.memories.models.MediaRekeyStage
import me.raddatz.veyq.mobile.api.memories.models.RekeyBucketSeal
import me.raddatz.veyq.mobile.api.memories.models.RekeyMediumSeal
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.memories.banner.Banner
import me.raddatz.veyq.mobile.session.NodeRekey

/**
 * Memories' part of a node's re-key (ADR 0109 point 2, ADR 0149 decision 2; the web's `features/memories/rekey.ts`): what
 * Memories seals under a Memory's or a chapter's key moves to the node's new key, and nothing else changes — every
 * plaintext re-sealed byte for byte, every `K_blob` kept with only its wrap moved.
 *   - in the node's meta (handed over opened, [NodeRekey.RekeyContext.meta]): the banner's blob and poster wraps (owner the
 *     node, roles `BANNER` / `BANNER_POSTER`), moved in place before core seals the meta;
 *   - per medium, live and trashed: its listed tier and its EXIF (`media-meta` / `media-exif`, bound to scope + medium);
 *   - each bucket name under the node's key (`bucket-name`, bound to scope + node + bucket).
 * A medium's original and tiers are blobs of core's blob service (ADR 0113): core's driver re-wraps them with the node's other
 * application blobs. Not here: a WRITE_ONLY guest's media and primary (under its own `K_up`, which the re-key keeps — the
 * server does not list them), and a READ_WRITE contributor's primary (the server copies its name again from the identity,
 * which core's part of the link root's commit re-sealed). Staged under the commit's re-key id at the contract's caps.
 * Fails closed: a field that does not open throws, and the node is not committed.
 */
object MemoryRekey {
    /** The listing's page and the stage's cap (256 media and 256 buckets per stage): one page is one stage. */
    internal const val PAGE = 256

    /** The kind's content hook for MEMORY and CHAPTER (`AppKinds.rekeys`). */
    val content = NodeRekey.ContentRekey { ctx ->
        val account = ctx.tenant.account
        val api = MediaApi(ctx.tenant.base, account.http)
        ctx.meta = move(
            account.suite, ctx.scopeId, ctx.nodeId, ctx.fromEpoch, ctx.oldKey, ctx.newKey, ctx.meta,
            list = { since -> account.call { api.listMediaRekey(ctx.scopeId, ctx.nodeId, since, PAGE) } },
            stage = { stage ->
                val r = account.respond { api.stageMediaRekey(ctx.scopeId, ctx.nodeId, ctx.rekeyId, stage) }
                check(r.success) { "stage_media_rekey_${r.status}" }
            },
        )
    }

    /**
     * The move itself over a [list] and a [stage] (the transport the hook hands it): [meta] with the banner's wraps moved
     * (returned; null where none), every page's media and the buckets staged under [newKey].
     */
    @PlatformApi
    @HiddenFromObjC
    suspend fun move(
        suite: V1, scopeId: String, nodeId: String, fromEpoch: Int, oldKey: ByteArray, newKey: ByteArray, meta: JsonObject?,
        list: suspend (since: String?) -> MediaRekeyPage, stage: suspend (MediaRekeyStage) -> Unit,
    ): JsonObject? {
        val moved = meta?.let { rewrapBanner(suite, scopeId, nodeId, oldKey, newKey, it) }
        var since: String? = null
        var bucketsStaged = false
        while (true) {
            val page = list(since)
            val media = page.media.map { m ->
                RekeyMediumSeal(
                    mediaId = m.mediaId, metaRevision = m.metaRevision,
                    wrappedMeta = m.wrappedMeta?.let { reseal(suite, Context("media-meta", "scopeId" to scopeId, "mediaId" to m.mediaId), oldKey, newKey, it) },
                    wrappedExif = m.wrappedExif?.let { reseal(suite, Context("media-exif", "scopeId" to scopeId, "mediaId" to m.mediaId), oldKey, newKey, it) },
                )
            }
            // the buckets ride on every page: staged once (a later page's are the same rows); a contributor's primary is
            // the server's copy of its identity's name, never staged
            val buckets = if (bucketsStaged) emptyList() else page.buckets.filter { it.shareId == null }.map { b ->
                RekeyBucketSeal(b.bucketId, reseal(suite, Context("bucket-name", "scopeId" to scopeId, "nodeId" to nodeId, "bucketId" to b.bucketId), oldKey, newKey, b.sealedName))
            }
            val batches = maxOf((media.size + PAGE - 1) / PAGE, (buckets.size + PAGE - 1) / PAGE)
            for (i in 0 until batches) {
                stage(MediaRekeyStage(fromEpoch = fromEpoch, media = media.drop(i * PAGE).take(PAGE), buckets = buckets.drop(i * PAGE).take(PAGE)))
            }
            bucketsStaged = true
            if (!page.more) return moved
            since = page.cursor ?: return moved
        }
    }

    /** A field re-sealed byte for byte under [newKey], bound as before; the plaintext zeroed after. */
    private fun reseal(suite: V1, context: Context, oldKey: ByteArray, newKey: ByteArray, sealed: String): String {
        val plain = suite.openField(context, oldKey, unb64(sealed))
        try {
            return b64(suite.sealField(context, newKey, plain))
        } finally {
            plain.fill(0)
        }
    }

    /**
     * The banner's refs inside the node's meta (`banner.blob`, `banner.poster`): their wraps moved, every other field and
     * every key's place as it was. A shape this client does not know is left as it is; a ref whose wrap does not open
     * throws (fail closed).
     */
    internal fun rewrapBanner(suite: V1, scopeId: String, nodeId: String, oldKey: ByteArray, newKey: ByteArray, meta: JsonObject): JsonObject {
        var banner = meta[Banner.FIELD] as? JsonObject ?: return meta
        for ((field, role) in listOf("blob" to Banner.ROLE, "poster" to Banner.POSTER_ROLE)) {
            val ref = banner[field] as? JsonObject ?: continue
            val blobId = (ref["blobId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val wrapped = (ref["wrappedBlobKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val rewrapped = NodeRekey.rewrapBlob(suite, scopeId, nodeId, blobId, role, oldKey, newKey, wrapped)
            banner = JsonObject(banner + (field to JsonObject(ref + ("wrappedBlobKey" to JsonPrimitive(rewrapped)))))
        }
        return JsonObject(meta + (Banner.FIELD to banner))
    }
}
