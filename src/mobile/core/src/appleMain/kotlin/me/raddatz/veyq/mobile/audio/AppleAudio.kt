package me.raddatz.veyq.mobile.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.Sniff
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.depot.FileSave
import me.raddatz.veyq.mobile.memories.music.MusicModel
import platform.Foundation.NSData
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A [Deck] as Swift implements it (ADR 0142 decision 2): Swift cannot implement a Kotlin `suspend fun`, so the Apple app's
 * deck (`AVPlayer` over `ReaderLoader`) answers [load] and [play] through a callback, and [AppleAudio.deck] makes the core's
 * [Deck] of it. Every callback once, on any thread.
 */
interface AppleDeck {
    /** Opens [reader] (type [mime]); [done] with the file's length in ms (`0` or less: unknown), or an error's words. */
    fun load(reader: RangeReader, mime: String, done: (lengthMs: Long, error: String?) -> Unit)

    /** Starts or continues playing; [done] with null, or why it refused. */
    fun play(done: (error: String?) -> Unit)

    fun pause()
    fun seek(positionMs: Long)
    fun positionMs(): Long
    fun ramp(to: Double, overMs: Long)
    fun clear()
}

/** What the Apple app needs of the core's music that Swift cannot do with its types itself. */
@OptIn(ExperimentalForeignApi::class)
object AppleAudio {
    /** The core's [Deck] over Swift's [apple]. */
    fun deck(apple: AppleDeck): Deck = object : Deck {
        override suspend fun load(reader: RangeReader, mime: String): Long = suspendCancellableCoroutine { c ->
            apple.load(reader, mime) { ms, error ->
                if (error == null) c.resume(ms) else c.resumeWithException(IllegalStateException(error))
            }
        }

        override suspend fun play() = suspendCancellableCoroutine { c ->
            apple.play { error -> if (error == null) c.resume(Unit) else c.resumeWithException(IllegalStateException(error)) }
        }

        override fun pause() = apple.pause()
        override fun seek(positionMs: Long) = apple.seek(positionMs)
        override suspend fun positionMs(): Long = apple.positionMs()
        override fun ramp(to: Double, overMs: Long) = apple.ramp(to, overMs)
        override fun clear() = apple.clear()
    }

    /**
     * Track [mediaId] of [music] saved at [path] (`MusicModel.save` into core's [FileSink], which leaves nothing at [path]
     * when it did not finish), awaited to its end; null where it may not be saved.
     */
    @Throws(Exception::class)
    suspend fun save(music: MusicModel, mediaId: String, path: String): FileSave.End? {
        val save = music.save(mediaId) { produce ->
            withContext(Dispatchers.Default) { FileSink.write(path, kept = { it }) { sink -> produce(sink) } }
        } ?: return null
        return save.state.first { it.end != null }.end
    }

    /**
     * A reader over [data] held in memory, cut into the frames a sealed blob has (debug only: the format matrix runs the
     * deck over the same reader a track is played through, without a server).
     */
    /**
     * A decoded end of a picked track ([pcm]: interleaved 32-bit float, as `AVAudioFile` reads it) as the WAV the core's
     * silence scan reads (inbox #932, `MusicUpload.Measure.ends`), copied once.
     */
    fun floatWav(pcm: NSData, channels: Int, sampleRate: Int): ByteArray =
        me.raddatz.veyq.mobile.memories.music.PcmWav.of(me.raddatz.veyq.mobile.account.AppleKit.bytes(pcm), channels, sampleRate, 32, float = true)

    fun reader(data: NSData): RangeReader {
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        val frame = V1.FRAME_PLAINTEXT
        val count = ((bytes.size + frame - 1) / frame).coerceAtLeast(1)
        return RangeReader(bytes.size.toLong(), count) { first, until, sink ->
            for (i in first until until) sink(bytes.copyOfRange(i * frame, minOf(bytes.size, (i + 1) * frame)))
        }
    }

    /** The type [data] is played under, by its head's bytes as a music upload types it (`Sniff.stream`); null: no audio or video container. Debug only, as [reader]. */
    fun mime(data: NSData): String? {
        val n = minOf(data.length.toInt(), 4096)
        val head = ByteArray(n)
        if (n > 0) head.usePinned { memcpy(it.addressOf(0), data.bytes, n.toULong()) }
        return Sniff.stream(head)?.mime
    }

    /** A track over [data] held in memory (debug only, as [reader]: the player driven over real decks without a server). */
    fun localTrack(id: String, title: String, data: NSData, mime: String): PlayerTrack =
        PlayerTrack(id, title) { reader(data).let { r -> TrackSource(r, mime) { r.close() } } }

    /** A player over [newDeck] and [controls] with the platform's randomness (debug only, as [reader]). */
    fun localPlayer(newDeck: (Deck.Events) -> Deck, controls: SystemControls?): MusicPlayer =
        MusicPlayer(newDeck, controls, { n -> kotlin.random.Random.nextInt(n) })
}
