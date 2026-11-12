package me.raddatz.veyq.mobile.expenses

/**
 * What a Pot takes when something is shared to veyq (ADR 0131 decision 2, owner 2026-11-08): images and PDFs within its
 * receipts' rules, which open the editor of a new expense with them as its receipts. Expenses' answer; the apps' pick mode
 * asks it, core's navigation knows no application.
 */
object PotShare {
    /** The kind that takes a share: a Pot. */
    const val KIND = PotSession.KIND

    /** At most this many files go into one new expense (its receipts' cap). */
    const val MAX_FILES = Receipts.MAX_PER_ENTRY

    /**
     * What a share holds whole at once, every file taken together (ADR 0131 decision 6): an iOS share extension's memory
     * is bounded, and one rule holds on every app (D8: Android's own 40 MiB read cap became this budget). A file beyond
     * it is left out as a too large one is.
     */
    const val BUDGET = 64L * 1024 * 1024

    /** Whether one shared file of [type] named [name], [size] bytes, can be a receipt. */
    fun takes(type: String?, name: String?, size: Long): Boolean = size in 1..Receipts.MAX_BYTES && Receipts.claims(type, name)

    /**
     * One share's files judged in order, as a device reads them: each one a receipt may be ([takes]), at most [MAX_FILES],
     * and all of them within [BUDGET]. A device reads a file only up to [room] (a HEIC larger than its JPEG included).
     */
    class Intake {
        private var count = 0
        private var held = 0L

        /** What is left of [BUDGET]: a device reads no more of the next file. */
        val room: Long get() = BUDGET - held

        /** Whether this file is taken; a taken one counts against the cap and the budget. */
        fun take(type: String?, name: String?, size: Long): Boolean {
            if (!takes(type, name, size) || count >= MAX_FILES || size > room) return false
            count++
            held += size
            return true
        }
    }

    /** The files a share hands over, split into those the new expense takes (in order, by an [Intake]) and the rest. */
    fun <T> split(files: List<T>, type: (T) -> String?, name: (T) -> String?, size: (T) -> Long): Pair<List<T>, List<T>> {
        val intake = Intake()
        return files.partition { intake.take(type(it), name(it), size(it)) }
    }
}
