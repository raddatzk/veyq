package me.raddatz.veyq.mobile.android.apps.memories

import android.content.Context
import me.raddatz.veyq.mobile.android.core.language
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Shown

/**
 * A Memory's date as a list row and its header say it (ADR 0143 D5; the web's `memoryDateRange`): a single day, a
 * closed range ("03/12/2026 – 04/02/2026"), an ongoing one "Since …"; null where it has no day yet.
 */
internal fun Context.memoryDateText(d: MemoryDate): String? {
    val from = d.from ?: return null
    if (d.mode == MemoryDate.Mode.ONGOING) return words(Msg.MEMORY_DATE_SINCE, Shown.memoryDay(from, language))
    return Shown.dayRange(from, d.to, language)
}
