package me.raddatz.veyq.mobile.offline

import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.words.Notice

/**
 * How the uploads tray shows the queued writes of [ops] where they carry an application's own content (ADR 0153
 * decision 5; `ApplicationKinds.queuedShown`): Expenses' entry is said by its title, opens its editor and saves a receipt
 * under the receipt's name. Every other write the tray shows from its intent alone ([QueuedShown]).
 */
@HiddenFromObjC
interface QueuedShownPart {
    /** The writes it shows; each one application's at most. */
    val ops: Set<Queued>

    /** What [write] is, as its tray row names it. */
    fun title(write: QueuedWrite): Notice

    /** Where held [write]'s *Open* goes; null where it opens nothing. */
    fun opens(write: QueuedWrite): QueuedOpen?

    /** The name and type [write]'s carried file [blobId] is saved under; null: the intent's own `name` and `type`. */
    fun copyName(write: QueuedWrite, blobId: String): Pair<String?, String?>? = null
}
