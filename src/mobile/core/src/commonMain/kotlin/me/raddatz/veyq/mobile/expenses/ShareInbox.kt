package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Files another app shared to veyq (ADR 0131), from their arrival to where they went: they wait in the pick mode until a
 * Pot takes them as its new expense's receipts; the expense's editor closed unsaved gives them back, its save ends the
 * share. Dropped onto an open Pot that takes them, they go to its new expense at once. Each platform reads the files
 * ([Incoming], its own readers, [PotShare.split]'s rule) and moves the shell to Home when they wait.
 */
class ShareInbox {
    /** The files waiting for the place they go, and how many were left out (no image or PDF, too large, too many). */
    class Incoming(val files: List<PotSession.PendingReceipt>, val refused: Int)

    private val _waiting = MutableStateFlow<Incoming?>(null)
    /** The files waiting (the pick mode is on); null when none wait. */
    val waiting: StateFlow<Incoming?> = _waiting.asStateFlow()

    /** What a Pot took, until its new expense is saved. */
    private var taken: Incoming? = null

    /** Files arrive: they wait for the place the person picks (the platform shows Home, never the object last open). */
    fun receive(incoming: Incoming) {
        _waiting.value = incoming
    }

    /** Whether the Pot shown ([view], null: none) takes waiting files into a new expense: it is writable and has people. */
    fun goesInto(view: PotView?): Boolean = view != null && view.canWrite && view.people.isNotEmpty()

    /** Whether files dropped onto the Pot shown go into its new expense at once, rather than waiting in the pick mode. */
    fun takesDropOn(view: PotView?, incoming: Incoming): Boolean = goesInto(view) && incoming.files.isNotEmpty()

    fun cancel() {
        _waiting.value = null
    }

    /** The waiting files, taken by the Pot opened as their place: its new expense's receipts; null when none wait. */
    fun take(): List<PotSession.PendingReceipt>? {
        val w = _waiting.value?.takeIf { it.files.isNotEmpty() } ?: return null
        taken = w
        _waiting.value = null
        return w.files
    }

    /** The new expense's editor closed unsaved: the files wait again; what waits now, or null when nothing was taken. */
    fun giveBack(): Incoming? {
        val t = taken ?: return null
        taken = null
        _waiting.value = t
        return t
    }

    /** The new expense the files went into was saved: the share is done. */
    fun saved() {
        taken = null
    }
}
