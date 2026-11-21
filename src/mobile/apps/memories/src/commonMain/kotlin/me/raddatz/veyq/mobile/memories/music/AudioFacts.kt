package me.raddatz.veyq.mobile.memories.music

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.raddatz.veyq.mobile.media.ByteSource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/** Samples the silence scan reads: per channel, at [sampleRate] (a WAV's ends, `Wav.samples`). */
class Decoded(val channels: List<FloatArray>, val sampleRate: Int) {
    val length: Int get() = channels.firstOrNull()?.size ?: 0
}

/**
 * A music track's facts (the web's `features/memories/music/audioFacts.ts`, ADR 0135 decisions 3–4, ADR 0142 decision 7):
 * its file's length and the span between the silence at its start and at its end — what plays, what the progress spans and
 * where the crossfades sit —, integers of milliseconds, sealed into its listed tier at its upload and never written after.
 *
 * The apps find the trim of a WAV from its PCM read at both ends ([measure]), and of a decoded format from its two ends as
 * the platform decodes them (inbox #932: MediaCodec, AVAudioFile, Media Foundation, GStreamer) — never the whole file; where
 * the platform decodes nothing, the track is sealed untrimmed ([untrimmed]). A raw ADTS file's length is counted from its
 * frames ([Frames.adtsDurationMs]), as on the web.
 */
data class AudioFacts(val durationMs: Long, val trimStartMs: Long, val trimEndMs: Long) {
    companion object {
        /** The level under which a window counts as silent: -60 dBFS as a sample's peak. */
        val SILENCE_PEAK: Double = 10.0.pow(-60.0 / 20)

        /** The windows the scan steps through from each end. */
        const val SCAN_WINDOW_MS = 20L

        /** How far into each end the scan looks: a silence longer than this is trimmed to this. */
        const val SCAN_LIMIT_MS = 30_000L

        /** The shortest span a trim may leave; a trim that would leave less keeps the whole file. */
        const val MIN_TRIMMED_MS = 1000L

        private class Windows(audio: Decoded) {
            val win = max(1, ((audio.sampleRate * SCAN_WINDOW_MS) / 1000.0).let { kotlin.math.round(it).toInt() })
            val rate = audio.sampleRate
            val channels = audio.channels
            val length = audio.length
            val limit = minOf(length, ceil(audio.sampleRate * SCAN_LIMIT_MS / 1000.0).toInt())
            fun loud(from: Int): Boolean {
                val to = minOf(length, from + win)
                for (data in channels) for (i in from until to) if (abs(data[i]) >= SILENCE_PEAK) return true
                return false
            }
            fun toMs(samples: Int): Long = (samples.toDouble() / rate * 1000).roundToLong()
        }

        /** How long [audio] is silent from its start: up to its first window that is not, at most [SCAN_LIMIT_MS]. */
        fun leadingSilenceMs(audio: Decoded): Long {
            if (audio.length == 0 || audio.sampleRate <= 0) return 0
            val w = Windows(audio)
            var at = 0
            while (at < w.limit && !w.loud(at)) at += w.win
            return minOf(w.toMs(minOf(at, w.limit)), SCAN_LIMIT_MS)
        }

        /** How long [audio] is silent before its end, counted back from its last sample, at most [SCAN_LIMIT_MS]. */
        fun trailingSilenceMs(audio: Decoded): Long {
            if (audio.length == 0 || audio.sampleRate <= 0) return 0
            val w = Windows(audio)
            var back = 0
            while (back < w.limit && !w.loud(maxOf(0, audio.length - back - w.win))) back += w.win
            return minOf(w.toMs(minOf(back, w.limit)), SCAN_LIMIT_MS)
        }

        /** The facts from a length and the silence at each end: a span of at least [MIN_TRIMMED_MS], else the whole file. */
        fun fromSilence(durationMs: Long, leadMs: Long, trailMs: Long): AudioFacts {
            val d = maxOf(0, durationMs)
            val start = maxOf(0, leadMs)
            val end = d - maxOf(0, trailMs)
            return if (end - start >= MIN_TRIMMED_MS) AudioFacts(d, start, end) else untrimmed(d)
        }

        /** A track with no trim: it plays whole. */
        fun untrimmed(durationMs: Long): AudioFacts = AudioFacts(durationMs, 0, durationMs)

        /**
         * A picked file's facts, its container [type] named by its bytes and its length [durationMs] read by the platform's
         * player (ADR 0142 decision 7): a raw ADTS file's length counted from its frames instead (where they count), a
         * WAV's trim from its PCM, every other container's from its first and last [SCAN_LIMIT_MS] as the platform
         * [decodeEnds] them (each a WAV file's bytes; null: it decodes nothing, and the file is sealed untrimmed). A failed
         * read of the trim is no trim, never a refusal.
         */
        suspend fun measure(
            file: ByteSource, type: String, durationMs: Long,
            decodeEnds: (suspend (limitMs: Long) -> Pair<ByteArray, ByteArray>?)? = null,
        ): AudioFacts {
            val length = if (type == "audio/aac") runCatching { Frames.adtsDurationMs(file) }.getOrNull() ?: durationMs else durationMs
            val ends: Pair<Decoded, Decoded> = (if (type == "audio/wav") runCatching { Wav.readEnds(file, SCAN_LIMIT_MS) }.getOrNull() else decodedEnds(decodeEnds))
                ?: return untrimmed(length)
            return fromSilence(length, leadingSilenceMs(ends.first), trailingSilenceMs(ends.second))
        }

        /** The ends [decodeEnds] hands (head, tail), each read as [Wav] reads a file; null where it hands none or they do not read. */
        private suspend fun decodedEnds(decodeEnds: (suspend (limitMs: Long) -> Pair<ByteArray, ByteArray>?)?): Pair<Decoded, Decoded>? = try {
            decodeEnds?.invoke(SCAN_LIMIT_MS)?.let { (head, tail) -> decoded(head).first to decoded(tail).second }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        /** A decoded end the platform handed as a WAV file's bytes, read as [Wav] reads a file (the whole of a short one). */
        private suspend fun decoded(wav: ByteArray): Pair<Decoded, Decoded> =
            Wav.readEnds(ByteSource(wav.size.toLong()) { from, length -> wav.copyOfRange(from.toInt(), minOf(wav.size, from.toInt() + length)) }, SCAN_LIMIT_MS)

        /**
         * A sealed tier's facts (the web's `audioFactsFrom`): all three non-negative integers, start < end ≤ duration; else
         * null — the whole file plays (ADR 0135 decision 6).
         */
        fun from(meta: JsonObject?): AudioFacts? {
            fun n(k: String): Long? {
                val p = meta?.get(k) as? JsonPrimitive ?: return null
                if (p.isString) return null
                // a JSON number that is an integer: `9000`, also `9000.0` as JavaScript reads it; never `10.5`
                val v = p.longOrNull ?: p.content.toDoubleOrNull()?.takeIf { it.isFinite() && it == kotlin.math.floor(it) && abs(it) < 9.007199254740992E15 }?.toLong()
                return v?.takeIf { it >= 0 }
            }
            val d = n("durationMs") ?: return null
            val s = n("trimStartMs") ?: return null
            val e = n("trimEndMs") ?: return null
            if (e <= s || e > d) return null
            return AudioFacts(d, s, e)
        }
    }
}
