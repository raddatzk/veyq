package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The media rows of an erased group. Phase 100, before core's blob service's rows (160) and core's blob rows
 * (`ScopeEraser.BLOB_ROWS_PHASE`): a medium's blobs are the service's rows held by it (ADR 0113), which the service's
 * own eraser removes; no row of this table references a blob.
 *
 * Deliberately **not** `MediaStore.purgeMediaRows` (the per-media purge): this erases set-based by scope.
 */
@ApplicationScoped
class MediaScopeEraser(private val measures: MeasureTargets) : ScopeEraser {
    override val phase = 100

    override fun eraseScope(scopeId: String) {
        val mediaIds = MediaEntity.find("scopeId", scopeId).list().map { it.id }
        MediaEntity.delete("scopeId", scopeId)
        // A content block on a medium ends with it (ADR 0093 § 6): after the delete, which holds the rows' locks.
        measures.erased(listOf(BlockKind.CONTENT_ITEM), mediaIds)
    }
}
