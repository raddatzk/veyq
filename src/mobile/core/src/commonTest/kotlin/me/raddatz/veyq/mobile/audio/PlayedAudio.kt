package me.raddatz.veyq.mobile.audio

import kotlinx.coroutines.CompletableDeferred
import me.raddatz.veyq.mobile.blob.RangeReader

/**
 * The platform as a test plays it (ADR 0142 decisions 2 and 3): two decks that "play" a track by name — its length from
 * [lengths], `broken` refused at load, `unplayable` refused at play — and the system's controls, recording what the
 * player asked of them. The web's `player.test.ts` stubs its media elements, `GainNode`s and MediaSession the same way.
 */
class PlayedAudio(private val lengths: Map<String, Long> = emptyMap()) {
    /** A volume ramp a deck was asked for: the deck's index, the volume, over how long. */
    data class Ramp(val deck: Int, val to: Double, val ms: Long)

    val decks = mutableListOf<PlayedDeck>()
    val ramps = mutableListOf<Ramp>()
    val controls = PlayedControls()

    /** The fades asked for (a ramp at once — a deck silenced before it loads, raised on a resume — is no fade). */
    val fades: List<Ramp> get() = ramps.filter { it.ms > 0 }

    private val names = mutableMapOf<RangeReader, String>()

    /** Every reader a track opened and that is not closed again: what a player leaked once its queue is gone. */
    val openReaders: Int get() = names.keys.count { !it.isClosed }

    fun newDeck(events: Deck.Events): Deck = PlayedDeck(decks.size, events).also { decks += it }

    /** The deck playing a track now, if any. */
    fun playing(): PlayedDeck? = decks.firstOrNull { it.playing && it.name != null }

    /** A track whose bytes are named [name] ([id] by default; `fail` makes its `open()` throw); [gate] holds `open()` back. */
    inner class Track(
        val id: String,
        name: String = id,
        startMs: Long? = 1000,
        endMs: Long? = 61_000,
        gate: CompletableDeferred<Unit>? = null,
    ) {
        var opens = 0
        var releases = 0
        val t = PlayerTrack(id, "Song $id", startMs, endMs) {
            opens++
            gate?.await()
            if (name == "fail") throw IllegalStateException("open_failed")
            val reader = RangeReader(0, 0) { _, _, _ -> }
            names[reader] = name
            TrackSource(reader, "audio/mpeg") {
                releases++
                reader.close()
            }
        }
    }

    inner class PlayedDeck(val index: Int, val events: Deck.Events) : Deck {
        var name: String? = null
        var playing = false
        var position = 0L
        var plays = 0
        var clears = 0

        override suspend fun load(reader: RangeReader, mime: String): Long {
            val n = names.getValue(reader)
            if ("broken" in n) throw IllegalStateException("media_error")
            name = n
            position = 0
            return lengths[n] ?: 0
        }

        override suspend fun play() {
            if (name?.contains("unplayable") == true) throw IllegalStateException("not_supported")
            plays++
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun seek(positionMs: Long) {
            position = positionMs
        }

        override suspend fun positionMs(): Long = position

        override fun ramp(to: Double, overMs: Long) {
            ramps += Ramp(index, to, overMs)
        }

        override fun clear() {
            clears++
            playing = false
            name = null
            position = 0
        }
    }

    /** The system's controls: the commands bound, the last thing shown, how often they were cleared. */
    class PlayedControls : SystemControls {
        var commands: PlayerCommands? = null
        var shown: NowPlaying? = null
        var cleared = 0

        override fun bind(commands: PlayerCommands) {
            this.commands = commands
        }

        override fun show(now: NowPlaying) {
            shown = now
        }

        override fun clear() {
            commands = null
            shown = null
            cleared++
        }
    }
}
