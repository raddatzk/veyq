@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.audio.web

import me.raddatz.veyq.mobile.audio.PlayOrder
import me.raddatz.veyq.mobile.audio.PreviousStep
import me.raddatz.veyq.mobile.audio.randomIntOf
import me.raddatz.veyq.mobile.crypto.noble.randomBytes
import org.khronos.webgl.Int8Array
import kotlin.js.collections.JsReadonlyArray
import me.raddatz.veyq.mobile.audio.autoFadeMs as autoFade
import me.raddatz.veyq.mobile.audio.fadeLengthAt as fadeLength
import me.raddatz.veyq.mobile.audio.fadeOutAtMs as fadeOutAt
import me.raddatz.veyq.mobile.audio.pressFadeMs as pressFade

// The music order and fades' façade for the web (ADR 0155 decisions 4, 5 and 7.2; `@veyq/core-js/audio`): the order a
// queue plays in (`audio/PlayOrder`) and where and how long a crossfade runs (`audio/Fades`) behind plain values
// (`Shapes.kt`), so the web's player shuffles, goes back and crossfades as the apps' does, and keeps only what a browser
// does: its media elements, the gain ramps, the MediaSession. The order is state kept across calls, so it crosses as an
// opaque handle (`PlayOrderHandle`), read only here. The randomness stays the caller's: the web hands in its own
// `(n) => number`, the platform's draw over `crypto.getRandomValues` (`cryptoRandomInt`) or a test's. Nothing here reaches
// kotlinx.serialization, coroutines, Ktor or a `NotOnTheWeb` refusal (the package's slice check), and no `Long` crosses:
// milliseconds are `number`s, an unknown end `Infinity`.

/** A queue's order under way. Opaque: passed back, never read. */
@JsExport
class PlayOrderHandle internal constructor(internal val order: PlayOrder)

/** A uniformly random integer in `[0, n)` from the platform's CSPRNG, unbiased by rejection ([randomIntOf]). No top-level
 *  value holds the draw: the module keeps no initializer a bundler must run. */
@JsExport
fun cryptoRandomInt(n: Int): Int =
    randomIntOf { k -> randomBytes(k).let { Int8Array(it.buffer, it.byteOffset, it.length).unsafeCast<ByteArray>() } }(n)

/** The order of a queue of [ids] (each once, in its first place), [startId] first where the queue holds it, drawn by
 *  [random]; its first track is current at once ([PlayOrder]). */
@JsExport
fun playOrder(ids: JsReadonlyArray<String>, random: (n: Int) -> Int, startId: String?): PlayOrderHandle =
    PlayOrderHandle(PlayOrder(ids.unsafeCast<Array<String>>().asList(), random, startId))

/** The track playing, or null when the queue holds none. */
@JsExport
fun playOrderCurrent(order: PlayOrderHandle): String? = order.order.current()

/** Go forward: through tracks gone back over first, then the round, then a fresh round. Null when no track is left. */
@JsExport
fun playOrderNext(order: PlayOrderHandle): String? = order.order.next()

/** The track [playOrderNext] will go to, without going there: it is fixed now, so the player can open it ahead. */
@JsExport
fun playOrderPeek(order: PlayOrderHandle): String? = order.order.peek()

/** Go back — or restart, where the track is [positionMs] > 3 s in or nothing was played before it. */
@JsExport
fun playOrderPrevious(order: PlayOrderHandle, positionMs: Double): PreviousShape {
    val o: dynamic = js("({})")
    when (val step = order.order.previous(positionMs)) {
        PreviousStep.Restart -> o.restart = true
        is PreviousStep.Back -> {
            o.restart = false
            o.id = step.id
        }
    }
    return o.unsafeCast<PreviousShape>()
}

/** Drop a track that does not play here: it leaves every round, and the history. */
@JsExport
fun playOrderDrop(order: PlayOrderHandle, id: String) = order.order.drop(id)

/** How many tracks the queue still holds. */
@JsExport
fun playOrderSize(order: PlayOrderHandle): Int = order.order.size

/** The fade a track ends with by itself: 5 s, or a third of its span where that is shorter than two fades. */
@JsExport
fun autoFadeMs(span: SpanShape): Double = autoFade(span.startMs, span.endMs)

/** The fade a press (next, previous, another queue) runs: 1 s, or the track's own where that is shorter. */
@JsExport
fun pressFadeMs(span: SpanShape): Double = pressFade(span.startMs, span.endMs)

/** Where in the file the track starts fading out by itself. */
@JsExport
fun fadeOutAtMs(span: SpanShape): Double = fadeOutAt(span.startMs, span.endMs)

/** The fade that starts at [positionMs]: the track's own, shortened to what is left where a seek landed inside it. */
@JsExport
fun fadeLengthAt(span: SpanShape, positionMs: Double): Double = fadeLength(span.startMs, span.endMs, positionMs)
