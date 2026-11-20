package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.words.Notice

/**
 * What a name dialog's save came to (ADR 0154 decision 1: an outcome a UI must tell apart is typed, never one nullable
 * notice) — the one shape New Folder, a rename and a guest's naming answer:
 *
 * - [problem]: the name is not taken as it is (empty, a sibling's): the dialog stays open, the word under its field;
 * - [notice]: the dialog closes and this is said (a refusal, a failure, a link that kept the old name);
 * - neither: done, the dialog closes silently;
 * - [queued]: offline the write waits to be sent and shows at once where it was made (ADR 0153): the dialog closes.
 *
 * A thrown error is the core's to turn into a [notice], never a platform's.
 */
class NameAnswer(val problem: Notice?, val notice: Notice?, val queued: Boolean = false) {
    /** Whether the dialog stays open, asking again ([problem]). */
    val keepsOpen: Boolean get() = problem != null

    override fun equals(other: Any?) = other is NameAnswer && other.problem == problem && other.notice == notice && other.queued == queued
    override fun hashCode() = (problem.hashCode() * 31 + notice.hashCode()) * 31 + queued.hashCode()
    override fun toString() = "NameAnswer(problem=$problem, notice=$notice, queued=$queued)"

    companion object {
        /** Done: the dialog closes, nothing said. */
        val DONE = NameAnswer(null, null)

        /** Queued offline: the dialog closes, the write shows where it was made. */
        val QUEUED = NameAnswer(null, null, queued = true)

        /** The name is not taken as it is: the dialog stays open with [problem] under its field. */
        fun problem(problem: Notice) = NameAnswer(problem, null)

        /** The dialog closes and [notice] is said. */
        fun said(notice: Notice) = NameAnswer(null, notice)
    }
}
