package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices

// Expenses' lines of core's notices table ([Notices], ADR 0154 decision 4): extensions of it, so every client calls them
// where it called core's (Swift's `Notices.shared.potRefused(outcome:)`, a category of `Notices`).

/** Why a Pot's copy did not go through. */
fun Notices.potCopyRefused(outcome: PotCopy.Outcome): Notice? = when (outcome) {
    PotCopy.Outcome.COPIED -> null
    PotCopy.Outcome.NAME_TAKEN -> Notice(Msg.COPY_NAME_TAKEN)
    PotCopy.Outcome.NOTHING_TO_COPY -> Notice(Msg.COPY_NOTHING)
    PotCopy.Outcome.BUSY -> Notice(Msg.MOVE_REFUSED_REKEY_PENDING)
    PotCopy.Outcome.BLOCKED, PotCopy.Outcome.REFUSED -> Notice(Msg.MOVE_REFUSED_ILLEGAL_STRUCTURE)
    PotCopy.Outcome.FAILED -> Notice(Msg.COPY_FAILED)
}

/** A problem of an entry's form or its write path (the web's `expenses.entry.problem.*`); any other: an unknown participant. */
fun Notices.entryProblem(problem: String): Msg = when (problem) {
    "title" -> Msg.PROBLEM_TITLE
    "date" -> Msg.PROBLEM_DATE
    "amount" -> Msg.PROBLEM_AMOUNT
    "rate" -> Msg.PROBLEM_RATE
    "payers" -> Msg.PROBLEM_PAYERS
    "split" -> Msg.PROBLEM_SPLIT
    "split-shares" -> Msg.PROBLEM_SPLIT_SHARES
    "split-percents" -> Msg.PROBLEM_SPLIT_PERCENTS
    "split-amounts" -> Msg.PROBLEM_SPLIT_AMOUNTS
    "items" -> Msg.PROBLEM_ITEMS
    "transfer" -> Msg.PROBLEM_TRANSFER
    "payers-unbalanced" -> Msg.PROBLEM_PAYERS_UNBALANCED
    "receivers-unbalanced" -> Msg.PROBLEM_RECEIVERS_UNBALANCED
    "items-above-total" -> Msg.PROBLEM_ITEMS_ABOVE_TOTAL
    "rest-unallocated" -> Msg.PROBLEM_REST_UNALLOCATED
    "percent-unbalanced" -> Msg.PROBLEM_PERCENT_UNBALANCED
    "amounts-unbalanced" -> Msg.PROBLEM_AMOUNTS_UNBALANCED
    "named-twice" -> Msg.PROBLEM_NAMED_TWICE
    else -> Msg.PROBLEM_UNKNOWN_PARTICIPANT
}

/**
 * What a saved entry says when receipts were left out for want of room (the web's `expenses.receipts.noRoom`): the entry
 * is saved and its editor closes; a receipt is attached again once there is room. Null when none was left out.
 */
fun Notices.receiptsSkipped(names: List<String>): Notice? =
    if (names.isEmpty()) null else Notice(Msg.RECEIPTS_NO_ROOM, Joined(names, ", "))

/** Why an entry was not saved (`PotWrites` outcome names). */
fun Notices.entryRefused(outcome: String): Msg = when (outcome) {
    "STALE" -> Msg.ED_STALE
    "BLOCKED" -> Msg.ED_BLOCKED
    "OFFLINE" -> Msg.OFFLINE_READ_ONLY
    "BUSY" -> Msg.POT_BUSY
    else -> Msg.ED_SAVE_FAILED
}

/** Why a write to a Pot's settings or people was not done. */
fun Notices.potRefused(outcome: String): Msg = when (outcome) {
    "STALE" -> Msg.ENTRY_STALE
    "NOT_SETTLED" -> Msg.PEOPLE_SETTLE_FIRST
    "BUSY" -> Msg.POT_BUSY
    "BLOCKED" -> Msg.POT_BLOCKED
    "INVALID" -> Msg.PEOPLE_INVALID
    "OFFLINE" -> Msg.OFFLINE_READ_ONLY
    "NOT_RESTORABLE" -> Msg.ENTRY_NOT_RESTORABLE
    else -> Msg.PEOPLE_FAILED
}

/** Why a new Pot was not created (`PotCreate.Outcome` names). */
fun Notices.potCreateRefused(outcome: String): Msg = when (outcome) {
    "NAME_TAKEN" -> Msg.CREATE_POT_NAME_TAKEN
    "PEOPLE_NOT_ADDED" -> Msg.CREATE_POT_PEOPLE_FAILED
    "BUSY" -> Msg.POT_BUSY
    "BLOCKED" -> Msg.POT_BLOCKED
    "INVALID" -> Msg.CREATE_POT_INVALID
    else -> Msg.CREATE_POT_FAILED
}
