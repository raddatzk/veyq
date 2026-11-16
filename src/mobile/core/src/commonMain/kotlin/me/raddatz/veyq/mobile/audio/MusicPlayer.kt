package me.raddatz.veyq.mobile.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.ContinuationInterceptor

/**
 * The core's music player (ADR 0142 decision 1), the web's `core/audio/player.ts` ported: ONE queue at a time, played
 * shuffled ([PlayOrder]), endlessly, crossfading at the tracks' trimmed edges (`Fades.kt`), with the system's media
 * controls ([SystemControls]). An application hands over a queue; each track's `open()` is the application's and hands a
 * reader, never a key. Every source it opened is released when it is stopped — the shell stops it where `closeTenant()`
 * closes the tenant's models (a lock, a sign-out, a switch).
 *
 * Two [Deck]s alternate, the platform's (ADR 0142 decision 2); the timing is here: a tick every [TICK_MS] opens the next
 * track [OPEN_AHEAD_MS] before the playing one's fade, starts the fade at the span's end minus the fade, and clears the
 * outgoing deck once its fade has run. A track that does not open, load or play is dropped from the order and skipped;
 * when none of the queue plays, the queue ends and [State.notice] says so. A source that opens after its queue moved on
 * is released at once.
 *
 * Every call is answered on the player's own lane, one at a time, in the order made — as the web's single thread does.
 * [interrupt] is a lightbox video's (ADR 0142 decision 13). A listener's [pause] holds a track still loading as an
 * interruption does: once loaded it is the active deck, paused, and only a play starts it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlayer(
    /** A new deck reporting to the [Deck.Events] handed in; called twice. */
    newDeck: (Deck.Events) -> Deck,
    private val controls: SystemControls?,
    private val random: RandomInt,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PlayerCommands {
    /** What the bar draws: what plays, why the last queue ended by itself (until the next [play]), and the tracks that did
     *  not play on this device in this session (ADR 0142 decision 9: the Music view marks them). */
    data class State(
        val nowPlaying: NowPlaying? = null,
        val notice: PlayerNotice? = null,
        val skipped: Set<String> = emptySet(),
    )

    private val lane = (scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default).limitedParallelism(1)
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + lane)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (State) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    private class Superseded : Exception("superseded")

    private inner class Slot : Deck.Events {
        lateinit var deck: Deck
        var track: PlayerTrack? = null
        var source: TrackSource? = null
        var span = Span(0, Span.UNKNOWN_END)
        var loaded = false
        var playing = false

        /** The fade out has started (the next track is on its way in). */
        var leaving = false

        /** A prepared next track's id: opened and loaded ahead, not yet playing. */
        var preparedId: String? = null

        /** The load in flight or done for the track the deck holds. */
        var loading: Deferred<Unit>? = null

        /** Bumped by every load and every clear: a load that comes back to another token lets its source go. */
        var token = 0

        override fun ended() {
            scope.launch { onEnded(this@Slot) }
        }

        override fun failed() {
            scope.launch { onFailed(this@Slot) }
        }
    }

    private val slots = arrayOf(Slot(), Slot())

    init {
        for (slot in slots) slot.deck = newDeck(slot)
    }

    private var active = 0
    private var queue: PlayerQueue? = null
    private var order: PlayOrder? = null

    /** Bumped by every new queue, stop and track start: a suspension that comes back to another generation lets go. */
    private var generation = 0
    private var failures = 0
    private var ticker: Job? = null
    private var lastPosition = 0L

    /** Bumped by what a listener does (play, pause, a new queue, stop): an interruption's end resumes only where none came
     *  between (ADR 0142 decision 13). */
    private var listened = 0

    /** A track is being brought in: opened and loaded, not yet playing ([bringIn] between its start and its sound). */
    private var starting = false

    /** The interruption that holds the music: while it is set, a track that finishes loading does not start (ADR 0142
     *  decision 13 — a load still pending when the video started stays silent until the video ends). */
    private var held: Interruption? = null

    /** The listener paused, and no play, track change, new queue or stop came after: it holds the music as an
     *  interruption does — a track that finishes loading becomes the active deck, paused, and only a play starts it. */
    private var pausedByListener = false

    /** What a listener did: an interruption's end no longer resumes, and a held track is no longer held. */
    private fun listen() {
        listened++
        held = null
        pausedByListener = false
    }

    // --- the commands: each answered on the lane, in the order made ------------------------------------------------------

    /** Plays [queue], replacing the one playing with a 1 s crossfade. */
    fun play(queue: PlayerQueue) {
        scope.launch { doPlay(queue) }
    }

    fun stop() {
        scope.launch {
            listen()
            doStop()
        }
    }

    override fun pause() {
        scope.launch {
            listen()
            pausedByListener = true
            doPause()
        }
    }

    override fun resume() {
        scope.launch {
            listen()
            doResume()
        }
    }

    override fun next() {
        scope.launch {
            val order = order ?: return@launch
            pausedByListener = false // a track change plays, as it did before the pause held loads
            val slot = slots[active]
            slot.leaving = true
            bringIn(order.next(), pressFadeMs(slot.span))
        }
    }

    override fun previous() {
        scope.launch {
            val order = order ?: return@launch
            val slot = slots[active]
            val into = slot.deck.positionMs() - slot.span.startMs
            when (val step = order.previous(into)) {
                PreviousStep.Restart -> doSeek(0)
                is PreviousStep.Back -> {
                    pausedByListener = false // a track change plays, as it did before the pause held loads
                    slot.leaving = true
                    bringIn(step.id, pressFadeMs(slot.span))
                }
            }
        }
    }

    override fun seek(positionMs: Long) {
        scope.launch { doSeek(positionMs) }
    }

    /**
     * A lightbox video started (ADR 0142 decision 13): a playing queue pauses. The [Interruption]'s [Interruption.end] —
     * the video paused or ended, the lightbox moved on or closed — resumes it, but only when it was playing when
     * interrupted and nothing came between: a listener's play or pause, a new queue, a stop. A track still loading when
     * it comes (the queue's first, or the next one on its way in) is held: it does not start during the video, and the
     * end starts it.
     */
    fun interrupt(): Interruption {
        val interruption = Interruption()
        scope.launch {
            // a load the listener paused stays paused: nothing to resume after
            if (queue != null && (slots[active].playing || (starting && !pausedByListener))) {
                doPause()
                interruption.paused = true
                interruption.at = listened
                held = interruption
            }
        }
        return interruption
    }

    inner class Interruption internal constructor() {
        internal var paused = false
        internal var at = -1
        private var ended = false

        /** The video stopped, or the lightbox moved on or closed: the music plays again where this interruption paused it. */
        fun end() {
            scope.launch {
                if (ended) return@launch
                ended = true
                if (held === this@Interruption) held = null
                if (paused && at == listened && queue != null) doResume()
            }
        }
    }

    // --- the lane's own work ---------------------------------------------------------------------------------------------

    private suspend fun doPlay(next: PlayerQueue) {
        listen()
        _state.update { it.copy(notice = null) }
        val ids = next.tracks.map { it.id }
        if (ids.isEmpty()) return
        val replacing = queue != null && slots[active].source != null
        val fade = if (replacing) pressFadeMs(slots[active].span) else pressFadeMs(Span(0, Span.UNKNOWN_END))
        queue = next
        val order = PlayOrder(ids, random, next.startId)
        this.order = order
        failures = 0
        // the idle deck may hold a prepared track of the old queue
        val idle = slots[1 - active]
        if (idle.source != null) clear(idle)
        controls?.bind(this)
        bringIn(order.current(), fade)
    }

    private fun doStop() {
        generation++
        starting = false
        ticker?.cancel()
        ticker = null
        for (slot in slots) clear(slot)
        queue = null
        order = null
        _state.update { it.copy(nowPlaying = null) }
        controls?.clear()
    }

    private fun doPause() {
        val slot = slots[active]
        val other = slots[1 - active]
        if (other.source != null && other.leaving) clear(other) // a crossfade in flight ends where it is
        slot.deck.pause()
        slot.playing = false
        publish(_state.value.nowPlaying?.copy(playing = false), toControls = true)
    }

    private suspend fun doResume() {
        val slot = slots[active]
        if (slot.source == null) return
        slot.deck.ramp(1.0, 0)
        try {
            slot.deck.play()
            slot.playing = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        publish(_state.value.nowPlaying?.copy(playing = true), toControls = true)
    }

    private fun doSeek(positionMs: Long) {
        val slot = slots[active]
        if (slot.track == null) return
        val span = slot.span
        val rel = positionMs.coerceAtLeast(0).let { if (span.known) minOf(it, span.lengthMs) else it }
        slot.deck.seek(span.startMs + rel)
        lastPosition = rel
        publish(_state.value.nowPlaying?.copy(positionMs = rel), toControls = true)
    }

    /** Let a deck go: stopped and emptied, its source released. */
    private fun clear(slot: Slot) {
        slot.token++
        slot.loading = null
        slot.deck.clear()
        slot.source?.release()
        slot.source = null
        slot.track = null
        slot.loaded = false
        slot.playing = false
        slot.leaving = false
        slot.preparedId = null
    }

    private fun spanOf(track: PlayerTrack, fileMs: Long): Span {
        val whole = if (fileMs > 0) fileMs else Span.UNKNOWN_END
        val start = track.startMs ?: 0
        val end = track.endMs ?: whole
        return if (end > start) Span(start, end) else Span(0, whole)
    }

    /** Open [track] into [slot] and load it to its span's start. Fails where it does not open or load, or was cleared. */
    private fun load(slot: Slot, track: PlayerTrack): Deferred<Unit> {
        if (slot.source != null) clear(slot)
        val token = ++slot.token
        val loading = scope.async {
            val source = track.open()
            if (token != slot.token) {
                source.release()
                throw Superseded()
            }
            slot.source = source
            slot.track = track
            slot.leaving = false
            slot.deck.ramp(0.0, 0)
            val fileMs = slot.deck.load(source.reader, source.mime)
            if (token != slot.token) throw Superseded()
            slot.loaded = true
            slot.span = spanOf(track, fileMs)
            slot.deck.seek(slot.span.startMs)
        }
        slot.loading = loading
        return loading
    }

    private fun trackOf(id: String?): PlayerTrack? = id?.let { wanted -> queue?.tracks?.firstOrNull { it.id == wanted } }

    /** Bring [id] in on the idle deck, fading the playing one out over [fadeMs]; skip it where it does not play. */
    private suspend fun bringIn(id: String?, fadeMs: Long) {
        val track = trackOf(id)
        if (track == null || queue == null || order == null) return endQueue(null)
        val gen = ++generation
        val outgoing = slots[active]
        val incoming = slots[1 - active]
        val prepared = if (incoming.preparedId == track.id) incoming.loading else null
        show(track, playing = false)
        starting = true
        try {
            (prepared ?: load(incoming, track)).await()
            if (gen != generation) return
            incoming.preparedId = null
            if (held != null || pausedByListener) {
                // an interruption or the listener's pause came while it loaded: it waits, at full volume, for the end or a play
                incoming.deck.ramp(1.0, 0)
                return hold(incoming, outgoing, track)
            }
            incoming.deck.ramp(1.0, fadeMs)
            incoming.deck.play()
            incoming.playing = true
        } catch (e: Superseded) {
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (gen != generation) return
            clear(incoming)
            return skip(track.id)
        } finally {
            if (gen == generation) starting = false
        }
        if (gen != generation) return
        failures = 0
        active = 1 - active
        if (outgoing.source != null) {
            outgoing.leaving = true
            if (fadeMs > 0) {
                outgoing.deck.ramp(0.0, fadeMs)
                val leaving = outgoing.track
                scope.launch {
                    delay(fadeMs + CLEAR_AFTER_FADE_MS)
                    if (outgoing.track === leaving && slots[active] !== outgoing) clear(outgoing)
                }
            } else {
                clear(outgoing)
            }
        }
        show(track, playing = true)
        startTicker()
    }

    /** [track] loaded on [incoming] while an interruption or the listener's pause holds the music: it becomes the playing
     *  deck, paused; the outgoing one (paused) is let go at once. The interruption's end or a play plays it. */
    private fun hold(incoming: Slot, outgoing: Slot, track: PlayerTrack) {
        failures = 0
        active = 1 - active
        if (outgoing.source != null) clear(outgoing)
        show(track, playing = false)
        startTicker()
    }

    /** A track that did not play: dropped from the order; the queue ends once none is left that plays. */
    private suspend fun skip(id: String) {
        val order = order ?: return
        failures++
        order.drop(id)
        _state.update { it.copy(skipped = it.skipped + id) }
        if (order.size == 0 || failures > (queue?.tracks?.size ?: 0)) return endQueue(PlayerNotice.UNPLAYABLE)
        bringIn(order.next(), 0)
    }

    private fun endQueue(why: PlayerNotice?) {
        doStop()
        if (why != null) _state.update { it.copy(notice = why) }
    }

    private fun show(track: PlayerTrack, playing: Boolean) {
        val slot = slots.firstOrNull { it.track === track } ?: slots[active]
        val span = if (slot.track === track) slot.span else Span(track.startMs ?: 0, track.endMs ?: Span.UNKNOWN_END)
        val q = queue ?: return
        publish(
            NowPlaying(
                key = q.key, trackId = track.id, title = track.title, album = q.album, route = q.route, playing = playing,
                positionMs = 0, durationMs = if (span.known) span.lengthMs else 0,
            ),
            toControls = true,
        )
    }

    private fun publish(now: NowPlaying?, toControls: Boolean) {
        _state.update { it.copy(nowPlaying = now) }
        if (toControls && now != null) controls?.show(now)
    }

    private suspend fun tick() {
        val slot = slots[active]
        val track = slot.track ?: return
        val order = order ?: return
        if (!slot.loaded) return
        val pos = slot.deck.positionMs()
        val span = slot.span
        val np = _state.value.nowPlaying
        if (np != null && np.trackId == track.id) {
            val positionMs = (minOf(pos, span.endMs) - span.startMs).coerceAtLeast(0)
            // the system's controls hear the position once a second
            publish(np.copy(positionMs = positionMs, playing = slot.playing), toControls = positionMs / 1000 != lastPosition / 1000)
            lastPosition = positionMs
        }
        if (!slot.playing || slot.leaving) return
        val fadeAt = fadeOutAtMs(span)
        if (pos >= fadeAt - OPEN_AHEAD_MS) prepareNext()
        if (pos >= fadeAt) {
            slot.leaving = true
            val fade = fadeLengthAt(span, pos)
            scope.launch { bringIn(order.next(), fade) }
        }
    }

    /** Open and load the next track on the idle deck ahead of the fade. */
    private fun prepareNext() {
        val idle = slots[1 - active]
        val order = order ?: return
        if (idle.preparedId != null || idle.source != null) return
        val track = trackOf(order.peek()) ?: return
        idle.preparedId = track.id
        // a failed preparation is met again where the fade awaits it, and skipped there
        load(idle, track)
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    /** The playing deck's end reached before its fade (a file shorter than its span, or one of unknown length): on. */
    private suspend fun onEnded(slot: Slot) {
        val order = order ?: return
        if (slot !== slots[active] || slot.track == null || slot.leaving) return
        slot.leaving = true
        bringIn(order.next(), 0)
    }

    /** A deck that stopped after it loaded: the playing one's track is skipped; a prepared one is let go and opened anew. */
    private suspend fun onFailed(slot: Slot) {
        val track = slot.track ?: return
        if (!slot.loaded) return
        val playing = slot === slots[active] && !slot.leaving
        clear(slot)
        if (playing) skip(track.id)
    }

    companion object {
        /** How often the player looks where the playing track stands. */
        const val TICK_MS = 250L

        /** How long before its fade the next track is opened and loaded, so it starts on time. */
        const val OPEN_AHEAD_MS = 15_000L

        /** How long after its fade the outgoing deck is cleared. */
        const val CLEAR_AFTER_FADE_MS = 50L
    }
}
