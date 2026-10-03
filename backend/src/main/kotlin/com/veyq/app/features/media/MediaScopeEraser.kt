package com.veyq.app.features.media

import com.veyq.app.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The media rows of an erased group. Phase 100, **below** `ScopeEraser.BLOB_ROWS_PHASE`, because
 * `veyq_media_derivative` references `veyq_media` and `veyq_blob` (`fk_derivative_media`, `fk_derivative_blob`)
 * and `veyq_media` references `veyq_blob` (`fk_media_blob`): derivative -> media -> blob, so these deletes must
 * land before core deletes the blobs. Derivatives carry no `scope_id` and go by their media ids.
 *
 * Deliberately **not** `MediaStore.purgeMediaRows` (the per-media purge): this erases set-based by scope.
 */
@ApplicationScoped
class MediaScopeEraser : ScopeEraser {
    override val phase = 100

    override fun eraseScope(scopeId: String) {
        val mediaIds = MediaEntity.find("scopeId", scopeId).list().map { it.id }
        if (mediaIds.isNotEmpty()) MediaDerivativeEntity.delete("mediaId in ?1", mediaIds)
        MediaEntity.delete("scopeId", scopeId)
    }
}
