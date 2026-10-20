package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.scope.ScopeEraser
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
class MediaScopeEraser(private val measures: MeasureTargets) : ScopeEraser {
    override val phase = 100

    override fun eraseScope(scopeId: String) {
        val mediaIds = MediaEntity.find("scopeId", scopeId).list().map { it.id }
        if (mediaIds.isNotEmpty()) MediaDerivativeEntity.delete("mediaId in ?1", mediaIds)
        MediaEntity.delete("scopeId", scopeId)
        // A content block on a medium ends with it (ADR 0093 § 6): after the delete, which holds the rows' locks.
        measures.erased(listOf(BlockKind.CONTENT_ITEM), mediaIds)
    }
}
