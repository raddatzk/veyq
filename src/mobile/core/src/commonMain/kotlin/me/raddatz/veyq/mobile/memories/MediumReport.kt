package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.CancellationException
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Report
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A Memory's medium reported to its server's moderators as an `ITEM` (the web's `media.ts` `mediumReportKeys`, ADR 0093,
 * ADR 0142 decision 11 — a music track's report; the lightbox's photo or video is #891's): the keys handed over are every
 * one of its blobs' `K_blob` by blob id, its listed tier's field key and — the apps' viewer is a member — its EXIF tier's.
 * Nothing else opens: not the node, not another medium.
 */
object MediumReport {
    /** The keys a report of [item] hands over; null where none of its blobs' wraps opens here. */
    suspend fun keys(tenant: TenantSession, item: MemoryMedia.Item, withExif: Boolean = true): List<Report.Key>? {
        val suite = tenant.account.suite
        val keys = ArrayList<Report.Key>()
        for ((blob, role) in listOfNotNull(
            item.original to MemoryMedia.ORIGINAL,
            item.thumbnail?.let { it to MemoryMedia.THUMBNAIL },
            item.preview?.let { it to MemoryMedia.PREVIEW },
        )) {
            // a ref whose wrap does not open is not this medium's to hand over
            MemoryMedia.openBlobKey(suite, item.scopeId, item.mediaId, item.key, blob, role)?.let { keys += Report.Key("BLOB", blob.blobId, it) }
        }
        if (keys.isEmpty()) return null
        fun field(context: Context, envelope: String?): ByteArray? =
            envelope?.let { runCatching { suite.fieldKeyOf(context, item.key, unb64(it)) }.getOrNull() }
        field(Context("media-meta", "scopeId" to item.scopeId, "mediaId" to item.mediaId), item.wrappedMeta)?.let { keys += Report.Key("MEDIA_META", null, it) }
        if (withExif) {
            val exif = try {
                tenant.account.call { MediaApi(tenant.base, tenant.account.http).getMediaExif(item.scopeId, item.mediaId) }.wrappedExif
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            field(Context("media-exif", "scopeId" to item.scopeId, "mediaId" to item.mediaId), exif)?.let { keys += Report.Key("MEDIA_EXIF", null, it) }
        }
        return keys
    }

    /** [item] reported in [category] for [reason], with [contact] (`NodeReport.reportItem`). */
    suspend fun report(tenant: TenantSession, item: MemoryMedia.Item, category: String, reason: String, contact: String): NodeReport.Sent =
        NodeReport.reportItem(tenant, item.scopeId, item.mediaId, keys(tenant, item), category, reason, contact)
}
