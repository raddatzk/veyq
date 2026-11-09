package me.raddatz.veyq.mobile.session

/**
 * The platform majors this app speaks (ADR 0085 decision 5's client window; the web's `EMBED_MAJORS`). Until a major 2
 * is released the window is the app's own major alone (owner 2026-11-09, ADR 0085 § Consequences "No adapter is due
 * before major 2"), so the sign-in gate is equality and the app carries no adapter. Major 1's adapter is due with
 * major 2 — its sign-in, session and every application's wire as major 1 serves them, tested against a major-1 server
 * image — and lands here by adding 1 to [MAJORS]; `ClientWindowTest` is red from VERSION 2 on until it does.
 */
object ClientWindow {
    /** The majors spoken: this app's own first, then each older major it carries an adapter for. */
    val MAJORS: List<Int> = listOf(PLATFORM_MAJOR)

    /** Whether a server of [major] may be signed in to. */
    fun speaks(major: Int): Boolean = major in MAJORS

    /** The older majors a client of [major] keeps speaking (the planned window: two back, released majors only). */
    fun olderMajorsInWindow(major: Int): List<Int> = listOf(major - 1, major - 2).filter { it >= 1 }
}
