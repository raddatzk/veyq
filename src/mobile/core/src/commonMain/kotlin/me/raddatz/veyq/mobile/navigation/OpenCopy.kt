package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.words.Notice

/**
 * The copy of an object open on its page, by the model the page holds (ADR 0156, inbox #1215): what the move sheet's *Copy
 * to…* asks for a kind that copies by its open model (`AppKinds.copiesByOpenModel`: Expenses' Pot, whose copy re-seals its
 * ledger under fresh ids), so the shell names no application. Expenses' `PotModel` implements it.
 */
interface OpenCopy {
    /**
     * [ref], the object open on the page, copied under [to] (null: the space's top) through [shell], a copied person erased
     * named [deletedName], each new id from [uuid]: why not, or null once copied. A [ref] that is not the open object fails
     * as a copy without a page does.
     */
    suspend fun duplicateOpen(shell: ShellDoor, ref: NodeAt, to: String?, deletedName: String, uuid: () -> String): Notice?
}
