package me.raddatz.veyq.memories.itemfavorites

import me.raddatz.veyq.core.identity.AccountEraser
import me.raddatz.veyq.core.identity.ColumnFate
import me.raddatz.veyq.core.identity.ERASED
import me.raddatz.veyq.core.identity.kept
import jakarta.enterprise.context.ApplicationScoped

/**
 * What of the Memories application goes with an erased account (ADR 0101 decisions 4-5): its item favorites, in every
 * scope — the membership removal took them from the shared scopes already, this takes what is left. Everything else it
 * wrote stays with its Memory: a bucket keeps its `user:{id}`, a medium its uploader and last editor, shown as a former
 * member's, as a guest's photos belong to the Memory (the security model's § Deletion & erasure).
 */
@ApplicationScoped
class MemoriesAccountEraser : AccountEraser {
    override val columns: Map<String, ColumnFate> = mapOf(
        "veyq_scope_item_favorites.user_id" to ERASED,
        "veyq_bucket.owner_key" to kept("content attribution: a contributor's bucket stays with its Memory"),
        "veyq_bucket.name_editor_user_id" to kept("content attribution: a bucket's name, shown as a former member's"),
        "veyq_media.created_by" to kept("content attribution: a medium's uploader, shown as a former member's"),
        "veyq_media.deleted_by" to kept("content attribution: who trashed a medium, for its restore"),
        "veyq_media.meta_editor_user_id" to kept("content attribution: a medium's meta, shown as a former member's"),
    )

    override fun eraseAccount(accountId: String) {
        ItemFavoritesEntity.delete("userId", accountId)
    }
}
