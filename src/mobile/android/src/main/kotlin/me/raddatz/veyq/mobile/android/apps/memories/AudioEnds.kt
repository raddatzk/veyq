package me.raddatz.veyq.mobile.android.apps.memories

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import me.raddatz.veyq.mobile.memories.music.PcmWav
import java.io.ByteArrayOutputStream

/**
 * A picked track's two ends decoded on the device (inbox #932; core's `MusicUpload.Measure.ends`): `MediaExtractor` and
 * the platform's `MediaCodec` decoder, the first and the last [limitMs] of its audio — never the whole file —, each handed
 * as a WAV file's bytes (`PcmWav`) for the core's silence scan. Null where the platform does not decode the format: the
 * track is then sealed untrimmed, as before.
 */
internal object AudioEnds {
    /** No end is read past this many PCM bytes (30 s of 8 channels of 32-bit float at 192 kHz is 184 MiB; a cap, not a size). */
    private const val CAP = 64 * 1024 * 1024
    private const val TIMEOUT_US = 10_000L

    fun decode(context: Context, uri: Uri, durationMs: Long, limitMs: Long): Pair<ByteArray, ByteArray>? {
        if (durationMs <= 0) return null
        val head = slice(context, uri, 0, minOf(limitMs, durationMs)) ?: return null
        val tail = slice(context, uri, maxOf(0, durationMs - limitMs), null) ?: return null
        return head to tail
    }

    /** The audio of [fromMs] up to [toMs] (null: the end) as a WAV, or null where it does not decode. */
    private fun slice(context: Context, uri: Uri, fromMs: Long, toMs: Long?): ByteArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: return null)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val fromUs = fromMs * 1000
                val toUs = toMs?.let { it * 1000 } ?: Long.MAX_VALUE
                if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var float = false
                val pcm = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val buffer = codec.getInputBuffer(i) ?: return null
                            val n = extractor.readSampleData(buffer, 0)
                            if (n < 0 || extractor.sampleTime > toUs) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        // whole buffers only, the one the seek landed before the slice left out
                        if (info.size > 0 && info.presentationTimeUs >= fromUs && info.presentationTimeUs < toUs) {
                            val out = codec.getOutputBuffer(o) ?: return null
                            val bytes = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(bytes)
                            pcm.write(bytes)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        if (pcm.size() > CAP) return null
                    }
                }
                return PcmWav.of(pcm.toByteArray(), channels, rate, if (float) 32 else 16, float)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } catch (_: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }
}
