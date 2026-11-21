package me.raddatz.veyq.mobile.memories

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.navigation.Plan

/**
 * What a medium's tile offers beside opening it (ADR 0154 decision 2, `GalleryModel.tileActions`): the web's tile has
 * the mark toggle and nothing else. Its words: [MARK] the mark's, `media_marks_mark` or `media_marks_unmark` by whether
 * the caller marked it.
 */
@Plan
@Serializable
enum class TileAction {
    /** The caller's mark on the medium set or taken away (`GalleryModel.toggleMark`). */
    @SerialName("mark") MARK,
}
