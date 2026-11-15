package me.raddatz.veyq.mobile.audio

import me.raddatz.veyq.mobile.blob.RangeReader

/**
 * One of the [MusicPlayer]'s two decks: the platform's player of one track's bytes (ADR 0142 decision 2). The core drives
 * two and decides everything — which track, when, how loud; a deck plays the bytes it is handed through a core reader and
 * ramps its volume when asked. Android two `MediaPlayer`s over `ReaderSource` (the ramp a `VolumeShaper`), Apple two
 * `AVPlayer`s over `ReaderLoader`, Windows and Linux theirs on the host's side of the bridge (`Audio.*` host requests).
 *
 * The crossfade is the core's timing and the deck's ramp: the core asks `ramp(1.0, 5000)` of the incoming deck and
 * `ramp(0.0, 5000)` of the outgoing one and never steps the volume itself.
 */
interface Deck {
    /**
     * Opens [reader] (a track of type [mime]) and readies it at its start, its volume as it was last ramped. The file's
     * length in ms, `0` or less where the platform cannot say. Throws where the platform cannot open it (ADR 0142
     * decision 9): the player skips the track.
     */
    @Throws(Exception::class)
    suspend fun load(reader: RangeReader, mime: String): Long

    /** Starts or continues playing what was loaded. Throws where the platform refuses to play it: the player skips it. */
    @Throws(Exception::class)
    suspend fun play()

    fun pause()

    /** Moves to [positionMs] of the file. */
    fun seek(positionMs: Long)

    /** Where in the file it stands, in ms. */
    suspend fun positionMs(): Long

    /** The volume (0.0 silent, 1.0 full) moved to [to] over [overMs], smoothly (steps of at most 50 ms); `0` sets it at once. */
    fun ramp(to: Double, overMs: Long)

    /** Stops, lets the platform's player go and closes its stream. The reader itself is the track's to close. */
    fun clear()

    /** What a deck reports by itself, on any thread: the player answers on its own. */
    interface Events {
        /** The file played to its end (one shorter than its span, or a span the core did not know). */
        fun ended()

        /** The platform stopped playing it after it loaded (a stream error). A load that fails throws instead. */
        fun failed()
    }
}

/**
 * The system's media controls (ADR 0142 decision 3): Android's `MediaSession` and its notification, Apple's
 * `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`, Windows' SMTC, Linux's MPRIS — the web's `MediaSession`. The core
 * owns what they show and which commands exist; a platform wires them to its system surface.
 */
interface SystemControls {
    /** A queue loaded: the system's play, pause, next, previous and seek reach [commands] from now on. */
    fun bind(commands: PlayerCommands)

    /** What plays now: title, node name, whether it plays, where it stands in its length. Shown again at each change and
     *  once a second while it plays. */
    fun show(now: NowPlaying)

    /** The queue stopped: the commands unbound and everything shown taken away. */
    fun clear()
}

/** What the system's controls (and the bar) can ask of the player. The system's "play" is [resume]. */
interface PlayerCommands {
    fun resume()
    fun pause()
    fun next()
    fun previous()

    /** Within the span, in ms from its start. */
    fun seek(positionMs: Long)
}
