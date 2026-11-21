package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.navigation.ShareAnswer
import me.raddatz.veyq.mobile.navigation.ShareInbox

/**
 * What a Pot takes when something is shared to veyq (ADR 0138 decision 2, owner 2026-11-08): images and PDFs within its
 * receipts' rules, which open the editor of a new expense with them as its receipts. Expenses' answer ([answer]); the apps'
 * pick mode asks it through `AppKinds.share`, core's navigation knows no application.
 */
object PotShare {
    /** The kind that takes a share: a Pot. */
    const val KIND = PotSession.KIND

    /** At most this many files go into one new expense (its receipts' cap). */
    const val MAX_FILES = Receipts.MAX_PER_ENTRY

    /**
     * What a share holds whole at once, every file taken together (ADR 0138 decision 6): an iOS share extension's memory
     * is bounded, and one rule holds on every app (D8: Android's own 40 MiB read cap became this budget). A file beyond
     * it is left out as a too large one is.
     */
    const val BUDGET = 64L * 1024 * 1024

    /**
     * Expenses' answer: a new expense's editor takes the files ([ShareAnswer.How.EDITOR]), judged by an [Intake]. Any Pot
     * can be chosen; its page says whether it takes them ([goesInto]).
     */
    val answer = ShareAnswer(ShareAnswer.How.EDITOR, ::Intake) { true }

    /** Whether one shared file of [type] named [name], [size] bytes, can be a receipt. */
    fun takes(type: String?, name: String?, size: Long): Boolean = size in 1..Receipts.MAX_BYTES && Receipts.claims(type, name)

    /**
     * One share's files judged in order, as a device reads them: each one a receipt may be ([takes]), at most [MAX_FILES],
     * and all of them within [BUDGET].
     */
    class Intake : ShareAnswer.Intake {
        private var count = 0
        private var held = 0L

        /** What is left of [BUDGET]. */
        val room: Long get() = BUDGET - held

        /** Whether this file is taken; a taken one counts against the cap and the budget. */
        override fun take(type: String?, name: String?, size: Long): Boolean {
            if (!takes(type, name, size) || count >= MAX_FILES || size > room) return false
            count++
            held += size
            return true
        }
    }

    /** The files a share hands over, split into those the new expense takes (in order, by an [Intake]) and the rest. */
    fun <T> split(files: List<T>, type: (T) -> String?, name: (T) -> String?, size: (T) -> Long): Pair<List<T>, List<T>> =
        answer.split(files, type, name, size)

    /** Whether the Pot shown ([view], null: none) takes waiting files into a new expense: it is writable and has people. */
    fun goesInto(view: PotView?): Boolean = view != null && view.canWrite && view.people.isNotEmpty()

    /** Whether files dropped onto the Pot shown go into its new expense at once, rather than waiting in the pick mode. */
    fun takesDropOn(view: PotView?, incoming: ShareInbox.Incoming): Boolean =
        goesInto(view) && answer.split(incoming.files, { it.type }, { it.name }, { it.size }).first.isNotEmpty()

    /** The receipts a Pot's new expense opens with, and how many shared files it left out. */
    class Taken(val pending: List<PotSession.PendingReceipt>, val refused: Int)

    /**
     * The waiting files taken by the Pot opened as their place: each one it takes read whole (at most [BUDGET] together) as
     * a pending receipt under an id of [newId]; one that does not read is left out with the refused. Null when none waits
     * or the Pot takes none.
     */
    suspend fun take(inbox: ShareInbox, newId: () -> String): Taken? {
        val taken = inbox.take(answer) ?: return null
        var unread = 0
        val pending = taken.files.mapNotNull { f ->
            val bytes = runCatching { f.read(0, f.size.toInt()) }.getOrNull().also { f.close() }
            if (bytes == null || bytes.size.toLong() != f.size) { unread++; null }
            else PotSession.PendingReceipt(newId(), bytes, f.type.orEmpty(), f.name, f.modifiedAt)
        }
        return Taken(pending, taken.refused + unread)
    }
}
