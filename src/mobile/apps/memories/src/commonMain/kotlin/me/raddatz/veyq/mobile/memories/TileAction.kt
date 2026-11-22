package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.navigation.Plan

/**
 * What a medium's tile offers beside opening it (ADR 0154 decision 2, `GalleryModel.tileActions`): the web's tile has
 * the mark toggle and, on a link guest's own upload, its delete. Its words: [MARK] the mark's, `media_marks_mark` or
 * `media_marks_unmark` by whether the caller marked it; [DELETE] `entry_delete`.
 */
@Plan
@Serializable
enum class TileAction {
    /** The caller's mark on the medium set or taken away (`GalleryModel.toggleMark`). */
    @SerialName("mark") MARK,
    /**
     * A link guest's own upload deleted (`GalleryModel.trash`: asked first, then purged): the web's tile overlay on a guest's
     * own medium (`MemoryDetail.vue`'s `isOwnMedia`; ADR 0155 § The rights and plans' slice, D3).
     */
    @SerialName("delete") DELETE,
}
