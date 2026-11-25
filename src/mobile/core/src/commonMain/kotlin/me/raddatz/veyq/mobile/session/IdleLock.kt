package me.raddatz.veyq.mobile.session

/**
 * When a TV's open account locks by itself (ADR 0160 decision 6; owner 2026-11-25: fixed, no setting): after [IDLE_MS]
 * without input while nothing plays, and at every return from the background. *Lock* chosen locks at once, through
 * [SessionModel.lock] like these. The core's one answer; each platform feeds it — every key or remote press ([input]),
 * its players' state ([playing]), the app leaving and coming back ([background], [foreground]) — and asks [due] on a
 * timer of its own (a minute is fine: the rule is minutes, not seconds), locking when it answers true.
 *
 * Playing is any medium running: a video, a slideshow, music (in the background too, where the platform lets it). Input
 * while something plays counts as well; when the playing stops, the 15 minutes start from the later of the two.
 */
class IdleLock(private val clock: () -> Long) {
    private var lastInput: Long = clock()
    private var playing = false
    private var playingEnded: Long = lastInput
    private var away = false

    /** A key or remote press, a focus move: the person is there. */
    fun input() {
        lastInput = clock()
    }

    /** Whether a medium plays now ([on]); its end restarts the idle time from then. */
    fun playing(on: Boolean) {
        if (playing && !on) playingEnded = clock()
        playing = on
    }

    /** The app went into the background. */
    fun background() {
        away = true
    }

    /**
     * The app came back to the foreground: true when it went away first — the account locks (decision 6: "when the app
     * returns from the background"). The idle time starts anew either way.
     */
    fun foreground(): Boolean {
        val locks = away
        away = false
        input()
        return locks
    }

    /** Whether the open account locks now: nothing plays, and the last input and the last playing both lie [IDLE_MS] back. */
    fun due(): Boolean {
        if (playing) return false
        val since = maxOf(lastInput, playingEnded)
        return clock() - since >= IDLE_MS
    }

    /** An account opened (an unlock): the idle time starts from now. */
    fun opened() {
        input()
        playingEnded = lastInput
        away = false
    }

    companion object {
        /** 15 minutes (owner 2026-11-25). */
        const val IDLE_MS: Long = 15 * 60 * 1000L
    }
}
