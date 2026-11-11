package me.raddatz.veyq.mobile.expenses

/**
 * What a Pot takes when something is shared to veyq (ADR 0129 decision 2, owner 2026-11-08): images and PDFs within its
 * receipts' rules, which open the editor of a new expense with them as its receipts. Expenses' answer; the apps' pick mode
 * asks it, core's navigation knows no application.
 */
object PotShare {
    /** The kind that takes a share: a Pot. */
    const val KIND = PotSession.KIND

    /** At most this many files go into one new expense (its receipts' cap). */
    const val MAX_FILES = Receipts.MAX_PER_ENTRY

    /** Whether one shared file of [type] named [name], [size] bytes, can be a receipt. */
    fun takes(type: String?, name: String?, size: Long): Boolean = size in 1..Receipts.MAX_BYTES && Receipts.claims(type, name)

    /** The files a share hands over, split into those the new expense takes (in order, at most [MAX_FILES]) and the rest. */
    fun <T> split(files: List<T>, type: (T) -> String?, name: (T) -> String?, size: (T) -> Long): Pair<List<T>, List<T>> {
        val (fit, refused) = files.partition { takes(type(it), name(it), size(it)) }
        return fit.take(MAX_FILES) to (refused + fit.drop(MAX_FILES))
    }
}
